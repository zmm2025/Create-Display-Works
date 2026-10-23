package com.zmm2025.createsignsoflife.dev.tools;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PresetFlatWorldScreen;
import net.minecraft.client.gui.screens.TitleScreen;
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
    static final String NAME = System.getProperty("csol.dev.world", "Dev Testing World");
    private static final String LAYERS = "minecraft:bedrock,62*minecraft:dirt,minecraft:grass_block;minecraft:the_void";
    private static boolean opened;
    /** Set when this session created the world, so the first player join can finish the setup. */
    private static volatile boolean fresh;

    private DevWorld() {}

    /** Called whenever the title screen opens; only the first one auto-joins, so leaving the world stays possible. */
    static void titleScreenOpened() {
        if(opened) return;
        opened = true;
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.tell(() -> {
            if(minecraft.getLevelSource().levelExists(NAME)) minecraft.createWorldOpenFlows().openWorld(NAME, () -> minecraft.setScreen(new TitleScreen()));
            else create(minecraft);
        });
    }

    private static void create(Minecraft minecraft) {
        GameRules rules = new GameRules();
        GameRules.visitGameRuleTypes(new GameRules.GameRuleTypeVisitor() {
            @Override public void visitBoolean(GameRules.Key<GameRules.BooleanValue> key, GameRules.Type<GameRules.BooleanValue> type) {
                if(key.getCategory() == GameRules.Category.SPAWNING || key.getCategory() == GameRules.Category.DROPS) rules.getRule(key).set(false, null);
            }
        });
        rules.getRule(GameRules.RULE_KEEPINVENTORY).set(true, null);
        rules.getRule(GameRules.RULE_DISABLE_RAIDS).set(true, null);
        rules.getRule(GameRules.RULE_MOBGRIEFING).set(false, null);
        rules.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
        rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, null);
        LevelSettings settings = new LevelSettings(NAME, GameType.CREATIVE, false, Difficulty.EASY, true, rules, WorldDataConfiguration.DEFAULT);
        fresh = true;
        minecraft.createWorldOpenFlows().createFreshLevel(NAME, settings, new WorldOptions(WorldOptions.randomSeed(), false, false), registries -> {
            HolderGetter<Biome> biomes = registries.lookupOrThrow(Registries.BIOME);
            HolderGetter<StructureSet> structures = registries.lookupOrThrow(Registries.STRUCTURE_SET);
            HolderGetter<PlacedFeature> features = registries.lookupOrThrow(Registries.PLACED_FEATURE);
            FlatLevelGeneratorSettings flat = PresetFlatWorldScreen.fromString(BuiltInRegistries.BLOCK.asLookup(), biomes, structures, features, LAYERS,
                FlatLevelGeneratorSettings.getDefault(biomes, structures, features));
            return registries.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions()
                .replaceOverworldGenerator(registries, new FlatLevelSource(flat));
        }, new TitleScreen());
    }

    /**
     * Finishes setting up a freshly created world when its first player joins. Advancements granted before the
     * player's first advancement sync arrive as initial state, so they don't flood the screen with toasts.
     */
    static void playerJoined(ServerPlayer player) {
        if(!fresh) return;
        fresh = false;
        MinecraftServer server = player.server;
        GameRules.BooleanValue announce = server.getGameRules().getRule(GameRules.RULE_ANNOUNCE_ADVANCEMENTS);
        boolean announced = announce.get();
        announce.set(false, server);
        var source = server.createCommandSourceStack().withSuppressedOutput();
        server.getCommands().performPrefixedCommand(source, "time set 6000");
        server.getCommands().performPrefixedCommand(source, "advancement grant " + player.getGameProfile().getName() + " everything");
        announce.set(announced, server);
    }
}
