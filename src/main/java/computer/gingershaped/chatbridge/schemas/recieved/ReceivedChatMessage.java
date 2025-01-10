package computer.gingershaped.chatbridge.schemas.recieved;

import java.util.List;

public record ReceivedChatMessage(String user, List<String> content) {}
