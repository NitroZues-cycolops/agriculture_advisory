package com.precisionfarming.advisory_service.controller;

import com.precisionfarming.advisory_service.model.Comment;
import com.precisionfarming.advisory_service.model.Conversation;
import com.precisionfarming.advisory_service.model.Member;
import com.precisionfarming.advisory_service.model.Message;
import com.precisionfarming.advisory_service.model.Post;
import com.precisionfarming.advisory_service.model.User;
import com.precisionfarming.advisory_service.repository.CommunityDao;
import com.precisionfarming.advisory_service.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

/**
 * Community workspace (workspace 6) - STEP 6.
 *
 *   GET  /community                      feed + new-post form + farmer directory
 *   POST /community/post                 create a post (text + optional photo)
 *   GET  /community/post/{id}            one post with its comments
 *   POST /community/post/{id}/comment    add a comment
 *   POST /community/post/{id}/vote       upvote / downvote (one vote per user)
 *   POST /community/post/{id}/delete     OWNER only
 *   POST /community/follow/{id}          follow / unfollow a farmer
 *   GET  /community/profile/{id}         a farmer's community profile
 *   GET  /community/messages             inbox
 *   GET  /community/messages/{id}        thread with one farmer
 *   POST /community/messages/{id}        send a message
 *   GET  /community/image/{id}           serve an uploaded photo (ownership checked)
 *
 * SECURITY: every form uses th:action (CSRF). The "me" id and role come from the
 * security context, never from the URL. Photos are capped at 2 MB, JPG/PNG only,
 * stored OUTSIDE src/main/resources/static and served back through this
 * controller - so the file can never be fetched straight off the web.
 *
 * NO JAVASCRIPT: the vote buttons are small POST forms, the follow button is a
 * POST form and the inbox is a list of links.
 */
@Controller
public class CommunityController {

    private static final Logger log = LoggerFactory.getLogger(CommunityController.class);

    private static final int MAX_CONTENT = 1000;
    private static final int MAX_COMMENT = 500;
    private static final long MAX_IMAGE = 2L * 1024 * 1024;

    private final CommunityDao community;
    private final UserRepository users;
    private final MessageSource messages;
    private final String uploadDir;

    public CommunityController(CommunityDao community, UserRepository users,
                               MessageSource messages,
                               @Value("${pfas.uploads.dir:uploads}") String uploadDir) {
        this.community = community;
        this.users = users;
        this.messages = messages;
        this.uploadDir = uploadDir;
    }

    /* ================================ FEED ================================ */

    /**
     * URL     : GET /community
     * Role    : any authenticated user
     * Model   : posts, members, following (ids I follow), unread, role, form, errors
     * Template: pages/community.html
     */
    @GetMapping("/community")
    public String feed(Authentication auth, Model model) {
        Long me = currentUserId(auth);
        model.addAttribute("posts", community.listPosts(me));
        addDirectory(model, me);
        model.addAttribute("role", role(auth));
        model.addAttribute("unread", community.unreadCount(me));
        if (!model.containsAttribute("form")) {
            model.addAttribute("form", Map.of());
        }
        if (!model.containsAttribute("errors")) {
            model.addAttribute("errors", Map.of());
        }
        return "pages/community";
    }

    /**
     * URL     : POST /community/post
     * Role    : any authenticated user
     * Validates the text (1..1000) and the optional photo (JPG/PNG, <= 2 MB),
     * saves the file outside static, then returns to the feed.
     */
    @PostMapping("/community/post")
    public String createPost(Authentication auth, Model model,
                             @RequestParam(name = "content", required = false) String content,
                             @RequestParam(name = "image", required = false) MultipartFile image) {
        Long me = currentUserId(auth);
        Map<String, String> errors = new LinkedHashMap<>();

        String text = content == null ? "" : content.trim();
        if (text.isEmpty()) {
            errors.put("content", msg("community.error.content"));
        } else if (text.length() > MAX_CONTENT) {
            errors.put("content", msg("error.too.long"));
        }

        String storedPath = null;
        if (image != null && !image.isEmpty()) {
            String check = validateImage(image);
            if (check != null) {
                errors.put("image", msg(check));
            }
        }

        if (!errors.isEmpty()) {
            log.info("[COMMUNITY] post rejected for user {}: {}", me, errors.keySet());
            model.addAttribute("posts", community.listPosts(me));
            addDirectory(model, me);
            model.addAttribute("role", role(auth));
            model.addAttribute("unread", community.unreadCount(me));
            Map<String, Object> echo = new HashMap<>();
            echo.put("content", text);
            model.addAttribute("form", echo);
            model.addAttribute("errors", errors);
            return "pages/community";
        }

        if (image != null && !image.isEmpty()) {
            storedPath = storeImage(me, image);
        }
        Long id = community.insertPost(me, text, storedPath);
        log.info("[COMMUNITY] post {} created by user {}", id, me);
        return "redirect:/community";
    }

