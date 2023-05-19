package io.github.gingerindustries.chatbridge;

import java.net.URI;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

// The value here should match an entry in the META-INF/mods.toml file
@Mod(ChatBridgeMod.MODID)
public class ChatBridgeMod {
	public static final String MODID = "chatbridge";
	private static final Logger LOGGER = LogUtils.getLogger();
	public static ChatBridge BRIDGE;

	public ChatBridgeMod() {
		BridgeConfig.register();
		MinecraftForge.EVENT_BUS.register(this);

	}

	// You can use SubscribeEvent and let the Event Bus discover methods to call
	@SubscribeEvent
	public void onServerStarted(ServerStartedEvent event) {
		LOGGER.info("Starting Chat Bridge");
		ChatBridgeMod.BRIDGE = new ChatBridge(URI.create(BridgeConfig.CONFIG.serverAddress.get()), event.getServer(),
				MinecraftForge.EVENT_BUS);
	}
	
	@SubscribeEvent
	public void onServerStopping(ServerStoppingEvent event) {
		LOGGER.info("Stopping Chat Bridge");
		ChatBridgeMod.BRIDGE.shutdown();
	}
}
