package com.precisionfarming.advisory_service.model;

/**
 * One row of the readable district table under the /map picture (PART C).
 *
 * The table is the accessible alternative to the map: same values, sorted the
 * same way, in a real {@code <table>} with header cells.
 *
 * `valueText` is the reading ("31 °C") or the crop name for the crop layer.
 * `farmers` is only shown on the crop layer (how many registered farmers the
 * live number is based on).
 */
public record MapRow(
        String code,
        String name,
        String valueText,
        String cssClass,
        int farmers,
        double sortValue) {
}