    /* ============================== ONE POST ============================== */

    /**
     * URL     : GET /community/post/{id}
     * Role    : any authenticated user
     * Model   : post, comments, author (Member), following, role, form, errors
     * Template: pages/community-post.html
     */
    @GetMapping("/community/post/{id}")
    public String post(@PathVariable Long id, Authentication auth, Model model) {
        Long me = currentUserId(auth);
        Post post = community.findPost(id, me)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        model.addAttribute("post", post);
        model.addAttribute("comments", community.listComments(id));
        model.addAttribute("author", community.findMember(post.getUserId()).orElse(null));
        model.addAttribute("following", community.isFollowing(me, post.getUserId()));
        model.addAttribute("role", role(auth));
        model.addAttribute("unread", community.unreadCount(me));
        if (!model.containsAttribute("form")) {
            model.addAttribute("form", Map.of());
        }
        if (!model.containsAttribute("errors")) {
            model.addAttribute("errors", Map.of());
        }
        return "pages/community-post";
    }

    @PostMapping("/community/post/{id}/comment")
    public String comment(@PathVariable Long id, Authentication auth,
                          @RequestParam(name = "content", required = false) String content) {
        Long me = currentUserId(auth);
        // The post must exist, else there is nothing to comment on.
        community.findPost(id, me)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        String text = content == null ? "" : content.trim();
        if (!text.isEmpty() && text.length() <= MAX_COMMENT) {
            community.insertComment(id, me, text);
            log.info("[COMMUNITY] user {} commented on post {}", me, id);
        }
        return "redirect:/community/post/" + id;
    }

    @PostMapping("/community/post/{id}/vote")
    public String vote(@PathVariable Long id, Authentication auth,
                       @RequestParam(name = "vote", required = false) String vote) {
        Long me = currentUserId(auth);
        community.findPost(id, me)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if ("UP".equals(vote) || "DOWN".equals(vote)) {
            community.vote(id, me, vote);
        }
        return "redirect:/community/post/" + id;
    }

