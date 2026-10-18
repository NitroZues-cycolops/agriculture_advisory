package com.precisionfarming.advisory_service.repository;

import com.precisionfarming.advisory_service.model.Comment;
import com.precisionfarming.advisory_service.model.Conversation;
import com.precisionfarming.advisory_service.model.Member;
import com.precisionfarming.advisory_service.model.Message;
import com.precisionfarming.advisory_service.model.Post;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * All SQL for the STEP 6 community tables:
 *   community_posts / community_comments / community_votes / follows /
 *   direct_messages.
 *
 * The feed query computes the vote totals, the viewer's own vote and the
 * comment count in one go, so the page never runs a query per post.
 *
 * Ownership rule kept here: every read of "my" data takes the id from the
 * security context (the controller), never from the URL.
 */
@Repository
public class CommunityDao {

    private final JdbcTemplate jdbc;

    public CommunityDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /* ---------- row mappers ---------- */

    private static final RowMapper<Post> POST_MAPPER = (rs, i) -> {
        Post p = new Post();
        p.setId(rs.getLong("id"));
        p.setUserId(rs.getLong("user_id"));
        p.setAuthorName(rs.getString("author_name"));
        p.setAuthorDistrict(rs.getString("author_district"));
        p.setContent(rs.getString("content"));
        p.setImagePath(rs.getString("image_path"));
        Timestamp ts = rs.getTimestamp("created_at");
        p.setCreatedAt(ts == null ? null : ts.toLocalDateTime());
        p.setUpVotes(rs.getInt("up_votes"));
        p.setDownVotes(rs.getInt("down_votes"));
        p.setCommentCount(rs.getInt("comment_count"));
        p.setMyVote(rs.getString("my_vote"));
        return p;
    };

    /** The shared SELECT the feed and the single-post page both use. */
    private static final String POST_SELECT = """
            SELECT p.id, p.user_id, p.content, p.image_path, p.created_at,
                   COALESCE(fp.full_name, u.username) AS author_name,
                   fp.district AS author_district,
                   (SELECT COUNT(*) FROM community_votes v
                     WHERE v.post_id = p.id AND v.vote_type = 'UP')   AS up_votes,
                   (SELECT COUNT(*) FROM community_votes v
                     WHERE v.post_id = p.id AND v.vote_type = 'DOWN') AS down_votes,
                   (SELECT COUNT(*) FROM community_comments c
                     WHERE c.post_id = p.id)                         AS comment_count,
                   (SELECT v.vote_type FROM community_votes v
                     WHERE v.post_id = p.id AND v.user_id = ?)       AS my_vote
            FROM community_posts p
            JOIN users u ON u.id = p.user_id
            LEFT JOIN farmer_profiles fp ON fp.user_id = p.user_id
            """;

    private static final RowMapper<Comment> COMMENT_MAPPER = (rs, i) -> {
        Comment c = new Comment();
        c.setId(rs.getLong("id"));
        c.setPostId(rs.getLong("post_id"));
        c.setUserId(rs.getLong("user_id"));
        c.setAuthorName(rs.getString("author_name"));
        c.setContent(rs.getString("content"));
        Timestamp ts = rs.getTimestamp("created_at");
        c.setCreatedAt(ts == null ? null : ts.toLocalDateTime());
        return c;
    };

    private static final RowMapper<Message> MESSAGE_MAPPER = (rs, i) -> {
        Message m = new Message();
        m.setId(rs.getLong("id"));
        m.setSenderId(rs.getLong("sender_id"));
        m.setReceiverId(rs.getLong("receiver_id"));
        m.setSenderName(rs.getString("sender_name"));
        m.setReceiverName(rs.getString("receiver_name"));
        m.setContent(rs.getString("content"));
        Timestamp ts = rs.getTimestamp("created_at");
        m.setCreatedAt(ts == null ? null : ts.toLocalDateTime());
        m.setRead(rs.getBoolean("is_read"));
        return m;
    };

