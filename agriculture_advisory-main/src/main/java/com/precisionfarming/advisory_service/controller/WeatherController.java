package com.precisionfarming.advisory_service.controller;

import com.precisionfarming.advisory_service.model.Alert;
import com.precisionfarming.advisory_service.model.District;
import com.precisionfarming.advisory_service.model.DistrictCrop;
import com.precisionfarming.advisory_service.model.FarmerProfile;
import com.precisionfarming.advisory_service.model.HeatCell;
import com.precisionfarming.advisory_service.model.HeatRect;
import com.precisionfarming.advisory_service.model.MapRow;
import com.precisionfarming.advisory_service.model.MapShape;
import com.precisionfarming.advisory_service.model.ReferenceData;
import com.precisionfarming.advisory_service.model.User;
import com.precisionfarming.advisory_service.model.WeatherDay;
import com.precisionfarming.advisory_service.model.WeatherReading;
import com.precisionfarming.advisory_service.repository.AlertDao;
import com.precisionfarming.advisory_service.repository.DistrictCropDao;
import com.precisionfarming.advisory_service.repository.DistrictDao;
import com.precisionfarming.advisory_service.repository.FarmerProfileDao;
import com.precisionfarming.advisory_service.repository.UserRepository;
import com.precisionfarming.advisory_service.repository.WeatherDao;
import com.precisionfarming.advisory_service.service.GeoMapService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.format.DateTimeFormatter;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Weather workspaces 2, 3 and the district map - STEP 4.
 *
 *   GET /nowcast    current conditions for the farmer's district + alerts
 *   GET /forecast   the 7-day outlook for the farmer's district
 *   GET /map        an inline SVG of Maharashtra, one dot per district HQ
 *
 * IMPORTANT: these three pages READ ONLY FROM weather_cache / weather_forecast.
 * They never make an HTTP request, so a page view can never hang on a slow
 * scrape and the demo keeps working while the college wifi is down.
 *
 * Role: any authenticated user. A FARMER with a saved district gets their own
 * district by default; everyone can still pick another district from the
 * picker (that is public weather data, not private data).
 *
 * NO JAVASCRIPT: the district picker is a normal GET form, the map layer
 * switcher is three links, and every dot on the map is a link.
 *
 * If this breaks, check:
 *   - "No reading yet" on every district -> WeatherService has not run; open
 *     /debug and look at weather_scrape_log, or run data_new.sql section 6.
 *   - A dot is grey -> that district has no row in weather_cache.
 *   - Text as a missing key -> nowcast.* / forecast.* / map.* keys.
 */
@Controller
public class WeatherController {

    private static final Logger log = LoggerFactory.getLogger(WeatherController.class);

    /** How many days of the outlook the pages show. */
    private static final int FORECAST_DAYS = 7;

    /* ---- SVG canvas. 820x660 keeps roughly the real proportions of
            Maharashtra, because 1 degree of longitude is only ~0.94 as long as
            one degree of latitude at this latitude. ---- */
    private static final double MAP_W = 820;
    private static final double MAP_H = 660;
    private static final double MAP_PAD = 44;

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm");

    private final WeatherDao weatherDao;
    private final DistrictDao districts;
    private final FarmerProfileDao profiles;
    private final AlertDao alerts;
    private final UserRepository users;
    private final DistrictCropDao districtCrops;
    private final GeoMapService geoMap;
    private final MessageSource messages;

    public WeatherController(WeatherDao weatherDao, DistrictDao districts,
                             FarmerProfileDao profiles, AlertDao alerts, UserRepository users,
                             DistrictCropDao districtCrops, GeoMapService geoMap,
                             MessageSource messages) {
        this.weatherDao = weatherDao;
        this.districts = districts;
        this.profiles = profiles;
        this.alerts = alerts;
        this.users = users;
        this.districtCrops = districtCrops;
        this.geoMap = geoMap;
        this.messages = messages;
    }

    /* ============================ 2. NOWCAST ============================ */

