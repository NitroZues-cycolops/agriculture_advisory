package com.precisionfarming.advisory_service.service;

import com.precisionfarming.advisory_service.model.WeatherConfig;

import java.util.Optional;

/**
 * One place the weather can be read from.
 *
 * WHY AN INTERFACE: the viva question is "what happens when the website is
 * down or the network is off?". The answer is this interface. WeatherService
 * tries the sources in order and the first one that returns a snapshot wins:
 *
 *   GREENALERTS -> https://www.greenalerts.in (Jsoup HTML scrape, IMD data)
 *   OPENMETEO   -> https://api.open-meteo.com (public JSON API, no key needed)
 *
 * If BOTH fail, WeatherService logs the failure and leaves the previous rows in
 * weather_cache untouched, so the pages keep showing the last good reading.
 */
public interface WeatherSource {

    /** The name written into weather_cache.source, e.g. "GREENALERTS". */
    String name();

    /**
     * Reads the current conditions (and any forecast days) for one location.
     *
     * @return a snapshot, or Optional.empty() when this source could not be
     *         read - never an exception, so one dead source cannot stop the job
     */
    Optional<WeatherSnapshot> fetch(WeatherConfig config);
}