    private static final RowMapper<Member> MEMBER_MAPPER = (rs, i) -> new Member(
            rs.getLong("user_id"),
            rs.getString("username"),
            rs.getString("full_name"),
            rs.getString("district"),
            rs.getString("taluka"),
            rs.getString("village"),
            rs.getString("crops"),
            rs.getDouble("farm_area"),
            rs.getString("unit"),
            rs.getObject("profile_id") != null);

    /**
     * The shared "farmer card" SELECT used by the directory, the profile
     * following/followers/connections lists and the /farmers search. The
     * WHERE / JOIN / ORDER BY parts are appended per query, so every card is
     * built from exactly the same columns (never phone or email).
     */
    private static final String MEMBER_SELECT = """
            SELECT u.id AS user_id, u.username, fp.id AS profile_id,
                   fp.full_name, fp.district, fp.taluka, fp.village, fp.crops,
                   fp.farm_area, fp.unit
            FROM users u
            LEFT JOIN farmer_profiles fp ON fp.user_id = u.id
            """;

    /* ---------- feed ---------- */

    /** The newest posts first, with the viewer's own vote attached. */
    public List<Post> listPosts(Long viewerId) {
        return jdbc.query(POST_SELECT + " ORDER BY p.created_at DESC, p.id DESC LIMIT 100",
                POST_MAPPER, viewerId);
    }

    public Optional<Post> findPost(Long postId, Long viewerId) {
        return jdbc.query(POST_SELECT + " WHERE p.id = ?",
                POST_MAPPER, viewerId, postId).stream().findFirst();
    }

    /** Posts by one author, for their community profile page. */
    public List<Post> postsBy(Long authorId, Long viewerId) {
        return jdbc.query(POST_SELECT + " WHERE p.user_id = ? ORDER BY p.created_at DESC, p.id DESC",
                POST_MAPPER, viewerId, authorId);
    }

