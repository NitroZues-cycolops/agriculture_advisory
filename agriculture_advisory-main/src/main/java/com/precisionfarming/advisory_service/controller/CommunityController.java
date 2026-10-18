package com.precisionfarming.advisory_service.controller;

import com.precisionfarming.advisory_service.model.Comment;
import com.precisionfarming.advisory_service.model.Conversation;
import com.precisionfarming.advisory_service.model.Member;
import com.precisionfarming.advisory_service.model.Message;
import com.precisionfarming.advisory_service.model.Post;
import com.precisionfarming.advisory_service.model.User;
import com.precisionfarming.advisory_service.repository.CommunityDao;
import com.precisionfarming.advisory_service.repository.DistrictDao;
import com.precisionfarming.advisory_service.repository.FarmerProfileDao;
import com.precisionfarming.advisory_service.repository.UserRepository;
import jakarta.servlet.http.HttpServletResponse;
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
import java.util.Objects;
import java.util.Set;
import java.util.HashSet;

/**
 * Community workspace (workspace 6) - STEP 6.
 *
 *   GET  /community                        feed + new-post form + farmer directory
 *   POST /community/post                   create a post (text + optional photo)
 *   GET  /community/post/{id}              one post with its comments
 *   POST /community/post/{id}/comment      add a comment
 *   POST /community/post/{id}/vote         upvote / downvote (one vote per user)
 *   GET  /community/post/{id}/edit         edit form (author or owner)
 *   POST /community/post/{id}/edit         update text and/or photo (author or owner)
 *   GET  /community/post/{id}/delete       confirm-delete page (author or owner)
 *   POST /community/post/{id}/delete       delete it (author or owner) + its photo file
 *   GET  /community/comment/{id}/delete    confirm-delete page (author or owner)
 *   POST /community/comment/{id}/delete    delete the comment (author or owner)
 *   GET  /community/message/{id}/delete    confirm-delete page (the sender)
 *   POST /community/message/{id}/delete    delete the message (the sender)
 *   POST /community/follow/{id}            follow a farmer
 *   POST /community/unfollow/{id}          unfollow a farmer
 *   GET  /community/profile/{id}           a farmer's community profile
 *   GET  /farmers                          find farmers (district filter + name search)
 *   GET  /messages                         inbox
 *   GET  /messages/{id}                    thread with one farmer (follow/follower only)
 *   POST /messages/{id}                    send a message (follow/follower only)
 *   GET  /community/image/{id}             serve an uploaded photo (ownership checked)
 *
 * SECURITY: every form uses th:action (CSRF). The "me" id and role come from the
 * security context, never from the URL. Photos are capped at 2 MB, JPG/PNG only,
 * stored OUTSIDE src/main/resources/static and served back through this
 * controller - so the file can never be fetched straight off the web.
 *
 * PART 2b - MANAGING MY OWN CONTENT: a farmer may EDIT and DELETE only their OWN
 * post (plus their own comments and chat messages); anyone else's id gives 403.
 * An OWNER may still moderate ANY post or comment (but not a chat message).
 * Deletions of a post also remove its photo file from disk; comments and votes
 * cascade in the database.
 *
 * PART 3 - CONNECTIONS AND CHAT: the canonical chat lives at /messages. A farmer
 * may chat with anyone they FOLLOW or who FOLLOWS them; anyone else gets a
 * friendly "follow first" page when opening a thread (HTTP status 403, with a
 * Follow button) and a 403 when sending. A user can never message or follow
 * themselves. Sends are capped at 20 per minute per user.
 *
 * NO JAVASCRIPT: the vote buttons are small POST forms, the follow button is a
 * POST form, the inbox is a list of links, the chat has a visible Refresh link
 * (there is no live update), and every destructive action goes through a
 * separate confirm PAGE (GET) whose "Yes, delete" button is a POST.
 */
@Controller
public class CommunityController {

    private static final Logger log = LoggerFactory.getLogger(CommunityController.class);

    private static final int MAX_CONTENT = 1000;
    private static final int MAX_COMMENT = 500;
    private static final long MAX_IMAGE = 2L * 1024 * 1024;
    /** PART 3: at most this many messages per minute per user. */
    private static final int RATE_LIMIT = 20;

    private final CommunityDao community;
    private final UserRepository users;
    private final FarmerProfileDao profiles;
    private final DistrictDao districts;
    private final MessageSource messages;
    private final String uploadDir;

