package com.precisionfarming.advisory_service.service;

import com.precisionfarming.advisory_service.model.EmailVerification;
import com.precisionfarming.advisory_service.repository.VerificationDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Email verification (PART B) - OPTIONAL for the user: it never blocks login.
 *
 * Real mail: when `spring.mail.host` is set, Spring Boot creates a
 * JavaMailSender bean and this service sends a message with a link that
 * carries a random token. The token is stored only as a SHA-256 hex digest and
 * expires after email.token.hours (default 24).
 *
 * Demo mode: with no SMTP properties there is no JavaMailSender bean at all,
 * so the link is written to the log and returned to the page, where it is shown
 * inside the same clearly labelled DEMO MODE box as the OTP. No key required.
 */
@Service
public class EmailVerificationService {

    private static final Logger log = LoggerFactory.getLogger(EmailVerificationService.class);

    public enum ConfirmStatus { OK, INVALID, EXPIRED, ALREADY }

    public record ConfirmResult(ConfirmStatus status, Long userId) {
    }

    /** @param demo true when nothing was really sent (token may be shown) */
    public record Dispatch(boolean demo, String token) {
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    private final VerificationDao dao;
    private final ObjectProvider<JavaMailSender> mailSender;
    private final int tokenHours;
    private final String from;

    public EmailVerificationService(VerificationDao dao,
                                    ObjectProvider<JavaMailSender> mailSender,
                                    @Value("${email.token.hours:24}") int tokenHours,
                                    @Value("${pfas.mail.from:no-reply@precisionfarming.local}") String from) {
        this.dao = dao;
        this.mailSender = mailSender;
        this.tokenHours = tokenHours;
        this.from = from;
    }

    /** True when a real mail server is configured (a JavaMailSender bean exists). */
    public boolean realMail() {
        return mailSender.getIfAvailable() != null;
    }

    /**
     * Creates a fresh token and delivers the link.
     *
     * @param linkPrefix absolute prefix, e.g. "http://localhost:8091"; the
     *                   returned/emailed link is prefix + /verify/email/confirm?token=...
     */
    public Dispatch send(Long userId, String email, String linkPrefix) {
        String token = newToken();
        LocalDateTime now = LocalDateTime.now();
        dao.saveEmailToken(userId, email, sha256Hex(token), now, now.plusHours(tokenHours));
        String link = linkPrefix + "/verify/email/confirm?token=" + token;

        JavaMailSender mail = mailSender.getIfAvailable();
        if (mail == null) {
            log.info("[EMAIL][DEMO] no SMTP is configured, so no email is sent. "
                    + "Verification link for {}: {}", email, link);
            return new Dispatch(true, token);
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
            return new Dispatch(false, null);
        } catch (RuntimeException ex) {
            // A mail failure must not break the flow: the account already works
            // (email verification is optional), so demo mode is used instead.
            log.warn("[EMAIL] sending to {} failed ({}); showing the link instead", email, ex.toString());
            return new Dispatch(true, token);
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
