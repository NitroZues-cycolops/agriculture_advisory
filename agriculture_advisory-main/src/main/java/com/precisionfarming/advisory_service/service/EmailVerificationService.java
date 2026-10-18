package com.precisionfarming.advisory_service.service;

import com.precisionfarming.advisory_service.model.EmailVerification;
import com.precisionfarming.advisory_service.repository.VerificationDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Email verification (PART B) - the ONLY verification step (phone OTP removed).
 *
 * Real mail: when `spring.mail.host` is set, Spring Boot creates a
 * JavaMailSender bean and this service sends a message with a link that
 * carries a random token. The token is stored only as a SHA-256 hex digest and
 * expires after email.token.hours (default 24).
 *
 * Demo mode: with no SMTP properties there is no JavaMailSender bean at all,
 * so the link is written to the log and returned to the page, where it is shown
 * inside a clearly labelled DEMO MODE box. No key required.
 *
 * RATE LIMIT: a new link may be requested at most once per minute and
 * `email.resend.per.hour` times per hour. The counters live on the
 * email_verification row (last_sent_at / window_start / sends_in_window), so
 * the limit survives a restart.
 *
 * PART 4: the check and the record run together inside one short transaction
 * that locks the row (SELECT ... FOR UPDATE); the SMTP send runs afterwards,
 * outside the transaction. There is no `synchronized` and no class-level lock,
 * so the rule holds with several app instances and a slow mail server cannot
 * block other users.
 */
@Service
public class EmailVerificationService {

    private static final Logger log = LoggerFactory.getLogger(EmailVerificationService.class);

    public enum ConfirmStatus { OK, INVALID, EXPIRED, ALREADY }

    /** Outcome of a send attempt; TOO_SOON / LIMIT are normal (not errors). */
    public enum SendStatus { OK, TOO_SOON, LIMIT }

    public record ConfirmResult(ConfirmStatus status, Long userId) {
    }

    /**
     * @param status     the outcome (OK, TOO_SOON or LIMIT)
     * @param demo       true when no email was delivered and the link is shown
     *                   on the page instead (no SMTP configured, or a send that failed)
     * @param token      the raw token, only when demo is true; null otherwise
     * @param sendFailed true when an SMTP send was ATTEMPTED and threw (so the
     *                   caller can say "we could not send the email" rather than
     *                   claiming it was sent); false in plain demo mode
     */
    public record Dispatch(SendStatus status, boolean demo, String token, boolean sendFailed) {
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    private final VerificationDao dao;
    private final ObjectProvider<JavaMailSender> mailSender;
    private final int tokenHours;
    private final int resendSeconds;
    private final int resendPerHour;
    private final String from;
    /** Optional public base URL; when blank the link is built from the request. */
    private final String baseUrlOverride;
    /** Runs the short rate-limit check + record step inside one transaction. */
    private final TransactionTemplate tx;

    public EmailVerificationService(VerificationDao dao,
                                    ObjectProvider<JavaMailSender> mailSender,
                                    PlatformTransactionManager txManager,
                                    @Value("${email.token.hours:24}") int tokenHours,
                                    @Value("${email.resend.seconds:60}") int resendSeconds,
                                    @Value("${email.resend.per.hour:5}") int resendPerHour,
                                    @Value("${pfas.mail.from:no-reply@precisionfarming.local}") String from,
                                    @Value("${app.base-url:}") String baseUrlOverride) {
        this.dao = dao;
        this.mailSender = mailSender;
        this.tokenHours = tokenHours;
        this.resendSeconds = resendSeconds;
        this.resendPerHour = resendPerHour;
        this.from = from;
        this.baseUrlOverride = baseUrlOverride;
        this.tx = new TransactionTemplate(txManager);
    }

    /** True when a real mail server is configured (a JavaMailSender bean exists). */
    public boolean realMail() {
        return mailSender.getIfAvailable() != null;
    }

    /**
     * PART 4: a real mail server needs a link that points at a host the reader
     * can reach. When SMTP is set up but app.base-url is blank the link is built
     * from whatever host the first request happened to use, which is usually
     * wrong behind a proxy - so warn once at start-up (we never block boot for a
     * college demo).
     */
    @EventListener(ApplicationReadyEvent.class)
    public void warnIfBaseUrlMissing() {
        if (realMail() && (baseUrlOverride == null || baseUrlOverride.isBlank())) {
            log.warn("[EMAIL] spring.mail.host is set but app.base-url is blank. "
                    + "Verification links will use the request's host, which may be unreachable "
                    + "from a mail client. Set app.base-url (e.g. https://your-host) for real mail.");
        }
    }

