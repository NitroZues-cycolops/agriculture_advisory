package com.precisionfarming.advisory_service.model;

import java.time.LocalDateTime;

/**
 * One REFRESH RUN of the weather job (PART A), read from weather_scrape_run.
 *
 * WHY THIS EXISTS NEXT TO ScrapeLog: ScrapeLog answers "what happened to this
 * one URL, ever"; ScrapeRun answers "how did the last few jobs go" - time,
 * which source actually supplied the data, how many districts were written and
 * whether the run succeeded. /debug shows the newest few of these.
 *
 * @param runAt          when the run started (set by the database)
 * @param triggerType    SCHEDULED (daily cron), CATCHUP (start-up) or MANUAL (owner button)
 * @param source         GREENALERTS, OPENMETEO, MIXED or FAILED
 * @param districtsOk    how many watched districts were written this run
 * @param districtsTotal how many watched districts exist
 * @param status         OK / FALLBACK / PARTIAL / FAILED
 * @param message        a short plain-language summary
 */
public record ScrapeRun(
        LocalDateTime runAt,
        String triggerType,
        String source,
        int districtsOk,
        int districtsTotal,
        String status,
        String message) {
}
