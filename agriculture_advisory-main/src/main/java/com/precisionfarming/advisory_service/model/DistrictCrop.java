package com.precisionfarming.advisory_service.model;

/**
 * One row of the `district_crops` table (PART C, crop map - REFERENCE data).
 *
 * `crop` is a ReferenceData.CROPS code (the label is a message key, never a
 * hard-coded word). `sample` is true for every seeded row: the value is NOT
 * from an official dataset and the UI must say so on screen.
 */
public record DistrictCrop(
        String districtCode,
        String crop,
        double share,
        String source,
        Integer year,
        boolean sample) {
}
