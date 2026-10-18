package com.precisionfarming.advisory_service.model;

import java.time.LocalDateTime;

/**
 * One row of the `alerts` table shown on the farmer's dashboard.
 *
 * WHY THE TABLE STORES MESSAGE KEYS, NOT SENTENCES: rule 4 says every visible
 * word must come from the message bundles and no ??key?? may ever appear. If the
 * alert text were written into the database it could not be translated, so the
 * row stores the NAME of a key ("alert.rain.title") and the template resolves it
 * with #messages.msg(...). Both keys exist in all four bundles.
 *
 * `dedupeKey` carries the date (e.g. "2026-10-18:RAIN"), and the table has a
 * unique key on (user_id, dedupe_key): the 3-hourly job can therefore fire the
 * same alert four times a day and the farmer still sees it once.
 */
public class Alert {

    private Long id;
    private String titleKey;
    private String messageKey;
    private String type;        // GENERAL or PERSONALIZED
    private String severity;    // SUCCESS, WARNING or SEVERE
    private String dedupeKey;
    private LocalDateTime createdAt;

    public Alert() {
    }

    public Alert(String titleKey, String messageKey, String type, String severity, String dedupeKey) {
        this.titleKey = titleKey;
        this.messageKey = messageKey;
        this.type = type;
        this.severity = severity;
        this.dedupeKey = dedupeKey;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getTitleKey() { return titleKey; }
    public void setTitleKey(String titleKey) { this.titleKey = titleKey; }

    public String getMessageKey() { return messageKey; }
    public void setMessageKey(String messageKey) { this.messageKey = messageKey; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }

    public String getDedupeKey() { return dedupeKey; }
    public void setDedupeKey(String dedupeKey) { this.dedupeKey = dedupeKey; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    /** The CSS class the template puts on the banner, e.g. "alert--warning". */
    public String severityClass() {
        if (severity == null) {
            return "alert--info";
        }
        return switch (severity) {
            case "SUCCESS" -> "alert--success";
            case "WARNING" -> "alert--warning";
            case "SEVERE" -> "alert--severe";
            default -> "alert--info";
        };
    }
}