    /**
     * GET /nowcast
     * Role: any authenticated user
     * Model: districts        -> all 36 districts for the picker
     *        selectedCode     -> district code currently shown
     *        selectedName     -> its name in the active language
     *        reading          -> newest WeatherReading from the cache, or null
     *        lastUpdated      -> formatted timestamp ("dd MMM yyyy, HH:mm")
     *        farmerDistrict   -> true when this is the farmer's own district
     *        personalAlerts   -> List<Alert> raised by the crop + forecast rules
     *        generalAlerts    -> List<Alert> that are not personalised
     * Template: pages/nowcast.html
     */
    @GetMapping("/nowcast")
    public String nowcast(Authentication auth, Locale locale, Model model,
                          @RequestParam(required = false) String district) {

        List<District> all = districts.findAll();
        District target = targetDistrict(auth, district, all);
        model.addAttribute("districts", all);

        if (target == null) {
            model.addAttribute("reading", null);
            model.addAttribute("selectedCode", null);
            model.addAttribute("personalAlerts", List.of());
            model.addAttribute("generalAlerts", List.of());
            return "pages/nowcast";
        }

        Optional<WeatherReading> reading = weatherDao.latestForDistrict(target.nameEn());
        model.addAttribute("selectedCode", target.code());
        model.addAttribute("selectedName", target.nameFor(locale.getLanguage()));
        model.addAttribute("reading", reading.orElse(null));
        model.addAttribute("lastUpdated",
                reading.map(WeatherController::stamp).orElse(null));
        // PART A: name the source that actually supplied this reading.
        model.addAttribute("source",
                reading.map(WeatherReading::getSource).orElse(null));
        model.addAttribute("farmerDistrict", isFarmersOwnDistrict(auth, target));

        addAlerts(auth, model);
        log.debug("[WEATHER] /nowcast district={} reading={}", target.nameEn(), reading.isPresent());
        return "pages/nowcast";
    }

    /* ============================ 3. FORECAST ============================ */

    /**
     * GET /forecast  -  PART D: the outlook in detail.
     *
     * Role: any authenticated user
     *
     * WHAT THE PAGE SHOWS
     *   1. one card per day  - weekday, date, sky word, max/min, rain (the stored
     *      figure with its own unit), humidity, wind and ONE plain-language
     *      farming tip chosen by the rules below;
     *   2. two SVG charts drawn on the SERVER (temperature lines + rain bars);
     *   3. a compact day-by-day table for anyone who prefers numbers.
     *
     * Model: districts      -> all 36 districts for the picker
     *        selectedCode   -> district code currently shown
     *        selectedName   -> its name in the active language
     *        days           -> List<WeatherDay>, one row per day, from the cache
     *        cards          -> List<ForecastCard>, the same days already worded
     *        tempMarks / rainBars / chartMaxPoints / chartMinPoints
     *                       -> the two charts, projected to SVG pixels in Java
     *        chartW/H/Pad/Slot/Base/High/Low/RainMax -> the chart geometry
     *        taluka         -> the watched block the rows belong to, or null
     * Template: pages/forecast.html
     */
    @GetMapping("/forecast")
    public String forecast(Authentication auth, Locale locale, Model model,
                           @RequestParam(required = false) String district) {

        List<District> all = districts.findAll();
        District target = targetDistrict(auth, district, all);
        model.addAttribute("districts", all);

        if (target == null) {
            model.addAttribute("days", List.of());
            model.addAttribute("cards", List.of());
            model.addAttribute("taluka", null);
            model.addAttribute("selectedCode", null);
            model.addAttribute("selectedName", null);
            model.addAttribute("lastUpdated", null);
            model.addAttribute("source", null);
            addChart(model, List.of());
            return "pages/forecast";
        }

        model.addAttribute("selectedCode", target.code());
        model.addAttribute("selectedName", target.nameFor(locale.getLanguage()));

        // Prefer the exact block page (district + taluka) when the farmer's own
        // taluka is watched; otherwise show every watched block of the district.
        String taluka = farmerTaluka(auth);
        List<WeatherDay> days = taluka == null
                ? weatherDao.forecastForDistrict(target.nameEn(), FORECAST_DAYS)
                : weatherDao.forecastForLocation(target.nameEn(), taluka, FORECAST_DAYS);
        if (days.isEmpty()) {
            days = weatherDao.forecastForDistrict(target.nameEn(), FORECAST_DAYS);
            taluka = null;
        }
        // A district may be watched at more than one block, so the query can
        // return the same day twice. The cards and the two charts read one block
        // at a time, so the FIRST row of each day wins and every day appears once.
        Map<LocalDate, WeatherDay> onePerDay = new LinkedHashMap<>();
        for (WeatherDay d : days) {
            onePerDay.putIfAbsent(d.getDay(), d);
        }
        days = new ArrayList<>(onePerDay.values());

        model.addAttribute("days", days);
        model.addAttribute("taluka", taluka);
        // The "last updated" stamp and source for the outlook: the newest
        // updated_at among the shown days, and the source those rows carry.
        LocalDateTime updated = null;
        for (WeatherDay d : days) {
            if (d.getUpdatedAt() != null && (updated == null || d.getUpdatedAt().isAfter(updated))) {
                updated = d.getUpdatedAt();
            }
        }
        model.addAttribute("lastUpdated", updated == null ? null : updated.format(STAMP));
        model.addAttribute("source", days.isEmpty() ? null : days.get(0).getSource());

        /* ---- 1. the day cards, with their advice line ---- */
        List<ForecastCard> cards = new ArrayList<>();
        for (WeatherDay d : days) {
            String wd = d.getDay().getDayOfWeek().name().toLowerCase(Locale.ROOT);
            cards.add(new ForecastCard(
                    messages.getMessage("forecast.weekday." + wd, null, wd, locale),
                    d.getDay().toString(),
                    conditionKey(d),
                    d.getTempMax(),
                    d.getTempMin(),
                    d.getRain(),
                    rainIsAmount(d.getRain()) ? "forecast.rain_amount" : "forecast.rain_chance",
                    d.getHumidity(),
                    d.getWind(),
                    tipKey(d),
                    d.getSource(),
                    d.isSample()));
        }
        model.addAttribute("cards", cards);

        /* ---- 2. the two charts ---- */
        addChart(model, days);

        log.debug("[WEATHER] /forecast district={} days={} cards={}", target.nameEn(), days.size(), cards.size());
        return "pages/forecast";
    }

