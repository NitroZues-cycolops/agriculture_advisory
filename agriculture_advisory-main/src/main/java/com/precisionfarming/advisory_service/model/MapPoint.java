package com.precisionfarming.advisory_service.model;

/**
 * One district headquarters placed on the STEP 4 SVG map.
 *
 * WHY PRE-COMPUTED COORDINATES: the template must stay JavaScript-free, so the
 * linear map projection (latitude/longitude -> SVG x/y) is done once in Java
 * and the template only draws a circle per point.
 *
 * @param code      district slug - the dot links to /map?district=<code>
 * @param name      district name in the active language
 * @param x         SVG x in the 0..1000 viewBox
 * @param y         SVG y in the 0..600 viewBox
 * @param band      0..5 colour band of the active layer, or -1 when there is
 *                  no reading for this district yet
 * @param valueText the value printed in the tooltip, e.g. "29 °C"
 */
public record MapPoint(
        String code,
        String name,
        double x,
        double y,
        int band,
        String valueText) {

    /** The CSS class the template puts on the dot, e.g. "band-3" or "band-none". */
    public String bandClass() {
        return band < 0 ? "band-none" : "band-" + band;
    }

    /**
     * The tooltip of one dot: "Pune - 33 °C", or just the district name when
     * that district has no cached reading yet. Built here so the template stays
     * JavaScript-free and needs no null check.
     */
    public String label() {
        return valueText == null || valueText.isBlank() ? name : name + " - " + valueText;
    }
}
