package com.precisionfarming.advisory_service.repository;

import com.precisionfarming.advisory_service.model.District;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Read access to the read-only `districts` table (36 rows, seeded by data_new.sql).
 *
 * Used by: RegisterController (dropdown), ProfileController (dropdown),
 *          and later the STEP 4 map and STEP 3 "find my district office".
 */
@Repository
public class DistrictDao {

    private final JdbcTemplate jdbc;

    public DistrictDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<District> MAPPER = (rs, i) -> new District(
            rs.getLong("id"),
            rs.getString("code"),
            rs.getString("name_en"),
            rs.getString("name_hi"),
            rs.getString("name_mr"),
            rs.getDouble("lat"),
            rs.getDouble("lon"));

    /** All 36 districts, in English alphabetical order. */
    public List<District> findAll() {
        return jdbc.query("SELECT * FROM districts ORDER BY name_en", MAPPER);
    }

    /** Look a district up by its URL slug (e.g. "raigad"). */
    public Optional<District> findByCode(String code) {
        return jdbc.query("SELECT * FROM districts WHERE code = ?", MAPPER, code).stream().findFirst();
    }

    /** Look a district up by the English name that farmer_profiles.district stores. */
    public Optional<District> findByName(String nameEn) {
        return jdbc.query("SELECT * FROM districts WHERE name_en = ?", MAPPER, nameEn).stream().findFirst();
    }

    /** True when the submitted district really is one of the 36 (server-side validation). */
    public boolean nameExists(String nameEn) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM districts WHERE name_en = ?", Integer.class, nameEn);
        return n != null && n > 0;
    }
}
