package com.precisionfarming.advisory_service.repository;

import com.precisionfarming.advisory_service.model.ScrapeLog;
import com.precisionfarming.advisory_service.model.ScrapeRun;
import com.precisionfarming.advisory_service.model.WeatherConfig;
import com.precisionfarming.advisory_service.model.WeatherDay;
import com.precisionfarming.advisory_service.model.WeatherReading;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * All SQL for the STEP 4 weather tables:
 *   weather_config      - the URLs the scheduled job watches
 *   weather_cache       - the current conditions the pages read
 *   weather_forecast    - one row per day of the 7-day outlook
 *   weather_scrape_log  - what happened on the last run of each URL
 *
 * READ RULE: the pages only ever call the find* methods, so they can never make
 * an HTTP request themselves. Only WeatherService calls the write methods.
 */
@Repository
public class WeatherDao {

    private final JdbcTemplate jdbc;

    public WeatherDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /* ---------- row mappers ---------- */

    private static final RowMapper<WeatherConfig> CONFIG_MAPPER = (rs, i) -> new WeatherConfig(
            rs.getLong("id"),
            rs.getString("state"),
            rs.getString("district"),
            rs.getString("taluka"),
            rs.getString("scrape_url"),
            rs.getString("source"));

    private static final RowMapper<WeatherReading> READING_MAPPER = (rs, i) -> {
        WeatherReading r = new WeatherReading();
        r.setId(rs.getLong("id"));
        r.setState(rs.getString("state"));
        r.setDistrict(rs.getString("district"));
        r.setTaluka(rs.getString("taluka"));
        r.setTemperature(rs.getString("temperature"));
        r.setRain(rs.getString("rain_chance"));
        r.setHumidity(rs.getString("humidity"));
        r.setWind(rs.getString("wind"));
        r.setSummary(rs.getString("summary"));
        r.setSource(rs.getString("source"));
        Timestamp ts = rs.getTimestamp("timestamp");
        r.setUpdatedAt(ts == null ? null : ts.toLocalDateTime());
        r.setSample(rs.getBoolean("is_sample"));
        return r;
    };

    private static final RowMapper<WeatherDay> DAY_MAPPER = (rs, i) -> {
        WeatherDay d = new WeatherDay(
                rs.getDate("forecast_day").toLocalDate(),
                rs.getString("temp_min"),
                rs.getString("temp_max"),
                rs.getString("rain_chance"),
                rs.getString("humidity"),
                rs.getString("wind"),
                rs.getString("summary"),
                rs.getString("source"));
        Timestamp ts = rs.getTimestamp("updated_at");
        d.setUpdatedAt(ts == null ? null : ts.toLocalDateTime());
        return d;
    };

    private static final RowMapper<ScrapeLog> LOG_MAPPER = (rs, i) -> {
        ScrapeLog l = new ScrapeLog();
        l.setUrl(rs.getString("url"));
        l.setSource(rs.getString("source"));
        l.setLastStatus(rs.getString("last_status"));
        l.setLastMessage(rs.getString("last_message"));
        Timestamp run = rs.getTimestamp("last_run_at");
        l.setLastRunAt(run == null ? null : run.toLocalDateTime());
        Timestamp ok = rs.getTimestamp("last_success_at");
        l.setLastSuccessAt(ok == null ? null : ok.toLocalDateTime());
        l.setRowsWritten(rs.getInt("rows_written"));
        return l;
    };

    private static final RowMapper<ScrapeRun> RUN_MAPPER = (rs, i) -> {
        Timestamp at = rs.getTimestamp("run_at");
        return new ScrapeRun(
                at == null ? null : at.toLocalDateTime(),
                rs.getString("trigger_type"),
                rs.getString("source"),
                rs.getInt("districts_ok"),
                rs.getInt("districts_total"),
                rs.getString("status"),
                rs.getString("message"));
    };

    /* ---------- weather_config ---------- */

