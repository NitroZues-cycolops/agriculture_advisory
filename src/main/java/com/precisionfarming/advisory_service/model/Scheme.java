package com.precisionfarming.advisory_service.model;

/**
 * One government scheme, as shown by /schemes and /schemes/{key} (STEP 3).
 *
 * A plain carrier, not a JPA entity: the rows come from two tables
 * (`schemes` + `scheme_text`) through a JdbcTemplate join, so the readable
 * content can live in the database (en / hi / mr) and be edited by the OWNER
 * later, instead of being hard-coded in the Java or the message files.
 *
 * The read-only list columns (id, schemeKey, officialLink, sortOrder) come from
 * `schemes`; the language columns (name, what, who, docs, steps, where) come
 * from `scheme_text` for the language that is currently selected.
 */
public class Scheme {

    private Long id;
    private String schemeKey;
    private String officialLink;
    private Integer sortOrder;

    // from scheme_text (active language)
    private String name;
    private String what;
    private String who;
    private String docs;
    private String steps;
    private String where;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getSchemeKey() {
        return schemeKey;
    }

    public void setSchemeKey(String schemeKey) {
        this.schemeKey = schemeKey;
    }

    public String getOfficialLink() {
        return officialLink;
    }

    public void setOfficialLink(String officialLink) {
        this.officialLink = officialLink;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getWhat() {
        return what;
    }

    public void setWhat(String what) {
        this.what = what;
    }

    public String getWho() {
        return who;
    }

    public void setWho(String who) {
        this.who = who;
    }

    public String getDocs() {
        return docs;
    }

    public void setDocs(String docs) {
        this.docs = docs;
    }

    public String getSteps() {
        return steps;
    }

    public void setSteps(String steps) {
        this.steps = steps;
    }

    public String getWhere() {
        return where;
    }

    public void setWhere(String where) {
        this.where = where;
    }
}