    /* ---------------------- PART D: the rules + the charts ---------------------- */

    /* ---- SVG canvas of the two forecast charts. Both share one x grid, so the
            day labels line up under the temperature lines and the rain bars. ---- */
    private static final int CHART_W = 700;
    private static final int CHART_H = 200;
    private static final int CHART_PAD_L = 36;
    private static final int CHART_PAD_R = 16;
    private static final int CHART_TOP = 18;
    private static final int CHART_BASE = 174;

    /**
     * Projects the seven days onto the two SVG charts. Everything is computed
     * here in Java - the browser only draws the numbers it is given, because
     * JavaScript is not allowed anywhere in this project.
     */
    private void addChart(Model model, List<WeatherDay> days) {
        model.addAttribute("chartW", CHART_W);
        model.addAttribute("chartH", CHART_H);
        model.addAttribute("chartPad", CHART_PAD_L);
        model.addAttribute("chartBase", CHART_BASE);

        int n = days.size();
        if (n == 0) {
            model.addAttribute("chartDays", 0);
            model.addAttribute("chartSlot", 0);
            model.addAttribute("tempMarks", List.of());
            model.addAttribute("rainBars", List.of());
            model.addAttribute("chartMaxPoints", "");
            model.addAttribute("chartMinPoints", "");
            model.addAttribute("chartHigh", 0);
            model.addAttribute("chartLow", 0);
            model.addAttribute("chartRainMax", 0);
            return;
        }
        int slot = (CHART_W - CHART_PAD_L - CHART_PAD_R) / n;

        // The temperature scale spans the coldest night to the hottest day with
        // a two degree margin, so the lines never touch the top or the bottom.
        int lo = Integer.MAX_VALUE;
        int hi = Integer.MIN_VALUE;
        int rainMax = 1;
        for (WeatherDay d : days) {
            Integer mx = WeatherReading.numberIn(d.getTempMax());
            Integer mn = WeatherReading.numberIn(d.getTempMin());
            if (mx != null) {
                hi = Math.max(hi, mx);
            }
            if (mn != null) {
                lo = Math.min(lo, mn);
            }
            Integer r = WeatherReading.numberIn(d.getRain());
            if (r != null) {
                rainMax = Math.max(rainMax, r);
            }
        }
        lo = lo == Integer.MAX_VALUE ? 0 : lo - 2;
        hi = hi == Integer.MIN_VALUE ? 1 : hi + 2;
        int span = Math.max(1, hi - lo);
        int plot = CHART_BASE - CHART_TOP;

        List<DayMark> marks = new ArrayList<>();
        List<RainBar> bars = new ArrayList<>();
        StringBuilder maxPts = new StringBuilder();
        StringBuilder minPts = new StringBuilder();

        for (int i = 0; i < n; i++) {
            WeatherDay d = days.get(i);
            int cx = CHART_PAD_L + slot * i + slot / 2;
            Integer mx = WeatherReading.numberIn(d.getTempMax());
            Integer mn = WeatherReading.numberIn(d.getTempMin());
            int yMax = mx == null ? CHART_BASE : CHART_TOP + (hi - mx) * plot / span;
            int yMin = mn == null ? CHART_BASE : CHART_TOP + (hi - mn) * plot / span;
            if (mx != null) {
                if (maxPts.length() > 0) {
                    maxPts.append(' ');
                }
                maxPts.append(cx).append(',').append(yMax);
            }
            if (mn != null) {
                if (minPts.length() > 0) {
                    minPts.append(' ');
                }
                minPts.append(cx).append(',').append(yMin);
            }
            marks.add(new DayMark(cx, yMax, yMin, d.getTempMax(), d.getTempMin()));

            // The rain bar is scaled to its own maximum, so 0-100 % and 0-9 mm
            // both read well. The tooltip carries the figure with its unit.
            Integer r = WeatherReading.numberIn(d.getRain());
            int barH = r == null ? 0 : Math.max(2, r * plot / rainMax);
            int barW = Math.max(10, slot / 2);
            bars.add(new RainBar(cx - barW / 2, CHART_BASE - barH, barW, barH, d.getRain()));
        }

        model.addAttribute("chartDays", n);
        model.addAttribute("chartSlot", slot);
        model.addAttribute("tempMarks", marks);
        model.addAttribute("rainBars", bars);
        model.addAttribute("chartMaxPoints", maxPts.toString());
        model.addAttribute("chartMinPoints", minPts.toString());
        model.addAttribute("chartHigh", hi - 2);
        model.addAttribute("chartLow", lo + 2);
        model.addAttribute("chartRainMax", rainMax);
    }

