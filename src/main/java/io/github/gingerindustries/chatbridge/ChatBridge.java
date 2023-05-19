package io.github.gingerindustries.chatbridge;

import java.net.URI;
import java.util.stream.Collectors;

import org.jetbrains.annotations.Nullable;
import org.json.JSONObject;
import org.slf4j.Logger;

import com.google.gson.Gson;
import com.mojang.logging.LogUtils;

import io.github.gingerindustries.chatbridge.schemas.recieved.Announcement;
import io.github.gingerindustries.chatbridge.schemas.recieved.Command;
import io.github.gingerindustries.chatbridge.schemas.recieved.ReceivedChatMessage;
import io.github.gingerindustries.chatbridge.schemas.sent.PlayerJoined;
import io.github.gingerindustries.chatbridge.schemas.sent.PlayerLeft;
import io.github.gingerindustries.chatbridge.schemas.sent.SentChatMessage;
import io.github.gingerindustries.chatbridge.schemas.sent.SentUser;
import io.socket.client.Ack;
import io.socket.client.IO;
import io.socket.client.Socket;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.IEventBus;

public class ChatBridge {
	private static final Gson GSON = new Gson();
	private static final Logger LOGGER = LogUtils.getLogger();
	private final Socket socket;
	private final MinecraftServer server;
	private Boolean registeredHandlers = false;

	public ChatBridge(URI uri, MinecraftServer server, IEventBus modEventBus) {
		LOGGER.info("Establishing connection");
		LOGGER.debug("Target: " + uri.toASCIIString());
		this.server = server;
		this.socket = IO.socket(uri);

		this.socket.io().on("reconnect_attempt", args -> LOGGER.warn("Connection failed, trying again..."));
		this.socket.io().on("open", args -> {
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
		LOGGER.info("Shutting down");
		this.socket.close();
	}

	private SentUser makeSentUser(Player player) {
		return new SentUser(player.getDisplayName().getString(), player.getStringUUID(),
				this.server.getPlayerList().isOp(player.getGameProfile()));
	}

	private void registerServerListeners(IEventBus modEventBus) {
		modEventBus.addListener(this::onServerChatEvent);
		modEventBus.addListener(this::onPlayerLoggedInEvent);
		modEventBus.addListener(this::onPlayerLoggedOutEvent);
	}

	public void onServerChatEvent(ServerChatEvent event) {
		this.socket.send(GSON.toJson(new SentChatMessage(makeSentUser(event.getPlayer()), event.getRawText())));
	}

	public void onPlayerLoggedInEvent(PlayerEvent.PlayerLoggedInEvent event) {
		this.socket.emit("playerJoined", GSON.toJson(new PlayerJoined(makeSentUser(event.getEntity()))));
	}

	public void onPlayerLoggedOutEvent(PlayerEvent.PlayerLoggedOutEvent event) {
		this.socket.emit("playerLeft", GSON.toJson(new PlayerLeft(makeSentUser(event.getEntity()))));
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
			LOGGER.debug("BALLS", args.length);
			Command command = GSON.fromJson(args[0].toString(), Command.class);
			if (args.length > 1 && args[1] instanceof Ack) {
				this.onCommand(command, ((Ack) args[1]));
			}
			else {				
				this.onCommand(command);
			}
		});
		this.socket.on("userList", (Object... args) -> {
			assert args[0] instanceof Ack;
			this.onUserList(((Ack) args[0]));
		});
		this.socket.on("tps", (Object... args) -> {
			assert args[0] instanceof Ack;
			this.onTPS(((Ack) args[0]));
		});
	}

	private void onMessage(ReceivedChatMessage message) {
		MutableComponent component = Component.empty();
		component.append(ComponentUtils.wrapInSquareBrackets(Component.literal(message.user().name())
				.withStyle(style -> style.withBold(message.user().bold()).withColor(
						message.user().color() != null ? TextColor.parseColor(message.user().color()) : null))));
		component.append(Component.literal(" "));
		component.append(Component.literal(message.content()));
		this.server.getPlayerList().broadcastSystemMessage(component, false);
	}

	private void onAnnouncement(Announcement announcement) {
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
	
	private void onUserList(Ack ack) {
		ack.call(GSON.toJson(this.server.getPlayerList().getPlayers().stream().map(this::makeSentUser).collect(Collectors.toList())));
	}
	
	private void onTPS(Ack ack) {
		ack.call(Math.min(20, 1000 / this.server.getAverageTickTime()));
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
