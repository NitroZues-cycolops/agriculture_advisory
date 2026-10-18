package com.precisionfarming.advisory_service.repository;

import com.precisionfarming.advisory_service.model.Scheme;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Read access to the government-scheme tables (STEP 3).
 *
 *   schemes      - one row per scheme: the key, the display order and the
 *                  official link
 *   scheme_text  - the readable content of each scheme in each language
 *                  (en / hi / mr)
 *
 * The two are joined here so the controller receives exactly what the page
 * needs for the language the visitor selected. Plain JdbcTemplate keeps the SQL
 * visible and easy to explain in the viva.
 *
 * The OWNER edits the rows of these tables in STEP 7; this class only reads.
 */
@Repository
public class SchemeDao {

    private final JdbcTemplate jdbc;

    public SchemeDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** List row: only the columns the /schemes cards need. */
    private static final RowMapper<Scheme> CARD_MAPPER = (rs, i) -> {
        Scheme s = new Scheme();
        s.setId(rs.getLong("id"));
        s.setSchemeKey(rs.getString("scheme_key"));
        s.setOfficialLink(rs.getString("official_link"));
        s.setSortOrder(rs.getInt("sort_order"));
        s.setName(rs.getString("scheme_name"));
        s.setWhat(rs.getString("what_text"));
        return s;
    };

    /** Detail row: every scheme_text column for the active language. */
    private static final RowMapper<Scheme> FULL_MAPPER = (rs, i) -> {
        Scheme s = new Scheme();
        s.setId(rs.getLong("id"));
        s.setSchemeKey(rs.getString("scheme_key"));
        s.setOfficialLink(rs.getString("official_link"));
        s.setSortOrder(rs.getInt("sort_order"));
        s.setName(rs.getString("scheme_name"));
        s.setWhat(rs.getString("what_text"));
        s.setWho(rs.getString("who_text"));
        s.setDocs(rs.getString("docs_text"));
        s.setSteps(rs.getString("steps_text"));
        s.setWhere(rs.getString("where_text"));
        return s;
    };

    /**
     * All schemes in display order, with name + short description in `lang`.
     * Uses a LEFT JOIN so a scheme still appears even if its text row for that
     * language is missing.
     */
    public List<Scheme> findAll(String lang) {
        return jdbc.query("""
                SELECT s.id, s.scheme_key, s.official_link, s.sort_order,
                       t.scheme_name, t.what_text
                FROM schemes s
                LEFT JOIN scheme_text t ON t.scheme_id = s.id AND t.lang = ?
                ORDER BY s.sort_order, s.id
                """, CARD_MAPPER, lang);
    }

    /** One scheme with its full text in `lang`, looked up by its URL key. */
    public Optional<Scheme> findByKey(String schemeKey, String lang) {
        return jdbc.query("""
                SELECT s.id, s.scheme_key, s.official_link, s.sort_order,
                       t.scheme_name, t.what_text, t.who_text, t.docs_text,
                       t.steps_text, t.where_text
                FROM schemes s
                LEFT JOIN scheme_text t ON t.scheme_id = s.id AND t.lang = ?
                WHERE s.scheme_key = ?
                """, FULL_MAPPER, lang, schemeKey)
                .stream().findFirst();
    }

    /**
     * OWNER-only (STEP 7): saves one scheme's text in one language.
     * The unique key (scheme_id, lang) plus ON DUPLICATE KEY UPDATE means the
     * first save inserts the row and every later save updates it, so the owner
     * can edit without the app ever inserting a duplicate.
     */
    public void updateText(Long schemeId, String lang, String name, String what,
                           String who, String docs, String steps, String where) {
        jdbc.update("""
                INSERT INTO scheme_text
                    (scheme_id, lang, scheme_name, what_text, who_text, docs_text,
                     steps_text, where_text)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    scheme_name = VALUES(scheme_name),
                    what_text   = VALUES(what_text),
                    who_text    = VALUES(who_text),
                    docs_text   = VALUES(docs_text),
                    steps_text  = VALUES(steps_text),
                    where_text  = VALUES(where_text)
                """, schemeId, lang, name, what, who, docs, steps, where);
    }
}
