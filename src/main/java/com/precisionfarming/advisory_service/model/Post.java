package com.precisionfarming.advisory_service.model;

import java.time.LocalDateTime;

/**
 * One row of the `community_posts` table, plus the numbers the feed shows.
 *
 * The vote totals, the viewer's own vote and the comment count are computed by
 * the feed query (see CommunityDao.listPosts), so the template only has to read
 * them - no counting inside the page.
 *
 * Plain carrier, not a JPA entity: the community tables are read with
 * JdbcTemplate.
 */
public class Post {

    private Long id;
    private Long userId;
    private String authorName;      // full name, or the username when there is no profile
    private String authorDistrict;
    private String content;
    private String imagePath;       // where the uploaded photo sits, or null
    private LocalDateTime createdAt;
    private int upVotes;
    private int downVotes;
    private int commentCount;
    private String myVote;          // "UP", "DOWN" or null for the logged-in viewer

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getAuthorName() { return authorName; }
    public void setAuthorName(String authorName) { this.authorName = authorName; }

    public String getAuthorDistrict() { return authorDistrict; }
    public void setAuthorDistrict(String authorDistrict) { this.authorDistrict = authorDistrict; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getImagePath() { return imagePath; }
    public void setImagePath(String imagePath) { this.imagePath = imagePath; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public int getUpVotes() { return upVotes; }
    public void setUpVotes(int upVotes) { this.upVotes = upVotes; }

    public int getDownVotes() { return downVotes; }
    public void setDownVotes(int downVotes) { this.downVotes = downVotes; }

    public int getCommentCount() { return commentCount; }
    public void setCommentCount(int commentCount) { this.commentCount = commentCount; }

    public String getMyVote() { return myVote; }
    public void setMyVote(String myVote) { this.myVote = myVote; }

    /** Net score, shown next to the arrows. */
    public int getScore() { return upVotes - downVotes; }

    public boolean isHasImage() { return imagePath != null && !imagePath.isBlank(); }
}
