package com.precisionfarming.advisory_service.controller;

import com.precisionfarming.advisory_service.repository.CommunityDao;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

@ControllerAdvice
public class TopbarModelAdvice {

    private final CommunityDao community;

    public TopbarModelAdvice(CommunityDao community) {
        this.community = community;
    }

    @ModelAttribute("topbarUnread")
    public int unreadMessages(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getName())) {
            return 0;
        }
        return community.unreadCountByUsername(authentication.getName());
    }
}