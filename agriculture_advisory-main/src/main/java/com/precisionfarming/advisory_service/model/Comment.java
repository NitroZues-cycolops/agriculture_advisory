package com.precisionfarming.advisory_service.model;

import java.time.LocalDateTime;

/**
 * One row of the `community_comments` table, joined with the author's name so
 * the thread can be drawn without a second lookup.
 */
public class Comment {

    private Long id;
    private Long postId;
    private Long userId;
    private String authorName;
    private String content;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getPostId() { return postId; }
    public void setPostId(Long postId) { this.postId = postId; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getAuthorName() { return authorName; }
    public void setAuthorName(String authorName) { this.authorName = authorName; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