    /**
     * A short sky word derived from the stored rain figure. This is OUR label,
     * not a claim about what the source published - the source column and the
     * SAMPLE badge stay visible next to it.
     */
    private String conditionKey(WeatherDay d) {
        Integer rain = WeatherReading.numberIn(d.getRain());
        if (rain == null) {
            return "forecast.cond.clear";
        }
        if (rainIsAmount(d.getRain())) {
            if (rain >= 5) {
                return "forecast.cond.rainy";
            }
            return rain >= 1 ? "forecast.cond.cloudy" : "forecast.cond.clear";
        }
        if (rain >= 60) {
            return "forecast.cond.rainy";
        }
        return rain >= 30 ? "forecast.cond.cloudy" : "forecast.cond.clear";
    }

    /**
     * ONE plain-language farming tip for a day, picked by simple rules in a
     * fixed order of importance (heavy rain, heat, cold, wind, humidity, light
     * rain, otherwise a good day). The tip is a message KEY, so it is written in
     * the reader's own language.
     */
    private String tipKey(WeatherDay d) {
        boolean mm = rainIsAmount(d.getRain());
        Integer rain = WeatherReading.numberIn(d.getRain());
        Integer max = WeatherReading.numberIn(d.getTempMax());
        Integer min = WeatherReading.numberIn(d.getTempMin());
        Integer hum = WeatherReading.numberIn(d.getHumidity());
        Integer wind = WeatherReading.numberIn(d.getWind());

        if (rain != null && (mm ? rain >= 5 : rain >= 60)) {
            return "forecast.tip.rain";
        }
        if (max != null && max >= 38) {
            return "forecast.tip.hot";
        }
        if (min != null && min <= 10) {
            return "forecast.tip.cold";
        }
        if (wind != null && wind >= 25) {
            return "forecast.tip.windy";
        }
        if (hum != null && hum >= 80) {
            return "forecast.tip.humid";
        }
        if (rain != null && (mm ? rain >= 1 : rain >= 30)) {
            return "forecast.tip.lightrain";
        }
        return "forecast.tip.good";
    }

