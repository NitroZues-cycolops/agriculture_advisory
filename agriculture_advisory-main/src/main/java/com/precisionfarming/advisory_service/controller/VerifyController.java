package com.precisionfarming.advisory_service.controller;

import com.precisionfarming.advisory_service.model.User;
import com.precisionfarming.advisory_service.repository.UserRepository;
import com.precisionfarming.advisory_service.service.EmailVerificationService;
import com.precisionfarming.advisory_service.service.VerificationSessionService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Email verification (PART 1 - the only verification step; phone OTP removed).
 *
 * ROUTES
 *   GET  /verify/email          ?h=...     - verify the email
 *   POST /verify/email/resend   h          - send the link (rate limited)
 *   GET  /verify/email/confirm  ?token=... - the link inside the email
 *   POST /verify/skip           h          - skip the page and go to login
 * ROLE: public (a new account is not logged in yet; SecurityConfig permits /verify/**)
 *
 * The account is identified by the random one-time handle in the link (table
 * verification_session), never by a user id typed into the URL - so nobody can
 * verify someone else's address by guessing an id. CSRF: every form uses
 * th:action, which adds the hidden token.
 */
@Controller
public class VerifyController {

    private static final Logger log = LoggerFactory.getLogger(VerifyController.class);

    private final UserRepository users;
    private final EmailVerificationService emailService;
    private final VerificationSessionService sessions;
    private final MessageSource messages;
    /** Optional public base URL for the verification link (blank -> use the request). */
    private final String baseUrlOverride;

    public VerifyController(UserRepository users,
                            EmailVerificationService emailService,
                            VerificationSessionService sessions, MessageSource messages,
                            @Value("${app.base-url:}") String baseUrlOverride) {
        this.users = users;
        this.emailService = emailService;
        this.sessions = sessions;
        this.messages = messages;
        this.baseUrlOverride = baseUrlOverride;
    }

    // ==================================================================
    // Email verification
    // ==================================================================

    /**
     * GET /verify/email?h=...
     * Shows the address and the button that sends the link; in demo mode the
     * link comes back as a flash attribute.
     * Template: pages/verify-email
     */
    @GetMapping("/verify/email")
    public String emailPage(@RequestParam(name = "h", required = false) String handle,
                            @RequestParam(name = "sent", required = false) String sent,
                            @RequestParam(name = "r", required = false) String sendStatus,
                            Model model, Locale locale) {

        Optional<Long> userId = sessions.resolve(handle);
        if (userId.isEmpty()) {
            return linkExpired(model);
        }
        Long id = userId.get();
        emailModel(model, id, handle);
        if (sent != null) {
            model.addAttribute("notice", msg("verify.email.sent", locale));
        } else if (sendStatus != null) {
            model.addAttribute("notice", sendMessage(sendStatus, locale));
        }
        return "pages/verify-email";
    }

    /** POST /verify/email/resend - creates and (really or in demo) sends the link. */
    @PostMapping("/verify/email/resend")
    public String resendEmail(@RequestParam(name = "h", required = false) String handle,
                              HttpServletRequest request, RedirectAttributes flash) {
        Optional<Long> userId = sessions.resolve(handle);
        if (userId.isEmpty()) {
            return "redirect:/login";
        }
        Long id = userId.get();
        User user = users.findById(id).orElse(null);
        if (user == null || user.getEmail() == null || user.getEmail().isBlank()) {
            return "redirect:/verify/email?h=" + handle;
        }
        EmailVerificationService.Dispatch dispatch =
                emailService.send(id, user.getEmail(), baseUrl(request));
        if (dispatch.status() != EmailVerificationService.SendStatus.OK) {
            // Refusing is a normal outcome: explain it and keep the page usable.
            return "redirect:/verify/email?h=" + handle + "&r=" + dispatch.status().name();
        }
        if (dispatch.demo()) {
            // Demo mode: carry the link through ONE redirect (flash attribute) so
            // the token is never written into the address bar.
            flash.addFlashAttribute("demoLink",
                    baseUrl(request) + "/verify/email/confirm?token=" + dispatch.token());
        }
        return "redirect:/verify/email?h=" + handle + "&sent=1";
    }

    /** GET /verify/email/confirm?token=... - the link that was emailed/displayed. */
    @GetMapping("/verify/email/confirm")
    public String confirmEmail(@RequestParam(name = "token", required = false) String token,
                               Model model) {
        EmailVerificationService.ConfirmResult result = emailService.confirm(token);
        model.addAttribute("result", result.status().name());
        model.addAttribute("ok", result.status() == EmailVerificationService.ConfirmStatus.OK
                || result.status() == EmailVerificationService.ConfirmStatus.ALREADY);
        return "pages/verify-email-done";
    }

    /**
     * POST /verify/skip - go straight to login. The one-time handle is removed
     * here: it has served its purpose.
     */
    @PostMapping("/verify/skip")
    public String skipEmail(@RequestParam(name = "h", required = false) String handle) {
        sessions.resolve(handle).ifPresent(sessions::clear);
        return "redirect:/login";
    }

    // ==================================================================
    // helpers
    // ==================================================================

    private String linkExpired(Model model) {
        return "pages/verify-link";
    }

    private void emailModel(Model model, Long userId, String handle) {
        User u = users.findById(userId).orElseThrow();
        model.addAttribute("h", handle);
        model.addAttribute("username", u.getUsername());
        model.addAttribute("email", u.getEmail());
        model.addAttribute("emailVerified", u.isEmailVerified());
        model.addAttribute("realMail", emailService.realMail());
        model.addAttribute("errors", Map.of());
    }

    private String sendMessage(String status, Locale locale) {
        return switch (status) {
            case "TOO_SOON" -> msg("verify.email.too_soon", locale);
            case "LIMIT" -> msg("verify.email.limit", locale);
            default -> msg("verify.email.sent", locale);
        };
    }

    /**
     * Base URL for the verification link. `app.base-url` pins it when set
     * (useful behind a proxy); otherwise it is taken from the request.
     */
    private String baseUrl(HttpServletRequest request) {
        if (baseUrlOverride != null && !baseUrlOverride.isBlank()) {
            String s = baseUrlOverride.trim();
            return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
        }
        return ServletUriComponentsBuilder.fromRequestUri(request)
                .replacePath(null).replaceQuery(null).build().toUriString();
    }

    private String msg(String key, Locale locale, Object... args) {
        return messages.getMessage(key, args, locale);
    }
}
