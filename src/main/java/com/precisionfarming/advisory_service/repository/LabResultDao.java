package com.precisionfarming.advisory_service.repository;

import com.precisionfarming.advisory_service.model.LabResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * All SQL for `lab_results` (STEP 5).
 *
 * The table has a UNIQUE key on request_id, so a lab can save the form twice
 * without creating a second row: the INSERT below updates the existing row
 * instead. `pdf_path` is written by LabReportService once the file exists.
 */
@Repository
public class LabResultDao {

    private final JdbcTemplate jdbc;

    public LabResultDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<LabResult> MAPPER = (rs, i) -> {
        LabResult r = new LabResult();
        r.setId(rs.getLong("id"));
        r.setRequestId(rs.getLong("request_id"));
        r.setPh(nullable(rs.getObject("ph")));
        r.setNitrogen(nullable(rs.getObject("nitrogen")));
        r.setPhosphorus(nullable(rs.getObject("phosphorus")));
        r.setPotassium(nullable(rs.getObject("potassium")));
        r.setOrganicCarbon(nullable(rs.getObject("organic_carbon")));
        r.setElectricalConductivity(nullable(rs.getObject("electrical_conductivity")));
        r.setRemarks(rs.getString("remarks"));
        r.setPdfPath(rs.getString("pdf_path"));
        java.sql.Timestamp ts = rs.getTimestamp("created_at");
        r.setCreatedAt(ts == null ? null : ts.toLocalDateTime());
        return r;
    };

    private static Double nullable(Object value) {
        return value == null ? null : ((Number) value).doubleValue();
    }

    public Optional<LabResult> findByRequestId(Long requestId) {
        return jdbc.query("SELECT * FROM lab_results WHERE request_id = ?", MAPPER, requestId)
                .stream().findFirst();
    }

    /**
     * Inserts the result, or updates it when the lab saved the form again.
     * Returns the result row id.
     */
    public void upsert(LabResult r) {
        jdbc.update("""
                INSERT INTO lab_results
                    (request_id, ph, nitrogen, phosphorus, potassium, organic_carbon,
                     electrical_conductivity, remarks, pdf_path)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    ph = VALUES(ph),
                    nitrogen = VALUES(nitrogen),
                    phosphorus = VALUES(phosphorus),
                    potassium = VALUES(potassium),
                    organic_carbon = VALUES(organic_carbon),
                    electrical_conductivity = VALUES(electrical_conductivity),
                    remarks = VALUES(remarks),
                    pdf_path = VALUES(pdf_path)
                """,
                r.getRequestId(), r.getPh(), r.getNitrogen(), r.getPhosphorus(), r.getPotassium(),
                r.getOrganicCarbon(), r.getElectricalConductivity(), r.getRemarks(), r.getPdfPath());
    }

    /** Remembers where the generated PDF was written. */
    public void updatePdfPath(Long requestId, String pdfPath) {
        jdbc.update("UPDATE lab_results SET pdf_path = ? WHERE request_id = ?", pdfPath, requestId);
    }

    public int countAll() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM lab_results", Integer.class);
        return n == null ? 0 : n;
    }

    /** How many requests have a finished report - shown on the owner panel. */
    public int countReports() {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM lab_results WHERE ph IS NOT NULL OR remarks IS NOT NULL",
                Integer.class);
        return n == null ? 0 : n;
    }
}