    /**
     * True when the stored rain figure is a DEPTH in millimetres (greenalerts)
     * rather than a CHANCE in percent (Open-Meteo). The two sources publish
     * different things, so the label follows the unit that was actually stored.
     */
    private static boolean rainIsAmount(String rain) {
        return rain != null && rain.toLowerCase(Locale.ROOT).contains("mm");
    }

    /** One day of the outlook, already worded for the page. */
    private record ForecastCard(String weekday, String dateText, String conditionKey,
                                String tempMax, String tempMin,
                                String rainValue, String rainLabelKey, String humidity,
                                String wind, String tipKey, String source, boolean sample) {
    }

    /** One day's two temperature values, already projected to SVG pixels. */
    private record DayMark(int x, int yMax, int yMin, String maxText, String minText) {
    }

    /** One rain bar, already projected to SVG pixels. */
    private record RainBar(int x, int y, int w, int h, String text) {
    }

    /* ============================== 4. MAP ============================== */

    /** PART C: the layer whitelist. A hand-typed ?layer= falls back to "temp". */
    private static final List<String> LAYERS =
            List.of("temp", "heat", "rain", "rainheat", "wind", "crop");

    /** Heat-map grid resolution (about 50 x 60 = 3000 cells, as specified). */
    private static final int HEAT_COLS = 50;
    private static final int HEAT_ROWS = 60;

    /* The temperature bands asked for in the specification (<15, 15-20, 20-25,
       25-30, 30-35, >35) and the rain / wind bands used by the same six colours. */
    private static final int[] TEMP_EDGES = {15, 20, 25, 30, 35};
    private static final List<String> TEMP_LABELS =
            List.of("< 15", "15-20", "20-25", "25-30", "30-35", "> 35");
    private static final int[] RAIN_EDGES = {20, 40, 60, 80};
    private static final List<String> RAIN_LABELS =
            List.of("0-20", "20-40", "40-60", "60-80", "80-100");
    private static final int[] WIND_EDGES = {5, 10, 15, 20, 25};
    private static final List<String> WIND_LABELS =
            List.of("< 5", "5-10", "10-15", "15-20", "20-25", "> 25");

