package com.precisionfarming.advisory_service.model;

/**
 * A soil / water testing lab (row of the `labs` table).
 *
 * `status` is PENDING for a lab that registered itself through /register/lab
 * and APPROVED only after the owner accepts it (STEP 7). Farmers only ever see
 * APPROVED labs (STEP 5 "labs near me").
 *
 * Plain carrier, not a JPA entity - see repository/LabDao (JdbcTemplate).
 */
public class Lab {

    private Long id;
    private Long userId;
    private String name;
    private String address;
    private String phone;
    private String timings;
    private String sampleTypes;
    private String requirements;
    private String district;
    private String taluka;
    private Double lat;
    private Double lon;
    private String status;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public String getTimings() { return timings; }
    public void setTimings(String timings) { this.timings = timings; }

    public String getSampleTypes() { return sampleTypes; }
    public void setSampleTypes(String sampleTypes) { this.sampleTypes = sampleTypes; }

    public String getRequirements() { return requirements; }
    public void setRequirements(String requirements) { this.requirements = requirements; }

    public String getDistrict() { return district; }
    public void setDistrict(String district) { this.district = district; }

    public String getTaluka() { return taluka; }
    public void setTaluka(String taluka) { this.taluka = taluka; }

    public Double getLat() { return lat; }
    public void setLat(Double lat) { this.lat = lat; }

    public Double getLon() { return lon; }
    public void setLon(Double lon) { this.lon = lon; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
