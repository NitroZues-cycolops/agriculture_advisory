package com.precisionfarming.advisory_service.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.precisionfarming.advisory_service.model.HeatCell;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PART C - turns the open district-boundary GeoJSON into inline SVG.
 *
 * WHAT IT DOES (once, at start-up)
 *   1. Reads src/main/resources/geo/maharashtra_districts.geojson (an open
 *      DataMeet community map; the source and licence are recorded in README.md).
 *   2. Projects every longitude/latitude to an SVG x/y in a FIXED viewBox
 *      (equirectangular fit, with the cos(latitude) correction so the state is
 *      not stretched sideways).
 *   3. Keeps ONE ready-to-draw path string per district CODE, so the template
 *      only has to print it - no geometry is computed while rendering a page.
 *
 * It also builds the interpolated HEAT map: an inverse-distance-weighted grid
 * (about 50 x 60 cells) over the state, returned as coloured rectangles that the
 * template clips to the state outline with an SVG <clipPath>. The grid is cached
 * per layer and {@link #invalidate()} is called after every weather refresh, so
 * the heat map follows the data without being recomputed on every page view.
 *
 * The names in the GeoJSON are matched to the `districts` table by a
 * lower-case slug, plus one alias (the open file has a single "Mumbai" polygon
 * while the table has Mumbai City and Mumbai Suburban; the shape is used for
 * both).
 *
 * If this breaks, check:
 *   - "Boundary file not found" in the log -> the file is missing from
 *     src/main/resources/geo/ (the map page then shows the table only).
 *   - A district is missing from the map -> the GeoJSON name does not slug to
 *     the district code; add it to nameAliases().
 */
@Service
public class GeoMapService {

    private static final Logger log = LoggerFactory.getLogger(GeoMapService.class);

    /** The SVG canvas every page uses. 820x660 keeps roughly Maharashtra's shape. */
    public static final int VIEW_W = 820;
    public static final int VIEW_H = 660;

    /** Padding so the outermost district does not touch the canvas edge. */
    private static final double PAD = 16;

    /** A district gets a printed name label only when it is at least this big
     *  (in viewBox square units) - otherwise small districts would overlap. */
    private static final double LABEL_MIN_AREA = 950;

    private static final String RESOURCE = "geo/maharashtra_districts.geojson";

    private final ObjectMapper mapper = new ObjectMapper();

    private boolean ready = false;
    private String outlinePath = "M 0 0";                  // union of every district shape
    private final Map<String, String> pathByCode = new HashMap<>();
    private final Map<String, double[]> labelByCode = new HashMap<>();   // {x, y, area}
    private final Map<String, List<HeatCell>> heatCache = new ConcurrentHashMap<>();

    /* the projection, filled in once by load() */
    private double minLat, maxLat, minLon, maxLon;
    private double lonScale, innerW, innerH, widthUnits, heightUnits;

    public GeoMapService() {
        load();
    }

    /** True when the boundary file was read and the map can be drawn. */
    public boolean ready() {
        return ready;
    }

    /** The ready-to-draw SVG path for a district code, or null when unknown. */
    public String path(String code) {
        return code == null ? null : pathByCode.get(code);
    }

    public double labelX(String code) {
        double[] l = labelByCode.get(code);
        return l == null ? 0 : l[0];
    }

    public double labelY(String code) {
        double[] l = labelByCode.get(code);
        return l == null ? 0 : l[1];
    }

    public boolean labelFits(String code) {
        double[] l = labelByCode.get(code);
        return l != null && l[2] >= LABEL_MIN_AREA;
    }

    /** The whole state outline, used as an SVG <clipPath> for the heat map. */
    public String outlinePath() {
        return outlinePath;
    }

    /** How many districts the boundary file actually contains (36 here). */
    public int shapeCount() {
        return pathByCode.size();
    }

    /** Drops the cached heat grids; called after every weather refresh. */
    public void invalidate() {
        heatCache.clear();
    }

    /* ==================== the heat map (IDW grid) ==================== */

    /**
     * Builds (or reuses) the interpolated heat grid for one layer.
     *
     * @param key     cache key - layer name plus a cheap data signature
     * @param samples {longitude, latitude, value} for every district that has a
     *                reading; districts without a reading are simply left out
     * @param cols    grid columns (the page uses 50)
     * @param rows    grid rows    (the page uses 60)
     * @return one coloured cell per grid square, already in SVG coordinates
     */
    public List<HeatCell> heat(String key, List<double[]> samples, int cols, int rows) {
        if (!ready || samples.isEmpty()) {
            return List.of();
        }
        return heatCache.computeIfAbsent(key, k -> buildHeat(samples, cols, rows));
    }

    private List<HeatCell> buildHeat(List<double[]> samples, int cols, int rows) {
        List<HeatCell> cells = new ArrayList<>(cols * rows);
        double lonStep = (maxLon - minLon) / cols;
        double latStep = (maxLat - minLat) / rows;

        for (int r = 0; r < rows; r++) {
            double latTop = maxLat - r * latStep;
            double latBottom = latTop - latStep;
            double latMid = latTop - latStep / 2;
            double y = py(latTop);
            double h = py(latBottom) - y;
            for (int c = 0; c < cols; c++) {
                double lonLeft = minLon + c * lonStep;
                double lonMid = lonLeft + lonStep / 2;
                double x = px(lonLeft);
                double w = px(lonLeft + lonStep) - x;
                double value = idw(lonMid, latMid, samples);
                // +0.5 on the size hides hairline seams between neighbouring cells.
                cells.add(new HeatCell(round(x), round(y), round(w) + 0.5, round(h) + 0.5, value));
            }
        }
        return cells;
    }

    /**
     * Inverse-distance weighting (power 2) over the district samples: a cell is
     * pulled towards the nearest districts more strongly than the far ones.
     * A cell that lands exactly on a sample takes that sample's value.
     */
    private double idw(double lon, double lat, List<double[]> samples) {
        double num = 0, den = 0;
        for (double[] p : samples) {
            double dx = (lon - p[0]) * lonScale;
            double dy = lat - p[1];
            double d2 = dx * dx + dy * dy;
            if (d2 < 1e-9) {
                return p[2];
            }
            double weight = 1.0 / d2;
            num += weight * p[2];
            den += weight;
        }
        return den == 0 ? 0 : num / den;
    }

    /* ==================== loading the GeoJSON ==================== */

    private void load() {
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            JsonNode root = mapper.readTree(in);
            JsonNode features = root.path("features");

            // Pass 1: collect each feature's outer rings and the overall bounding box.
            List<String> names = new ArrayList<>();
            List<List<double[][]>> ringsPerFeature = new ArrayList<>();
            minLat = 90; maxLat = -90; minLon = 180; maxLon = -180;

            for (JsonNode feature : features) {
                String name = feature.path("properties").path("district").asText("");
                if (name.isBlank()) {
                    continue;
                }
                List<double[][]> rings = outerRings(feature.path("geometry"));
                if (rings.isEmpty()) {
                    continue;
                }
                for (double[][] ring : rings) {
                    for (double[] point : ring) {
                        minLat = Math.min(minLat, point[1]);
                        maxLat = Math.max(maxLat, point[1]);
                        minLon = Math.min(minLon, point[0]);
                        maxLon = Math.max(maxLon, point[0]);
                    }
                }
                names.add(name);
                ringsPerFeature.add(rings);
            }

            if (names.isEmpty()) {
                log.warn("[MAP] {} contained no usable polygons; the map page will show the table only",
                        RESOURCE);
                return;
            }

            // The projection constants (identical formula for paths, labels and heat).
            lonScale = Math.cos(Math.toRadians((minLat + maxLat) / 2));
            widthUnits = Math.max(0.01, (maxLon - minLon) * lonScale);
            heightUnits = Math.max(0.01, maxLat - minLat);
            innerW = VIEW_W - 2 * PAD;
            innerH = VIEW_H - 2 * PAD;

            // Pass 2: project + build one path per district code.
            StringBuilder outline = new StringBuilder();
            for (int i = 0; i < names.size(); i++) {
                String path = buildPath(ringsPerFeature.get(i));
                if (path.isEmpty()) {
                    continue;
                }
                outline.append(path);
                for (String code : codesFor(names.get(i))) {
                    pathByCode.putIfAbsent(code, path);
                    labelByCode.putIfAbsent(code, centroid(ringsPerFeature.get(i)));
                }
            }
            outlinePath = outline.length() == 0 ? "M 0 0" : outline.toString();
            ready = true;
            log.info("[MAP] {} loaded: {} district shape(s), {} code(s), viewBox {}x{}",
                    RESOURCE, names.size(), pathByCode.size(), VIEW_W, VIEW_H);
        } catch (Exception e) {
            log.warn("[MAP] Could not read {} ({}). "
                    + "The /map page will list the districts without the picture.",
                    RESOURCE, e.getMessage());
        }
    }

    /** The outer ring of a Polygon, or the outer ring of every part of a MultiPolygon. */
    private List<double[][]> outerRings(JsonNode geometry) {
        List<double[][]> rings = new ArrayList<>();
        String type = geometry.path("type").asText("");
        JsonNode coordinates = geometry.path("coordinates");
        if ("Polygon".equals(type)) {
            double[][] ring = ring(coordinates.path(0));
            if (ring.length > 2) {
                rings.add(ring);
            }
        } else if ("MultiPolygon".equals(type)) {
            for (JsonNode polygon : coordinates) {
                double[][] ring = ring(polygon.path(0));
                if (ring.length > 2) {
                    rings.add(ring);
                }
            }
        }
        return rings;
    }

    private double[][] ring(JsonNode points) {
        double[][] out = new double[points.size()][];
        for (int i = 0; i < points.size(); i++) {
            JsonNode p = points.get(i);
            out[i] = new double[]{p.path(0).asDouble(), p.path(1).asDouble()};
        }
        return out;
    }

    /** Builds "M x,y L x,y ... Z" for every ring of one feature. */
    private String buildPath(List<double[][]> rings) {
        StringBuilder d = new StringBuilder();
        for (double[][] ring : rings) {
            for (int i = 0; i < ring.length; i++) {
                d.append(i == 0 ? 'M' : 'L')
                        .append(coordinate(px(ring[i][0]))).append(',')
                        .append(coordinate(py(ring[i][1])));
            }
            d.append('Z');
        }
        return d.toString();
    }

    /**
     * Where to print a district's name, plus how much room it has.
     *
     * The label follows the BIGGEST part of the shape (a district such as Pune
     * has one main polygon), and the area decides whether a name fits at all.
     * Returns {x, y, area}.
     */
    private double[] centroid(List<double[][]> rings) {
        double bestArea = -1;
        double[] best = {0, 0, 0};
        for (double[][] ring : rings) {
            double rx = 0, ry = 0;
            for (double[] point : ring) {
                rx += px(point[0]);
                ry += py(point[1]);
            }
            double area = Math.abs(shoelace(ring));
            if (area > bestArea) {
                bestArea = area;
                best = new double[]{rx / ring.length, ry / ring.length, area};
            }
        }
        return best;
    }

    /** Signed area of a projected ring (used only to pick the label spot). */
    private double shoelace(double[][] ring) {
        double sum = 0;
        for (int i = 0; i < ring.length; i++) {
            double[] a = ring[i];
            double[] b = ring[(i + 1) % ring.length];
            sum += px(a[0]) * py(b[1]) - px(b[0]) * py(a[1]);
        }
        return sum / 2;
    }

    /* ---- the projection (one formula, used everywhere) ---- */
    private double px(double lon) {
        return PAD + (lon - minLon) * lonScale / widthUnits * innerW;
    }

    private double py(double lat) {
        return PAD + (maxLat - lat) / heightUnits * innerH;
    }

    private static double round(double v) {
        return Math.round(v * 10) / 10.0;
    }

    /** One decimal place keeps the SVG small while staying accurate on screen. */
    private static String coordinate(double v) {
        return String.valueOf(Math.round(v * 10) / 10.0);
    }

    /**
     * GeoJSON district name -> district CODE(s).
     *
     * The default is a lower-case slug ("Ahmednagar" -> "ahmednagar"), which
     * matches every row of the `districts` table except Mumbai: the open file
     * has ONE "Mumbai" polygon while the table has two districts, so the same
     * shape is registered for both.
     */
    private List<String> codesFor(String geoName) {
        String slug = geoName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        if ("mumbai".equals(slug)) {
            return List.of("mumbai-city", "mumbai-suburban");
        }
        Map<String, String> aliases = nameAliases();
        String mapped = aliases.get(slug);
        return List.of(mapped == null ? slug : mapped);
    }

    /** Spelling differences between the boundary file and the `districts` table. */
    private Map<String, String> nameAliases() {
        Map<String, String> aliases = new LinkedHashMap<>();
        // The boundary file is older than the 2023 renaming, so nothing is needed
        // for Ahmednagar/Osmanabad/Aurangabad today. Kept as the single place to
        // add an alias if the file is ever replaced.
        return aliases;
    }
}
