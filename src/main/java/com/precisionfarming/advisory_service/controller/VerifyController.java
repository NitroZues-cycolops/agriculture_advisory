package com.precisionfarming.advisory_service.controller;

import com.precisionfarming.advisory_service.model.User;
import com.precisionfarming.advisory_service.repository.UserRepository;
import com.precisionfarming.advisory_service.service.EmailVerificationService;
import com.precisionfarming.advisory_service.service.VerificationSessionService;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Locale;
import java.util.Map;

@Controller
public class VerifyController {

    private final UserRepository users;
    private final EmailVerificationService emailService;
    private final VerificationSessionService sessions;
    private final MessageSource messages;

    public VerifyController(UserRepository users, EmailVerificationService emailService,
                            VerificationSessionService sessions, MessageSource messages) {
        this.users = users;
        this.emailService = emailService;
        this.sessions = sessions;
        this.messages = messages;
    }

    @GetMapping("/verify")
    public String verify(@RequestParam(name = "h", required = false) String handle) {
        return "redirect:/verify/email?h=" + (handle == null ? "" : handle);
    }

    @GetMapping("/verify/email")
    public String emailPage(@RequestParam(name = "h", required = false) String handle,
                            @RequestParam(name = "sent", required = false) String sent,
                            @RequestParam(name = "r", required = false) String status,
                            Model model, Locale locale) {
        Long userId = sessions.resolve(handle).orElse(null);
        if (userId == null) {
            return "pages/verify-link";
        }
        User user = users.findById(userId).orElse(null);
        if (user == null) {
            return "pages/verify-link";
        }
        model.addAttribute("h", handle);
        model.addAttribute("username", user.getUsername());
        model.addAttribute("email", user.getEmail());
        model.addAttribute("emailVerified", user.isEmailVerified());
        model.addAttribute("realMail", emailService.realMail());
        model.addAttribute("errors", Map.of());
        if (sent != null) {
            model.addAttribute("notice", msg("verify.email.sent", locale));
        }
        if (status != null) {
            model.addAttribute("notice", switch (status) {
                case "TOO_SOON" -> msg("verify.email.too_soon", locale);
                case "LIMIT" -> msg("verify.email.limit", locale);
                case "ALREADY" -> msg("verify.email.verified", locale);
                default -> msg("verify.email.sent", locale);
            });
        }
        return "pages/verify-email";
    }

    @PostMapping("/verify/email/resend")
    public String resendEmail(@RequestParam(name = "h", required = false) String handle,
                              RedirectAttributes flash) {
        Long userId = sessions.resolve(handle).orElse(null);
        if (userId == null) {
            return "redirect:/verify/email?h=";
        }
        User user = users.findById(userId).orElse(null);
        if (user == null || user.getEmail() == null || user.getEmail().isBlank()) {
            return "redirect:/verify/email?h=" + handle;
        }
        EmailVerificationService.Dispatch dispatch = emailService.send(userId, user.getEmail());
        if (dispatch.demo() && dispatch.token() != null) {
            flash.addFlashAttribute("demoLink", emailService.verificationLink(dispatch.token()));
        }
        return "redirect:/verify/email?h=" + handle + "&r=" + dispatch.status().name();
    }

    @GetMapping("/verify/email/confirm")
    public String confirmEmail(@RequestParam(name = "token", required = false) String token,
                               Model model) {
        EmailVerificationService.ConfirmResult result = emailService.confirm(token);
        if (result.userId() != null) {
            sessions.clear(result.userId());
        }
        model.addAttribute("result", result.status().name());
        model.addAttribute("ok", result.status() == EmailVerificationService.ConfirmStatus.OK);
        return "pages/verify-email-done";
    }

    @PostMapping("/verify/recover")
    public String recover(@RequestParam(name = "username", required = false) String username,
                          RedirectAttributes flash) {
        User user = users.findByUsername(username == null ? "" : username.trim()).orElse(null);
        if (user != null && !user.isEmailVerified() && user.getEmail() != null) {
            String handle = sessions.create(user.getId());
            EmailVerificationService.Dispatch dispatch = emailService.send(user.getId(), user.getEmail());
            if (dispatch.demo() && dispatch.token() != null) {
                flash.addFlashAttribute("demoLink", emailService.verificationLink(dispatch.token()));
            }
            return "redirect:/verify/email?h=" + handle + "&r=" + dispatch.status().name();
        }
        return "redirect:/login?recoverSent";
    }

    private String msg(String key, Locale locale) {
        return messages.getMessage(key, null, locale);
    }
}