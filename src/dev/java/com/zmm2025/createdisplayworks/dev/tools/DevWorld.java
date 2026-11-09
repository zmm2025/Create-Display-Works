package com.zmm2025.createdisplayworks.dev.tools;

import com.google.gson.JsonObject;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.PresetFlatWorldScreen;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.levelgen.structure.StructureSet;

/** Opens the shared dev test world on launch, creating it with the standard test settings if it doesn't exist yet. */
final class DevWorld {
    static final String NAME = System.getProperty("createdisplayworks.dev.world", "Dev Testing World");
    private static final String LAYERS = "minecraft:bedrock,62*minecraft:dirt,minecraft:grass_block;minecraft:the_void";
    private static boolean opened;
    /** Set when this session created the world, so the first player join can finish the setup. */
    private static volatile boolean fresh;

    private DevWorld() {}

    /** Called whenever the title screen opens; only the first one auto-joins, so leaving the world stays possible. */
    static void titleScreenOpened() {
        if (opened) return;
        opened = true;
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.tell(() -> {
            if (minecraft.getLevelSource().levelExists(NAME))
                minecraft.createWorldOpenFlows().openWorld(NAME, () -> minecraft.setScreen(new TitleScreen()));
            else create(minecraft);
        });
    }

    /**
     * Leaves the current world, if any, and joins {@code host} (a server address) or {@code world} (a saved world, by
     * default the test world), returning once the player is in. Joining a server tests what that server lets the
     * player do, for example without operator permissions.
     */
    static JsonObject connect(Map<String, String> query) throws Exception {
        Minecraft minecraft = Minecraft.getInstance();
        String host = query.get("host"), world = query.getOrDefault("world", NAME);
        minecraft
                .submit(() -> {
                    leave(minecraft);
                    if (host != null)
                        ConnectScreen.startConnecting(
                                new TitleScreen(),
                                minecraft,
                                ServerAddress.parseString(host),
                                new ServerData("Test server", host, ServerData.Type.OTHER),
                                false,
                                null);
                    else
                        minecraft.createWorldOpenFlows().openWorld(world, () -> minecraft.setScreen(new TitleScreen()));
                })
                .get(2, TimeUnit.MINUTES);
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(2);
        while (System.nanoTime() < deadline) {
            boolean joined = minecraft
                    .submit(() -> minecraft.player != null
                            && minecraft.level != null
                            && minecraft.getOverlay() == null
                            && !(minecraft.screen instanceof ReceivingLevelScreen)
                            && !(minecraft.screen instanceof ConnectScreen))
                    .get(1, TimeUnit.MINUTES);
            if (joined) return DevTools.status();
            if (minecraft
                    .submit(() -> minecraft.screen instanceof DisconnectedScreen)
                    .get(1, TimeUnit.MINUTES)) {
                JsonObject response = DevScreens.screen();
                response.addProperty("ok", false);
                return response;
            }
            Thread.sleep(100);
        }
        return DevApi.failure("Didn't get into the world within 2 minutes; check /status.");
    }

    /** Writes the world to disk now. {@code save-all} is only a command on dedicated servers. */
    static JsonObject save() throws Exception {
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) return DevApi.failure("Not in a single-player world.");
        server.submit(() -> server.saveEverything(true, true, true)).get(2, TimeUnit.MINUTES);
        return DevApi.ok();
    }

    /** Saves and leaves the current world for the title screen, as Save and Quit does. */
    static JsonObject disconnect() throws Exception {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.submit(() -> leave(minecraft)).get(2, TimeUnit.MINUTES);
        return DevApi.ok();
    }

    private static void leave(Minecraft minecraft) {
        if (minecraft.level == null) return;
        minecraft.level.disconnect();
        minecraft.disconnect();
        minecraft.setScreen(new TitleScreen());
    }

    private static void create(Minecraft minecraft) {
        GameRules rules = new GameRules();
        GameRules.visitGameRuleTypes(new GameRules.GameRuleTypeVisitor() {
            @Override
            public void visitBoolean(
                    GameRules.Key<GameRules.BooleanValue> key, GameRules.Type<GameRules.BooleanValue> type) {
                if (key.getCategory() == GameRules.Category.SPAWNING || key.getCategory() == GameRules.Category.DROPS)
                    rules.getRule(key).set(false, null);
            }
        });
        rules.getRule(GameRules.RULE_KEEPINVENTORY).set(true, null);
        rules.getRule(GameRules.RULE_DISABLE_RAIDS).set(true, null);
        rules.getRule(GameRules.RULE_MOBGRIEFING).set(false, null);
        rules.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
        rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, null);
        LevelSettings settings = new LevelSettings(
                NAME, GameType.CREATIVE, false, Difficulty.EASY, true, rules, WorldDataConfiguration.DEFAULT);
        fresh = true;
        minecraft
                .createWorldOpenFlows()
                .createFreshLevel(
                        NAME,
                        settings,
                        new WorldOptions(WorldOptions.randomSeed(), false, false),
                        registries -> {
                            HolderGetter<Biome> biomes = registries.lookupOrThrow(Registries.BIOME);
                            HolderGetter<StructureSet> structures = registries.lookupOrThrow(Registries.STRUCTURE_SET);
                            HolderGetter<PlacedFeature> features = registries.lookupOrThrow(Registries.PLACED_FEATURE);
                            FlatLevelGeneratorSettings flat = PresetFlatWorldScreen.fromString(
                                    BuiltInRegistries.BLOCK.asLookup(),
                                    biomes,
                                    structures,
                                    features,
                                    LAYERS,
                                    FlatLevelGeneratorSettings.getDefault(biomes, structures, features));
                            return registries
                                    .registryOrThrow(Registries.WORLD_PRESET)
                                    .getHolderOrThrow(WorldPresets.FLAT)
                                    .value()
                                    .createWorldDimensions()
                                    .replaceOverworldGenerator(registries, new FlatLevelSource(flat));
                        },
                        new TitleScreen());
    }

    /**
     * Finishes setting up a freshly created world when its first player joins. Advancements granted before the
     * player's first advancement sync arrive as initial state, so they don't flood the screen with toasts.
     */
    static void playerJoined(ServerPlayer player) {
        if (!fresh) return;
        fresh = false;
        MinecraftServer server = player.server;
        GameRules.BooleanValue announce = server.getGameRules().getRule(GameRules.RULE_ANNOUNCE_ADVANCEMENTS);
        boolean announced = announce.get();
        announce.set(false, server);
        var source = server.createCommandSourceStack().withSuppressedOutput();
        server.getCommands().performPrefixedCommand(source, "time set 6000");
        server.getCommands()
                .performPrefixedCommand(
                        source, "advancement grant " + player.getGameProfile().getName() + " everything");
        announce.set(announced, server);
    }
}
