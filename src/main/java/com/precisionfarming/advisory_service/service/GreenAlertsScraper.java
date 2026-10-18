package com.precisionfarming.advisory_service.service;

import com.precisionfarming.advisory_service.model.WeatherConfig;
import com.precisionfarming.advisory_service.model.WeatherDay;
import com.precisionfarming.advisory_service.model.WeatherReading;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads the current weather and the 7-day outlook from greenalerts.in with
 * Jsoup. This is the primary source; OpenMeteoSource is the fallback.
 *
 * ===================== HOW THE SELECTORS WERE CHOSEN =====================
 * They were read off the LIVE pages with curl, not guessed, and robots.txt was
 * checked first (nothing under /weather-forecast-india/ is disallowed):
 *
 *   curl -A "$UA" https://www.greenalerts.in/robots.txt
 *   curl -A "$UA" https://www.greenalerts.in/weather-forecast-india/maharashtra/
 *   curl -A "$UA" https://www.greenalerts.in/weather-forecast-india/maharashtra/raigad/
 *   curl -A "$UA" https://www.greenalerts.in/weather-forecast-india/maharashtra/raigad/panvel/
 *
 * The site publishes the same data at three levels, so this class detects which
 * level it was handed:
 *
 * 1. BLOCK page  (e.g. .../maharashtra/raigad/panvel/)  -> current + 7 days
 *      article.ga-day                  one per day, id="day-YYYY-MM-DD"
 *      h3.ga-day__date time            datetime attribute = the real date
 *      span.ga-day__chip               sky text, e.g. "Partly cloudy"
 *      ul.ga-day__tiles li             one per measurement
 *        span.ga-day__tkey             label: Max temp / Min temp / Rainfall /
 *                                      Humidity / Cloud / Wind
 *        span.ga-day__tval             value with its unit, e.g. "33.5 °C"
 *
 * 2. DISTRICT page (e.g. .../maharashtra/raigad/)       -> current only
 *      .today-snapshot h2              heading, carries the date
 *      p.ts-lead strong                [1] = max temp, [2] = min temp,
 *                                      [3] = the sky sentence
 *      .ts-chip b                      "34.0° / 28.0°C" (fallback parse)
 *      .ga-source__meta dd             "India Meteorological Department"
 *
 * 3. STATE page  (e.g. .../maharashtra/)                -> no readings at all
 *      ul.ga-gp__list li a             only links to districts
 *    A state page therefore returns Optional.empty() and Open-Meteo answers.
 *
 * IMPORTANT: rainfall on this site is in MILLIMETRES, not a percentage, so the
 * value is stored exactly as published ("0 mm") and the UI shows the unit.
 * Nothing is converted or invented.
 *
 * POLITENESS: a real User-Agent, a 15s timeout, and WeatherService sleeps
 * between requests (pfas.weather.delay-ms) so the site is never hammered.
 */
@Component
public class GreenAlertsScraper implements WeatherSource {

    private static final Logger log = LoggerFactory.getLogger(GreenAlertsScraper.class);

    /** The tile labels the live block page uses. Taken from the real HTML. */
    private static final String KEY_MAX_TEMP = "Max temp";
    private static final String KEY_MIN_TEMP = "Min temp";
    private static final String KEY_RAINFALL = "Rainfall";
    private static final String KEY_HUMIDITY = "Humidity";
    private static final String KEY_WIND = "Wind";

    private final String userAgent;
    private final int timeoutMs;

    public GreenAlertsScraper(
            @Value("${pfas.weather.user-agent}") String userAgent,
            @Value("${pfas.weather.timeout-ms}") int timeoutMs) {
        this.userAgent = userAgent;
        this.timeoutMs = timeoutMs;
    }

    @Override
    public String name() {
        return "GREENALERTS";
    }

    @Override
    public Optional<WeatherSnapshot> fetch(WeatherConfig config) {
        try {
            Document doc = Jsoup.connect(config.scrapeUrl())
                    .userAgent(userAgent)
                    .timeout(timeoutMs)
                    .followRedirects(true)
                    .get();

            Elements dayCards = doc.select("article.ga-day");
            if (!dayCards.isEmpty()) {
                return blockPage(config, dayCards);
            }

            Element snapshot = doc.selectFirst(".today-snapshot");
            if (snapshot != null) {
                return districtPage(config, snapshot);
            }

            // A state page only lists districts - there is no reading to take.
            log.debug("[SCRAPER] {} has no reading (state/listing page)", config.scrapeUrl());
            return Optional.empty();

        } catch (Exception e) {
            // Never throw: WeatherService treats this as "try the next source".
            log.warn("[SCRAPER] greenalerts.in read failed for {} : {}",
                    config.scrapeUrl(), e.getMessage());
            return Optional.empty();
        }
    }