    /**
     * GET /map  -  PART C: REAL GIS MAPS.
     *
     * Role: any authenticated user
     *
     * LAYERS (ordinary links, no JavaScript)
     *   temp      temperature polygons, 6 bands with a legend
     *   heat      interpolated temperature surface (IDW grid) + district borders
     *   rain      rain-chance polygons, 5 bands (0-20 .. 80-100)
     *   rainheat  interpolated rain-chance surface
     *   wind      wind-speed polygons, 6 bands
     *   crop      majority crop per district, with a toggle between
     *             crop=ref (table district_crops, SAMPLE) and
     *             crop=live (computed from farmer_profiles.crops)
     *
     * Model: shapes       -> List<MapShape>, each an SVG path + band class + tooltip
     *        rows         -> List<MapRow>   the readable table below the picture
     *        layer / cropSource / heatMode / legendLabels / legendCrops / cropNote
     *        heatRects    -> the interpolated cells (heat layers only)
     *        outlinePath  -> the state outline used as the SVG <clipPath>
     *        geoReady     -> false when the boundary file is missing
     *        districts / selectedCode / selectedName / selected / reading /
     *        readingTime / selectedCrop / selectedFarmers / selectedDays
     *        mapW / mapH  -> the SVG viewBox size
     * Template: pages/map.html
     */
    @GetMapping("/map")
    public String map(Authentication auth, Locale locale, Model model,
                      @RequestParam(required = false) String district,
                      @RequestParam(required = false, defaultValue = "temp") String layer,
                      @RequestParam(required = false, defaultValue = "ref") String crop) {

        String activeLayer = LAYERS.contains(layer) ? layer : "temp";
        String cropSource = "live".equalsIgnoreCase(crop) ? "live" : "ref";
        boolean cropLayer = "crop".equals(activeLayer);
        boolean heatMode = "heat".equals(activeLayer) || "rainheat".equals(activeLayer);
        String base = baseLayer(activeLayer);

        List<District> all = districts.findAll();
        Map<String, WeatherReading> latest = weatherDao.latestPerDistrict();
        Map<String, DistrictCrop> refCrops = districtCrops.majorityByDistrict();
        LiveCrops live = liveCrops(all);

        District selected = targetDistrict(auth, district, all);

        List<MapShape> shapes = new ArrayList<>();
        List<MapRow> rows = new ArrayList<>();
        List<double[]> heatSamples = new ArrayList<>();
        MapShape selectedShape = null;

        for (District d : all) {
            String name = d.nameFor(locale.getLanguage());
            WeatherReading reading = latest.get(d.nameEn());
            int farmers = live.countByCode.getOrDefault(d.code(), 0);

            String cssClass;
            String tooltip;
            String valueText;
            double sortValue;

            if (cropLayer) {
                String cropKey = majorityCrop(d.code(), cropSource, refCrops, live);
                String cropLabel = cropKey == null ? null
                        : messages.getMessage("crop." + cropKey, null, cropKey, locale);
                cssClass = cropKey == null ? "band-none" : "crop-" + cropKey;
                tooltip = cropKey == null ? name : name + " - " + cropLabel;
                valueText = cropLabel;
                sortValue = 0;
            } else {
                Integer value = valueFor(reading, base);
                int band = bandFor(value, base);
                cssClass = band < 0 ? "band-none" : "band-" + band;
                tooltip = value == null ? name : name + " - " + textFor(reading, base);
                valueText = textFor(reading, base);
                sortValue = value == null ? -1 : value;
                if (value != null && d.lat() != null && d.lon() != null) {
                    heatSamples.add(new double[]{d.lon(), d.lat(), value});
                }
            }

            String path = geoMap.path(d.code());
            if (path != null) {
                // Only name the districts that have room for a label.
                boolean label = geoMap.labelFits(d.code()) && !"mumbai-city".equals(d.code());
                MapShape shape = new MapShape(d.code(), name, path, cssClass, tooltip,
                        label, geoMap.labelX(d.code()), geoMap.labelY(d.code()));
                if (selected != null && selected.code().equals(d.code())) {
                    selectedShape = shape;         // drawn LAST so its highlight shows
                } else {
                    shapes.add(shape);
                }
            }
            rows.add(new MapRow(d.code(), name, valueText, cssClass, farmers, sortValue));
        }
        if (selectedShape != null) {
            shapes.add(selectedShape);
        }
        // Table: biggest value first; the crop layer stays in district order.
        if (!cropLayer) {
            rows.sort(Comparator.comparingDouble(MapRow::sortValue).reversed()
                    .thenComparing(MapRow::name));
        }

        /* ---- the interpolated heat surface (cached in GeoMapService) ---- */
        List<HeatRect> heatRects = List.of();
        if (heatMode && geoMap.ready() && !heatSamples.isEmpty()) {
            double sum = 0;
            for (double[] s : heatSamples) {
                sum += s[2];
            }
            String key = activeLayer + "#" + heatSamples.size() + "#" + Math.round(sum);
            List<HeatCell> cells = geoMap.heat(key, heatSamples, HEAT_COLS, HEAT_ROWS);
            List<HeatRect> out = new ArrayList<>(cells.size());
            for (HeatCell cell : cells) {
                int band = bandFor((int) Math.round(cell.value()), base);
                if (band >= 0) {
                    out.add(new HeatRect("band-" + band, cell.x(), cell.y(), cell.w(), cell.h()));
                }
            }
            heatRects = out;
        }

        /* ---- the selected district's side tile ---- */
        String selectedCropKey = selected == null ? null
                : majorityCrop(selected.code(), cropSource, refCrops, live);
        model.addAttribute("selectedCrop", selectedCropKey == null ? null
                : messages.getMessage("crop." + selectedCropKey, null, selectedCropKey, locale));
        model.addAttribute("selectedFarmers", selected == null ? 0
                : live.countByCode.getOrDefault(selected.code(), 0));
        model.addAttribute("selectedDays", selected == null ? List.of()
                : weatherDao.forecastForDistrict(selected.nameEn(), FORECAST_DAYS));

        model.addAttribute("shapes", shapes);
        model.addAttribute("rows", rows);
        model.addAttribute("layer", activeLayer);
        model.addAttribute("cropSource", cropSource);
        model.addAttribute("cropLayer", cropLayer);
        model.addAttribute("heatMode", heatMode);
        model.addAttribute("legendLabels", legendLabels(activeLayer));
        model.addAttribute("legendCrops", cropLayer ? ReferenceData.CROPS : List.of());
        model.addAttribute("cropNote", cropLayer && "ref".equals(cropSource) && districtCrops.anySample());
        model.addAttribute("heatRects", heatRects);
        model.addAttribute("outlinePath", geoMap.outlinePath());
        model.addAttribute("geoReady", geoMap.ready());
        model.addAttribute("districts", all);
        model.addAttribute("selectedCode", selected == null ? null : selected.code());
        model.addAttribute("selectedName",
                selected == null ? null : selected.nameFor(locale.getLanguage()));
        model.addAttribute("selected", selectedShape);
        model.addAttribute("reading", selected == null ? null : latest.get(selected.nameEn()));
        model.addAttribute("readingTime", selected == null ? null
                : stamp(latest.get(selected.nameEn())));
        model.addAttribute("mapW", (int) MAP_W);
        model.addAttribute("mapH", (int) MAP_H);

        log.debug("[WEATHER] /map layer={} shapes={} heat={} selected={}",
                activeLayer, shapes.size(), heatRects.size(),
                selected == null ? "-" : selected.code());
        return "pages/map";
    }

