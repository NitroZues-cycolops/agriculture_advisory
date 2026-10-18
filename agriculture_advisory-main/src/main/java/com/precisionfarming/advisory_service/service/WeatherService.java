package com.precisionfarming.advisory_service.service;

import com.precisionfarming.advisory_service.model.Alert;
import com.precisionfarming.advisory_service.model.FarmerProfile;
import com.precisionfarming.advisory_service.model.ScrapeLog;
import com.precisionfarming.advisory_service.model.WeatherConfig;
import com.precisionfarming.advisory_service.model.WeatherDay;
import com.precisionfarming.advisory_service.model.WeatherReading;
import com.precisionfarming.advisory_service.repository.AlertDao;
import com.precisionfarming.advisory_service.repository.FarmerProfileDao;
import com.precisionfarming.advisory_service.repository.WeatherDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The only place in the whole application that talks to the internet for weather.
 *
 * TWO JOBS
 * 1. REFRESH (once a day at 06:00 Asia/Kolkata, cron from weather.refresh.cron;
 *    plus a start-up catch-up when a district's data is missing or stale):
 *    for each row of `weather_config` it tries
 *    GreenAlertsScraper first and OpenMeteoSource second, then writes the result
 *    into weather_cache (current conditions) and weather_forecast (7 days), and
 *    records the outcome in weather_scrape_log so /debug can show it.
 *    If BOTH sources fail, nothing is deleted - the previous reading stays and
 *    the page simply shows an older "last updated" time.
 *
 * 2. ALERT RULES (right after a refresh): every farmer's district + crops are
 *    matched against the cached forecast. The matching rules below are simple
 *    on purpose so they can be explained line by line in the viva.
 *
 * EVERY PAGE READS ONLY FROM THE CACHE (see WeatherController): a page visit
 * never performs an HTTP request, so the demo cannot hang or fail because the
 * college wifi is down.
 *
 * If this breaks, check:
 *   - Log lines with [WEATHER] -> the scheduled refresh (enable DEBUG logging).
 *   - Log lines with [SCRAPER] -> the greenalerts.in scrape specifically.
 *   - weather_scrape_log rows -> query this table; /debug prints it too.
 *   - No alerts appear -> the farmer needs a district in `farmer_profiles`.
 */
@Service
public class WeatherService {

    private static final Logger log = LoggerFactory.getLogger(WeatherService.class);

    /* ---- rule thresholds, all in one place so they are easy to justify ---- */
    private static final int HEAT_TEMP_C = 35;         // "too hot" from now on
    private static final int RAIN_HIGH = 50;           // high rain figure
    private static final int RAIN_SOME = 30;           // some rain expected
    private static final int DRY_CROP_TEMP_C = 30;     // warm + dry -> irrigate
    private static final int SPRAY_RAIN = 40;          // wet -> fungal risk

    /* crop codes (or words) that make a rule fire; matched case-insensitively */
    private static final List<String> THIRSTY_CROPS =
            List.of("rice", "sugarcane", "onion", "बाजरी", "भात", "ऊस", "कांदा");
    private static final List<String> FUNGUS_PRONE_CROPS =
            List.of("grapes", "mango", "pomegranate", "onion", "द्राक्ष", "आंबा", "डाळिंब", "कांदा");

    private final WeatherDao weatherDao;
    private final AlertDao alertDao;
    private final FarmerProfileDao profiles;
    private final GreenAlertsScraper scraper;
    private final OpenMeteoSource openMeteo;
    private final GeoMapService geoMap;          // PART C: heat-map cache to drop
    private final long delayMs;

    /* PART A: the daily schedule, the retention window and the catch-up window
       all come from application.properties so nothing here is hard-coded. */
    private final String refreshCron;
    private final int historyDays;
    private final int catchupHours;

    /* PART A: a single background worker so a manual refresh (owner button) or
       the start-up catch-up never blocks a request thread or slows boot. */
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "weather-refresh");
        t.setDaemon(true);
        return t;
    });

    public WeatherService(WeatherDao weatherDao,
                          AlertDao alertDao,
                          FarmerProfileDao profiles,
                          GreenAlertsScraper scraper,
                          OpenMeteoSource openMeteo,
                          GeoMapService geoMap,
                          @Value("${pfas.weather.delay-ms}") long delayMs,
                          @Value("${weather.refresh.cron:0 0 6 * * *}") String refreshCron,
                          @Value("${weather.history.days:30}") int historyDays,
                          @Value("${weather.catchup.hours:24}") int catchupHours) {
        this.weatherDao = weatherDao;
        this.alertDao = alertDao;
        this.profiles = profiles;
        this.scraper = scraper;
        this.openMeteo = openMeteo;
        this.geoMap = geoMap;
        this.delayMs = delayMs;
        this.refreshCron = refreshCron;
        this.historyDays = historyDays;
        this.catchupHours = catchupHours;
    }

    /** The cron the daily refresh uses - shown on /debug so it can be verified. */
    public String refreshCron() {
        return refreshCron;
    }

    /** The retention window in days - shown on /debug. */
    public int historyDays() {
        return historyDays;
    }

    /**
     * PART A: runs ONCE A DAY at 06:00 India time.
     *
     * The cron comes from `weather.refresh.cron` (application.properties,
     * default "0 0 6 * * *") and the zone is fixed to Asia/Kolkata so the job
     * always fires at 06:00 IST - the hour a farmer checks the outlook - no
     * matter what timezone the server runs in. Change the property (for example
     * every six hours) to run more often; no code change is needed.
     */
    @Scheduled(cron = "${weather.refresh.cron:0 0 6 * * *}", zone = "Asia/Kolkata")
    public void scheduledRefresh() {
        refreshAll("SCHEDULED");
    }

    /**
     * PART A: start-up CATCH-UP.
     *
     * Runs after the application is ready, on a background thread, so it never
     * slows down boot. If any watched district has no reading, or its newest
     * reading is older than `weather.catchup.hours` (default 24 h), a refresh is
     * started immediately so the first page load is never stale.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void catchUpOnStartup() {
        worker.submit(() -> {
            if (anyDistrictStale()) {
                log.info("[WEATHER] Catch-up refresh started (a district is missing data or older than {} h)",
                        catchupHours);
                refreshAll("CATCHUP");
            } else {
                log.info("[WEATHER] Catch-up not needed: every watched district has a reading newer than {} h",
                        catchupHours);
            }
        });
    }

    /**
     * PART A: run a refresh in the background (used by the owner's "Refresh now"
     * button so the POST returns at once instead of waiting for the network).
     */
    public void refreshInBackground(String trigger) {
        worker.submit(() -> refreshAll(trigger));
    }

    /** True when any watched district has no reading, or one older than the window. */
    public boolean anyDistrictStale() {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(catchupHours);
        for (WeatherConfig config : weatherDao.findAllConfigs()) {
            Optional<WeatherReading> latest = weatherDao.latestForDistrict(config.district());
            if (latest.isEmpty() || latest.get().getUpdatedAt() == null
                    || latest.get().getUpdatedAt().isBefore(cutoff)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Refreshes every watched location once, then runs the alert rules.
     *
     * @param trigger SCHEDULED (daily cron), CATCHUP (start-up) or MANUAL (owner button)
     * @return how many locations were written this round (0 when everything failed)
     */
    public int refreshAll(String trigger) {
        List<WeatherConfig> configs = weatherDao.findAllConfigs();
        log.info("[WEATHER] Refresh ({}) started: {} watched location(s)", trigger, configs.size());

        int greenalerts = 0;
        int openmeteo = 0;
        int failed = 0;
        for (WeatherConfig config : configs) {
            String used = refreshOne(config);
            if ("GREENALERTS".equals(used)) {
                greenalerts++;
            } else if ("OPENMETEO".equals(used)) {
                openmeteo++;
            } else {
                failed++;
            }
            pause();                                   // be polite to the source
        }

        runAlertRules();

        int written = greenalerts + openmeteo;
        String source = greenalerts > 0 && openmeteo == 0 ? "GREENALERTS"
                : openmeteo > 0 && greenalerts == 0 ? "OPENMETEO"
                : written > 0 ? "MIXED" : "FAILED";
        String status = written == 0 ? "FAILED"
                : failed > 0 ? "PARTIAL"
                : openmeteo > 0 ? "FALLBACK" : "OK";
        String message = String.format(
                "source=%s, districts %d/%d written (greenalerts=%d, openmeteo=%d, failed=%d)",
                source, written, configs.size(), greenalerts, openmeteo, failed);

        weatherDao.insertRun(trigger, source, written, configs.size(), status, message);
        log.info("[WEATHER] Refresh ({}) finished: {}", trigger, message);

        // PART C: the heat map is interpolated from these readings, so the cached
        // grid must be dropped - it is rebuilt on the next /map view.
        geoMap.invalidate();

        // PART A retention: drop anything older than the retention window.
        int removed = weatherDao.retention(historyDays);
        if (removed > 0) {
            log.info("[WEATHER] Retention: removed {} row(s) older than {} day(s)",
                    removed, historyDays);
        }
        return written;
    }

    /* ======================= 1. the refresh ======================= */

    /**
     * One location: scrape first, public API second, keep the old rows last.
     *
     * @return the NAME of the source that supplied the data ("GREENALERTS" or
     *         "OPENMETEO"), or null when both failed and the old cache was kept.
     */
    private String refreshOne(WeatherConfig config) {
        ScrapeLog entry = new ScrapeLog();
        entry.setUrl(config.scrapeUrl());

        Optional<WeatherSnapshot> fromScraper = safeFetch(scraper, config);
        if (fromScraper.isPresent()) {
            write(config, fromScraper.get(), "OK", entry);
            return "GREENALERTS";
        }

        Optional<WeatherSnapshot> fromApi = safeFetch(openMeteo, config);
        if (fromApi.isPresent()) {
            write(config, fromApi.get(), "FALLBACK", entry);
            return "OPENMETEO";
        }

        // Both sources failed. Nothing is deleted: the old cache rows stay and
        // the pages keep showing them with their own (older) last-updated time.
        entry.setSource(scraper.name());
        entry.setLastStatus("FAILED");
        entry.setLastMessage("Both sources returned no data; previous readings kept.");
        entry.setRowsWritten(0);
        entry.setLastSuccessAt(null);                  // NULL keeps the old success time
        weatherDao.upsertLog(entry);
        log.warn("[WEATHER] {} : both sources failed, keeping the previous reading",
                config.location());
        return null;
    }

    /** A source never throws, but a defensive try costs nothing here. */
    private Optional<WeatherSnapshot> safeFetch(WeatherSource source, WeatherConfig config) {
        try {
            return source.fetch(config);
        } catch (Exception e) {
            log.warn("[WEATHER] {} source {} threw: {}", config.location(), source.name(), e.getMessage());
            return Optional.empty();
        }
    }

    /** Writes the current reading + up to 7 forecast days + the scrape log row. */
    private void write(WeatherConfig config, WeatherSnapshot snapshot, String status, ScrapeLog entry) {
        int rows = 0;

        WeatherReading current = snapshot.current();
        if (current != null) {
            weatherDao.insertReading(current);         // history is kept, never overwritten
            rows++;
        }
        for (WeatherDay day : snapshot.days()) {
            weatherDao.upsertForecast(config.district(), config.taluka(), day);
            rows++;
        }

        entry.setSource(snapshot.source());
        entry.setLastStatus(status);
        entry.setLastMessage("Wrote " + rows + " row(s) from " + snapshot.source() + ".");
        entry.setRowsWritten(rows);
        entry.setLastSuccessAt(LocalDateTime.now());
        weatherDao.upsertLog(entry);
    }

    private void pause() {
        if (delayMs <= 0) {
            return;
        }
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();        // stop politely on shutdown
        }
    }

    /* ======================= 2. the alert rules ======================= */

    /**
     * Turns the cached forecast into a few plain-language warnings.
     *
     * The alert row stores the NAME of a message key (never a sentence), so the
     * same alert is readable in English, Hindi and Marathi. `dedupeKey` carries
     * today's date, and alerts has a unique key on (user_id, dedupe_key), so the
     * daily job (and any manual refresh) can run more than once a day and the
     * farmer still sees one copy.
     */
    private void runAlertRules() {
        List<FarmerProfile> allProfiles = profiles.findAll();
        String today = LocalDate.now().toString();
        int raised = 0;

        for (FarmerProfile profile : allProfiles) {
            if (profile.getDistrict() == null || profile.getDistrict().isBlank()) {
                continue;                              // no district -> no district forecast
            }

            Optional<WeatherReading> current = weatherDao.latestForDistrict(profile.getDistrict());
            List<WeatherDay> days = weatherDao.forecastForDistrict(profile.getDistrict(), 7);
            if (current.isEmpty() && days.isEmpty()) {
                continue;                              // nothing cached for this district yet
            }

            String crops = profile.getCrops() == null ? "" : profile.getCrops().toLowerCase(Locale.ROOT);

            // RULE 1 - heat: is it already hot right now?
            Integer nowTemp = current.map(WeatherReading::tempCelsius).orElse(null);
            if (nowTemp != null && nowTemp >= HEAT_TEMP_C) {
                raised += raise(profile, new Alert("alert.heat.title", "alert.heat.message",
                        "PERSONALIZED", "SEVERE", today + ":HEAT"));
            }

            // RULE 2 - high rain figure in the next three days.
            if (maxRain(days, 3) >= RAIN_HIGH) {
                raised += raise(profile, new Alert("alert.rain.title", "alert.rain.message",
                        "PERSONALIZED", "WARNING", today + ":RAIN"));
            }

            // RULE 3 - thirsty crop + warm and almost no rain -> irrigate.
            if (matchesCrop(crops, THIRSTY_CROPS)
                    && maxRain(days, 3) < RAIN_SOME
                    && hottest(days, 3) >= DRY_CROP_TEMP_C) {
                raised += raise(profile, new Alert("alert.irrigation.title", "alert.irrigation.message",
                        "PERSONALIZED", "WARNING", today + ":IRRIGATION"));
            }

            // RULE 4 - fungus-prone crop + wet weather ahead -> protective spray.
            if (matchesCrop(crops, FUNGUS_PRONE_CROPS) && maxRain(days, 2) >= SPRAY_RAIN) {
                raised += raise(profile, new Alert("alert.spray.title", "alert.spray.message",
                        "PERSONALIZED", "WARNING", today + ":SPRAY"));
            }
        }

        log.info("[WEATHER] Alert rules finished: {} new alert(s) for {} farmer profile(s)",
                raised, allProfiles.size());
    }

    /** Inserts the alert unless the same farmer already has it today. */
    private int raise(FarmerProfile profile, Alert alert) {
        alertDao.insertIfNew(alert, profile.getUserId());
        return 1;
    }

    /** Highest rain figure in the next `days` days; 0 when there is no number. */
    private int maxRain(List<WeatherDay> days, int daysAhead) {
        int max = 0;
        for (int i = 0; i < days.size() && i < daysAhead; i++) {
            Integer value = WeatherReading.numberIn(days.get(i).getRain());
            if (value != null && value > max) {
                max = value;
            }
        }
        return max;
    }

    /** Highest maximum temperature in the next `days` days; 0 when unknown. */
    private int hottest(List<WeatherDay> days, int daysAhead) {
        int max = 0;
        for (int i = 0; i < days.size() && i < daysAhead; i++) {
            Integer value = days.get(i).tempMaxNumber();
            if (value != null && value > max) {
                max = value;
            }
        }
        return max;
    }

    /**
     * True when the farmer's saved crops mention one of the given crop words.
     * Crops are stored either as codes ("rice,onion") or as words
     * ("Rice, Sugarcane"), so a simple case-insensitive contains() covers both.
     */
    private boolean matchesCrop(String cropsLower, List<String> cropWords) {
        for (String word : cropWords) {
            if (cropsLower.contains(word.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
