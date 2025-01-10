package io.github.gingerindustries.chatbridge;

import java.net.URI;
import java.util.Collections;
import java.util.stream.Collectors;

import org.jetbrains.annotations.Nullable;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.mojang.logging.LogUtils;

import io.github.gingerindustries.chatbridge.schemas.recieved.Announcement;
import io.github.gingerindustries.chatbridge.schemas.recieved.Command;
import io.github.gingerindustries.chatbridge.schemas.recieved.ReceivedChatMessage;
import io.github.gingerindustries.chatbridge.schemas.sent.AdvancementGet;
import io.github.gingerindustries.chatbridge.schemas.sent.PlayerDied;
import io.github.gingerindustries.chatbridge.schemas.sent.PlayerJoined;
import io.github.gingerindustries.chatbridge.schemas.sent.PlayerLeft;
import io.github.gingerindustries.chatbridge.schemas.sent.QueryResponse;
import io.github.gingerindustries.chatbridge.schemas.sent.SentChatMessage;
import io.github.gingerindustries.chatbridge.schemas.sent.SentUser;
import io.socket.client.Ack;
import io.socket.client.IO;
import io.socket.client.Manager;
import io.socket.client.Socket;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.AdvancementEvent.AdvancementEarnEvent;
import net.minecraftforge.eventbus.api.IEventBus;

public class ChatBridge {
	private static final Gson GSON = new Gson();
	private static final Logger LOGGER = LogUtils.getLogger();
	private final Socket socket;
	private final MinecraftServer server;
	private Boolean registeredHandlers = false;

	public ChatBridge(URI uri, String secret, MinecraftServer server, IEventBus modEventBus) {
        LOGGER.info("Connecting to server: {}", uri.toASCIIString());
		this.server = server;
		IO.Options options = IO.Options.builder()
				.setAuth(Collections.singletonMap("secret", secret))
				.build();
		this.socket = IO.socket(uri, options);

		this.socket.io().on(Manager.EVENT_RECONNECT, args -> LOGGER.info("Reconnected to server!"));
		this.socket.io().on(Manager.EVENT_CLOSE, args -> LOGGER.warn("Connection lost!"));
		this.socket.io().on(Manager.EVENT_OPEN, args -> {
			LOGGER.info("Connected!");
			if (!this.registeredHandlers) {
				this.registerSocketListeners();
				this.registerServerListeners(modEventBus);
				this.registeredHandlers = true;
			}
		});
		this.socket.connect();
	}

	public void shutdown() {
		LOGGER.info("Closing connection");
		this.socket.close();
	}

	private JSONObject jsonify(Object o) {
		try {
			return new JSONObject(GSON.toJson(o));
		} catch (JSONException e) {
			throw new IllegalStateException();
		}
	}

	private SentUser makeSentUser(Player player) {
		return new SentUser(player.getDisplayName().getString(), player.getStringUUID(),
				this.server.getPlayerList().isOp(player.getGameProfile()));
	}

	private void registerServerListeners(IEventBus modEventBus) {
		modEventBus.addListener(this::onServerChatEvent);
		modEventBus.addListener(this::onPlayerLoggedInEvent);
		modEventBus.addListener(this::onPlayerLoggedOutEvent);
		modEventBus.addListener(this::onPlayerGotAdvancementEvent);
		modEventBus.addListener(this::onPlayerDiedEvent);
	}

	public void onServerChatEvent(ServerChatEvent event) {
		this.socket.send(jsonify(new SentChatMessage(makeSentUser(event.getPlayer()), event.getRawText())));
	}

	public void onPlayerLoggedInEvent(PlayerEvent.PlayerLoggedInEvent event) {
		this.socket.emit("playerJoined", jsonify(new PlayerJoined(makeSentUser(event.getEntity()))));
	}

	public void onPlayerLoggedOutEvent(PlayerEvent.PlayerLoggedOutEvent event) {
		this.socket.emit("playerLeft", jsonify(new PlayerLeft(makeSentUser((event.getEntity())))));
	}

