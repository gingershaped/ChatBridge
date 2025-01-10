package computer.gingershaped.chatbridge.schemas.sent;

import java.io.Serializable;

public record SentChatMessage(SentUser user, String content) implements Serializable {}