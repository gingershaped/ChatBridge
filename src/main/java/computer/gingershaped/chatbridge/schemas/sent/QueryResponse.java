package computer.gingershaped.chatbridge.schemas.sent;

import java.util.List;

public record QueryResponse(List<SentUser> online, float tps, long timeOfDay, String status) {}
