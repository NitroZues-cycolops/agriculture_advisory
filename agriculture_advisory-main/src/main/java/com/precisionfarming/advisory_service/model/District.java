package com.precisionfarming.advisory_service.model;

/**
 * One district of Maharashtra (row of the `districts` table).
 *
 * WHY A RECORD: it is read-only reference data, so an immutable carrier is
 * enough and needs no JPA entity. Latitude/longitude are APPROXIMATE
 * district-headquarters coordinates and are only used to place a dot on the
 * STEP 4 SVG map.
 *
 * @param code   stable slug used in URLs, e.g. "raigad"
 * @param nameEn English name, also stored in farmer_profiles.district
 */
public record District(
        Long id,
        String code,
        String nameEn,
        String nameHi,
        String nameMr,
        Double lat,
        Double lon) {

    /** Returns the district name in the language of the current request. */
    public String nameFor(String lang) {
        return switch (lang == null ? "en" : lang) {
            case "hi" -> nameHi;
            case "mr" -> nameMr;
            default -> nameEn;
        };
    }
}
