package com.zmm2025.createdisplayworks.ponder;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.foundation.ponder.CreateSceneBuilder;
import com.zmm2025.createdisplayworks.*;
import com.zmm2025.createdisplayworks.flipdot.*;
import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.api.registration.*;
import net.createmod.ponder.api.scene.*;
import net.minecraft.core.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.*;

public final class DisplayWorksPonderPlugin implements PonderPlugin {
    public String getModId() { return CreateDisplayWorks.MOD_ID; }
    public void registerScenes(PonderSceneRegistrationHelper<ResourceLocation> helper) {
        helper.forComponents(ModContent.FLIP_DOT.getId())
            .addStoryBoard("flip_dot", DisplayWorksPonderPlugin::drive)
            .addStoryBoard("flip_dot", DisplayWorksPonderPlugin::information)
            .addStoryBoard("flip_dot", DisplayWorksPonderPlugin::media);
    }
    public void registerTags(PonderTagRegistrationHelper<ResourceLocation> helper) {
        helper.addToTag(ResourceLocation.fromNamespaceAndPath("create", "kinetic_appliances")).add(ModContent.FLIP_DOT.getId());
        helper.addToTag(ResourceLocation.fromNamespaceAndPath("create", "display_targets")).add(ModContent.FLIP_DOT.getId());
    }
    private static CreateSceneBuilder base(SceneBuilder builder, SceneBuildingUtil util, String id, String title) {
        var scene = new CreateSceneBuilder(builder);
        scene.title(id, title); scene.configureBasePlate(0, 0, 6); scene.showBasePlate();
        // The schematic predates connected borders, so join the 4x2 canvas before showing it.
        for (int x = 1; x <= 4; x++) for (int y = 1; y <= 2; y++) {
            BlockPos pos = new BlockPos(x, y, 3);
            scene.world().modifyBlock(pos, state -> FlipDotBlock.connect(state, d -> {
                BlockPos next = pos.relative(d);
                return next.getZ() == 3 && next.getX() >= 1 && next.getX() <= 4 && next.getY() >= 1 && next.getY() <= 2;
            }), false);
        }
        scene.world().showSection(util.select().fromTo(1, 1, 3, 4, 2, 3), Direction.DOWN);
        scene.idle(15); return scene;
    }
    private static void text(CreateSceneBuilder scene, String value, float rpm) {
        for (int x=1; x<=4; x++) {
            int column=4-x;
            scene.world().modifyBlockEntity(new BlockPos(x, 2, 3), FlipDotBlockEntity.class, be -> {
                be.showVirtualFrame(DotMatrix.tile(value,column,32),rpm);
            });
        }
    }
    public static void drive(SceneBuilder builder, SceneBuildingUtil util) {
        var scene=base(builder, util, "flip_dot_drive", "Building and powering a Flip-Dot Display");
        scene.overlay().showText(80).text("Each block has 8 by 8 dots. Place matching faces together to build one seamless canvas.")
            .pointAt(util.vector().of(3, 2, 3)).placeNearTarget().attachKeyFrame(); scene.idle(90);
        scene.world().showSection(util.select().fromTo(0, 1, 3, 0, 1, 4), Direction.EAST);
        scene.world().setKineticSpeed(util.select().everywhere(), 32); text(scene, "IRON", 32);
        scene.overlay().showText(80).text("Drive the built-in cogs from the side. Rotation passes between adjacent displays.")
            .pointAt(util.vector().centerOf(0, 1, 3)).placeNearTarget().attachKeyFrame(); scene.idle(90);
        scene.world().setKineticSpeed(util.select().everywhere(), 128); text(scene, "FULL", 128);
        scene.overlay().showText(80).text("Faster rotation means faster diagonal flips. Changed discs start together and can reverse immediately when new information arrives.")
            .pointAt(util.vector().of(3, 2, 3)).placeNearTarget().attachKeyFrame(); scene.idle(90);
        scene.world().setKineticSpeed(util.select().everywhere(), 0);
        scene.overlay().showText(80).text("Stopping the drive finishes the current flips, then holds. Video pauses; new information waits until power returns.")
            .pointAt(util.vector().of(3, 2, 3)).placeNearTarget().attachKeyFrame(); scene.idle(90);
        scene.markAsFinished();
    }
    public static void information(SceneBuilder builder, SceneBuildingUtil util) {
        var scene=base(builder, util, "flip_dot_information", "Text, Display Links and dye");
        scene.world().showSection(util.select().fromTo(0, 1, 3, 0, 1, 4), Direction.EAST);
        scene.world().setKineticSpeed(util.select().everywhere(), 64); text(scene, "IRON", 64);
        scene.overlay().showControls(util.vector().of(3, 2.5, 3), Pointing.RIGHT, 70).rightClick().withItem(AllBlocks.DISPLAY_LINK.asStack());
        scene.overlay().showText(85).text("Select this display with a Display Link, then place the link on a source such as a Stockpile Switch. Select the target row in the link interface.")
            .pointAt(util.vector().of(3, 2, 3)).placeNearTarget().attachKeyFrame(); scene.idle(95);
        scene.overlay().showControls(util.vector().of(3, 2.5, 3), Pointing.RIGHT, 60).rightClick().withItem(new ItemStack(Items.NAME_TAG));
        text(scene, "READY", 64);
        scene.overlay().showText(80).text("A renamed Name Tag writes one row. A Create Clipboard writes several rows. Right-click with an empty hand to open media controls.")
            .pointAt(util.vector().of(3, 2, 3)).placeNearTarget().attachKeyFrame(); scene.idle(90);
        scene.world().modifyBlockEntity(new BlockPos(4, 2, 3), FlipDotBlockEntity.class, be -> be.setDye(DyeColor.LIME));
        scene.overlay().showControls(util.vector().of(4, 2.5, 3), Pointing.RIGHT, 60).rightClick().withItem(new ItemStack(Items.LIME_DYE));
        scene.overlay().showText(80).text("Dye changes the front color across the whole board. Applying either existing color swaps front and back. Apply a new dye twice to make it the back color.")
            .pointAt(util.vector().of(4, 2, 3)).placeNearTarget().attachKeyFrame(); scene.idle(90);
        scene.markAsFinished();
    }
    public static void media(SceneBuilder builder, SceneBuildingUtil util) {
        var scene=base(builder, util, "flip_dot_media", "Images, video and pixel drawings");
        scene.overlay().showControls(util.vector().of(3,2.5,3),Pointing.RIGHT,60).rightClick();
        scene.overlay().showText(100).text("Right-click with an empty hand. Enter a media URL, public YouTube video or local file, import a preview, then apply it.")
            .pointAt(util.vector().of(3,2,3)).placeNearTarget().attachKeyFrame();scene.idle(110);
        scene.overlay().showText(100).text("Import images or silent animations up to 100 MB and 60 seconds at 10 FPS. Choose stretch, fit or fill; adjust threshold, inversion and dithering.")
            .pointAt(util.vector().of(3,2,3)).placeNearTarget().attachKeyFrame();scene.idle(110);
        scene.world().showSection(util.select().fromTo(0,1,3,0,1,4),Direction.EAST);scene.world().setKineticSpeed(util.select().everywhere(),256);
        text(scene,"PLAY",256);
        scene.overlay().showText(100).text("Dot frames are saved in the world. Slow cogs may not finish each flip before the next frame arrives. New Display Link input replaces media playback.")
            .pointAt(util.vector().of(3,2,3)).placeNearTarget().attachKeyFrame();scene.idle(110);
        scene.overlay().showControls(util.vector().of(3,2.5,3),Pointing.RIGHT,60).rightClick().withItem(AllBlocks.CLIPBOARD.asStack());
        scene.overlay().showText(100).text("For hand-drawn symbols, start a Clipboard page with [pixels]. Add rows using # for light dots and periods or spaces for dark dots.")
            .pointAt(util.vector().of(3,2,3)).placeNearTarget().attachKeyFrame();scene.idle(110);scene.markAsFinished();
    }
}