    /** OWNER only. The post, its comments and its votes are removed together. */
    @PostMapping("/community/post/{id}/delete")
    public String deletePost(@PathVariable Long id, Authentication auth) {
        if (!"OWNER".equals(role(auth))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        community.deletePost(id);
        log.info("[COMMUNITY] owner {} deleted post {}", auth.getName(), id);
        return "redirect:/community";
    }

    /* ============================== FOLLOW ============================== */

    @PostMapping("/community/follow/{id}")
    public String follow(@PathVariable Long id, Authentication auth,
                         @RequestParam(name = "back", required = false) String back) {
        Long me = currentUserId(auth);
        if (!me.equals(id) && community.findMember(id).isPresent()) {
            community.follow(me, id);
        }
        return "redirect:" + safeBack(back, "/community");
    }

    @PostMapping("/community/unfollow/{id}")
    public String unfollow(@PathVariable Long id, Authentication auth,
                           @RequestParam(name = "back", required = false) String back) {
        community.unfollow(currentUserId(auth), id);
        return "redirect:" + safeBack(back, "/community");
    }

    /* ============================ PROFILE ============================ */

    /**
     * URL     : GET /community/profile/{id}
     * Role    : any authenticated user
     * Model   : member, posts, following, followerCount, followingCount, role
     * Template: pages/community-profile.html
     */
    @GetMapping("/community/profile/{id}")
    public String profile(@PathVariable Long id, Authentication auth, Model model) {
        Long me = currentUserId(auth);
        Member member = community.findMember(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        model.addAttribute("member", member);
        model.addAttribute("posts", community.postsBy(id, me));
        model.addAttribute("following", community.isFollowing(me, id));
        model.addAttribute("self", me.equals(id));
        model.addAttribute("followerCount", community.followerCount(id));
        model.addAttribute("followingCount", community.followingCount(id));
        model.addAttribute("postCount", community.postCount(id));
        model.addAttribute("role", role(auth));
        model.addAttribute("unread", community.unreadCount(me));
        return "pages/community-profile";
    }

    /* ============================ MESSAGES ============================ */

    @GetMapping("/community/messages")
    public String inbox(Authentication auth, Model model) {
        Long me = currentUserId(auth);
        model.addAttribute("conversations", community.inbox(me));
        model.addAttribute("members", community.listMembers());
        model.addAttribute("role", role(auth));
        model.addAttribute("unread", community.unreadCount(me));
        return "pages/community-inbox";
    }

    @GetMapping("/community/messages/{id}")
    public String thread(@PathVariable Long id, Authentication auth, Model model) {
        Long me = currentUserId(auth);
        if (me.equals(id)) {
            return "redirect:/community/messages";
        }
        Member other = community.findMember(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        community.markRead(me, id);   // opening the thread clears its unread badge
        model.addAttribute("other", other);
        model.addAttribute("thread", community.thread(me, id));
        model.addAttribute("me", me);
        model.addAttribute("role", role(auth));
        model.addAttribute("unread", community.unreadCount(me));
        return "pages/community-thread";
    }

    @PostMapping("/community/messages/{id}")
    public String send(@PathVariable Long id, Authentication auth,
                       @RequestParam(name = "content", required = false) String content) {
        Long me = currentUserId(auth);
        String text = content == null ? "" : content.trim();
        if (!me.equals(id) && !text.isEmpty() && text.length() <= MAX_CONTENT
                && community.findMember(id).isPresent()) {
            community.insertMessage(me, id, text);
            log.info("[COMMUNITY] message from {} to {}", me, id);
        }
        return "redirect:/community/messages/" + id;
    }

    /* ============================== IMAGE ============================== */

    /**
     * URL  : GET /community/image/{id}
     * Role : any authenticated user (the feed is visible to logged-in users)
     * Reads the stored file and streams it back. The file lives outside static,
     * so this is the only way to reach it.
     */
    @GetMapping("/community/image/{id}")
    public ResponseEntity<byte[]> image(@PathVariable Long id) {
        String path = community.imagePath(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        File file = new File(path);
        if (!file.exists()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        try {
            byte[] bytes = Files.readAllBytes(file.toPath());
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(mediaTypeFor(path));
            headers.setContentDisposition(ContentDisposition.inline().build());
            return new ResponseEntity<>(bytes, headers, HttpStatus.OK);
        } catch (IOException e) {
            log.error("[COMMUNITY] could not read image {}: {}", path, e.getMessage());
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /* ---------- helpers ---------- */

    private void addDirectory(Model model, Long me) {
        List<Member> members = community.listMembers();
        Set<Long> following = new HashSet<>(community.followingIds(me));
        model.addAttribute("members", members);
        model.addAttribute("following", following);
        model.addAttribute("me", me);
    }

    /** Returns an error-key when the upload is not allowed, else null. */
    private String validateImage(MultipartFile file) {
        if (file.getSize() > MAX_IMAGE) {
            return "error.image.size";
        }
        String type = file.getContentType();
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        boolean okType = "image/jpeg".equals(type) || "image/png".equals(type);
        boolean okExt = name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png");
        return okType && okExt ? null : "error.image.type";
    }

    /** Writes the photo under <uploads>/community/ and returns its absolute path. */
    private String storeImage(Long userId, MultipartFile file) {
        // getAbsoluteFile() matters: MultipartFile.transferTo resolves a RELATIVE
        // path against the servlet container's temp dir, not the project dir.
        File dir = new File(uploadDir, "community").getAbsoluteFile();
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("Cannot create community upload folder: " + dir.getAbsolutePath());
        }
        String original = file.getOriginalFilename() == null ? "photo.jpg" : file.getOriginalFilename();
        String ext = original.toLowerCase().endsWith(".png") ? ".png" : ".jpg";
        String fileName = "post-" + userId + "-" + System.currentTimeMillis() + ext;
        File target = new File(dir, fileName).getAbsoluteFile();
        try {
            file.transferTo(target);
        } catch (IOException e) {
            throw new IllegalStateException("Could not store the uploaded photo", e);
        }
        return target.getAbsolutePath();
    }

    private MediaType mediaTypeFor(String path) {
        return path.toLowerCase().endsWith(".png") ? MediaType.IMAGE_PNG : MediaType.IMAGE_JPEG;
    }

    /** Only allow redirects back inside this site. */
    private String safeBack(String back, String fallback) {
        if (back != null && back.startsWith("/") && !back.startsWith("//")) {
            return back;
        }
        return fallback;
    }

    private Long currentUserId(Authentication auth) {
        return users.findByUsername(auth.getName())
                .map(User::getId)
                .orElseThrow(() -> new IllegalStateException("Logged-in user not found: " + auth.getName()));
    }

    private static String role(Authentication auth) {
        if (auth == null) {
            return "";
        }
        return auth.getAuthorities().stream()
                .map(a -> a.getAuthority().replace("ROLE_", ""))
                .findFirst().orElse("");
    }

    private String msg(String key) {
        return messages.getMessage(key, null, key, LocaleContextHolder.getLocale());
    }
}
