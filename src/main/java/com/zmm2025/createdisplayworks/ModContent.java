package com.zmm2025.createdisplayworks;

import com.simibubi.create.api.behaviour.display.DisplayTarget;
import com.simibubi.create.api.registry.CreateRegistries;
import com.zmm2025.createdisplayworks.flipdot.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.*;

public final class ModContent {
    private static final String ID = CreateDisplayWorks.MOD_ID;
    public static final DeferredRegister<net.minecraft.sounds.SoundEvent> SOUNDS=DeferredRegister.create(Registries.SOUND_EVENT,ID);
    public static final DeferredHolder<net.minecraft.sounds.SoundEvent,net.minecraft.sounds.SoundEvent> RATTLE=SOUNDS.register("disc_rattle",()->net.minecraft.sounds.SoundEvent.createVariableRangeEvent(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(ID,"disc_rattle")));
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(Registries.BLOCK, ID);
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(Registries.ITEM, ID);
    public static final DeferredRegister<BlockEntityType<?>> ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, ID);
    public static final DeferredRegister<DisplayTarget> TARGETS = DeferredRegister.create(CreateRegistries.DISPLAY_TARGET, ID);
    public static final DeferredHolder<Block, FlipDotBlock> FLIP_DOT = BLOCKS.register("flip_dot_display",
        () -> new FlipDotBlock(BlockBehaviour.Properties.of().strength(3,6).requiresCorrectToolForDrops()
            .mapColor(net.minecraft.world.level.material.MapColor.COLOR_GRAY).sound(SoundType.METAL).noOcclusion()));
    public static final DeferredHolder<Item, BlockItem> FLIP_DOT_ITEM = ITEMS.register("flip_dot_display",
        () -> new FlipDotItem(FLIP_DOT.get(), new Item.Properties()));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<FlipDotBlockEntity>> FLIP_DOT_ENTITY =
        ENTITIES.register("flip_dot_display", () -> BlockEntityType.Builder.of(FlipDotBlockEntity::new, FLIP_DOT.get()).build(null));
    public static final DeferredHolder<DisplayTarget, FlipDotDisplayTarget> TARGET = TARGETS.register("flip_dot_display", FlipDotDisplayTarget::new);
    public static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, ID);
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB = TABS.register("main", () -> CreativeModeTab.builder()
        .title(net.minecraft.network.chat.Component.translatable("itemGroup." + ID))
        .icon(() -> new ItemStack(FLIP_DOT_ITEM.get()))
        .withTabsBefore(com.simibubi.create.AllCreativeModeTabs.PALETTES_CREATIVE_TAB.getKey())
        .displayItems((params, output) -> ITEMS.getEntries().forEach(item -> output.accept(item.get())))
        .build());

    public static void register(IEventBus bus) {
        SOUNDS.register(bus); BLOCKS.register(bus); ITEMS.register(bus); ENTITIES.register(bus); TARGETS.register(bus); TABS.register(bus);
    }
}
