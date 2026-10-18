package com.precisionfarming.advisory_service.model;

/**
 * A farmer as shown in the community: the login name plus whatever the farmer
 * saved in their profile. `hasProfile` tells the page whether to draw the farm
 * details or the "no profile yet" note.
 *
 * A record keeps this read-only carrier short enough to read aloud in the viva.
 */
public record Member(
        Long userId,
        String username,
        String fullName,
        String district,
        String taluka,
        String village,
        String crops,
        double farmArea,
        String unit,
        boolean hasProfile) {

    /** The name to print: the saved full name, else the username. */
    public String displayName() {
        return fullName != null && !fullName.isBlank() ? fullName : username;
    }
}