    /** Every watched location. The scheduled job loops over this list. */
    public List<WeatherConfig> findAllConfigs() {
        return jdbc.query("SELECT * FROM weather_config ORDER BY district, taluka", CONFIG_MAPPER);
    }

    /* ---------- weather_cache (writes: scheduler only) ---------- */

    /** Appends one current reading. Rows are never updated, so history is kept. */
    public void insertReading(WeatherReading r) {
        jdbc.update("""
                INSERT INTO weather_cache
                    (config_id, timestamp, temperature, rain_chance, humidity, wind,
                     daily_forecast, source, district, taluka, state, summary, is_sample)
                VALUES (NULL, NOW(), ?, ?, ?, ?, NULL, ?, ?, ?, ?, ?, 0)
                """,
                r.getTemperature(), r.getRain(), r.getHumidity(), r.getWind(),
                r.getSource(), r.getDistrict(), r.getTaluka(), r.getState(), r.getSummary());
    }

    /**
     * The newest reading for one district. ORDER BY timestamp DESC + LIMIT 1 is
     * enough because the table holds only a few thousand demo rows.
     */
    public Optional<WeatherReading> latestForDistrict(String district) {
        return jdbc.query("""
                SELECT * FROM weather_cache
                WHERE district = ?
                ORDER BY timestamp DESC, id DESC
                LIMIT 1
                """, READING_MAPPER, district).stream().findFirst();
    }

    /** The newest reading for one district + taluka (a watched block page). */
    public Optional<WeatherReading> latestForLocation(String district, String taluka) {
        return jdbc.query("""
                SELECT * FROM weather_cache
                WHERE district = ? AND taluka = ?
                ORDER BY timestamp DESC, id DESC
                LIMIT 1
                """, READING_MAPPER, district, taluka).stream().findFirst();
    }

    /**
     * One newest reading per district, keyed by district name.
     *
     * The inner query finds the newest timestamp per district and the join then
     * pulls that exact row - a standard "latest row per group" query, which is
     * easy to draw on the whiteboard in the viva.
     */
    public Map<String, WeatherReading> latestPerDistrict() {
        List<WeatherReading> rows = jdbc.query("""
                SELECT c.* FROM weather_cache c
                JOIN (
                    SELECT district, MAX(timestamp) AS newest
                    FROM weather_cache
                    WHERE district IS NOT NULL
                    GROUP BY district
                ) x ON x.district = c.district AND x.newest = c.timestamp
                ORDER BY c.district
                """, READING_MAPPER);
        Map<String, WeatherReading> byDistrict = new HashMap<>();
        for (WeatherReading r : rows) {
            byDistrict.put(r.getDistrict(), r);
        }
        return byDistrict;
    }