    /** heat -> temp, rainheat -> rain, everything else unchanged. */
    private static String baseLayer(String layer) {
        return switch (layer) {
            case "heat" -> "temp";
            case "rainheat" -> "rain";
            default -> layer;
        };
    }

    /** The six legend ranges of a band layer; the crop layer has its own legend. */
    private List<String> legendLabels(String layer) {
        return switch (baseLayer(layer)) {
            case "rain" -> RAIN_LABELS;
            case "wind" -> WIND_LABELS;
            default -> "crop".equals(layer) ? List.of() : TEMP_LABELS;
        };
    }

    /**
     * The band index of one value: the number of thresholds the value has
     * passed. -1 means "no reading", drawn grey. Each layer has its own edges,
     * so the same code paints temperature (6 bands), rain (5) and wind (6).
     */
    private int bandFor(Integer value, String base) {
        if (value == null) {
            return -1;
        }
        int[] edges = switch (base) {
            case "rain" -> RAIN_EDGES;
            case "wind" -> WIND_EDGES;
            default -> TEMP_EDGES;
        };
        for (int i = 0; i < edges.length; i++) {
            if (value < edges[i]) {
                return i;
            }
        }
        return edges.length;
    }

    /** The number a band layer colours a district by. */
    private Integer valueFor(WeatherReading reading, String base) {
        if (reading == null) {
            return null;
        }
        return switch (base) {
            case "rain" -> reading.rainNumber();
            case "wind" -> reading.windNumber();
            default -> reading.tempCelsius();
        };
    }

    /** The text shown in a district's tooltip / table cell, unit included. */
    private String textFor(WeatherReading reading, String base) {
        if (reading == null) {
            return null;
        }
        return switch (base) {
            case "rain" -> reading.getRain();
            case "wind" -> reading.getWind();
            default -> reading.getTemperature();
        };
    }

    /**
     * The crop the crop map shows for one district.
     *   ref  -> the district_crops table (SAMPLE, unless a real dataset is loaded)
     *   live -> computed from the crops the registered farmers in that district saved
     */
    private String majorityCrop(String code, String cropSource,
                                Map<String, DistrictCrop> refCrops, LiveCrops live) {
        if ("live".equals(cropSource)) {
            return live.cropByCode.get(code);
        }
        DistrictCrop row = refCrops.get(code);
        return row == null ? null : row.crop();
    }

