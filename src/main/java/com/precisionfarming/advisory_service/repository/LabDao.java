package com.precisionfarming.advisory_service.repository;

import com.precisionfarming.advisory_service.model.Lab;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * All SQL for the `labs` table (Step 2 registration, Step 5 "labs near me",
 * Step 7 owner approval).
 *
 * Visibility rule enforced here:
 *   * a FARMER only ever sees APPROVED labs (findApprovedByDistrict)
 *   * the OWNER sees every lab, including PENDING ones (findPending / findAll)
 */
@Repository
public class LabDao {

    private final JdbcTemplate jdbc;

    public LabDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<Lab> MAPPER = (rs, i) -> {
        Lab l = new Lab();
        l.setId(rs.getLong("id"));
        long uid = rs.getLong("user_id");
        l.setUserId(rs.wasNull() ? null : uid);
        l.setName(rs.getString("name"));
        l.setAddress(rs.getString("address"));
        l.setPhone(rs.getString("phone"));
        l.setTimings(rs.getString("timings"));
        l.setSampleTypes(rs.getString("sample_types"));
        l.setRequirements(rs.getString("requirements"));
        l.setDistrict(rs.getString("district"));
        l.setTaluka(rs.getString("taluka"));
        double lat = rs.getDouble("lat");
        l.setLat(rs.wasNull() ? null : lat);
        double lon = rs.getDouble("lon");
        l.setLon(rs.wasNull() ? null : lon);
        l.setStatus(rs.getString("status"));
        return l;
    };

    /** The lab row owned by this login (a lab user has exactly one). */
    public Optional<Lab> findByUserId(Long userId) {
        return jdbc.query("SELECT * FROM labs WHERE user_id = ?", MAPPER, userId).stream().findFirst();
    }

    public Optional<Lab> findById(Long id) {
        return jdbc.query("SELECT * FROM labs WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    /** APPROVED labs in one district - what a farmer is allowed to see. */
    public List<Lab> findApprovedByDistrict(String district) {
        return jdbc.query(
                "SELECT * FROM labs WHERE district = ? AND status = 'APPROVED' ORDER BY name",
                MAPPER, district);
    }

    /** Every approved lab, for the district-less view. */
    public List<Lab> findApproved() {
        return jdbc.query("SELECT * FROM labs WHERE status = 'APPROVED' ORDER BY district, name", MAPPER);
    }

    /** Labs waiting for the owner's decision (STEP 7). */
    public List<Lab> findPending() {
        return jdbc.query("SELECT * FROM labs WHERE status = 'PENDING' ORDER BY name", MAPPER);
    }

    public List<Lab> findAll() {
        return jdbc.query("SELECT * FROM labs ORDER BY district, name", MAPPER);
    }

    /** Registers a new lab. Status starts as PENDING (see schema default). */
    public Long insert(Lab l) {
        jdbc.update("""
                INSERT INTO labs
                    (user_id, name, address, phone, timings, sample_types, requirements,
                     district, taluka, lat, lon, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                l.getUserId(), l.getName(), l.getAddress(), l.getPhone(), l.getTimings(),
                l.getSampleTypes(), l.getRequirements(), l.getDistrict(), l.getTaluka(),
                l.getLat(), l.getLon(), "PENDING");
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    /** Owner-only: APPROVED or REJECTED. */
    public int updateStatus(Long labId, String status) {
        return jdbc.update("UPDATE labs SET status = ? WHERE id = ?", status, labId);
    }

    public int countAll() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM labs", Integer.class);
        return n == null ? 0 : n;
    }

    public int countByStatus(String status) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM labs WHERE status = ?", Integer.class, status);
        return n == null ? 0 : n;
    }

    /** True when this login already registered a lab (one lab per account). */
    public boolean existsForUser(Long userId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM labs WHERE user_id = ?", Integer.class, userId);
        return n != null && n > 0;
    }
}
