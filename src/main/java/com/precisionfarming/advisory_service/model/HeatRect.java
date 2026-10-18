package com.precisionfarming.advisory_service.model;

/**
 * One coloured rectangle of the interpolated heat map, with its band class
 * already resolved (PART C). The controller owns the colour rules, so the
 * template never has to know what a value means.
 */
public record HeatRect(String cssClass, double x, double y, double w, double h) {
}