    public Long insertPost(Long userId, String content, String imagePath) {
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO community_posts (user_id, content, image_path) VALUES (?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, userId);
            ps.setString(2, content);
            ps.setString(3, imagePath);
            return ps;
        }, key);
        Number id = key.getKey();
        return id == null ? null : id.longValue();
    }

    public Optional<String> imagePath(Long postId) {
        return jdbc.query("SELECT image_path FROM community_posts WHERE id = ?",
                        (rs, i) -> rs.getString("image_path"), postId)
                .stream().findFirst().filter(s -> s != null && !s.isBlank());
    }

    /**
     * Author or owner delete; comments and votes go with it (ON DELETE CASCADE).
     * The photo FILE on disk is removed by the controller before this call.
     */
    public int deletePost(Long postId) {
        return jdbc.update("DELETE FROM community_posts WHERE id = ?", postId);
    }

    /**
     * PART 2b: the author edits an existing post - the text and the photo path
     * (null means "no photo"). Ownership is enforced in the controller.
     */
    public int updatePost(Long postId, String content, String imagePath) {
        return jdbc.update("UPDATE community_posts SET content = ?, image_path = ? WHERE id = ?",
                content, imagePath, postId);
    }

    /** Total number of posts, for the owner dashboard. */
    public int countPosts() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM community_posts", Integer.class);
        return n == null ? 0 : n;
    }

    /* ---------- comments ---------- */

    public List<Comment> listComments(Long postId) {
        return jdbc.query("""
                SELECT c.id, c.post_id, c.user_id, c.content, c.created_at,
                       COALESCE(fp.full_name, u.username) AS author_name
                FROM community_comments c
                JOIN users u ON u.id = c.user_id
                LEFT JOIN farmer_profiles fp ON fp.user_id = c.user_id
                WHERE c.post_id = ?
                ORDER BY c.created_at ASC, c.id ASC
                """, COMMENT_MAPPER, postId);
    }

    public void insertComment(Long postId, Long userId, String content) {
        jdbc.update("INSERT INTO community_comments (post_id, user_id, content) VALUES (?, ?, ?)",
                postId, userId, content);
    }

    /** PART 2b: one comment, so the controller can check the author and post. */
    public Optional<Comment> findComment(Long commentId) {
        return jdbc.query("""
                SELECT c.id, c.post_id, c.user_id, c.content, c.created_at,
                       COALESCE(fp.full_name, u.username) AS author_name
                FROM community_comments c
                JOIN users u ON u.id = c.user_id
                LEFT JOIN farmer_profiles fp ON fp.user_id = c.user_id
                WHERE c.id = ?
                """, COMMENT_MAPPER, commentId).stream().findFirst();
    }

    /** PART 2b: delete one comment (author or owner; checked in the controller). */
    public int deleteComment(Long commentId) {
        return jdbc.update("DELETE FROM community_comments WHERE id = ?", commentId);
    }

    /* ---------- votes (one per user per post) ---------- */

    /**
     * Records the viewer's vote. The unique key (post_id, user_id) plus
     * ON DUPLICATE KEY UPDATE means changing an UP to a DOWN replaces the row
     * instead of adding a second one - exactly "one vote per user per post".
     */
    public void vote(Long postId, Long userId, String voteType) {
        jdbc.update("""
                INSERT INTO community_votes (post_id, user_id, vote_type)
                VALUES (?, ?, ?)
                ON DUPLICATE KEY UPDATE vote_type = VALUES(vote_type)
                """, postId, userId, voteType);
    }

    /* ---------- follow ---------- */

    public boolean isFollowing(Long followerId, Long followingId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM follows WHERE follower_id = ? AND following_id = ?",
                Integer.class, followerId, followingId);
        return n != null && n > 0;
    }

    public void follow(Long followerId, Long followingId) {
        jdbc.update("INSERT IGNORE INTO follows (follower_id, following_id) VALUES (?, ?)",
                followerId, followingId);
    }

    /** Ids the user already follows - lets the directory mark Follow/Unfollow. */
    public List<Long> followingIds(Long followerId) {
        return jdbc.queryForList(
                "SELECT following_id FROM follows WHERE follower_id = ?", Long.class, followerId);
    }

    public void unfollow(Long followerId, Long followingId) {
        jdbc.update("DELETE FROM follows WHERE follower_id = ? AND following_id = ?",
                followerId, followingId);
    }

    public int followerCount(Long userId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM follows WHERE following_id = ?", Integer.class, userId);
        return n == null ? 0 : n;
    }

    public int followingCount(Long userId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM follows WHERE follower_id = ?", Integer.class, userId);
        return n == null ? 0 : n;
    }

    public int postCount(Long userId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM community_posts WHERE user_id = ?", Integer.class, userId);
        return n == null ? 0 : n;
    }

    /* ---------- PART 3: connections ---------- */

    /**
     * The farmers this user follows, for the "Following" tile on /profile.
     * The user is excluded from their own list even if a stray row ever existed.
     */
    public List<Member> following(Long userId) {
        return jdbc.query(MEMBER_SELECT + """
                JOIN follows f ON f.following_id = u.id
                WHERE f.follower_id = ? AND u.id <> ?
                ORDER BY COALESCE(fp.full_name, u.username)
                """, MEMBER_MAPPER, userId, userId);
    }

    /** The farmers who follow this user, for the "Followers" tile on /profile. */
    public List<Member> followers(Long userId) {
        return jdbc.query(MEMBER_SELECT + """
                JOIN follows f ON f.follower_id = u.id
                WHERE f.following_id = ? AND u.id <> ?
                ORDER BY COALESCE(fp.full_name, u.username)
                """, MEMBER_MAPPER, userId, userId);
    }

    /**
     * The farmers who follow each other with this user ("Connections": I follow
     * them AND they follow me). This is the definition the task asks for.
     */
    public List<Member> connections(Long userId) {
        return jdbc.query(MEMBER_SELECT + """
                JOIN follows f1 ON f1.following_id = u.id AND f1.follower_id = ?
                JOIN follows f2 ON f2.follower_id = u.id AND f2.following_id = ?
                WHERE u.id <> ?
                ORDER BY COALESCE(fp.full_name, u.username)
                """, MEMBER_MAPPER, userId, userId, userId);
    }

    /**
     * PART 3 chat permission: true when either user follows the other. A farmer
     * may chat with anyone they FOLLOW or who FOLLOWS them. The controller
     * rejects "chatting with yourself" before this is ever called, so self is
     * never allowed here.
     */
    public boolean canChat(Long a, Long b) {
        Integer n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM follows
                WHERE (follower_id = ? AND following_id = ?)
                   OR (follower_id = ? AND following_id = ?)
                """, Integer.class, a, b, b, a);
        return n != null && n > 0;
    }

    /* ---------- members ---------- */

    /** Every farmer login with its profile (if any), for the directory. */
    public List<Member> listMembers() {
        return jdbc.query("""
                SELECT u.id AS user_id, u.username, fp.id AS profile_id,
                       fp.full_name, fp.district, fp.taluka, fp.village, fp.crops,
                       fp.farm_area, fp.unit
                FROM users u
                LEFT JOIN farmer_profiles fp ON fp.user_id = u.id
                WHERE u.role = 'FARMER'
                ORDER BY COALESCE(fp.full_name, u.username)
                """, MEMBER_MAPPER);
    }

    public Optional<Member> findMember(Long userId) {
        return jdbc.query("""
                SELECT u.id AS user_id, u.username, fp.id AS profile_id,
                       fp.full_name, fp.district, fp.taluka, fp.village, fp.crops,
                       fp.farm_area, fp.unit
                FROM users u
                LEFT JOIN farmer_profiles fp ON fp.user_id = u.id
                WHERE u.id = ?
                """, MEMBER_MAPPER, userId).stream().findFirst();
    }

    /**
     * PART 3: the "Find farmers" directory. Every farmer EXCEPT the viewer, with
     * an optional district filter and a case-insensitive name search. The same
     * MEMBER_SELECT columns are used, so a card never carries phone or email.
     */
    public List<Member> searchFarmers(Long me, String district, String name) {
        StringBuilder sql = new StringBuilder(MEMBER_SELECT)
                .append(" WHERE u.role = 'FARMER' AND u.id <> ?");
        List<Object> args = new ArrayList<>();
        args.add(me);
        if (district != null && !district.isBlank()) {
            sql.append(" AND fp.district = ?");
            args.add(district);
        }
        if (name != null && !name.isBlank()) {
            sql.append(" AND LOWER(COALESCE(fp.full_name, u.username)) LIKE ?");
            args.add("%" + name.toLowerCase() + "%");
        }
        sql.append(" ORDER BY COALESCE(fp.full_name, u.username)");
        return jdbc.query(sql.toString(), MEMBER_MAPPER, args.toArray());
    }

    /* ---------- direct messages ---------- */

    /** The display name of one user (profile full name, else the username). */
    public String displayName(Long userId) {
        return jdbc.query("""
                SELECT COALESCE(fp.full_name, u.username)
                FROM users u LEFT JOIN farmer_profiles fp ON fp.user_id = u.id
                WHERE u.id = ?
                """, (rs, i) -> rs.getString(1), userId).stream().findFirst().orElse("");
    }

    /** The inbox: one row per farmer this user has exchanged messages with. */
    public List<Conversation> inbox(Long userId) {
        List<Long> others = jdbc.queryForList("""
                SELECT other_id FROM (
                    SELECT receiver_id AS other_id, created_at
                      FROM direct_messages WHERE sender_id = ?
                    UNION ALL
                    SELECT sender_id AS other_id, created_at
                      FROM direct_messages WHERE receiver_id = ?
                ) t
                GROUP BY other_id
                ORDER BY MAX(created_at) DESC
                """, Long.class, userId, userId);

        List<Conversation> out = new ArrayList<>();
        for (Long other : others) {
            String name = displayName(other);
            String last = jdbc.query("""
                    SELECT content FROM direct_messages
                    WHERE (sender_id = ? AND receiver_id = ?) OR (sender_id = ? AND receiver_id = ?)
                    ORDER BY created_at DESC, id DESC LIMIT 1
                    """, (rs, i) -> rs.getString(1), userId, other, other, userId)
                    .stream().findFirst().orElse("");
            Timestamp at = jdbc.query("""
                    SELECT MAX(created_at) FROM direct_messages
                    WHERE (sender_id = ? AND receiver_id = ?) OR (sender_id = ? AND receiver_id = ?)
                    """, (rs, i) -> rs.getTimestamp(1), userId, other, other, userId)
                    .stream().findFirst().orElse(null);
            int unread = unreadFrom(userId, other);
            out.add(new Conversation(other, name, last,
                    at == null ? null : at.toLocalDateTime(), unread));
        }
        return out;
    }

    /** The full thread between two users, oldest first. */
    public List<Message> thread(Long userId, Long otherId) {
        return jdbc.query("""
                SELECT dm.id, dm.sender_id, dm.receiver_id, dm.content, dm.created_at, dm.is_read,
                       su.username AS sender_name, ru.username AS receiver_name
                FROM direct_messages dm
                JOIN users su ON su.id = dm.sender_id
                JOIN users ru ON ru.id = dm.receiver_id
                WHERE (dm.sender_id = ? AND dm.receiver_id = ?)
                   OR (dm.sender_id = ? AND dm.receiver_id = ?)
                ORDER BY dm.created_at ASC, dm.id ASC
                """, MESSAGE_MAPPER, userId, otherId, otherId, userId);
    }

    public void insertMessage(Long senderId, Long receiverId, String content) {
        jdbc.update("INSERT INTO direct_messages (sender_id, receiver_id, content) VALUES (?, ?, ?)",
                senderId, receiverId, content);
    }

    /** PART 2b: one message, so the sender can be checked before deleting. */
    public Optional<Message> findMessage(Long messageId) {
        return jdbc.query("""
                SELECT dm.id, dm.sender_id, dm.receiver_id, dm.content, dm.created_at, dm.is_read,
                       su.username AS sender_name, ru.username AS receiver_name
                FROM direct_messages dm
                JOIN users su ON su.id = dm.sender_id
                JOIN users ru ON ru.id = dm.receiver_id
                WHERE dm.id = ?
                """, MESSAGE_MAPPER, messageId).stream().findFirst();
    }

    /**
     * PART 2b: SOFT delete - the row stays but its text is cleared, so BOTH
     * sides of the conversation see "This message was deleted" (the thread and
     * the inbox show that placeholder whenever the content is blank).
     */
    public int deleteMessage(Long messageId) {
        return jdbc.update("UPDATE direct_messages SET content = '' WHERE id = ?", messageId);
    }

    /** Marks every message the other user sent me as read. */
    public int markRead(Long receiverId, Long senderId) {
        return jdbc.update(
                "UPDATE direct_messages SET is_read = 1 WHERE receiver_id = ? AND sender_id = ? AND is_read = 0",
                receiverId, senderId);
    }

    public int unreadCount(Long userId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM direct_messages WHERE receiver_id = ? AND is_read = 0",
                Integer.class, userId);
        return n == null ? 0 : n;
    }

    /**
     * PART 3 rate limit: how many messages this user has sent in the last
     * minute. The controller blocks the send once this reaches 20, so a farmer
     * can post at most 20 messages per minute (no extra table needed).
     */
    public int sentInLastMinute(Long senderId) {
        Integer n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM direct_messages
                WHERE sender_id = ? AND created_at >= (NOW() - INTERVAL 1 MINUTE)
                """, Integer.class, senderId);
        return n == null ? 0 : n;
    }

    private int unreadFrom(Long receiverId, Long senderId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM direct_messages WHERE receiver_id = ? AND sender_id = ? AND is_read = 0",
                Integer.class, receiverId, senderId);
        return n == null ? 0 : n;
    }
}
