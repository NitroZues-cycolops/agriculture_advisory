package com.precisionfarming.advisory_service.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The three pages that do not belong to a workspace: home, login and the
 * signup chooser.
 *
 * SIGNUP IS A CHOOSER
 * /signup shows two links instead of one form: a farmer registers on
 * /register (RegisterController) and a lab on /register/lab
 * (LabRegisterController). The old single "create any role" form was removed
 * on purpose - it let a visitor create an OWNER account.
 */
@Controller
public class MainController {

    private static final Logger log = LoggerFactory.getLogger(MainController.class);

    /**
     * GET /
     * Role: ANY (public)
     * Model: none needed - home.html asks the security context for the CV
     * Template: pages/home.html
     */
    @GetMapping("/")
    public String home(Model model) {
        log.debug("[NAV] Home page");
        return "pages/home";
    }

    /**
     * GET /login
     * Role: ANY (public)
     * Template: pages/login.html
     */
    @GetMapping("/login")
    public String login() {
        return "pages/login";
    }

    /**
     * GET /signup
     * Role: ANY (public)
     * Template: pages/signup.html  (chooser: farmer or lab)
     */
    @GetMapping("/signup")
    public String signup() {
        return "pages/signup";
    }
}
