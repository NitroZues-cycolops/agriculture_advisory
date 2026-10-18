package com.precisionfarming.advisory_service.model;

import java.time.LocalDateTime;

/**
 * One row of the inbox: the other farmer, the last message exchanged and how
 * many of their messages are still unread.
 */
public record Conversation(
        Long otherId,
        String otherName,
        String lastContent,
        LocalDateTime lastAt,
        int unread) { }
