package com.precisionfarming.advisory_service.repository;

import com.precisionfarming.advisory_service.model.LabRequest;
import com.precisionfarming.advisory_service.model.LabStatus;
import com.precisionfarming.advisory_service.model.LabStatusEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * All SQL for `lab_requests` and `lab_status_history` (STEP 5).
 *
 * OWNERSHIP IS ENFORCED IN THE WHERE CLAUSE, never in the template:
 *   * findingsForFarmer(farmerId)  -> a farmer's own requests only
 *   * findingsForLab(labId)        -> one lab's queue only
 * A page therefore cannot show another user's row even if a URL is edited,
 * because the id used is the one from the security context.
 */
@Repository
public class LabRequestDao {

    private final JdbcTemplate jdbc;

    public LabRequestDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** A request plus the two joined display names (the list pages need both). */
    private static final RowMapper<LabRequest> MAPPER = (rs, i) -> {
        LabRequest r = new LabRequest();
        r.setId(rs.getLong("id"));
        r.setFarmerId(rs.getLong("farmer_id"));
        r.setLabId(rs.getLong("lab_id"));
        r.setSampleType(rs.getString("sample_type"));
        r.setCrop(rs.getString("crop"));
        r.setNotes(rs.getString("notes"));
        r.setStatus(rs.getString("status"));
        r.setTrackingId(rs.getString("tracking_id"));
        java.sql.Timestamp created = rs.getTimestamp("created_at");
        r.setCreatedAt(created == null ? null : created.toLocalDateTime());
        r.setLabName(rs.getString("lab_name"));
        r.setLabDistrict(rs.getString("lab_district"));
        r.setFarmerName(rs.getString("farmer_name"));
        return r;
    };

    private static final String SELECT_WITH_NAMES = """
            SELECT r.*, l.name AS lab_name, l.district AS lab_district, u.username AS farmer_name
              FROM lab_requests r
              JOIN labs  l ON l.id = r.lab_id
              JOIN users u ON u.id = r.farmer_id
            """;

    /* ---------- reads ---------- */

    /** One farmer's own requests, newest first. */
    public List<LabRequest> findingsForFarmer(Long farmerId) {
        return jdbc.query(SELECT_WITH_NAMES + " WHERE r.farmer_id = ? ORDER BY r.created_at DESC, r.id DESC",
                MAPPER, farmerId);
    }

    /** One lab's queue (what the lab dashboard shows), newest first. */
    public List<LabRequest> findingsForLab(Long labId) {
        return jdbc.query(SELECT_WITH_NAMES + " WHERE r.lab_id = ? ORDER BY r.created_at DESC, r.id DESC",
                MAPPER, labId);
    }

    /** Used by the tracker page and by the report download. */
    public Optional<LabRequest> findById(Long id) {
        return jdbc.query(SELECT_WITH_NAMES + " WHERE r.id = ?", MAPPER, id).stream().findFirst();
    }

    public Optional<LabRequest> findByTrackingId(String trackingId) {
        return jdbc.query(SELECT_WITH_NAMES + " WHERE r.tracking_id = ?", MAPPER, trackingId)
                .stream().findFirst();
    }

    public int countAll() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM lab_requests", Integer.class);
        return n == null ? 0 : n;
    }

    public int countByStatus(String status) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM lab_requests WHERE status = ?", Integer.class, status);
        return n == null ? 0 : n;
    }

    /* ---------- writes ---------- */

    /**
     * Creates a request. The caller supplies the tracking id, so the same
     * caller can check beforehand that it is not already taken.
     * Returns the new row id.
     */
    public Long insert(LabRequest r) {
        jdbc.update("""
                INSERT INTO lab_requests
                    (farmer_id, lab_id, sample_type, crop, notes, status, tracking_id)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                r.getFarmerId(), r.getLabId(), r.getSampleType(), r.getCrop(),
                r.getNotes(), LabStatus.REQUEST_SUBMITTED.name(), r.getTrackingId());
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        addEvent(id, LabStatus.REQUEST_SUBMITTED, null);
        return id;
    }

    /** Moves one request to a new stage. Returns the number of rows changed. */
    public int updateStatus(Long requestId, LabStatus status, String note) {
        int changed = jdbc.update("UPDATE lab_requests SET status = ? WHERE id = ?",
                status.name(), requestId);
        if (changed > 0) {
            addEvent(requestId, status, note);
        }
        return changed;
    }

    /** Appends one line to the request's history. */
    public void addEvent(Long requestId, LabStatus status, String note) {
        jdbc.update("INSERT INTO lab_status_history (request_id, status, note) VALUES (?, ?, ?)",
                requestId, status.name(), note);
    }

    /** The request's own timeline, oldest first. */
    public List<LabStatusEvent> history(Long requestId) {
        return jdbc.query("""
                SELECT id, request_id, status, note, changed_at
                  FROM lab_status_history
                 WHERE request_id = ?
                 ORDER BY changed_at, id
                """,
                (rs, i) -> {
                    LabStatusEvent e = new LabStatusEvent();
                    e.setId(rs.getLong("id"));
                    e.setRequestId(rs.getLong("request_id"));
                    e.setStatus(rs.getString("status"));
                    e.setNote(rs.getString("note"));
                    java.sql.Timestamp ts = rs.getTimestamp("changed_at");
                    e.setChangedAt(ts == null ? null : ts.toLocalDateTime());
                    return e;
                }, requestId);
    }
}
