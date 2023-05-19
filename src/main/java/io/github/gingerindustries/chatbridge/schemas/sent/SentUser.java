package io.github.gingerindustries.chatbridge.schemas.sent;

import java.io.Serializable;

public record SentUser(String name, String uuid, Boolean isOp) implements Serializable {}