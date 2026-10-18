package com.precisionfarming.advisory_service.service;

import com.precisionfarming.advisory_service.model.PhoneVerification;
import com.precisionfarming.advisory_service.repository.VerificationDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * The phone OTP rules (PART B), in ONE place so they can be explained once:
 *
 *   - a 6-digit random code, stored HASHED (BCrypt), never in clear
 *   - valid for otp.expiry.minutes (default 10)
 *   - at most otp.max.attempts wrong codes (default 5), then a
 *     otp.lock.minutes lockout (default 15)
 *   - a new code at most once every otp.resend.seconds (default 60) and at
 *     most otp.resend.per.hour times per hour (default 5)
 *
 * The sender is chosen by the `otp.provider` property (demo | sms), so this
 * class never mentions a provider directly.
 */
@Service
public class OtpService {

    private static final Logger log = LoggerFactory.getLogger(OtpService.class);

    /** Result of submitting a code. */
    public enum Status { OK, WRONG, EXPIRED, LOCKED, ALREADY, NONE }

    /** Result of asking for a new code. */
    public enum ResendStatus { OK, TOO_SOON, LIMIT, LOCKED, NONE }

    /**
     * @param attemptsLeft wrong tries left before the lockout (WRONG only)
     * @param lockMinutes  minutes of lockout (LOCKED only)
     */
    public record VerifyResult(Status status, int attemptsLeft, long lockMinutes) {
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    private final VerificationDao dao;
    private final PasswordEncoder encoder;
    private final List<OtpSender> senders;
    private final String provider;
    private final int expiryMinutes;
    private final int maxAttempts;
    private final int lockMinutes;
    private final int resendSeconds;
    private final int resendPerHour;

    public OtpService(VerificationDao dao,
                      PasswordEncoder encoder,
                      List<OtpSender> senders,
                      @Value("${otp.provider:demo}") String provider,
                      @Value("${otp.expiry.minutes:10}") int expiryMinutes,
                      @Value("${otp.max.attempts:5}") int maxAttempts,
                      @Value("${otp.lock.minutes:15}") int lockMinutes,
                      @Value("${otp.resend.seconds:60}") int resendSeconds,
                      @Value("${otp.resend.per.hour:5}") int resendPerHour) {
        this.dao = dao;
        this.encoder = encoder;
        this.senders = senders;
        this.provider = provider;
        this.expiryMinutes = expiryMinutes;
        this.maxAttempts = maxAttempts;
        this.lockMinutes = lockMinutes;
        this.resendSeconds = resendSeconds;
        this.resendPerHour = resendPerHour;
    }

    /** The sender selected by `otp.provider` (falls back to the first bean). */
    public OtpSender activeSender() {
        return senders.stream()
                .filter(s -> s.provider().equalsIgnoreCase(provider))
                .findFirst()
                .orElseGet(() -> senders.isEmpty() ? null : senders.get(0));
    }

    /** True when the active sender sends nothing real (demo mode). */
    public boolean demoMode() {
        OtpSender s = activeSender();
        return s != null && s.demo();
    }

    /** The code to display on the page, ONLY in demo mode (else null). */
    public String demoCode(String phone) {
        OtpSender s = activeSender();
        if (s instanceof DemoOtpSender demo) {
            return demo.lastCode(phone);
        }
        return null;
    }

    /** How many wrong tries are allowed (shown to the user as a hint). */
    public int maxAttempts() {
        return maxAttempts;
    }

    /** Length of the lockout in minutes (shown in the "too many tries" message). */
    public long lockMinutes() {
        return lockMinutes;
    }

    // ------------------------------------------------------------------
    // Issue / resend
    // ------------------------------------------------------------------

    /** First code for an account (called right after registration). */
    public void issue(Long userId, String phone) {
        String code = newCode();
        LocalDateTime now = LocalDateTime.now();
        dao.startPhone(userId, phone, encoder.encode(code), now, now.plusMinutes(expiryMinutes));
        deliver(phone, code);
        log.info("[OTP] code issued for user {} (phone {})", userId, phone);
    }

