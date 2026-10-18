package com.precisionfarming.advisory_service.repository;

import com.precisionfarming.advisory_service.model.FarmerProfile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

/**
 * All SQL for the `farmer_profiles` table (Step 2 + Step 3 "find my office").
 *
 * Plain JdbcTemplate on purpose: the queries are short, explicit and easy to
 * read aloud in the viva, and the table is small.
 */
@Repository
public class FarmerProfileDao {

    private final JdbcTemplate jdbc;

    public FarmerProfileDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<FarmerProfile> MAPPER = (rs, i) -> {
        FarmerProfile p = new FarmerProfile();
        p.setId(rs.getLong("id"));
        p.setUserId(rs.getLong("user_id"));
        p.setFullName(rs.getString("full_name"));
        p.setPhone(rs.getString("phone"));
        p.setDistrict(rs.getString("district"));
        p.setTaluka(rs.getString("taluka"));
        p.setVillage(rs.getString("village"));
        p.setCrops(rs.getString("crops"));
        p.setFarmArea(rs.getDouble("farm_area"));
        p.setUnit(rs.getString("unit"));
        p.setSoilType(rs.getString("soil_type"));
        p.setIrrigation(rs.getString("irrigation"));
        p.setDescription(rs.getString("description"));
        p.setPreferredLang(rs.getString("preferred_lang"));
        p.setPhotoPath(rs.getString("photo_path"));
        return p;
    };

    /** The profile of the logged-in farmer, if it exists yet. */
    public Optional<FarmerProfile> findByUserId(Long userId) {
        return jdbc.query("SELECT * FROM farmer_profiles WHERE user_id = ?", MAPPER, userId)
                .stream().findFirst();
    }

    /**
     * Every saved profile. The STEP 4 alert job runs the crop + forecast rules
     * for each of these, so it needs the user id, the district and the crops.
     */
    public List<FarmerProfile> findAll() {
        return jdbc.query("SELECT * FROM farmer_profiles ORDER BY id", MAPPER);
    }

    /** Inserts a new profile and returns the generated id. */
    public Long insert(FarmerProfile p) {
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO farmer_profiles
                        (user_id, full_name, phone, district, taluka, village, crops,
                        farm_area, unit, soil_type, irrigation, description, preferred_lang, photo_path)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, p.getUserId());
            ps.setString(2, p.getFullName());
            ps.setString(3, p.getPhone());
            ps.setString(4, p.getDistrict());
            ps.setString(5, p.getTaluka());
            ps.setString(6, p.getVillage());
            ps.setString(7, p.getCrops());
            ps.setDouble(8, p.getFarmArea());
            ps.setString(9, p.getUnit());
            ps.setString(10, p.getSoilType());
            ps.setString(11, p.getIrrigation());
            ps.setString(12, p.getDescription());
            ps.setString(13, p.getPreferredLang());
            ps.setString(14, p.getPhotoPath());
            return ps;
        }, key);
        Number id = key.getKey();
        return id == null ? null : id.longValue();
    }

    /** Overwrites an existing profile. Ownership is enforced by the caller (WHERE user_id). */
    public int update(FarmerProfile p) {
        return jdbc.update("""
                UPDATE farmer_profiles SET
                    full_name = ?, phone = ?, district = ?, taluka = ?, village = ?, crops = ?,
                    farm_area = ?, unit = ?, soil_type = ?, irrigation = ?, description = ?,
                    preferred_lang = ?
                WHERE user_id = ?
                """,
                p.getFullName(), p.getPhone(), p.getDistrict(), p.getTaluka(), p.getVillage(),
                p.getCrops(), p.getFarmArea(), p.getUnit(), p.getSoilType(), p.getIrrigation(),
                p.getDescription(), p.getPreferredLang(), p.getUserId());
    }

    public int updatePhoto(Long userId, String photoPath) {
        return jdbc.update("UPDATE farmer_profiles SET photo_path = ? WHERE user_id = ?",
                photoPath, userId);
    }

    /** Names of every farmer who has a profile - used by the STEP 7 owner counts. */
    public List<String> allNames() {
        return jdbc.queryForList("SELECT full_name FROM farmer_profiles ORDER BY full_name", String.class);
    }
}
