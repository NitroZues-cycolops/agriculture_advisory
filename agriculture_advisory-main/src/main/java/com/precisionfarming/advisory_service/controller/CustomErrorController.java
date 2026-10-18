package com.precisionfarming.advisory_service.controller;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
public class CustomErrorController implements ErrorController {

    @RequestMapping("/error")
    public String handleError(HttpServletRequest request, Model model) {
        Object status = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        Object exception = request.getAttribute(RequestDispatcher.ERROR_EXCEPTION);
        
        model.addAttribute("activeWorkspace", 0);
        model.addAttribute("layoutClass", "layout-single");

        if (status != null) {
            int statusCode = Integer.parseInt(status.toString());

            if (statusCode == HttpStatus.NOT_FOUND.value()) {
                return "error/404";
            } else if (statusCode == HttpStatus.FORBIDDEN.value()) {
                return "error/403";
            } else if (statusCode == HttpStatus.INTERNAL_SERVER_ERROR.value()) {
                if (exception != null) {
                    model.addAttribute("message", exception.toString());
                }
                return "error/500";
            }
        }
        return "error/500";
    }
}
