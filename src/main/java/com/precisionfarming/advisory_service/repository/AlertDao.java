package com.precisionfarming.advisory_service.repository;

import com.precisionfarming.advisory_service.model.Alert;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;

/**
 * All SQL for the `alerts` table (STEP 4 personalised alerts, reused on the
 * dashboard in later steps).
 *
 * INSERT IGNORE + the unique key (user_id, dedupe_key) is what stops the
 * 3-hourly job from showing the same warning four times a day.
 */
@Repository
public class AlertDao {

    private final JdbcTemplate jdbc;

    public AlertDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<Alert> MAPPER = (rs, i) -> {
        Alert a = new Alert();
        a.setId(rs.getLong("id"));
        a.setTitleKey(rs.getString("title"));
        a.setMessageKey(rs.getString("message"));
        a.setType(rs.getString("type"));
        a.setSeverity(rs.getString("severity"));
        a.setDedupeKey(rs.getString("dedupe_key"));
        Timestamp ts = rs.getTimestamp("created_at");
        a.setCreatedAt(ts == null ? null : ts.toLocalDateTime());
        return a;
    };

    /** Adds an alert unless the same user already has it for the same day. */
    public void insertIfNew(Alert a, Long userId) {
        jdbc.update("""
                INSERT IGNORE INTO alerts (user_id, title, message, type, severity, dedupe_key)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                userId, a.getTitleKey(), a.getMessageKey(), a.getType(), a.getSeverity(),
                a.getDedupeKey());
    }

    /** The newest alerts of one kind for one user (GENERAL or PERSONALIZED). */
    public List<Alert> findForUser(Long userId, String type, int limit) {
        return jdbc.query("""
                SELECT * FROM alerts
                WHERE user_id = ? AND type = ?
                ORDER BY created_at DESC, id DESC
                LIMIT ?
                """, MAPPER, userId, type, limit);
    }

    /** Total rows in alerts - the owner panel and /debug show this count. */
    public int countAll() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM alerts", Integer.class);
        return n == null ? 0 : n;
    }
}
