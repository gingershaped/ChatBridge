package io.github.gingerindustries.chatbridge.schemas.recieved;

import org.jetbrains.annotations.Nullable;

public record RecievedUser(String name, Boolean bold, @Nullable String color) {}