    /** Replaces the code of an account that already has a row. */
    public ResendStatus resend(Long userId, String phone) {
        Optional<PhoneVerification> found = dao.findPhone(userId);
        if (found.isEmpty()) {
            return ResendStatus.NONE;
        }
        PhoneVerification pv = found.get();
        if (pv.isVerified()) {
            return ResendStatus.NONE;
        }
        LocalDateTime now = LocalDateTime.now();
        if (pv.lockedUntil() != null && pv.lockedUntil().isAfter(now)) {
            return ResendStatus.LOCKED;
        }
        if (pv.lastSentAt().plusSeconds(resendSeconds).isAfter(now)) {
            return ResendStatus.TOO_SOON;
        }
        // A window older than one hour starts a fresh count.
        LocalDateTime windowStart = pv.windowStart();
        int sends = pv.sendsInWindow();
        if (windowStart == null || windowStart.plusHours(1).isBefore(now)) {
            windowStart = now;
            sends = 0;
        }
        if (sends >= resendPerHour) {
            return ResendStatus.LIMIT;
        }
        String code = newCode();
        dao.resendPhone(userId, encoder.encode(code), now, now.plusMinutes(expiryMinutes),
                windowStart, sends + 1);
        deliver(phone, code);
        log.info("[OTP] code resent for user {} ({} of {} in this hour)", userId, sends + 1, resendPerHour);
        return ResendStatus.OK;
    }

    // ------------------------------------------------------------------
    // Verify
    // ------------------------------------------------------------------

    public VerifyResult verify(Long userId, String entered) {
        Optional<PhoneVerification> found = dao.findPhone(userId);
        if (found.isEmpty()) {
            return new VerifyResult(Status.NONE, 0, 0);
        }
        PhoneVerification pv = found.get();
        LocalDateTime now = LocalDateTime.now();

        if (pv.isVerified()) {
            return new VerifyResult(Status.ALREADY, 0, 0);
        }
        if (pv.lockedUntil() != null && pv.lockedUntil().isAfter(now)) {
            long minutes = Math.max(1, Duration.between(now, pv.lockedUntil()).toMinutes() + 1);
            return new VerifyResult(Status.LOCKED, 0, minutes);
        }
        if (pv.expiresAt().isBefore(now)) {
            return new VerifyResult(Status.EXPIRED, 0, 0);
        }
        if (entered == null || !encoder.matches(entered.trim(), pv.otpHash())) {
            int attempts = pv.attempts() + 1;
            if (attempts >= maxAttempts) {
                dao.recordPhoneFailure(userId, 0, now.plusMinutes(lockMinutes));
                log.info("[OTP] user {} locked out for {} minutes after {} wrong codes",
                        userId, lockMinutes, attempts);
                return new VerifyResult(Status.LOCKED, 0, lockMinutes);
            }
            dao.recordPhoneFailure(userId, attempts, null);
            return new VerifyResult(Status.WRONG, maxAttempts - attempts, 0);
        }

        dao.markPhoneVerified(userId, now);
        log.info("[OTP] phone verified for user {}", userId);
        return new VerifyResult(Status.OK, 0, 0);
    }

    // ------------------------------------------------------------------

    private void deliver(String phone, String code) {
        OtpSender sender = activeSender();
        if (sender == null) {
            log.warn("[OTP] no OtpSender bean is available; the code was not delivered");
            return;
        }
        try {
            sender.send(phone, code);
        } catch (RuntimeException ex) {
            // Delivery must never break registration: the hashed code is stored
            // already, so the user can ask for a new one.
            log.warn("[OTP] sender '{}' failed for {}: {}", sender.provider(), phone, ex.toString());
        }
    }

    /** A 6-digit code, uniformly random, with leading zeros kept. */
    private static String newCode() {
        return String.format("%06d", RANDOM.nextInt(1_000_000));
    }
}
