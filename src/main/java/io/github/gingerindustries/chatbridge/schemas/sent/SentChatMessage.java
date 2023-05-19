package io.github.gingerindustries.chatbridge.schemas.sent;

import java.io.Serializable;

public record SentChatMessage(SentUser user, String content) implements Serializable {}