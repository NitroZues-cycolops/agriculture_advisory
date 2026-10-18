package com.precisionfarming.advisory_service.service;

import com.precisionfarming.advisory_service.model.WeatherDay;
import com.precisionfarming.advisory_service.model.WeatherReading;

import java.util.List;

/**
 * What one successful call to a WeatherSource produced.
 *
 * @param source  the name of the source that answered, e.g. "GREENALERTS"
 * @param current the current conditions (never null on success)
 * @param days    whatever part of the 7-day outlook the source published; may
 *                be empty (a greenalerts.in DISTRICT page has no day rows, only
 *                a block page does)
 */
public record WeatherSnapshot(
        String source,
        WeatherReading current,
        List<WeatherDay> days) {
}
