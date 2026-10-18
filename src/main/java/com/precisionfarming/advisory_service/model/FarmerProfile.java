package com.precisionfarming.advisory_service.model;

/**
 * A farmer's profile (row of the `farmer_profiles` table).
 *
 * Crops are kept as a comma-separated list of crop codes (e.g. "rice,onion")
 * because a mini project only ever reads the whole list - this keeps the
 * table simple enough to draw on a whiteboard during the viva.
 *
 * This is a plain carrier, not a JPA entity: the new tables are accessed with
 * JdbcTemplate (see repository/FarmerProfileDao), so no annotation is needed.
 */
public class FarmerProfile {

    private Long id;
    private Long userId;
    private String fullName;
    private String phone;
    private String district;
    private String taluka;
    private String village;
    private String crops;
    private double farmArea;
    private String unit;
    private String soilType;
    private String irrigation;
    private String description;
    private String preferredLang;
    private String photoPath;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public String getDistrict() { return district; }
    public void setDistrict(String district) { this.district = district; }

    public String getTaluka() { return taluka; }
    public void setTaluka(String taluka) { this.taluka = taluka; }

    public String getVillage() { return village; }
    public void setVillage(String village) { this.village = village; }

    public String getCrops() { return crops; }
    public void setCrops(String crops) { this.crops = crops; }

    public double getFarmArea() { return farmArea; }
    public void setFarmArea(double farmArea) { this.farmArea = farmArea; }

    public String getUnit() { return unit; }
    public void setUnit(String unit) { this.unit = unit; }

    public String getSoilType() { return soilType; }
    public void setSoilType(String soilType) { this.soilType = soilType; }

    public String getIrrigation() { return irrigation; }
    public void setIrrigation(String irrigation) { this.irrigation = irrigation; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getPreferredLang() { return preferredLang; }
    public void setPreferredLang(String preferredLang) { this.preferredLang = preferredLang; }

    public String getPhotoPath() { return photoPath; }
    public void setPhotoPath(String photoPath) { this.photoPath = photoPath; }
}