	public void onPlayerGotAdvancementEvent(AdvancementEarnEvent event) {
		DisplayInfo display = event.getAdvancement().getDisplay();
		if (display != null && display.shouldAnnounceChat()) {
			this.socket.emit("advancementGet", jsonify(new AdvancementGet(
				makeSentUser(event.getEntity()),
				display.getTitle().getString(), 
				display.getDescription().getString()
			)));
		}
	}

	public void onPlayerDiedEvent(LivingDeathEvent event) {
		LivingEntity entity = event.getEntity();
		if (entity instanceof Player) {
			this.socket.emit("playerDied", jsonify(new PlayerDied(
				makeSentUser((Player) entity),
				event.getSource().getLocalizedDeathMessage(entity).getString())
			));
		}
	}

	private void registerSocketListeners() {
		this.socket.on("message", (Object... args) -> {
			ReceivedChatMessage message = GSON.fromJson(args[0].toString(), ReceivedChatMessage.class);
			this.onMessage(message);
		});
		this.socket.on("announcement", (Object... args) -> {
			Announcement announcement = GSON.fromJson(args[0].toString(), Announcement.class);
			this.onAnnouncement(announcement);
		});
		this.socket.on("command", (Object... args) -> {
			Command command = GSON.fromJson(args[0].toString(), Command.class);
			if (args.length > 1 && args[1] instanceof Ack) {
				this.onCommand(command, ((Ack) args[1]));
			}
			else {				
				this.onCommand(command);
			}
		});
		this.socket.on("query", (Object... args) -> {
			assert args[0] instanceof Ack;
			this.onQuery(((Ack) args[0]));
		});
	}

	private void onMessage(ReceivedChatMessage message) {
		this.onMessage(message, null);
	}
	private void onMessage(ReceivedChatMessage message, @Nullable Ack ack) {
		MutableComponent component = Component.empty();
		try {
			component.append(ComponentUtils.wrapInSquareBrackets(Component.Serializer.fromJson(message.user())));
		} catch (JsonParseException e) {
			if (ack != null) {
				ack.call(e.getMessage());
			}
			return;
		}
		component.append(Component.literal(" "));
		try {
            for (String str : message.content()) {
			    component.append(Component.Serializer.fromJson(str));
            }
		} catch (JsonParseException e) {
			if (ack != null) {
				ack.call(e.getMessage());
			}
			return;
		}
		this.server.getPlayerList().broadcastSystemMessage(component, false);
		if (ack != null) {
			ack.call(true);
		}
	}

	private void onAnnouncement(Announcement announcement) {
		this.onAnnouncement(announcement, null);
	}
	private void onAnnouncement(Announcement announcement, @Nullable Ack ack) {
		this.server.getPlayerList().broadcastSystemMessage(Component.literal(announcement.message()), false);
	}

	private void onCommand(Command command) {
		onCommand(command, null);
	}
	private void onCommand(Command command, @Nullable Ack ack) {
		ServerLevel serverlevel = this.server.overworld();
		this.server.getCommands().performPrefixedCommand(
				new CommandSourceStack(new ChatBridge.BridgeCommandSource(ack), Vec3.atLowerCornerOf(serverlevel.getSharedSpawnPos()), Vec2.ZERO,
						serverlevel, 4, "Chat Bridge", Component.literal("Chat Bridge"), this.server, (Entity) null),
				command.command());
	}

	private void onQuery(Ack ack) {
		ack.call(jsonify(new QueryResponse(
			this.server.getPlayerList().getPlayers().stream().map(this::makeSentUser).collect(Collectors.toList()),
			Math.min(20, 1000 / this.server.getAverageTickTime()),
			this.server.getWorldData().overworldData().getGameTime(),
			this.server.getStatusJson()
		)));
	}


	private class BridgeCommandSource implements CommandSource {
		private final Ack ack;
		
		public BridgeCommandSource(@Nullable Ack ack) {
			this.ack = ack;
		}
		@Override
		public void sendSystemMessage(Component response) {
			if (this.ack != null) {
				this.ack.call(response.getString());
			}
		}

		@Override
		public boolean acceptsSuccess() {
			return true;
		}

		@Override
		public boolean acceptsFailure() {
			return true;
		}

		@Override
		public boolean shouldInformAdmins() {
			return false;
		}
	}

}
