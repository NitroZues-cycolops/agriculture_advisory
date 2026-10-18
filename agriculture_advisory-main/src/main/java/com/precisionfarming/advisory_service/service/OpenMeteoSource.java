package com.precisionfarming.advisory_service.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.precisionfarming.advisory_service.model.District;
import com.precisionfarming.advisory_service.model.WeatherConfig;
import com.precisionfarming.advisory_service.model.WeatherDay;
import com.precisionfarming.advisory_service.model.WeatherReading;
import com.precisionfarming.advisory_service.repository.DistrictDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The FALLBACK weather source: the public Open-Meteo API.
 *
 * WHY IT IS THE FALLBACK: greenalerts.in publishes IMD's official figures, but
 * a scrape can break (site down, layout changed, no internet in the lab).
 * Open-Meteo needs no key and no scraping, so the scheduled job still fills
 * weather_cache when the scrape fails. The trade-off is stated in the UI: this
 * source reports a RAIN CHANCE in percent, while greenalerts reports rainfall
 * in millimetres, and both are stored with their unit so neither is misread.
 *
 * The exact request used (verified live with curl):
 *   /v1/forecast?latitude=..&longitude=..
 *     &current=temperature_2m,relative_humidity_2m,wind_speed_10m
 *     &daily=temperature_2m_max,temperature_2m_min,
 *            precipitation_probability_max,wind_speed_10m_max
 *     &timezone=Asia/Kolkata&forecast_days=7
 *
 * Coordinates come from the `districts` table and are APPROXIMATE district
 * headquarters positions, which is accurate enough for a district-level demo.
 */
@Component
public class OpenMeteoSource implements WeatherSource {

    private static final Logger log = LoggerFactory.getLogger(OpenMeteoSource.class);

    private static final String ENDPOINT = "https://api.open-meteo.com/v1/forecast";

    private final DistrictDao districtDao;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http;
    private final String userAgent;
    private final int timeoutMs;

    public OpenMeteoSource(DistrictDao districtDao,
                           @Value("${pfas.weather.user-agent}") String userAgent,
                           @Value("${pfas.weather.timeout-ms}") int timeoutMs) {
        this.districtDao = districtDao;
        this.userAgent = userAgent;
        this.timeoutMs = timeoutMs;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(timeoutMs))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public String name() {
        return "OPENMETEO";
    }

    @Override
    public Optional<WeatherSnapshot> fetch(WeatherConfig config) {
        Optional<District> maybeDistrict = districtDao.findByName(config.district());
        if (maybeDistrict.isEmpty()
                || maybeDistrict.get().lat() == null
                || maybeDistrict.get().lon() == null) {
            log.warn("[WEATHER] Open-Meteo skipped: no coordinates for district {}",
                    config.district());
            return Optional.empty();
        }
        District district = maybeDistrict.get();

        try {
            String url = String.format(
                    "%s?latitude=%.4f&longitude=%.4f"
                    + "&current=temperature_2m,relative_humidity_2m,wind_speed_10m"
                    + "&daily=temperature_2m_max,temperature_2m_min,"
                    + "precipitation_probability_max,wind_speed_10m_max"
                    + "&timezone=Asia%%2FKolkata&forecast_days=7",
                    ENDPOINT, district.lat(), district.lon());

            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("User-Agent", userAgent)
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response =
                    http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("[WEATHER] Open-Meteo HTTP {} for {}", response.statusCode(),
                        config.location());
                return Optional.empty();
            }

            JsonNode root = mapper.readTree(response.body());
            JsonNode current = root.path("current");
            JsonNode daily = root.path("daily");

            WeatherReading reading = new WeatherReading();
            reading.setState(config.state());
            reading.setDistrict(config.district());
            reading.setTaluka(config.taluka());
            reading.setTemperature(whole(current.path("temperature_2m"), " °C"));
            reading.setHumidity(whole(current.path("relative_humidity_2m"), " %"));
            reading.setWind(whole(current.path("wind_speed_10m"), " km/h"));
            reading.setRain(firstOf(daily.path("precipitation_probability_max"), " %"));
            reading.setSummary(null);                 // this API has no plain sky word
            reading.setSource(name());

            List<WeatherDay> days = readDays(daily);
            log.info("[WEATHER] Open-Meteo {} : {} , {} day rows",
                    config.location(), reading.getTemperature(), days.size());
            return Optional.of(new WeatherSnapshot(name(), reading, days));

        } catch (Exception e) {
            log.warn("[WEATHER] Open-Meteo call failed for {} : {}",
                    config.location(), e.getMessage());
            return Optional.empty();
        }
    }

    /** Builds the 7 day rows from the parallel daily arrays. */
    private List<WeatherDay> readDays(JsonNode daily) {
        List<WeatherDay> days = new ArrayList<>();
        JsonNode dates = daily.path("time");
        if (!dates.isArray()) {
            return days;
        }
        for (int i = 0; i < dates.size(); i++) {
            LocalDate day;
            try {
                day = LocalDate.parse(dates.get(i).asText());
            } catch (Exception bad) {
                continue;
            }
            days.add(new WeatherDay(
                    day,
                    nodeOf(daily, "temperature_2m_min", i, " °C"),
                    nodeOf(daily, "temperature_2m_max", i, " °C"),
                    nodeOf(daily, "precipitation_probability_max", i, " %"),
                    null,                              // the daily block has no humidity
                    nodeOf(daily, "wind_speed_10m_max", i, " km/h"),
                    null,
                    name()));
        }
        return days;
    }

    private String nodeOf(JsonNode daily, String field, int index, String unit) {
        JsonNode array = daily.path(field);
        if (!array.isArray() || index >= array.size()) {
            return null;
        }
        return whole(array.get(index), unit);
    }

    private String firstOf(JsonNode array, String unit) {
        if (!array.isArray() || array.isEmpty()) {
            return null;
        }
        return whole(array.get(0), unit);
    }

    /** "31.7" + " °C" -> "32 °C"; a missing value stays missing. */
    private String whole(JsonNode value, String unit) {
        if (value == null || !value.isNumber()) {
            return null;
        }
        return Math.round(value.asDouble()) + unit;
    }
}
