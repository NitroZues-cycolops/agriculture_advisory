package com.precisionfarming.advisory_service.model;

/**
 * One district POLYGON on the /map SVG (PART C).
 *
 * `path` is the ready-to-draw SVG path data (already projected from
 * longitude/latitude). `cssClass` is either a `.band-*` class (temperature,
 * rain, wind) or a `.crop-*` class (crop map). `tooltip` is the text the
 * browser shows from the path's SVG {@code <title>} element.
 *
 * The label is only drawn when the district is big enough on screen
 * (`showLabel`), so small districts do not turn into a pile of overlapping
 * names.
 */
public record MapShape(
        String code,
        String name,
        String path,
        String cssClass,
        String tooltip,
        boolean showLabel,
        double labelX,
        double labelY) {
}
