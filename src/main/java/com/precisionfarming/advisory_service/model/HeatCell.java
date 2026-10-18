package com.precisionfarming.advisory_service.model;

/**
 * One coloured cell of the interpolated heat map (PART C).
 *
 * The cell is already in SVG coordinates (x/y are the top-left corner, w/h the
 * size in viewBox units) and carries the INTERPOLATED value. The controller
 * turns the value into a band class, so the colour rules stay in one place
 * (exactly the same rules the polygon layers use).
 */
public record HeatCell(double x, double y, double w, double h, double value) {
}