    /**
     * The LIVE crop data: for every district, which crop the most registered
     * farmers grow, and how many farmer profiles the number is based on.
     *
     * Farmers save crops either as codes ("rice,onion") or as free words, so only
     * the codes in ReferenceData.CROPS are counted - a typo can never invent a
     * crop on the map.
     */
    private LiveCrops liveCrops(List<District> all) {
        Map<String, Map<String, Integer>> countsByName = new HashMap<>();
        Map<String, Integer> farmersByName = new HashMap<>();

        for (FarmerProfile p : profiles.findAll()) {
            String district = p.getDistrict();
            if (district == null || district.isBlank()) {
                continue;
            }
            farmersByName.merge(district, 1, Integer::sum);
            if (p.getCrops() == null) {
                continue;
            }
            for (String raw : p.getCrops().split(",")) {
                String crop = raw.trim().toLowerCase(Locale.ROOT);
                if (ReferenceData.CROPS.contains(crop)) {
                    countsByName.computeIfAbsent(district, k -> new HashMap<>())
                            .merge(crop, 1, Integer::sum);
                }
            }
        }

        LiveCrops out = new LiveCrops();
        for (District d : all) {
            out.countByCode.put(d.code(), farmersByName.getOrDefault(d.nameEn(), 0));
            Map<String, Integer> counts = countsByName.get(d.nameEn());
            if (counts == null) {
                continue;
            }
            String topCrop = null;
            int topCount = 0;
            for (Map.Entry<String, Integer> e : counts.entrySet()) {
                if (e.getValue() > topCount) {
                    topCount = e.getValue();
                    topCrop = e.getKey();
                }
            }
            out.cropByCode.put(d.code(), topCrop);
        }
        return out;
    }

    /** District code -> live majority crop / farmer count. */
    private static final class LiveCrops {
        private final Map<String, String> cropByCode = new HashMap<>();
        private final Map<String, Integer> countByCode = new HashMap<>();
    }

    /* ============================== helpers ============================== */

    /**
     * Which district a page should show, in order of preference:
     *   1. ?district=<code> from the URL (used by the map dots and the picker)
     *   2. the district saved in the logged-in farmer's own profile
     *   3. the first district, so a page is never empty
     */
    private District targetDistrict(Authentication auth, String districtCode, List<District> all) {
        if (districtCode != null && !districtCode.isBlank()) {
            Optional<District> chosen = districts.findByCode(districtCode);
            if (chosen.isPresent()) {
                return chosen.get();
            }
        }
        String mine = farmerDistrict(auth);
        if (mine != null) {
            Optional<District> own = districts.findByName(mine);
            if (own.isPresent()) {
                return own.get();
            }
        }
        return all.isEmpty() ? null : all.get(0);
    }

    /** True when the shown district is the one saved in the farmer's profile. */
    private boolean isFarmersOwnDistrict(Authentication auth, District target) {
        String mine = farmerDistrict(auth);
        return mine != null && mine.equalsIgnoreCase(target.nameEn());
    }

    /** The logged-in farmer's district, or null for every other role / no profile. */
    private String farmerDistrict(Authentication auth) {
        Optional<FarmerProfile> profile = farmerProfile(auth);
        return profile.map(FarmerProfile::getDistrict)
                .filter(d -> d != null && !d.isBlank())
                .orElse(null);
    }

    private String farmerTaluka(Authentication auth) {
        return farmerProfile(auth).map(FarmerProfile::getTaluka)
                .filter(t -> t != null && !t.isBlank())
                .orElse(null);
    }

    /** Looks the logged-in farmer's profile up; empty for any other role. */
    private Optional<FarmerProfile> farmerProfile(Authentication auth) {
        if (auth == null || !"FARMER".equals(role(auth))) {
            return Optional.empty();
        }
        return users.findByUsername(auth.getName())
                .map(User::getId)
                .flatMap(profiles::findByUserId);
    }

    /** Personalised alerts (own data only) plus the general notices. */
    private void addAlerts(Authentication auth, Model model) {
        Long userId = userId(auth);
        if (userId == null) {
            model.addAttribute("personalAlerts", List.of());
            model.addAttribute("generalAlerts", List.of());
            return;
        }
        List<Alert> personal = alerts.findForUser(userId, "PERSONALIZED", 6);
        List<Alert> general = alerts.findForUser(userId, "GENERAL", 4);
        model.addAttribute("personalAlerts", personal);
        model.addAttribute("generalAlerts", general);
    }

    private Long userId(Authentication auth) {
        if (auth == null) {
            return null;
        }
        return users.findByUsername(auth.getName()).map(User::getId).orElse(null);
    }

    private static String stamp(WeatherReading reading) {
        if (reading == null || reading.getUpdatedAt() == null) {
            return null;
        }
        return reading.getUpdatedAt().format(STAMP);
    }

    /** ROLE_FARMER -> FARMER, without the Spring prefix. */
    private static String role(Authentication auth) {
        if (auth == null) {
            return "";
        }
        return auth.getAuthorities().stream()
                .map(a -> a.getAuthority().replace("ROLE_", ""))
                .findFirst().orElse("");
    }
}
