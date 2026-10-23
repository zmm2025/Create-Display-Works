package com.zmm2025.createdisplayworks.dev;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllDisplaySources;
import com.simibubi.create.content.redstone.displayLink.DisplayLinkBlock;
import com.simibubi.create.content.redstone.displayLink.DisplayLinkBlockEntity;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import com.zmm2025.createdisplayworks.*;
import com.zmm2025.createdisplayworks.flipdot.*;
import net.minecraft.commands.Commands;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.*;
import java.util.*;

/** Development-only fixture, excluded from the distributable jar. */
@EventBusSubscriber(modid=CreateDisplayWorks.MOD_ID)
public final class FlipDotDev {
    private static BlockPos origin;
    private static ServerLevel world;
    private static ServerPlayer viewer;
    private static long started = -1, held;
    private static int stage;
    private static final List<String> failures = new ArrayList<>();
    public static volatile boolean verificationComplete;
    @SubscribeEvent public static void joined(PlayerEvent.PlayerLoggedInEvent event) {
        if(System.getProperty("flipdot.showcase", "").startsWith("media-ui"))return;
        if(System.getProperty("flipdot.showcase", "").equals("arrivals-capped")){verificationComplete=true;return;}
        if(Boolean.getBoolean("flipdot.verify") && event.getEntity() instanceof ServerPlayer player) {
            build(player); started=world.getGameTime();stage=0;failures.clear();speed(32);text("32");
        }
    }
    @SubscribeEvent public static void commands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("flipdot_demo").requires(s->s.hasPermission(2))
            .executes(c->{build(c.getSource().getPlayerOrException());return 1;})
            .then(Commands.literal("speed").then(Commands.argument("rpm",IntegerArgumentType.integer(-256,256)).executes(c->{speed(IntegerArgumentType.getInteger(c,"rpm"));return 1;})))
            .then(Commands.literal("text").then(Commands.argument("text",StringArgumentType.greedyString()).executes(c->{text(StringArgumentType.getString(c,"text"));return 1;})))
            .then(Commands.literal("test").executes(c->{started=world.getGameTime();stage=0;failures.clear();speed(32);text("32");return 1;})));
    }
    private static FlipDotBlockEntity tile(int x,int y) { return (FlipDotBlockEntity)world.getBlockEntity(origin.offset(x,y,0)); }
    private static void build(ServerPlayer player) {
        viewer=player;world=player.serverLevel();origin=new BlockPos(0,100,0);
        for (int x=-8;x<=10;x++) for(int z=-10;z<=5;z++) world.setBlockAndUpdate(new BlockPos(x,99,z),Blocks.SMOOTH_STONE.defaultBlockState());
        for(int x=-3;x<=6;x++) for(int y=0;y<5;y++) for(int z=-1;z<=2;z++) world.setBlockAndUpdate(origin.offset(x,y,z),Blocks.AIR.defaultBlockState());
        for(int x=0;x<4;x++) for(int y=0;y<2;y++) world.setBlockAndUpdate(origin.offset(x,y,0),ModContent.FLIP_DOT.get().defaultBlockState().setValue(FlipDotBlock.HORIZONTAL_FACING,Direction.NORTH));
        world.setBlockAndUpdate(origin.offset(-1,0,0),AllBlocks.COGWHEEL.getDefaultState().setValue(BlockStateProperties.AXIS,Direction.Axis.Z));
        world.setBlockAndUpdate(origin.offset(-1,0,1),AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(CreativeMotorBlock.FACING,Direction.NORTH));
        player.teleportTo(1.8,100,-5.5);player.setYRot(0);player.setXRot(0);
        player.getInventory().clearContent();
        player.getInventory().setItem(0,new ItemStack(ModContent.FLIP_DOT_ITEM.get(),64));
        player.getInventory().setItem(1,new ItemStack(Items.RED_DYE,64));
        player.getInventory().setItem(2,new ItemStack(Items.BLACK_DYE,64));
        player.getInventory().setItem(3,new ItemStack(Items.WHITE_DYE,64));
        ItemStack name=new ItemStack(Items.NAME_TAG);name.set(DataComponents.CUSTOM_NAME,Component.literal("LIFE"));player.getInventory().setItem(4,name);
        player.getInventory().setItem(5,AllBlocks.DISPLAY_LINK.asStack());
        text("LIFE");tile(0,0).setTextAtBlock("32RPM");
    }
    private static void text(String text) { if(world!=null)tile(0,1).setTextAtBlock(text); }
    private static void speed(int rpm) {
        if(world!=null && world.getBlockEntity(origin.offset(-1,0,1)) instanceof CreativeMotorBlockEntity motor) { motor.generatedSpeed.setValue(rpm);motor.updateGeneratedRotation(); }
    }
    private static void check(boolean condition,String label) {
        if(!condition)failures.add(label);
        System.out.println("FLIP_DOT_TEST "+(condition?"PASS ":"FAIL ")+label);
        viewer.sendSystemMessage(Component.literal((condition?"PASS ":"FAIL ")+label));
    }
    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        if(started<0 || world==null)return;
        long elapsed=world.getGameTime()-started;
        if(stage==0 && elapsed>=80) {
            check(tile(0,1).assembly().tiles().size()==8,"8-block tiled canvas discovered");
            check(Math.abs(tile(0,0).getSpeed())==32 && Math.abs(tile(3,1).getSpeed())==32,"real cog drive reaches all tiles at 32 RPM");
            check(tile(3,1).displayedBits()!=0,"text spans the tiled canvas");
            text("64");stage++;
        } else if(stage==1 && elapsed>=82) {
            long start=tile(0,1).assembly().tiles().stream().mapToLong(FlipDotBlockEntity::flipStarted).max().orElseThrow();boolean synced=true;
            for(int x=0;x<4;x++)for(int y=0;y<2;y++)synced&=tile(x,y).flipStarted()<started+80 || tile(x,y).flipStarted()==start;
            check(synced,"all tiles share the same flip start tick");
            check(tile(0,1).flipDuration()==8,"32 RPM flip has an 8-tick duration");speed(64);text("FAST");stage++;
        } else if(stage==2 && elapsed>=150) {
            check(Math.abs(tile(3,1).getSpeed())==64,"64 RPM propagates through the array");
            check(tile(0,1).flipDuration()==4,"64 RPM flip has a 4-tick duration");
            tile(0,1).setDye(DyeColor.BLACK);check(tile(0,1).discColor()==(DyeColor.BLACK.getTextureDiffuseColor()&0xFFFFFF),"dye matching the back swaps both faces");
            tile(0,1).setDye(DyeColor.WHITE);speed(0);stage++;
        } else if(stage==3 && elapsed>=180) { held=tile(3,1).displayedBits();text("STOP");stage++;
        } else if(stage==4 && elapsed>=240) {
            check(tile(3,1).getSpeed()==0,"drive stopped");check(tile(3,1).displayedBits()==held,"unpowered display retains its image");
            check(tile(3,1).pendingBits()!=held,"new text queues while stopped");speed(32);stage++;
        } else if(stage==5 && elapsed>=300) {
            check(tile(3,1).displayedBits()==tile(3,1).pendingBits(),"queued image applies after power returns");
            var saved=tile(3,1).saveWithoutMetadata(world.registryAccess());
            var restored=new FlipDotBlockEntity(tile(3,1).getBlockPos(),tile(3,1).getBlockState());
            restored.loadWithComponents(saved,world.registryAccess());
            check(restored.displayedBits()==tile(3,1).displayedBits() && restored.pendingBits()==tile(3,1).pendingBits(),"saved dot states survive serialization");
            world.setBlockAndUpdate(origin.offset(5,0,0),Blocks.TARGET.defaultBlockState());
            world.setBlockAndUpdate(origin.offset(6,0,0),Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACING,Direction.EAST)
                .setValue(LeverBlock.FACE,net.minecraft.world.level.block.state.properties.AttachFace.WALL).setValue(LeverBlock.POWERED,true));
            world.setBlockAndUpdate(origin.offset(5,1,0),AllBlocks.DISPLAY_LINK.getDefaultState().setValue(DisplayLinkBlock.FACING,Direction.UP));
            var link=(DisplayLinkBlockEntity)world.getBlockEntity(origin.offset(5,1,0));
            link.target(origin.offset(3,0,0));link.targetLine=1;link.activeSource=AllDisplaySources.REDSTONE_POWER.get();link.updateGatheredData();
            check(link.activeTarget==ModContent.TARGET.get(),"Create Display Link recognizes the new target");stage++;
        } else if(stage==6 && elapsed>=350) {
            check(tile(3,0).displayedBits()==DotMatrix.tile("15",0,32),"real Display Link transfers a powered target's redstone value");
            world.setBlockAndUpdate(origin.offset(4,1,0),ModContent.FLIP_DOT.get().defaultBlockState().setValue(FlipDotBlock.HORIZONTAL_FACING,Direction.NORTH));stage++;
        } else if(stage==7 && elapsed>=410) {
            check(tile(4,1).displayedBits()==DotMatrix.tile("STOP",0,40),"adding a new leftmost tile preserves row text");
            for(DyeColor dye:DyeColor.values()) {
                int back=tile(3,1).backColor(),front=tile(3,1).discColor(),rgb=dye.getTextureDiffuseColor()&0xFFFFFF;
                tile(3,1).setDye(dye);check(tile(3,1).discColor()==(rgb==front||rgb==back?back:rgb),"exact dye / swap RGB: "+dye.getName());
                check(tile(0,0).discColor()==tile(3,1).discColor() && tile(0,0).backColor()==tile(3,1).backColor(),"whole-board palette: "+dye.getName());
            }
            viewer.setGameMode(GameType.SURVIVAL);
            ItemStack red=new ItemStack(Items.RED_DYE,2);viewer.setItemInHand(InteractionHand.MAIN_HAND,red);
            var hit=new BlockHitResult(new Vec3(3.5,101.95,.2),Direction.NORTH,origin.offset(3,1,0),false);
            viewer.gameMode.useItemOn(viewer,world,red,InteractionHand.MAIN_HAND,hit);
            check(red.getCount()==1 && tile(3,1).discColor()==0xB02E26,"survival right-click dyes the block and consumes one dye");
            viewer.setGameMode(GameType.CREATIVE);
            ItemStack name=new ItemStack(Items.NAME_TAG);name.set(DataComponents.CUSTOM_NAME,Component.literal("RUN"));viewer.setItemInHand(InteractionHand.MAIN_HAND,name);
            viewer.gameMode.useItemOn(viewer,world,name,InteractionHand.MAIN_HAND,hit);
            check(tile(4,1).pendingBits()==DotMatrix.tile("RUN",0,40),"named name tag edits the physical row through normal interaction");
            ItemStack block=new ItemStack(ModContent.FLIP_DOT_ITEM.get());viewer.setItemInHand(InteractionHand.MAIN_HAND,block);
            viewer.gameMode.useItemOn(viewer,world,block,InteractionHand.MAIN_HAND,hit);
            check(world.getBlockState(origin.offset(3,2,0)).is(ModContent.FLIP_DOT.get()),"face-click placement helper extends the canvas upwards");
            check(tile(4,1).pendingBits()==DotMatrix.tile("RUN",0,40),"placement leaves existing text intact");
            world.setBlockAndUpdate(origin.offset(3,2,0),Blocks.AIR.defaultBlockState());
            world.setBlockAndUpdate(origin.offset(4,1,0),Blocks.AIR.defaultBlockState());stage++;
        } else if(stage==8 && elapsed>=470) {
            check(tile(3,1).assembly().width()==4 && tile(3,1).displayedBits()==DotMatrix.tile("RUN",0,32),"removing the added tile preserves and reflows the row");
            check(world.getRecipeManager().byKey(ModContent.FLIP_DOT.getId()).isPresent(),"crafting recipe is loaded in the recipe manager");
            check(com.simibubi.create.api.stress.BlockStressValues.getImpact(ModContent.FLIP_DOT.get())==1,"Create stress registry advertises the correct impact");
            BlockPos isolated=origin.offset(8,1,0);
            world.setBlockAndUpdate(isolated,ModContent.FLIP_DOT.get().defaultBlockState().setValue(BlockStateProperties.WATERLOGGED,true));
            check(world.getFluidState(isolated).isSource(),"waterlogged display retains a water source");
            var state=world.getBlockState(isolated);
            var wrenchHit=new BlockHitResult(Vec3.atCenterOf(isolated),Direction.UP,isolated,false);
            var context=new net.minecraft.world.item.context.UseOnContext(viewer,InteractionHand.MAIN_HAND,wrenchHit);
            ModContent.FLIP_DOT.get().onWrenched(state,context);
            check(world.getBlockState(isolated).getValue(FlipDotBlock.HORIZONTAL_FACING)!=state.getValue(FlipDotBlock.HORIZONTAL_FACING),"Create wrench rotates the display");
            check(world.getBlockState(isolated).getValue(BlockStateProperties.WATERLOGGED),"wrench rotation preserves waterlogging");
            viewer.setGameMode(GameType.SURVIVAL);
            int before=viewer.getInventory().countItem(ModContent.FLIP_DOT_ITEM.get());
            ModContent.FLIP_DOT.get().onSneakWrenched(world.getBlockState(isolated),context);
            check(viewer.getInventory().countItem(ModContent.FLIP_DOT_ITEM.get())==before+1,"sneak-wrench returns the block to survival inventory");
            viewer.setGameMode(GameType.CREATIVE);
            var clipboard=AllBlocks.CLIPBOARD.asStack();
            clipboard.set(com.simibubi.create.AllDataComponents.CLIPBOARD_CONTENT,com.simibubi.create.content.equipment.clipboard.ClipboardContent.EMPTY.setPages(List.of(List.of(
                new com.simibubi.create.content.equipment.clipboard.ClipboardEntry(false,Component.literal("IRON\n240"))))));
            viewer.setItemInHand(InteractionHand.MAIN_HAND,clipboard);
            var hit=new BlockHitResult(Vec3.atCenterOf(origin.offset(3,1,0)),Direction.NORTH,origin.offset(3,1,0),false);
            viewer.gameMode.useItemOn(viewer,world,clipboard,InteractionHand.MAIN_HAND,hit);
            check(tile(3,1).pendingBits()==DotMatrix.tile("IRON",0,32) && tile(3,0).pendingBits()==DotMatrix.tile("240",0,32),"Create Clipboard writes multiple rows through normal interaction");
            clipboard.set(com.simibubi.create.AllDataComponents.CLIPBOARD_CONTENT,com.simibubi.create.content.equipment.clipboard.ClipboardContent.EMPTY.setPages(List.of(List.of(
                new com.simibubi.create.content.equipment.clipboard.ClipboardEntry(false,Component.literal("[pixels]\n#.......#\n.#"))))));
            viewer.gameMode.useItemOn(viewer,world,clipboard,InteractionHand.MAIN_HAND,hit);
            check(tile(3,1).pendingBits()==513 && tile(2,1).pendingBits()==1,"clipboard pixel drawing crosses the tile seam");
            long pending=tile(3,1).pendingBits();tile(3,1).lazyTick();
            check(tile(3,1).pendingBits()==pending,"bitmap input survives assembly refresh");
            check(!tile(3,1).submitFrame(new long[1],1,1),"incorrect frame dimensions are rejected atomically");
            long[] frames=new long[24];Arrays.fill(frames,0,8,1);Arrays.fill(frames,8,16,2);Arrays.fill(frames,16,24,4);
            speed(0);held=tile(3,1).displayedBits();tile(3,1).assembly().controller().installMedia(new com.zmm2025.createdisplayworks.media.MediaClip(4,2,frames));
            var mediaSaved=tile(3,1).saveWithoutMetadata(world.registryAccess());var mediaRestored=new FlipDotBlockEntity(tile(3,1).getBlockPos(),tile(3,1).getBlockState());mediaRestored.loadWithComponents(mediaSaved,world.registryAccess());
            check(mediaRestored.mediaFrameCount()==3,"imported media survives world-save serialization");stage++;
        } else if(stage==9 && elapsed>=510) {
            check(tile(3,1).displayedBits()==held,"imported video holds its image without rotation");speed(256);stage++;
        } else if(stage==10 && elapsed>=530) {
            check(tile(3,1).displayedBits()!=held,"imported video advances when the real cog drive resumes");
            tile(3,1).mediaControl("pause");held=tile(3,1).displayedBits();stage++;
        } else if(stage==11 && elapsed>=550) {
            check(tile(3,1).displayedBits()==held,"pause holds an imported frame while the cogs still turn");
            speed(32);long[] frames=new long[16];Arrays.fill(frames,0,8,-1L);tile(3,1).installMedia(new com.zmm2025.createdisplayworks.media.MediaClip(4,2,frames));stage++;
        } else if(stage==12 && elapsed>=552) {
            speed(0);held=tile(3,1).displayedBits();stage++;
        } else if(stage==13 && elapsed>=650) {
            check(tile(3,1).displayedBits()==held && tile(3,1).movingDiscs(0)==0,"power loss finishes the current flip then holds");
            text("NEW");check(tile(3,1).mediaFrameCount()==0,"Display Link / text updates replace video playback");
            var drops=Block.getDrops(tile(3,1).getBlockState(),world,tile(3,1).getBlockPos(),tile(3,1));
            check(drops.size()==1 && drops.getFirst().getComponentsPatch().isEmpty() && drops.getFirst().getMaxStackSize()==64,"drops are ordinary stackable blocks without saved colors or content");
            viewer.sendSystemMessage(Component.literal("Flip-dot checks complete: "+failures.size()+" failures."));started=-1;verificationComplete=failures.isEmpty();
            if(Boolean.getBoolean("flipdot.verify")&&System.getProperty("flipdot.showcase","").equals("audit")){
                if(!failures.isEmpty())throw new IllegalStateException("Flip-dot regression failures: "+failures);
                System.out.println("FLIP_DOT_GAMEPLAY_CHECK PASS block behavior, cogs, dye, text, media and persistence");
                world.getServer().halt(false);
            }
        }
    }
    @EventBusSubscriber(modid=CreateDisplayWorks.MOD_ID,value=net.neoforged.api.distmarker.Dist.CLIENT)
    public static final class ClientExit {
        @SubscribeEvent public static void tick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event){
            if(verificationComplete&&System.getProperty("flipdot.showcase","").equals("audit"))
                net.minecraft.client.Minecraft.getInstance().stop();
        }
    }
}
