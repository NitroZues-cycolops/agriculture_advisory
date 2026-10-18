package com.precisionfarming.advisory_service.model;

import java.time.LocalDateTime;

/**
 * One row of the `email_verification` table (PART B).
 *
 * The token sent in the link is stored only as a SHA-256 hex digest
 * (`tokenHash`), so reading the table cannot reveal a usable link.
 */
public record EmailVerification(
        Long id,
        Long userId,
        String email,
        String tokenHash,
        LocalDateTime expiresAt,
        LocalDateTime verifiedAt,
        LocalDateTime lastSentAt,
        LocalDateTime windowStart,
        int sendsInWindow) {

    public boolean isVerified() {
        return verifiedAt != null;
    }
}