    /** Total rows in weather_cache (shown on /debug). */
    public int countCacheRows() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM weather_cache", Integer.class);
        return n == null ? 0 : n;
    }

    /** Rows in weather_cache that are seeded SAMPLE data (shown on /debug). */
    public int countSampleRows() {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM weather_cache WHERE is_sample = 1", Integer.class);
        return n == null ? 0 : n;
    }

    /* ---------- weather_forecast ---------- */

    /**
     * Writes one forecast day, replacing the row for the same
     * (district, taluka, day). Because of this, a real forecast replaces a
     * seeded SAMPLE row as soon as the scraper succeeds.
     */
    public void upsertForecast(String district, String taluka, WeatherDay d) {
        jdbc.update("""
                INSERT INTO weather_forecast
                    (district, taluka, forecast_day, temp_min, temp_max, rain_chance,
                     humidity, wind, summary, source, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())
                ON DUPLICATE KEY UPDATE
                    temp_min = ?, temp_max = ?, rain_chance = ?, humidity = ?,
                    wind = ?, summary = ?, source = ?, updated_at = NOW()
                """,
                district, taluka, d.getDay(), d.getTempMin(), d.getTempMax(), d.getRain(),
                d.getHumidity(), d.getWind(), d.getSummary(), d.getSource(),
                d.getTempMin(), d.getTempMax(), d.getRain(), d.getHumidity(),
                d.getWind(), d.getSummary(), d.getSource());
    }

    /**
     * The forecast for one district, from today onwards.
     * When the farmer's district has several watched blocks, the rows of every
     * block are returned - the page groups them by taluka.
     */
    public List<WeatherDay> forecastForDistrict(String district, int days) {
        return jdbc.query("""
                SELECT * FROM weather_forecast
                WHERE district = ? AND forecast_day >= CURDATE()
                ORDER BY forecast_day, taluka
                LIMIT ?
                """, DAY_MAPPER, district, days);
    }

    /**
     * The forecast for one district + taluka. Used when a farmer's taluka
     * matches a watched block page exactly.
     */
    public List<WeatherDay> forecastForLocation(String district, String taluka, int days) {
        return jdbc.query("""
                SELECT * FROM weather_forecast
                WHERE district = ? AND taluka = ? AND forecast_day >= CURDATE()
                ORDER BY forecast_day
                LIMIT ?
                """, DAY_MAPPER, district, taluka, days);
    }

    /* ---------- weather_scrape_log ---------- */

    /** One row per watched URL, so /debug can show the whole watch list. */
    public List<ScrapeLog> findAllLogs() {
        return jdbc.query("SELECT * FROM weather_scrape_log ORDER BY url", LOG_MAPPER);
    }

    /**
     * Records the outcome of one URL. `last_success_at` is only moved forward
     * when the run actually produced data, so /debug can show how stale a
     * broken feed has become.
     */
    public void upsertLog(ScrapeLog l) {
        jdbc.update("""
                INSERT INTO weather_scrape_log
                    (url, source, last_status, last_message, last_run_at, last_success_at, rows_written)
                VALUES (?, ?, ?, ?, NOW(), ?, ?)
                ON DUPLICATE KEY UPDATE
                    source = ?, last_status = ?, last_message = ?,
                    last_run_at = NOW(),
                    last_success_at = COALESCE(?, last_success_at),
                    rows_written = ?
                """,
                l.getUrl(), l.getSource(), l.getLastStatus(), l.getLastMessage(),
                l.getLastSuccessAt(), l.getRowsWritten(),
                l.getSource(), l.getLastStatus(), l.getLastMessage(),
                l.getLastSuccessAt(), l.getRowsWritten());
    }

    /* ---------- weather_scrape_run (PART A: run history) ---------- */

    /** Records one refresh run so /debug can list the newest few. */
    public void insertRun(String trigger, String source, int ok, int total,
                          String status, String message) {
        jdbc.update("""
                INSERT INTO weather_scrape_run
                    (trigger_type, source, districts_ok, districts_total, status, message)
                VALUES (?, ?, ?, ?, ?, ?)
                """, trigger, source, ok, total, status, message);
    }

    /** The newest `limit` runs, most recent first. */
    public List<ScrapeRun> findRecentRuns(int limit) {
        return jdbc.query(
                "SELECT * FROM weather_scrape_run ORDER BY id DESC LIMIT ?",
                RUN_MAPPER, limit);
    }

    /**
     * PART A retention: removes history older than `days` days.
     * Current readings are appended (never overwritten), so without this the
     * cache would grow forever. Returns the number of rows deleted.
     */
    public int retention(int days) {
        int readings = jdbc.update(
                "DELETE FROM weather_cache WHERE timestamp < (NOW() - INTERVAL ? DAY)", days);
        int forecast = jdbc.update(
                "DELETE FROM weather_forecast WHERE forecast_day < (CURDATE() - INTERVAL ? DAY)", days);
        int runs = jdbc.update(
                "DELETE FROM weather_scrape_run WHERE run_at < (NOW() - INTERVAL ? DAY)", days);
        return readings + forecast + runs;
    }
}
