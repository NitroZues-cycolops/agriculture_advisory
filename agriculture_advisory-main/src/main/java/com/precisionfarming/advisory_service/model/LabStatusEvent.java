package com.precisionfarming.advisory_service.model;

import java.time.LocalDateTime;

/**
 * One row of `lab_status_history`: "who moved this request to which stage, when".
 *
 * The tracker on the farmer's page is drawn from the CURRENT status alone, but
 * this table is what proves the order things happened in - the lab dashboard
 * prints it as a small timeline under the request.
 */
public class LabStatusEvent {

    private Long id;
    private Long requestId;
    private String status;
    private String note;
    private LocalDateTime changedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getRequestId() { return requestId; }
    public void setRequestId(Long requestId) { this.requestId = requestId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }

    public LocalDateTime getChangedAt() { return changedAt; }
    public void setChangedAt(LocalDateTime changedAt) { this.changedAt = changedAt; }

    /** The message key of this event's stage, e.g. "lab.status.ready". */
    public String messageKey() {
        return LabStatus.parse(status).messageKey();
    }
}
