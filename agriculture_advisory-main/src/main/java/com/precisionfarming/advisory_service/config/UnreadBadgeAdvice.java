package com.precisionfarming.advisory_service.config;

import com.precisionfarming.advisory_service.model.User;
import com.precisionfarming.advisory_service.repository.CommunityDao;
import com.precisionfarming.advisory_service.repository.UserRepository;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * PART 3 - the unread-message badge in the top bar.
 *
 * This advice puts ONE value, {@code unreadCount}, into the model of EVERY page
 * so the shared header can show a count next to the Messages link without any
 * controller having to remember it. It is the ONLY chat query a page render runs
 * for that badge: a single cheap COUNT over direct_messages. For anonymous
 * visitors it returns 0 and runs NO query at all.
 *
 * The count is derived from the authentication name (never the URL), so it can
 * only ever be the visitor's own number.
 */
@ControllerAdvice
public class UnreadBadgeAdvice {

    private final CommunityDao community;
    private final UserRepository users;

    public UnreadBadgeAdvice(CommunityDao community, UserRepository users) {
        this.community = community;
        this.users = users;
    }

    @ModelAttribute("unreadCount")
    public int unreadCount(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()
                || auth.getName() == null || "anonymousUser".equals(auth.getName())) {
            return 0;
        }
        return users.findByUsername(auth.getName())
                .map(User::getId)
                .map(community::unreadCount)
                .orElse(0);
    }
}