    /**
     * Creates a fresh token and delivers the link, unless the rate limit refuses.
     *
     * PART 4: the rate limit is checked and recorded ATOMICALLY inside one short
     * database transaction that locks the account's row (SELECT ... FOR UPDATE),
     * so two requests fired at the same time can never both pass. The SMTP call
     * happens AFTER that transaction has committed, so a slow mail server never
     * holds a database lock and a mail failure never breaks the flow.
     *
     * @param linkPrefix absolute prefix, e.g. "http://localhost:8091"; the
     *                   returned/emailed link is prefix + /verify/email/confirm?token=...
     */
    public Dispatch send(Long userId, String email, String linkPrefix) {
        // (1) SHORT TRANSACTION: check the limit and record the send together.
        Attempt attempt = tx.execute(status -> {
            LocalDateTime now = LocalDateTime.now();

            LocalDateTime windowStart = now;
            int sendsInWindow = 0;
            Optional<EmailVerification> maybe = dao.lockEmail(userId);
            if (maybe.isPresent()) {
                EmailVerification ev = maybe.get();
                if (ev.lastSentAt() != null
                        && ev.lastSentAt().isAfter(now.minusSeconds(resendSeconds))) {
                    return Attempt.refused(SendStatus.TOO_SOON);
                }
                windowStart = ev.windowStart();
                sendsInWindow = ev.sendsInWindow();
                if (windowStart == null || windowStart.isBefore(now.minusHours(1))) {
                    windowStart = now;
                    sendsInWindow = 0;
                }
                if (sendsInWindow >= resendPerHour) {
                    return Attempt.refused(SendStatus.LIMIT);
                }
            }

            String fresh = newToken();
            dao.saveEmailToken(userId, email, sha256Hex(fresh), now, now.plusHours(tokenHours),
                    windowStart, sendsInWindow + 1);
            return Attempt.sent(fresh);
        });

        if (attempt == null || attempt.isRefused()) {
            // Refusing is normal (TOO_SOON / LIMIT); never an error page.
            SendStatus refused = (attempt == null) ? SendStatus.TOO_SOON : attempt.refused();
            return new Dispatch(refused, false, null, false);
        }

        // (2) OUTSIDE THE TRANSACTION: build the link and deliver it.
        String token = attempt.token();
        String link = linkPrefix + "/verify/email/confirm?token=" + token;

        JavaMailSender mail = mailSender.getIfAvailable();
        if (mail == null) {
            log.info("[EMAIL][DEMO] no SMTP is configured, so no email is sent. "
                    + "Verification link for {}: {}", email, link);
            return new Dispatch(SendStatus.OK, true, token, false);
        }
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(from);
            message.setTo(email);
            message.setSubject("Verify your email - Precision Farming Advisory Service");
            message.setText("Hello,\n\n"
                    + "Open this link to verify your email address:\n" + link + "\n\n"
                    + "The link is valid for " + tokenHours + " hours. "
                    + "If you did not create this account you can ignore this message.\n");
            mail.send(message);
            log.info("[EMAIL] verification link sent to {}", email);
            return new Dispatch(SendStatus.OK, false, null, false);
        } catch (RuntimeException ex) {
            // A mail failure must not break the flow (never a 500): fall back to
            // demo mode, show the link AND tell the user the email could not be
            // sent, so the wording never claims a delivery that did not happen.
            log.warn("[EMAIL] sending to {} failed ({}); showing the link instead", email, ex.toString());
            return new Dispatch(SendStatus.OK, true, token, true);
        }
    }

    /** Outcome of the locked check-and-record step (only ever one of the two). */
    private record Attempt(SendStatus refused, String token) {
        static Attempt refused(SendStatus status) {
            return new Attempt(status, null);
        }

        static Attempt sent(String token) {
            return new Attempt(null, token);
        }

        boolean isRefused() {
            return refused != null;
        }
    }

    /** Called by the link in the email (GET /verify/email/confirm?token=...). */
    public ConfirmResult confirm(String token) {
        if (token == null || token.isBlank()) {
            return new ConfirmResult(ConfirmStatus.INVALID, null);
        }
        Optional<EmailVerification> found = dao.findEmailByToken(sha256Hex(token.trim()));
        if (found.isEmpty()) {
            return new ConfirmResult(ConfirmStatus.INVALID, null);
        }
        EmailVerification ev = found.get();
        if (ev.isVerified()) {
            return new ConfirmResult(ConfirmStatus.ALREADY, ev.userId());
        }
        if (ev.expiresAt().isBefore(LocalDateTime.now())) {
            return new ConfirmResult(ConfirmStatus.EXPIRED, ev.userId());
        }
        dao.markEmailVerified(ev.userId(), LocalDateTime.now());
        log.info("[EMAIL] address verified for user {}", ev.userId());
        return new ConfirmResult(ConfirmStatus.OK, ev.userId());
    }

    // ------------------------------------------------------------------

    private static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /** SHA-256 hex digest - deterministic, so the emailed token can be looked up. */
    static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }
}
