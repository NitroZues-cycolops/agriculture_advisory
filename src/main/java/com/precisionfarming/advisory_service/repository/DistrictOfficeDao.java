package com.precisionfarming.advisory_service.repository;

import com.precisionfarming.advisory_service.model.DistrictOffice;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Read access to the `district_offices` table (STEP 3 - "Find my district
 * office" tile on /schemes).
 *
 * The seeded rows are generic SAMPLE entries, one per district, generated from
 * the `districts` table by data_new.sql. The tile shows the SAMPLE warning so a
 * demo never presents an unverified address as fact.
 */
@Repository
public class DistrictOfficeDao {

    private final JdbcTemplate jdbc;

    public DistrictOfficeDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<DistrictOffice> MAPPER = (rs, i) -> new DistrictOffice(
            rs.getLong("id"),
            rs.getString("district_code"),
            rs.getString("office_name"),
            rs.getString("address"),
            rs.getString("note"));

    /** The office row for a district (keyed by the districts.code slug). */
    public Optional<DistrictOffice> findByDistrictCode(String code) {
        return jdbc.query("SELECT * FROM district_offices WHERE district_code = ?", MAPPER, code)
                .stream().findFirst();
    }
}