    /* ================= 1. BLOCK page: current + all forecast days ================= */

    private Optional<WeatherSnapshot> blockPage(WeatherConfig config, Elements dayCards) {
        List<WeatherDay> days = new ArrayList<>();

        for (Element card : dayCards) {
            Element time = card.selectFirst("h3.ga-day__date time");
            if (time == null) {
                continue;
            }
            LocalDate day;
            try {
                day = LocalDate.parse(time.attr("datetime"));
            } catch (Exception bad) {
                continue;                       // an unparsable date is skipped
            }

            Map<String, String> tiles = tileValues(card);
            days.add(new WeatherDay(
                    day,
                    tiles.get(KEY_MIN_TEMP),
                    tiles.get(KEY_MAX_TEMP),
                    tiles.get(KEY_RAINFALL),
                    tiles.get(KEY_HUMIDITY),
                    tiles.get(KEY_WIND),
                    skyOf(card),
                    name()));
        }

        if (days.isEmpty()) {
            return Optional.empty();
        }

        // The first card on the page is today, so it is the current conditions.
        // The site publishes today's MAXIMUM temperature, which is what the
        // "temperature" cell therefore holds; the UI names the source so the
        // number is never presented as a bare current reading.
        WeatherDay today = days.get(0);
        WeatherReading current = new WeatherReading();
        current.setState(config.state());
        current.setDistrict(config.district());
        current.setTaluka(config.taluka());
        current.setTemperature(today.getTempMax());
        current.setRain(today.getRain());
        current.setHumidity(today.getHumidity());
        current.setWind(today.getWind());
        current.setSummary(today.getSummary());
        current.setSource(name());

        log.info("[SCRAPER] {} {} : {} , {} day rows",
                config.location(), today.getDay(), today.getTempMax(), days.size());
        return Optional.of(new WeatherSnapshot(name(), current, days));
    }

    /** label -> value, e.g. "Max temp" -> "33.5 °C". */
    private Map<String, String> tileValues(Element dayCard) {
        Map<String, String> values = new HashMap<>();
        for (Element tile : dayCard.select("ul.ga-day__tiles li")) {
            Element key = tile.selectFirst("span.ga-day__tkey");
            Element val = tile.selectFirst("span.ga-day__tval");
            if (key != null && val != null) {
                values.put(key.text().trim(), val.text().trim());
            }
        }
        return values;
    }

    /** The sky words from span.ga-day__chip, with its inline SVG left out. */
    private String skyOf(Element dayCard) {
        Element chip = dayCard.selectFirst("span.ga-day__chip");
        if (chip == null) {
            return null;
        }
        String sky = chip.ownText().trim();     // ownText() skips the <svg> icon
        return sky.isEmpty() ? null : sky;
    }

    /* ================= 2. DISTRICT page: current conditions only ================= */

    private Optional<WeatherSnapshot> districtPage(WeatherConfig config, Element snapshot) {
        String max = null;
        String min = null;
        String sky = null;
        String rain = null;

        // "Today the weather in <strong>Raigad</strong> will have a maximum
        //  temperature of <strong>34.0°C</strong> and a minimum of
        //  <strong>28.0°C</strong>, with <strong>Rain ... evening</strong>."
        Elements strongs = snapshot.select("p.ts-lead strong");
        for (Element s : strongs) {
            String text = clean(s.text());
            if (text.contains("°C") || text.contains("&deg;")) {
                if (max == null) {
                    max = text;
                } else if (min == null) {
                    min = text;
                }
            }
        }
        if (!strongs.isEmpty()) {
            sky = clean(strongs.last().text());
        }

        // Fallback: the chip prints "34.0° / 28.0°C" in one piece.
        if (max == null) {
            Element chipValue = snapshot.selectFirst(".ts-chip b");
            if (chipValue != null) {
                String[] parts = clean(chipValue.text()).split("/");
                max = parts.length > 0 ? parts[0].trim() : null;
                min = parts.length > 1 ? parts[1].trim() : null;
            }
        }

        if (max == null && sky == null) {
            return Optional.empty();
        }

        WeatherReading current = new WeatherReading();
        current.setState(config.state());
        current.setDistrict(config.district());
        current.setTaluka(config.taluka());
        current.setTemperature(max);
        current.setRain(rain);
        current.setHumidity(null);
        current.setWind(null);
        current.setSummary(sky);
        current.setSource(name());

        log.info("[SCRAPER] {} {} (district page, no day rows)", config.location(), max);
        return Optional.of(new WeatherSnapshot(name(), current, List.of()));
    }

    private String clean(String text) {
        return text == null ? null : text.replace('\u00a0', ' ').trim();
    }
}
