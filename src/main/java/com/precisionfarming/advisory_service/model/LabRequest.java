package com.precisionfarming.advisory_service.model;

import java.time.LocalDateTime;
import java.util.List;

/**
 * One row of `lab_requests`: a farmer's request to have a sample tested.
 *
 * The row carries the `trackingId` the farmer writes down, and the current
 * `status` (see LabStatus). The two joined display fields (labName, labDistrict)
 * are filled in by the DAO so a list page needs no extra query.
 */
public class LabRequest {

    private Long id;
    private Long farmerId;
    private Long labId;
    private String sampleType;
    private String crop;
    private String notes;
    private String status = LabStatus.REQUEST_SUBMITTED.name();
    private String trackingId;
    private LocalDateTime createdAt;

    /* filled by a JOIN, not stored on this row */
    private String labName;
    private String labDistrict;
    private String farmerName;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getFarmerId() { return farmerId; }
    public void setFarmerId(Long farmerId) { this.farmerId = farmerId; }

    public Long getLabId() { return labId; }
    public void setLabId(Long labId) { this.labId = labId; }

    public String getSampleType() { return sampleType; }
    public void setSampleType(String sampleType) { this.sampleType = sampleType; }

    public String getCrop() { return crop; }
    public void setCrop(String crop) { this.crop = crop; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getTrackingId() { return trackingId; }
    public void setTrackingId(String trackingId) { this.trackingId = trackingId; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public String getLabName() { return labName; }
    public void setLabName(String labName) { this.labName = labName; }

    public String getLabDistrict() { return labDistrict; }
    public void setLabDistrict(String labDistrict) { this.labDistrict = labDistrict; }

    public String getFarmerName() { return farmerName; }
    public void setFarmerName(String farmerName) { this.farmerName = farmerName; }

    /** The stage this request is sitting at, as an enum. */
    public LabStatus stage() {
        return LabStatus.parse(status);
    }

    /** The tracker rows for THIS request: every stage, each marked done or not. */
    public List<LabStatus> stages() {
        return LabStatus.ordered();
    }

    /** True when the lab can still push this request forward. */
    public boolean isFinished() {
        return stage().next() == null;
    }
}
