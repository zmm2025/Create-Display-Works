package com.zmm2025.createdisplayworks;

import static com.simibubi.create.api.behaviour.display.DisplayTarget.displayTarget;
import static com.simibubi.create.foundation.data.ModelGen.customItemModel;
import static com.simibubi.create.foundation.data.TagGen.pickaxeOnly;
import static com.zmm2025.createdisplayworks.CreateDisplayWorks.REGISTRATE;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllItems;
import com.tterrag.registrate.providers.RegistrateRecipeProvider;
import com.tterrag.registrate.util.entry.BlockEntry;
import com.zmm2025.createdisplayworks.flipdot.FlipDotBlock;
import com.zmm2025.createdisplayworks.flipdot.FlipDotBlockStateGen;
import com.zmm2025.createdisplayworks.flipdot.FlipDotItem;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.data.recipes.ShapedRecipeBuilder;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.material.MapColor;

/**
 * Blocks and their items. Each entry declares what datagen writes for it: blockstate, item model, loot table, tags,
 * recipe and English name. Datagen's default loot table drops the block itself.
 */
public final class ModBlocks {
    static {
        REGISTRATE.defaultCreativeTab(ModCreativeTabs.MAIN.getKey());
    }

    public static final BlockEntry<FlipDotBlock> FLIP_DOT_DISPLAY = REGISTRATE
            .block("flip_dot_display", FlipDotBlock::new)
            .properties(p -> p.strength(3, 6)
                    .requiresCorrectToolForDrops()
                    .mapColor(MapColor.COLOR_GRAY)
                    .sound(SoundType.METAL)
                    .noOcclusion())
            .transform(pickaxeOnly())
            .transform(displayTarget(ModDisplayTargets.FLIP_DOT_DISPLAY))
            .blockstate(FlipDotBlockStateGen::generate)
            .recipe((c, p) -> ShapedRecipeBuilder.shaped(RecipeCategory.REDSTONE, c.get())
                    .pattern(" E ")
                    .pattern("BCB")
                    .pattern(" P ")
                    .define('E', AllItems.ELECTRON_TUBE)
                    .define('B', AllItems.BRASS_SHEET)
                    .define('C', AllBlocks.BRASS_CASING)
                    .define('P', AllBlocks.COGWHEEL)
                    .unlockedBy("has_brass_casing", RegistrateRecipeProvider.has(AllBlocks.BRASS_CASING))
                    .save(p))
            .lang("Flip-Dot Display")
            // The item's geometry is hand-made in models/block/flip_dot_display/item.json.
            .item(FlipDotItem::new)
            .transform(customItemModel())
            .register();

    public static void register() {}
}
