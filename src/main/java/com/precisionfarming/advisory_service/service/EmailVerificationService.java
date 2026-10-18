package com.precisionfarming.advisory_service.service;

import com.precisionfarming.advisory_service.model.EmailVerification;
import com.precisionfarming.advisory_service.repository.VerificationDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Email-only account verification. The hashed token is the login gate.
 *
 * Real mail: when `spring.mail.host` is set, Spring Boot creates a
 * JavaMailSender bean and this service sends a message with a link that
 * carries a random token. The token is stored only as a SHA-256 hex digest and
 * expires after email.token.hours (default 24).
 *
 * Demo mode: with no SMTP properties there is no JavaMailSender bean at all,
 * so the link is written to the log and returned to the page in a DEMO MODE box.
 */
@Service
public class EmailVerificationService {

    private static final Logger log = LoggerFactory.getLogger(EmailVerificationService.class);

    public enum ConfirmStatus { OK, INVALID, EXPIRED, ALREADY }

    public record ConfirmResult(ConfirmStatus status, Long userId) {
    }

    public enum SendStatus { SENT, TOO_SOON, LIMIT, ALREADY }

    /** @param demo true when nothing was really sent (token may be shown) */
    public record Dispatch(SendStatus status, boolean demo, String token) {
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    private final VerificationDao dao;
    private final ObjectProvider<JavaMailSender> mailSender;
    private final int tokenHours;
    private final String from;
    private final String baseUrl;
    private final int resendSeconds;
    private final int resendPerHour;

    public EmailVerificationService(VerificationDao dao,
                                    ObjectProvider<JavaMailSender> mailSender,
                                    @Value("${email.token.hours:24}") int tokenHours,
                                    @Value("${pfas.mail.from:no-reply@precisionfarming.local}") String from,
                                    @Value("${app.base-url:http://localhost:8080}") String baseUrl,
                                    @Value("${email.resend.seconds:60}") int resendSeconds,
                                    @Value("${email.resend.per.hour:5}") int resendPerHour) {
        this.dao = dao;
        this.mailSender = mailSender;
        this.tokenHours = tokenHours;
        this.from = from;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.resendSeconds = resendSeconds;
        this.resendPerHour = resendPerHour;
    }

    /** True when a real mail server is configured (a JavaMailSender bean exists). */
    public boolean realMail() {
        return mailSender.getIfAvailable() != null;
    }

    public String verificationLink(String token) {
        return baseUrl + "/verify/email/confirm?token=" + token;
    }

    /**
     * Creates a fresh token and delivers the link.
     *
     * @param linkPrefix absolute prefix, e.g. "http://localhost:8091"; the
     *                   returned/emailed link is prefix + /verify/email/confirm?token=...
     */
    public synchronized Dispatch send(Long userId, String email) {
        Optional<EmailVerification> existing = dao.findEmail(userId);
        if (existing.isPresent() && existing.get().isVerified()) {
            return new Dispatch(SendStatus.ALREADY, false, null);
        }
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime windowStart = now;
        int sends = 1;
        if (existing.isPresent()) {
            EmailVerification old = existing.get();
            if (old.lastSentAt().plusSeconds(resendSeconds).isAfter(now)) {
                return new Dispatch(SendStatus.TOO_SOON, false, null);
            }
            windowStart = old.resendWindowStart() == null ? old.lastSentAt() : old.resendWindowStart();
            sends = old.sendsInWindow();
            if (Duration.between(windowStart, now).toHours() >= 1) {
                windowStart = now;
                sends = 0;
            }
            if (sends >= resendPerHour) {
                return new Dispatch(SendStatus.LIMIT, false, null);
            }
            sends++;
        }
        String token = newToken();
        dao.saveEmailToken(userId, email, sha256Hex(token), now, now.plusHours(tokenHours), windowStart, sends);
        String link = verificationLink(token);

        JavaMailSender mail = mailSender.getIfAvailable();
        if (mail == null) {
            log.info("[EMAIL][DEMO] no SMTP configured; a demo verification link was issued");
                return new Dispatch(SendStatus.SENT, true, token);
        }
        try {
                MimeMessage message = mail.createMimeMessage();
                MimeMessageHelper helper = new MimeMessageHelper(message, StandardCharsets.UTF_8.name());
                helper.setFrom(from);
                helper.setTo(email);
                helper.setSubject("Verify your email - Precision Farming Advisory Service");
                String plain = "Hello,\n\n"
                    + "Open this link to verify your email address:\n" + link + "\n\n"
                    + "The link is valid for " + tokenHours + " hours. "
                    + "If you did not create this account you can ignore this message.\n";
                String html = "<p>Hello,</p><p>Open the link below to verify your email address:</p>"
                    + "<p><a href=\"" + html(link) + "\">Verify your email</a></p>"
                    + "<p>This link is valid for " + tokenHours + " hours. "
                    + "If you did not create this account, you can ignore this message.</p>";
                helper.setText(plain, html);
            mail.send(message);
                log.info("[EMAIL] verification message sent");
                return new Dispatch(SendStatus.SENT, false, null);
            } catch (RuntimeException | MessagingException ex) {
                log.warn("[EMAIL] mail delivery failed; showing the verification link in demo mode");
                return new Dispatch(SendStatus.SENT, true, token);
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

    private static String html(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
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