    public CommunityController(CommunityDao community, UserRepository users,
                               FarmerProfileDao profiles, DistrictDao districts,
                               MessageSource messages,
                               @Value("${pfas.uploads.dir:uploads}") String uploadDir) {
        this.community = community;
        this.users = users;
        this.profiles = profiles;
        this.districts = districts;
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
        model.addAttribute("me", me);
        model.addAttribute("role", role(auth));
        model.addAttribute("withPhoto", profiles.idsWithPhoto());
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

    /* ========================= PART 2b: EDIT / DELETE MY OWN ========================= */

    /**
     * URL     : GET /community/post/{id}/edit
     * Role    : the author (or an OWNER); anyone else -> 403
     * Model   : post, form, errors, unread, withPhoto
     * Template: pages/community-post-edit.html
     * The form is pre-filled with the current text and shows the current photo.
     */
    @GetMapping("/community/post/{id}/edit")
    public String editPostForm(@PathVariable Long id, Authentication auth, Model model) {
        Long me = currentUserId(auth);
        Post post = community.findPost(id, me)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        requireAuthorOrOwner(post.getUserId(), auth);
        model.addAttribute("post", post);
        if (!model.containsAttribute("form")) {
            model.addAttribute("form", Map.of("content", post.getContent()));
        }
        if (!model.containsAttribute("errors")) {
            model.addAttribute("errors", Map.of());
        }
        model.addAttribute("withPhoto", profiles.idsWithPhoto());
        return "pages/community-post-edit";
    }

    /**
     * URL     : POST /community/post/{id}/edit
     * Role    : the author (or an OWNER); anyone else -> 403
     * Same text validation as posting (non-empty, <= 1000). The author may also
     * REPLACE the photo (a new file) or REMOVE it (the "removeImage" checkbox):
     * either way the old file is deleted from disk afterwards.
     */
    @PostMapping("/community/post/{id}/edit")
    public String editPost(@PathVariable Long id, Authentication auth, Model model,
                           @RequestParam(name = "content", required = false) String content,
                           @RequestParam(name = "image", required = false) MultipartFile image,
                           @RequestParam(name = "removeImage", required = false) String removeImage) {
        Long me = currentUserId(auth);
        Post post = community.findPost(id, me)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        requireAuthorOrOwner(post.getUserId(), auth);

        Map<String, String> errors = new LinkedHashMap<>();
        String text = content == null ? "" : content.trim();
        if (text.isEmpty()) {
            errors.put("content", msg("community.error.content"));
        } else if (text.length() > MAX_CONTENT) {
            errors.put("content", msg("error.too.long"));
        }

        boolean hasNewImage = image != null && !image.isEmpty();
        if (hasNewImage) {
            String check = validateImage(image);
            if (check != null) {
                errors.put("image", msg(check));
            }
        }

        if (!errors.isEmpty()) {
            log.info("[COMMUNITY] post {} edit rejected for user {}: {}", id, me, errors.keySet());
            model.addAttribute("post", post);
            Map<String, Object> echo = new HashMap<>();
            echo.put("content", text);   // keep what the farmer typed
            model.addAttribute("form", echo);
            model.addAttribute("errors", errors);
            model.addAttribute("withPhoto", profiles.idsWithPhoto());
            return "pages/community-post-edit";
        }

        String oldPath = post.getImagePath();
        String newPath = oldPath;
        if (hasNewImage) {                       // replace
            newPath = storeImage(me, image);
        } else if (removeImage != null) {        // remove
            newPath = null;
        }

        community.updatePost(id, text, newPath);
        if (!Objects.equals(oldPath, newPath)) {
            deleteImageQuietly(oldPath);         // delete the replaced / removed file
        }
        log.info("[COMMUNITY] post {} edited by user {}", id, me);
        return "redirect:/community/post/" + id;
    }

    /**
     * URL     : GET /community/post/{id}/delete
     * Role    : the author (or an OWNER); anyone else -> 403
     * Shows WHAT will be deleted and the "Yes, delete" / "Cancel" buttons.
     * A GET never deletes: only the POST below does.
     */
    @GetMapping("/community/post/{id}/delete")
    public String deletePostForm(@PathVariable Long id, Authentication auth, Model model) {
        Long me = currentUserId(auth);
        Post post = community.findPost(id, me)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        requireAuthorOrOwner(post.getUserId(), auth);
        model.addAttribute("post", post);
        model.addAttribute("withPhoto", profiles.idsWithPhoto());
        return "pages/community-post-delete";
    }

    /**
     * URL  : POST /community/post/{id}/delete
     * Role : the author OR an OWNER.
     * The post, its comments and its votes are removed together (cascade), and
     * the photo FILE is deleted from disk. CSRF is required (th:action form).
     */
    @PostMapping("/community/post/{id}/delete")
    public String deletePost(@PathVariable Long id, Authentication auth) {
        Long me = currentUserId(auth);
        Post post = community.findPost(id, me)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        requireAuthorOrOwner(post.getUserId(), auth);
        String path = post.getImagePath();
        community.deletePost(id);
        deleteImageQuietly(path);
        log.info("[COMMUNITY] user {} deleted post {}", me, id);
        return "redirect:/community";
    }

    /**
     * URL     : GET /community/comment/{id}/delete
     * Role    : the comment author (or an OWNER); anyone else -> 403
     * Template: pages/community-comment-delete.html
     */
    @GetMapping("/community/comment/{id}/delete")
    public String deleteCommentForm(@PathVariable Long id, Authentication auth, Model model) {
        Long me = currentUserId(auth);
        Comment c = community.findComment(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        requireAuthorOrOwner(c.getUserId(), auth);
        model.addAttribute("comment", c);
        model.addAttribute("withPhoto", profiles.idsWithPhoto());
        return "pages/community-comment-delete";
    }

    /**
     * URL  : POST /community/comment/{id}/delete
     * Role : the comment author (or an OWNER). Returns to that post.
     */
    @PostMapping("/community/comment/{id}/delete")
    public String deleteComment(@PathVariable Long id, Authentication auth) {
        Long me = currentUserId(auth);
        Comment c = community.findComment(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        requireAuthorOrOwner(c.getUserId(), auth);
        community.deleteComment(id);
        log.info("[COMMUNITY] user {} deleted comment {}", me, id);
        return "redirect:/community/post/" + c.getPostId();
    }

    /**
     * URL     : GET /community/message/{id}/delete
     * Role    : the message SENDER only; anyone else -> 403
     * Template: pages/community-message-delete.html
     */
    @GetMapping("/community/message/{id}/delete")
    public String deleteMessageForm(@PathVariable Long id, Authentication auth, Model model) {
        Long me = currentUserId(auth);
        Message m = community.findMessage(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        requireSender(m.getSenderId(), auth);
        model.addAttribute("message", m);
        model.addAttribute("otherId", me.equals(m.getSenderId()) ? m.getReceiverId() : m.getSenderId());
        return "pages/community-message-delete";
    }

    /**
     * URL  : POST /community/message/{id}/delete
     * Role : the message SENDER only.
     * SOFT delete: the text is cleared, so both sides see "This message was
     * deleted" in the thread and the inbox.
     */
    @PostMapping("/community/message/{id}/delete")
    public String deleteMessage(@PathVariable Long id, Authentication auth) {
        Long me = currentUserId(auth);
        Message m = community.findMessage(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        requireSender(m.getSenderId(), auth);
        community.deleteMessage(id);
        Long other = me.equals(m.getSenderId()) ? m.getReceiverId() : m.getSenderId();
        log.info("[COMMUNITY] user {} deleted message {}", me, id);
        return "redirect:/messages/" + other;
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
        model.addAttribute("withPhoto", profiles.idsWithPhoto());
        return "pages/community-profile";
    }

    /* ============================ FIND FARMERS (PART 3) ============================ */

    /**
     * URL     : GET /farmers
     * Role    : any authenticated user
     * Model   : farmers, following (ids I follow), me, districts, district, q, withPhoto
     * Template: pages/farmers.html
     * A directory of every farmer EXCEPT the viewer, with an optional district
     * filter and a case-insensitive name search. Cards show only public details.
     */
    @GetMapping("/farmers")
    public String farmers(Authentication auth, Model model,
                          @RequestParam(name = "district", required = false) String district,
                          @RequestParam(name = "q", required = false) String q) {
        Long me = currentUserId(auth);
        String name = q == null ? "" : q.trim();
        model.addAttribute("farmers", community.searchFarmers(me, district, name));
        model.addAttribute("following", new HashSet<>(community.followingIds(me)));
        model.addAttribute("me", me);
        model.addAttribute("districts", districts.findAll());
        model.addAttribute("district", district);
        model.addAttribute("q", name);
        model.addAttribute("withPhoto", profiles.idsWithPhoto());
        return "pages/farmers";
    }

    /* ============================ MESSAGES (PART 3) ============================ */

    /**
     * URL     : GET /messages
     * Role    : any authenticated user
     * Model   : conversations, withPhoto (the unread badge comes from UnreadBadgeAdvice)
     * Template: pages/messages-inbox.html
     * One row per farmer this user has exchanged messages with, newest first.
     * A user can never appear in their own inbox (a self-chat row cannot exist).
     */
    @GetMapping("/messages")
    public String inbox(Authentication auth, Model model) {
        Long me = currentUserId(auth);
        model.addAttribute("conversations", community.inbox(me));
        model.addAttribute("withPhoto", profiles.idsWithPhoto());
        return "pages/messages-inbox";
    }

    /**
     * URL     : GET /messages/{id}
     * Role    : a farmer the viewer FOLLOWS or who FOLLOWS the viewer; anyone else
     *           gets the friendly "follow first" page (status 403, with a Follow
     *           button); the viewer themself is redirected to the inbox.
     * Model   : other, me, following, withPhoto (thread, errors, form when allowed)
     * Template: pages/messages-thread.html (allowed) / pages/messages-blocked.html
     * Opening an allowed thread marks its incoming messages as read.
     */
    @GetMapping("/messages/{id}")
    public String thread(@PathVariable Long id, Authentication auth, Model model,
                         HttpServletResponse response) {
        Long me = currentUserId(auth);
        if (me.equals(id)) {
            return "redirect:/messages";     // never open a thread with yourself
        }
        Member other = community.findMember(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        boolean following = community.isFollowing(me, id);
        model.addAttribute("other", other);
        model.addAttribute("me", me);
        model.addAttribute("following", following);
        model.addAttribute("withPhoto", profiles.idsWithPhoto());

        if (!community.canChat(me, id)) {
            // A friendly page (explanation + Follow button), not the error view -
            // but the HTTP status stays 403 so the rule is visible to clients.
            response.setStatus(HttpStatus.FORBIDDEN.value());
            return "pages/messages-blocked";
        }

        community.markRead(me, id);   // opening the thread clears its unread badge
        model.addAttribute("thread", community.thread(me, id));
        model.addAttribute("errors", Map.of());
        model.addAttribute("form", Map.of());
        return "pages/messages-thread";
    }

    /**
     * URL     : POST /messages/{id}
     * Role    : a farmer the viewer FOLLOWS or who FOLLOWS the viewer; anyone else
     *           -> 403. Sending to yourself -> 403 and no row.
     * Validates the text (1..1000), enforces the 20-per-minute rate limit, and on
     * any error re-renders the thread with the message kept and the error beside
     * the field. No JavaScript: the page is refreshed with a link.
     */
    @PostMapping("/messages/{id}")
    public String send(@PathVariable Long id, Authentication auth, Model model,
                       @RequestParam(name = "content", required = false) String content) {
        Long me = currentUserId(auth);
        if (me.equals(id)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);   // no self-chat
        }
        Member other = community.findMember(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!community.canChat(me, id)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }

        String text = content == null ? "" : content.trim();
        Map<String, String> errors = new LinkedHashMap<>();
        if (text.isEmpty()) {
            errors.put("content", msg("messages.send.empty"));
        } else if (text.length() > MAX_CONTENT) {
            errors.put("content", msg("messages.send.long"));
        } else if (community.sentInLastMinute(me) >= RATE_LIMIT) {
            errors.put("content", msg("messages.send.rate"));
        }

        if (!errors.isEmpty()) {
            log.info("[COMMUNITY] message from {} to {} rejected: {}", me, id, errors.keySet());
            model.addAttribute("other", other);
            model.addAttribute("me", me);
            model.addAttribute("following", community.isFollowing(me, id));
            model.addAttribute("thread", community.thread(me, id));
            model.addAttribute("withPhoto", profiles.idsWithPhoto());
            model.addAttribute("errors", errors);
            model.addAttribute("form", Map.of("content", text));   // keep what was typed
            return "pages/messages-thread";
        }

        community.insertMessage(me, id, text);
        log.info("[COMMUNITY] message from {} to {}", me, id);
        return "redirect:/messages/" + id;
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
        model.addAttribute("withPhoto", profiles.idsWithPhoto());
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

    /**
     * PART 2b ownership gate: allows the row's own author, and also an OWNER
     * (who may moderate any post or comment). Anyone else -> 403.
     */
    private void requireAuthorOrOwner(Long authorId, Authentication auth) {
        if ("OWNER".equals(role(auth))) {
            return;
        }
        if (authorId != null && authorId.equals(currentUserId(auth))) {
            return;
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }

    /**
     * PART 2b ownership gate for a private message: only its SENDER may delete
     * it (not even the owner can read or remove someone's chat). Else 403.
     */
    private void requireSender(Long senderId, Authentication auth) {
        if (senderId != null && senderId.equals(currentUserId(auth))) {
            return;
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }

    /**
     * Removes a community photo from disk, best-effort: a missing file or a
     * permission error must never break the delete (the DB row is already gone).
     */
    private void deleteImageQuietly(String path) {
        if (path == null || path.isBlank()) {
            return;
        }
        try {
            File file = new File(path);
            if (file.exists() && !file.delete()) {
                log.warn("[COMMUNITY] could not delete photo file {}", path);
            }
        } catch (RuntimeException e) {
            log.warn("[COMMUNITY] could not delete photo file {}: {}", path, e.getMessage());
        }
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
