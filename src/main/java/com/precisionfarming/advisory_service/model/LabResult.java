package com.precisionfarming.advisory_service.model;

import java.time.LocalDateTime;

/**
 * One row of `lab_results`: the numbers the lab measured, plus the path of the
 * generated PDF report on disk.
 *
 * One request has at most one result row (the table has a UNIQUE key on
 * request_id), so the lab dashboard can save the form again to correct a value
 * - the row is updated in place and the PDF is regenerated.
 */
public class LabResult {

    private Long id;
    private Long requestId;
    private Double ph;
    private Double nitrogen;
    private Double phosphorus;
    private Double potassium;
    private Double organicCarbon;
    private Double electricalConductivity;
    private String remarks;
    private String pdfPath;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getRequestId() { return requestId; }
    public void setRequestId(Long requestId) { this.requestId = requestId; }

    public Double getPh() { return ph; }
    public void setPh(Double ph) { this.ph = ph; }

    public Double getNitrogen() { return nitrogen; }
    public void setNitrogen(Double nitrogen) { this.nitrogen = nitrogen; }

    public Double getPhosphorus() { return phosphorus; }
    public void setPhosphorus(Double phosphorus) { this.phosphorus = phosphorus; }

    public Double getPotassium() { return potassium; }
    public void setPotassium(Double potassium) { this.potassium = potassium; }

    public Double getOrganicCarbon() { return organicCarbon; }
    public void setOrganicCarbon(Double organicCarbon) { this.organicCarbon = organicCarbon; }

    public Double getElectricalConductivity() { return electricalConductivity; }
    public void setElectricalConductivity(Double electricalConductivity) {
        this.electricalConductivity = electricalConductivity;
    }

    public String getRemarks() { return remarks; }
    public void setRemarks(String remarks) { this.remarks = remarks; }

    public String getPdfPath() { return pdfPath; }
    public void setPdfPath(String pdfPath) { this.pdfPath = pdfPath; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
