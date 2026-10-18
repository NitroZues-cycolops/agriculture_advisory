package com.precisionfarming.advisory_service.model;

import java.time.LocalDateTime;

/**
 * One row of the `weather_scrape_log` table: what happened the last time the
 * scheduled job opened one URL.
 *
 * WHY IT EXISTS: the /debug screen must be able to say "this URL last succeeded
 * at 14:05" without anyone reading a log file. The status is one of:
 *   OK       - the scrape worked and weather_cache was written
 *   FALLBACK - the scrape failed and Open-Meteo supplied the reading instead
 *   FAILED    - both sources failed; the previous weather_cache rows are kept
 */
public class ScrapeLog {

    private String url;
    private String source;
    private String lastStatus;
    private String lastMessage;
    private LocalDateTime lastRunAt;
    private LocalDateTime lastSuccessAt;
    private int rowsWritten;

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getLastStatus() { return lastStatus; }
    public void setLastStatus(String lastStatus) { this.lastStatus = lastStatus; }

    public String getLastMessage() { return lastMessage; }
    public void setLastMessage(String lastMessage) { this.lastMessage = lastMessage; }

    public LocalDateTime getLastRunAt() { return lastRunAt; }
    public void setLastRunAt(LocalDateTime lastRunAt) { this.lastRunAt = lastRunAt; }

    public LocalDateTime getLastSuccessAt() { return lastSuccessAt; }
    public void setLastSuccessAt(LocalDateTime lastSuccessAt) { this.lastSuccessAt = lastSuccessAt; }

    public int getRowsWritten() { return rowsWritten; }
    public void setRowsWritten(int rowsWritten) { this.rowsWritten = rowsWritten; }
}
