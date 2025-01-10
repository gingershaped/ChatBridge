package computer.gingershaped.chatbridge;

import org.apache.commons.lang3.tuple.Pair;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.common.ForgeConfigSpec.ConfigValue;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;

public class BridgeConfig {
    public final ConfigValue<String> serverAddress;
    public final ConfigValue<String> secret;

    public BridgeConfig(ForgeConfigSpec.Builder builder) {
        serverAddress = builder.comment("The address of the server to connect to.")
                .comment("Schema is mandatory.")
                .define("serverAddress", "");
        secret = builder.comment("A secret which will be sent during connection")
                .comment("as the \"secret\" auth key.")
                .define("secret", "");
    }

    public static final BridgeConfig CONFIG;
    public static final ForgeConfigSpec spec;

    static {
        final Pair<BridgeConfig, ForgeConfigSpec> pair = new ForgeConfigSpec.Builder().configure(BridgeConfig::new);
        CONFIG = pair.getLeft();
        spec = pair.getRight();
    }

    public static void register() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, spec);
    }
}
