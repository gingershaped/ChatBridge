package computer.gingershaped.chatbridge.schemas.recieved;

import org.jetbrains.annotations.Nullable;

public record RecievedUser(String name, Boolean bold, @Nullable String color) {}