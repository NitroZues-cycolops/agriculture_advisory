package com.precisionfarming.advisory_service.model;

/**
 * One row of the `weather_config` table: a location the 3-hourly job watches.
 *
 * @param state     always "Maharashtra" today
 * @param district  English district name - must match districts.name_en so the
 *                  cached reading can be placed on the SVG map
 * @param taluka    the block (taluka) of the watched page
 * @param scrapeUrl the greenalerts.in page to read (a state, district or block
 *                  page - the scraper detects which one it got)
 * @param source    which WeatherSource to try first (GREENALERTS or OPENMETEO)
 */
public record WeatherConfig(
        Long id,
        String state,
        String district,
        String taluka,
        String scrapeUrl,
        String source) {

    /** "Pune / Haveli" - used in logs and on the /debug tile. */
    public String location() {
        return district + " / " + (taluka == null || taluka.isBlank() ? "-" : taluka);
    }
}
