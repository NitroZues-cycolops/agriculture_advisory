package com.precisionfarming.advisory_service.repository;

import com.precisionfarming.advisory_service.model.EmailVerification;
import com.precisionfarming.advisory_service.model.PhoneVerification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * All SQL for the PART B verification tables (schema_new.sql section 10):
 *   phone_verification    - one row per account, the phone OTP state
 *   email_verification    - one row per account, the email token
 *   verification_session  - the random /verify?h=... handle
 *
 * Plain JdbcTemplate on purpose (same as the other DAOs): short, explicit
 * queries that are easy to read aloud in the viva.
 *
 * Every "start" query is an INSERT ... ON DUPLICATE KEY UPDATE on the unique
 * user_id, so there is exactly one row per account and the methods are safe to
 * call again (a resend never creates a second row).
 */
@Repository
public class VerificationDao {

    private final JdbcTemplate jdbc;

    public VerificationDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<PhoneVerification> PHONE_MAPPER = (rs, i) -> new PhoneVerification(
            rs.getLong("id"),
            rs.getLong("user_id"),
            rs.getString("phone"),
            rs.getString("otp_hash"),
            rs.getInt("attempts"),
            rs.getTimestamp("locked_until") == null ? null : rs.getTimestamp("locked_until").toLocalDateTime(),
            rs.getTimestamp("expires_at").toLocalDateTime(),
            rs.getTimestamp("verified_at") == null ? null : rs.getTimestamp("verified_at").toLocalDateTime(),
            rs.getTimestamp("last_sent_at").toLocalDateTime(),
            rs.getTimestamp("window_start").toLocalDateTime(),
            rs.getInt("sends_in_window"));

    private static final RowMapper<EmailVerification> EMAIL_MAPPER = (rs, i) -> new EmailVerification(
            rs.getLong("id"),
            rs.getLong("user_id"),
            rs.getString("email"),
            rs.getString("token_hash"),
            rs.getTimestamp("expires_at").toLocalDateTime(),
            rs.getTimestamp("verified_at") == null ? null : rs.getTimestamp("verified_at").toLocalDateTime(),
            rs.getTimestamp("last_sent_at").toLocalDateTime());

    // ------------------------------------------------------------------
    // phone_verification
    // ------------------------------------------------------------------

    public Optional<PhoneVerification> findPhone(Long userId) {
        return jdbc.query("SELECT * FROM phone_verification WHERE user_id = ?", PHONE_MAPPER, userId)
                .stream().findFirst();
    }

    /**
     * Issues a first code (or replaces the state completely) and starts a new
     * 1-hour send window with one send in it.
     * `verified_at` is deliberately NOT touched, so re-issuing a code can never
     * silently un-verify an account.
     */
    public int startPhone(Long userId, String phone, String otpHash,
                          LocalDateTime now, LocalDateTime expiresAt) {
        return jdbc.update("""
                INSERT INTO phone_verification
                    (user_id, phone, otp_hash, attempts, locked_until, expires_at,
                     verified_at, last_sent_at, window_start, sends_in_window, created_at)
                VALUES (?, ?, ?, 0, NULL, ?, NULL, ?, ?, 1, ?)
                ON DUPLICATE KEY UPDATE
                    phone = VALUES(phone),
                    otp_hash = VALUES(otp_hash),
                    attempts = 0,
                    locked_until = NULL,
                    expires_at = VALUES(expires_at),
                    last_sent_at = VALUES(last_sent_at),
                    window_start = VALUES(window_start),
                    sends_in_window = 1
                """, userId, phone, otpHash, expiresAt, now, now, now);
    }

    /** Replaces the code of an EXISTING row and bumps the send counter. */
    public int resendPhone(Long userId, String otpHash, LocalDateTime now,
                           LocalDateTime expiresAt, LocalDateTime windowStart, int sends) {
        return jdbc.update("""
                UPDATE phone_verification
                   SET otp_hash = ?, attempts = 0, locked_until = NULL,
                       expires_at = ?, last_sent_at = ?, window_start = ?, sends_in_window = ?
                 WHERE user_id = ?
                """, otpHash, expiresAt, now, windowStart, sends, userId);
    }

    /** Counts one wrong code; locks the account when `lockedUntil` is not null. */
    public int recordPhoneFailure(Long userId, int attempts, LocalDateTime lockedUntil) {
        return jdbc.update("""
                UPDATE phone_verification SET attempts = ?, locked_until = ? WHERE user_id = ?
                """, attempts, lockedUntil, userId);
    }

    /** Marks the OTP verified AND flips users.phone_verified in the same call. */
    public int markPhoneVerified(Long userId, LocalDateTime now) {
        jdbc.update("UPDATE users SET phone_verified = 1 WHERE id = ?", userId);
        return jdbc.update("UPDATE phone_verification SET verified_at = ? WHERE user_id = ?", now, userId);
    }

    // ------------------------------------------------------------------
    // email_verification
    // ------------------------------------------------------------------

    public Optional<EmailVerification> findEmail(Long userId) {
        return jdbc.query("SELECT * FROM email_verification WHERE user_id = ?", EMAIL_MAPPER, userId)
                .stream().findFirst();
    }

    public Optional<EmailVerification> findEmailByToken(String tokenHash) {
        return jdbc.query("SELECT * FROM email_verification WHERE token_hash = ?", EMAIL_MAPPER, tokenHash)
                .stream().findFirst();
    }

    public int saveEmailToken(Long userId, String email, String tokenHash,
                              LocalDateTime now, LocalDateTime expiresAt) {
        return jdbc.update("""
                INSERT INTO email_verification
                    (user_id, email, token_hash, expires_at, verified_at, last_sent_at, created_at)
                VALUES (?, ?, ?, ?, NULL, ?, ?)
                ON DUPLICATE KEY UPDATE
                    email = VALUES(email),
                    token_hash = VALUES(token_hash),
                    expires_at = VALUES(expires_at),
                    last_sent_at = VALUES(last_sent_at)
                """, userId, email, tokenHash, expiresAt, now, now);
    }

    /** Marks the email verified AND flips users.email_verified in the same call. */
    public int markEmailVerified(Long userId, LocalDateTime now) {
        jdbc.update("UPDATE users SET email_verified = 1 WHERE id = ?", userId);
        return jdbc.update("UPDATE email_verification SET verified_at = ? WHERE user_id = ?", now, userId);
    }

    // ------------------------------------------------------------------
    // verification_session (the /verify?h=... handle)
    // ------------------------------------------------------------------

    public int createSession(String handle, Long userId, LocalDateTime expiresAt) {
        return jdbc.update("""
                INSERT INTO verification_session (handle, user_id, expires_at, created_at)
                VALUES (?, ?, ?, NOW())
                """, handle, userId, expiresAt);
    }

    /** The account behind a handle, or empty when unknown or expired. */
    public Optional<Long> findSessionUser(String handle) {
        return jdbc.queryForList(
                "SELECT user_id FROM verification_session WHERE handle = ? AND expires_at > NOW()",
                Long.class, handle).stream().findFirst();
    }

    /** Called once the phone is verified: the handle must not work again. */
    public int deleteSessionsForUser(Long userId) {
        return jdbc.update("DELETE FROM verification_session WHERE user_id = ?", userId);
    }

    /** Housekeeping: drop expired handles so the table cannot grow for ever. */
    public int deleteExpiredSessions() {
        return jdbc.update("DELETE FROM verification_session WHERE expires_at < NOW()");
    }
}
