package com.precisionfarming.advisory_service.model;

import java.time.LocalDateTime;

/**
 * One row of the `phone_verification` table (PART B) - the phone OTP state of
 * one account. Plain carrier: JdbcTemplate fills it, the service reads it.
 *
 * The code itself is NOT here: only `otpHash` (BCrypt). A 6-digit code must
 * never be stored in clear.
 *
 * @param attempts      wrong codes entered since the last code was issued
 * @param lockedUntil   set after too many wrong codes; null when not locked
 * @param windowStart   start of the current "max 5 per hour" window
 * @param sendsInWindow how many codes were issued inside that window
 */
public record PhoneVerification(
        Long id,
        Long userId,
        String phone,
        String otpHash,
        int attempts,
        LocalDateTime lockedUntil,
        LocalDateTime expiresAt,
        LocalDateTime verifiedAt,
        LocalDateTime lastSentAt,
        LocalDateTime windowStart,
        int sendsInWindow) {

    public boolean isVerified() {
        return verifiedAt != null;
    }
}
