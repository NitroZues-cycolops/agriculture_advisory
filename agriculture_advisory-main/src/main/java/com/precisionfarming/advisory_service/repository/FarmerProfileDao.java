package com.precisionfarming.advisory_service.repository;

import com.precisionfarming.advisory_service.model.FarmerProfile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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
                         farm_area, unit, soil_type, irrigation, description, preferred_lang)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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

    /** Names of every farmer who has a profile - used by the STEP 7 owner counts. */
    public List<String> allNames() {
        return jdbc.queryForList("SELECT full_name FROM farmer_profiles ORDER BY full_name", String.class);
    }

    /**
     * PART 2a: saves the profile picture path for a user and creates the profile
     * row if the farmer has none yet (UPSERT). Only photo_path is written on a
     * clash, so the other profile fields are never clobbered. The NOT NULL
     * columns without a default get empty placeholders in the INSERT branch; in
     * practice the row already exists because registration inserts it, so this
     * branch is only a safety net (e.g. a user whose profile row was removed).
     */
    public int upsertPhoto(Long userId, String photoPath) {
        return jdbc.update("""
                INSERT INTO farmer_profiles (user_id, full_name, phone, district, taluka, photo_path)
                VALUES (?, '', '', '', '', ?)
                ON DUPLICATE KEY UPDATE photo_path = VALUES(photo_path)
                """, userId, photoPath);
    }

    /** PART 2a: forgets the stored picture (the file is deleted by the caller). */
    public int clearPhoto(Long userId) {
        return jdbc.update("UPDATE farmer_profiles SET photo_path = NULL WHERE user_id = ?", userId);
    }

    /**
     * PART 2a: the ids of every user who has uploaded a profile picture. The
     * community pages use it to decide between the real photo (img) and the CSS
     * initials circle, without the read-only Member/Post records having to carry
     * a photo flag.
     */
    public Set<Long> idsWithPhoto() {
        return new HashSet<>(jdbc.queryForList(
                "SELECT user_id FROM farmer_profiles WHERE photo_path IS NOT NULL", Long.class));
    }
}
