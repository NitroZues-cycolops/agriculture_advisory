package com.precisionfarming.advisory_service.model;

/**
 * A district agriculture office row used by the "Find my district office" tile
 * on /schemes (STEP 3).
 *
 * The seeded rows are generic SAMPLE entries (one per district) and the UI
 * marks them SAMPLE: the exact address and phone number must be verified
 * locally. Kept as a record because it is only ever read, never edited.
 */
public record DistrictOffice(
        Long id,
        String districtCode,
        String officeName,
        String address,
        String note) {
}
