package com.precisionfarming.advisory_service.controller;

import com.precisionfarming.advisory_service.model.ScrapeLog;
import com.precisionfarming.advisory_service.model.ScrapeRun;
import com.precisionfarming.advisory_service.repository.AlertDao;
import com.precisionfarming.advisory_service.repository.WeatherDao;
import com.precisionfarming.advisory_service.service.WeatherService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Owner-only diagnostic screen.
 *
 * PURPOSE : one page that answers "is the weather job alive?" without reading
 *           a log file or opening MySQL: the last outcome of every watched
 *           URL, the newest refresh RUNS (time, source, districts, status),
 *           how many rows the cache holds and how many are seeded SAMPLE rows,
 *           plus the current session, the build and the next-run cron.
 *
 * PART A ADDS : the "Refresh now" button (POST /debug/refresh, CSRF-protected)
 *               and the run-history table (weather_scrape_run, last 20 runs).
 *
 * WHY THE DAOs ARE READ HERE: this is the ONLY place the app is allowed to
 * read the whole weather_scrape_log / weather_scrape_run tables. The normal
 * weather pages read the cache only.
 *
 * If this breaks, check:
 *   - 403 instead of the page -> the logged-in user is not OWNER
 *     (SecurityConfig maps /debug and /debug/** to hasRole("OWNER")).
 *   - Empty watch-list table -> the refresh has not run yet. It runs at start-up
 *     (catch-up) and then once a day at 06:00 IST (WeatherService).
 *   - "Refresh now" does nothing visible -> the refresh runs in the background;
 *     reload /debug a few seconds later to see the new run row.
 */
@Controller
public class DebugController {

    private static final Logger log = LoggerFactory.getLogger(DebugController.class);

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm");

    /** How many refresh runs the page lists. */
    private static final int RUN_HISTORY = 20;

    private final WeatherDao weatherDao;
    private final AlertDao alertDao;
    private final WeatherService weatherService;

    public DebugController(WeatherDao weatherDao, AlertDao alertDao,
                           WeatherService weatherService) {
        this.weatherDao = weatherDao;
        this.alertDao = alertDao;
        this.weatherService = weatherService;
    }

    /**
     * One row of the /debug watch list, already formatted for display so the
     * template needs no date library.
     */
    public record ScrapeRow(String url, String source, String status,
                            String lastSuccess, int rows) {
    }

    /**
     * One row of the /debug run history, formatted for display.
     */
    public record RunRow(String when, String trigger, String source,
                         String districts, String status, String message) {
    }

    /**
     * URL     : GET /debug
     * Method  : debug()
     * Role    : OWNER only
     * Model   : username, roles, appVersion, cacheRows, sampleRows, alertRows,
     *           scrapeRows (List of ScrapeRow), runRows (List of RunRow),
     *           refreshCron, historyDays, stale
     * Template: pages/debug.html
     */
    @GetMapping("/debug")
    public String debug(Model model) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        log.info("[SCRAPER] /debug opened by {}", auth.getName());

        model.addAttribute("username", auth.getName());
        model.addAttribute("roles", auth.getAuthorities());
        model.addAttribute("appVersion", "0.0.1-SNAPSHOT");

        model.addAttribute("cacheRows", weatherDao.countCacheRows());
        model.addAttribute("sampleRows", weatherDao.countSampleRows());
        model.addAttribute("alertRows", alertDao.countAll());

        List<ScrapeRow> rows = new ArrayList<>();
        for (ScrapeLog l : weatherDao.findAllLogs()) {
            rows.add(new ScrapeRow(l.getUrl(), l.getSource(), l.getLastStatus(),
                    stamp(l.getLastSuccessAt()), l.getRowsWritten()));
        }
        model.addAttribute("scrapeRows", rows);

        List<RunRow> runRows = new ArrayList<>();
        for (ScrapeRun r : weatherDao.findRecentRuns(RUN_HISTORY)) {
            runRows.add(new RunRow(stamp(r.runAt()), r.triggerType(), r.source(),
                    r.districtsOk() + " / " + r.districtsTotal(), r.status(), r.message()));
        }
        model.addAttribute("runRows", runRows);

        // PART A: prove the schedule and retention windows that are in force.
        model.addAttribute("refreshCron", weatherService.refreshCron());
        model.addAttribute("historyDays", weatherService.historyDays());
        model.addAttribute("stale", weatherService.anyDistrictStale());

        return "pages/debug";
    }

    /**
     * URL     : POST /debug/refresh
     * Method  : refreshNow()
     * Role    : OWNER only (see SecurityConfig)
     * Effect  : starts a refresh in the BACKGROUND (so the POST returns at once)
     *           and redirects back to /debug with a confirmation flag.
     * CSRF    : the form in debug.html uses th:action, so the token is sent.
     */
    @PostMapping("/debug/refresh")
    public String refreshNow(Authentication auth) {
        log.info("[SCRAPER] manual refresh requested by {}", auth.getName());
        weatherService.refreshInBackground("MANUAL");
        return "redirect:/debug?refreshed";
    }

    private String stamp(LocalDateTime value) {
        return value == null ? null : STAMP.format(value);
    }
}
