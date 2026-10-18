package com.precisionfarming.advisory_service.repository;

import com.precisionfarming.advisory_service.model.DistrictCrop;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * All SQL for the `district_crops` table (PART C, crop map).
 *
 * Plain JdbcTemplate like the other DAOs: the queries are short and can be read
 * out loud in the viva. Every seeded row carries is_sample=1, and the crop map
 * prints "Sample data. Verify before use." whenever any row it used is a sample.
 */
@Repository
public class DistrictCropDao {

    private final JdbcTemplate jdbc;

    public DistrictCropDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<DistrictCrop> MAPPER = (rs, i) -> new DistrictCrop(
            rs.getString("district_code"),
            rs.getString("crop"),
            rs.getDouble("share_percent"),
            rs.getString("source"),
            (Integer) rs.getObject("year"),
            rs.getBoolean("is_sample"));

    /** Every reference row, biggest share first. */
    public List<DistrictCrop> findAll() {
        return jdbc.query(
                "SELECT * FROM district_crops ORDER BY district_code, share_percent DESC", MAPPER);
    }

    /**
     * The MAJORITY crop of each district: district code -> the row with the
     * biggest share. Used to colour the crop map and to label the side tile.
     */
    public Map<String, DistrictCrop> majorityByDistrict() {
        Map<String, DistrictCrop> out = new HashMap<>();
        for (DistrictCrop row : findAll()) {
            DistrictCrop kept = out.get(row.districtCode());
            if (kept == null || row.share() > kept.share()) {
                out.put(row.districtCode(), row);
            }
        }
        return out;
    }

    /** How many reference rows exist - shown on /debug and used by the smoke test. */
    public int count() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM district_crops", Integer.class);
        return n == null ? 0 : n;
    }

    /** True when at least one row used for the crop map is marked SAMPLE. */
    public boolean anySample() {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM district_crops WHERE is_sample = 1", Integer.class);
        return n != null && n > 0;
    }
}
