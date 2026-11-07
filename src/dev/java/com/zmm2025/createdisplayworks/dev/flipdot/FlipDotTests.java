package com.zmm2025.createdisplayworks.dev.flipdot;

import static com.zmm2025.createdisplayworks.CreateDisplayWorks.MOD_ID;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllDataComponents;
import com.simibubi.create.AllDisplaySources;
import com.simibubi.create.AllItems;
import com.simibubi.create.api.behaviour.display.DisplayTarget;
import com.simibubi.create.api.stress.BlockStressValues;
import com.simibubi.create.content.equipment.clipboard.ClipboardContent;
import com.simibubi.create.content.equipment.clipboard.ClipboardEntry;
import com.simibubi.create.content.redstone.displayLink.DisplayLinkBlock;
import com.simibubi.create.content.redstone.displayLink.DisplayLinkBlockEntity;
import com.zmm2025.createdisplayworks.ModBlocks;
import com.zmm2025.createdisplayworks.ModDisplayTargets;
import com.zmm2025.createdisplayworks.dev.gametest.DevGameTests;
import com.zmm2025.createdisplayworks.dev.gametest.GameTestKit;
import com.zmm2025.createdisplayworks.flipdot.DotMatrix;
import com.zmm2025.createdisplayworks.flipdot.FlipDotBlock;
import com.zmm2025.createdisplayworks.flipdot.FlipDotBlockEntity;
import com.zmm2025.createdisplayworks.media.MediaClip;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * GameTests for the Flip-Dot Display: boards, power, dyes, text, Display Links, media and saving. Registered in
 * {@link DevGameTests}.
 */
@PrefixGameTestTemplate(false)
public final class FlipDotTests {
    private FlipDotTests() {}

    @GameTest(templateNamespace = MOD_ID, template = GameTestKit.EMPTY, timeoutTicks = 400)
    public static void tilesFormOneBoardAndFlipTogether(GameTestHelper helper) {
        var board = Board.build(helper, 4, 2, 32);
        board.text(0, "32RPM");
        long[] changedAt = {0};
        helper.startSequence()
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(board.tile(0, 1).assembly().tiles().size(), 8, "tiles in the board");
                    board.assertSpeed(0, 0, 32);
                    board.assertSpeed(3, 1, 32);
                    // The text runs across all four tiles of its row.
                    for (int x = 0; x < 4; x++) board.assertShows(x, 0, DotMatrix.tile("32RPM", 3 - x, 32));
                })
                .thenExecute(() -> {
                    changedAt[0] = helper.getLevel().getGameTime();
                    board.text(1, "64");
                    board.text(0, "FLIP");
                })
                .thenWaitUntil(() -> board.forEachTile(tile -> helper.assertValueEqual(
                        tile.displayedBits(), tile.pendingBits(), "shown dots on the changed board")))
                .thenExecute(() -> {
                    Set<Long> starts = new HashSet<>();
                    board.forEachTile(tile -> {
                        if (tile.flipStarted() <= changedAt[0]) return;
                        starts.add(tile.flipStarted());
                        helper.assertValueEqual(tile.flipDuration(), 8, "flip duration at 32 RPM");
                    });
                    helper.assertValueEqual(starts.size(), 1, "different ticks the changed tiles started flipping on");
                    board.speed(64);
                })
                .thenWaitUntil(() -> board.assertSpeed(3, 1, 64))
                .thenExecute(() -> board.text(1, "FAST"))
                .thenWaitUntil(() -> {
                    board.assertShows(3, 1, DotMatrix.tile("FAST", 0, 32));
                    helper.assertValueEqual(board.tile(3, 1).flipDuration(), 4, "flip duration at 64 RPM");
                })
                .thenSucceed();
    }

    @GameTest(templateNamespace = MOD_ID, template = GameTestKit.EMPTY)
    public static void dyesRecolorTheWholeBoard(GameTestHelper helper) {
        var board = Board.build(helper, 4, 2, 32);
        var tile = board.tile(3, 1);
        var player = GameTestKit.player(helper, GameType.SURVIVAL, new BlockPos(2, 0, 0));
        var red = new ItemStack(Items.RED_DYE, 2);
        GameTestKit.use(helper, player, red, board.pos(3, 1), Direction.NORTH);
        helper.assertValueEqual(red.getCount(), 1, "red dye left after dyeing in survival");
        helper.assertValueEqual(tile.discColor(), rgb(DyeColor.RED), "disc color after red dye");
        // The back is black, so black dye swaps the two faces.
        tile.setDye(DyeColor.BLACK);
        helper.assertValueEqual(tile.discColor(), rgb(DyeColor.BLACK), "disc color after black dye");
        helper.assertValueEqual(tile.backColor(), rgb(DyeColor.RED), "back color after black dye");
        for (DyeColor dye : DyeColor.values()) {
            int front = tile.discColor(), back = tile.backColor(), color = rgb(dye);
            tile.setDye(dye);
            helper.assertValueEqual(
                    tile.discColor(),
                    color == front || color == back ? back : color,
                    "disc color after " + dye.getName() + " dye");
            helper.assertValueEqual(board.tile(0, 0).discColor(), tile.discColor(), "disc color across the board");
            helper.assertValueEqual(board.tile(0, 0).backColor(), tile.backColor(), "back color across the board");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MOD_ID, template = GameTestKit.EMPTY, timeoutTicks = 400)
    public static void unpoweredBoardsHoldTheirImage(GameTestHelper helper) {
        var board = Board.build(helper, 4, 2, 32);
        board.text(1, "LIFE");
        long[] held = {0};
        helper.startSequence()
                .thenWaitUntil(() -> board.assertShows(3, 1, DotMatrix.tile("LIFE", 0, 32)))
                .thenExecute(() -> board.speed(0))
                .thenWaitUntil(() -> board.assertSpeed(3, 1, 0))
                .thenExecute(() -> {
                    held[0] = board.tile(3, 1).displayedBits();
                    board.text(1, "STOP");
                })
                .thenExecuteAfter(40, () -> {
                    helper.assertValueEqual(board.tile(3, 1).displayedBits(), held[0], "shown dots while unpowered");
                    helper.assertValueEqual(
                            board.tile(3, 1).pendingBits(),
                            DotMatrix.tile("STOP", 0, 32),
                            "queued dots while unpowered");
                    board.speed(32);
                })
                .thenWaitUntil(() -> board.assertShows(3, 1, DotMatrix.tile("STOP", 0, 32)))
                .thenSucceed();
    }

    @GameTest(templateNamespace = MOD_ID, template = GameTestKit.EMPTY, timeoutTicks = 400)
    public static void savedBoardsKeepTheirDotsAndMedia(GameTestHelper helper) {
        var board = Board.build(helper, 4, 2, 32);
        board.text(1, "SAVE");
        helper.startSequence()
                .thenWaitUntil(() -> board.assertShows(3, 1, DotMatrix.tile("SAVE", 0, 32)))
                .thenExecute(() -> board.speed(0))
                .thenWaitUntil(() -> board.assertSpeed(3, 1, 0))
                .thenExecute(() -> {
                    // Unpowered, so the new text waits: the shown and queued dots differ, and both must load.
                    board.text(1, "LOAD");
                    board.tile(0, 0).setDye(DyeColor.RED);
                    var tile = board.tile(3, 1);
                    var copy = GameTestKit.reload(helper, tile);
                    helper.assertValueEqual(copy.displayedBits(), tile.displayedBits(), "shown dots after loading");
                    helper.assertValueEqual(copy.pendingBits(), tile.pendingBits(), "queued dots after loading");
                    helper.assertValueEqual(copy.discColor(), rgb(DyeColor.RED), "disc color after loading");

                    var controller = tile.assembly().controller();
                    controller.installMedia(new MediaClip(4, 2, threeFrames()));
                    helper.assertValueEqual(
                            GameTestKit.reload(helper, controller).mediaFrameCount(), 3, "media frames after loading");
                })
                .thenSucceed();
    }

    @GameTest(templateNamespace = MOD_ID, template = GameTestKit.EMPTY, timeoutTicks = 400)
    public static void displayLinksWriteRedstonePower(GameTestHelper helper) {
        var board = Board.build(helper, 4, 2, 32);
        BlockPos target = new BlockPos(6, 0, 1), linkPos = target.above();
        helper.setBlock(target, Blocks.TARGET);
        helper.setBlock(
                target.east(),
                Blocks.LEVER
                        .defaultBlockState()
                        .setValue(LeverBlock.FACING, Direction.EAST)
                        .setValue(LeverBlock.FACE, AttachFace.WALL)
                        .setValue(LeverBlock.POWERED, true));
        helper.setBlock(
                linkPos, AllBlocks.DISPLAY_LINK.getDefaultState().setValue(DisplayLinkBlock.FACING, Direction.UP));
        DisplayLinkBlockEntity link = helper.getBlockEntity(linkPos);
        link.target(helper.absolutePos(board.pos(3, 0)));
        link.targetLine = 1;
        link.activeSource = AllDisplaySources.REDSTONE_POWER.get();
        helper.startSequence()
                .thenWaitUntil(() -> board.assertSpeed(3, 0, 32))
                .thenExecute(() -> {
                    link.updateGatheredData();
                    helper.assertTrue(
                            link.activeTarget == ModDisplayTargets.FLIP_DOT_DISPLAY.get(),
                            "The Display Link doesn't target the board");
                })
                .thenWaitUntil(() -> board.assertShows(3, 0, DotMatrix.tile("15", 0, 32)))
                .thenSucceed();
    }

    @GameTest(templateNamespace = MOD_ID, template = GameTestKit.EMPTY, timeoutTicks = 400)
    public static void addingOrRemovingTilesKeepsTheText(GameTestHelper helper) {
        var board = Board.build(helper, 4, 2, 32);
        board.text(1, "STOP");
        helper.startSequence()
                .thenWaitUntil(() -> board.assertShows(3, 1, DotMatrix.tile("STOP", 0, 32)))
                // A new tile on the left becomes the start of the row.
                .thenExecute(() -> helper.setBlock(board.pos(4, 1), Board.display()))
                .thenWaitUntil(() -> board.assertShows(4, 1, DotMatrix.tile("STOP", 0, 40)))
                .thenExecute(() -> helper.setBlock(board.pos(4, 1), Blocks.AIR))
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(board.tile(3, 1).assembly().width(), 4, "board width");
                    board.assertShows(3, 1, DotMatrix.tile("STOP", 0, 32));
                })
                .thenSucceed();
    }

    @GameTest(templateNamespace = MOD_ID, template = GameTestKit.EMPTY)
    public static void nameTagsAndPlacementEditTheBoard(GameTestHelper helper) {
        var board = Board.build(helper, 4, 2, 32);
        var player = GameTestKit.player(helper, GameType.CREATIVE, new BlockPos(2, 0, 0));
        var name = new ItemStack(Items.NAME_TAG);
        name.set(DataComponents.CUSTOM_NAME, Component.literal("RUN"));
        GameTestKit.use(helper, player, name, board.pos(3, 1), Direction.NORTH);
        for (int x = 0; x < 4; x++)
            helper.assertValueEqual(
                    board.tile(x, 1).pendingBits(),
                    DotMatrix.tile("RUN", 3 - x, 32),
                    "queued dots on tile " + x + " after the name tag");
        // A display used near the top of a tile's face goes above it, facing the same way.
        var nearTop = Vec3.atLowerCornerOf(board.pos(3, 1)).add(0.5, 0.95, 0.2);
        GameTestKit.use(
                helper, player, ModBlocks.FLIP_DOT_DISPLAY.asStack(), board.pos(3, 1), Direction.NORTH, nearTop);
        helper.assertBlockPresent(ModBlocks.FLIP_DOT_DISPLAY.get(), board.pos(3, 2));
        helper.assertBlockProperty(board.pos(3, 2), FlipDotBlock.HORIZONTAL_FACING, Direction.NORTH);
        helper.assertValueEqual(
                board.tile(3, 1).pendingBits(), DotMatrix.tile("RUN", 0, 32), "queued dots after placing a tile");
        helper.succeed();
    }

    @GameTest(templateNamespace = MOD_ID, template = GameTestKit.EMPTY)
    public static void clipboardsWriteRowsAndPixels(GameTestHelper helper) {
        var board = Board.build(helper, 4, 2, 32);
        var tile = board.tile(3, 1);
        var player = GameTestKit.player(helper, GameType.CREATIVE, new BlockPos(2, 0, 0));
        GameTestKit.use(helper, player, clipboard("IRON\n240"), board.pos(3, 1), Direction.NORTH);
        helper.assertValueEqual(tile.pendingBits(), DotMatrix.tile("IRON", 0, 32), "top row after the clipboard");
        helper.assertValueEqual(
                board.tile(3, 0).pendingBits(), DotMatrix.tile("240", 0, 32), "bottom row after the clipboard");
        // A [pixels] page draws dots. Its ninth column is the first column of the next tile.
        GameTestKit.use(helper, player, clipboard("[pixels]\n#.......#\n.#"), board.pos(3, 1), Direction.NORTH);
        helper.assertValueEqual(tile.pendingBits(), 1L | 1L << 9, "drawn dots on the first tile");
        helper.assertValueEqual(board.tile(2, 1).pendingBits(), 1L, "drawn dots on the second tile");
        long drawn = tile.pendingBits();
        helper.assertFalse(tile.submitFrame(new long[1], 1, 1), "The board took a frame of the wrong size");
        helper.assertValueEqual(tile.pendingBits(), drawn, "drawn dots after a frame of the wrong size");
        helper.startSequence()
                .thenExecuteAfter(25, () -> {
                    tile.lazyTick();
                    helper.assertValueEqual(tile.pendingBits(), drawn, "drawn dots after the board refreshed");
                })
                .thenSucceed();
    }

    @GameTest(templateNamespace = MOD_ID, template = GameTestKit.EMPTY)
    public static void wrenchesTurnAndPickUpDisplays(GameTestHelper helper) {
        BlockPos pos = new BlockPos(4, 0, 1);
        helper.setBlock(pos, Board.display().setValue(BlockStateProperties.WATERLOGGED, true));
        helper.assertTrue(
                helper.getLevel().getFluidState(helper.absolutePos(pos)).isSource(),
                "The waterlogged display holds no water source");
        var player = GameTestKit.player(helper, GameType.SURVIVAL, new BlockPos(4, 0, 0));
        GameTestKit.use(helper, player, AllItems.WRENCH.asStack(), pos, Direction.UP);
        BlockState turned = helper.getBlockState(pos);
        helper.assertFalse(
                turned.getValue(FlipDotBlock.HORIZONTAL_FACING) == Direction.NORTH, "The wrench didn't turn it");
        helper.assertTrue(turned.getValue(BlockStateProperties.WATERLOGGED), "Turning it lost its water");
        player.setShiftKeyDown(true);
        GameTestKit.use(helper, player, AllItems.WRENCH.asStack(), pos, Direction.UP);
        helper.assertBlockNotPresent(ModBlocks.FLIP_DOT_DISPLAY.get(), pos);
        helper.assertValueEqual(
                player.getInventory().countItem(ModBlocks.FLIP_DOT_DISPLAY.asItem()),
                1,
                "displays a survival player got back from sneak-wrenching");
        // Its water stays behind; don't let it spread into other tests.
        helper.setBlock(pos, Blocks.AIR);
        helper.succeed();
    }

    @GameTest(templateNamespace = MOD_ID, template = GameTestKit.EMPTY, timeoutTicks = 400)
    public static void mediaPlaysOnlyWhileTheBoardTurns(GameTestHelper helper) {
        var board = Board.build(helper, 4, 2, 32);
        board.text(1, "PLAY");
        long[] held = {0};
        helper.startSequence()
                .thenWaitUntil(() -> board.assertShows(3, 1, DotMatrix.tile("PLAY", 0, 32)))
                .thenExecute(() -> board.speed(0))
                .thenWaitUntil(() -> board.assertSpeed(3, 1, 0))
                .thenExecute(() -> {
                    held[0] = board.tile(3, 1).displayedBits();
                    board.controller().installMedia(new MediaClip(4, 2, threeFrames()));
                })
                .thenExecuteAfter(40, () -> {
                    helper.assertValueEqual(board.tile(3, 1).displayedBits(), held[0], "shown dots while unpowered");
                    board.speed(256);
                })
                // Frames 1 and 2 show 2 and 4; frame 0 shows 1.
                .thenWaitUntil(() -> {
                    long shown = board.tile(3, 1).displayedBits();
                    helper.assertTrue(shown == 2 || shown == 4, "The media didn't play past its first frame");
                })
                .thenExecute(() -> {
                    board.controller().mediaControl("pause");
                    held[0] = board.tile(3, 1).displayedBits();
                })
                .thenExecuteAfter(
                        20,
                        () -> helper.assertValueEqual(
                                board.tile(3, 1).displayedBits(), held[0], "shown dots while paused"))
                .thenSucceed();
    }

    @GameTest(templateNamespace = MOD_ID, template = GameTestKit.EMPTY, timeoutTicks = 400)
    public static void powerLossFinishesTheCurrentFlip(GameTestHelper helper) {
        var board = Board.build(helper, 4, 2, 32);
        board.text(1, "LIFE");
        long[] held = {0};
        helper.startSequence()
                .thenWaitUntil(() -> board.assertShows(3, 1, DotMatrix.tile("LIFE", 0, 32)))
                .thenExecute(() -> {
                    // Every dot on, then every dot off, so each frame flips every disc.
                    long[] frames = new long[16];
                    Arrays.fill(frames, 0, 8, -1L);
                    board.controller().installMedia(new MediaClip(4, 2, frames));
                })
                .thenExecuteAfter(2, () -> board.speed(0))
                .thenWaitUntil(() -> board.assertSpeed(3, 1, 0))
                .thenExecute(() -> held[0] = board.tile(3, 1).displayedBits())
                .thenExecuteAfter(40, () -> {
                    var tile = board.tile(3, 1);
                    helper.assertValueEqual(tile.displayedBits(), held[0], "shown dots after power loss");
                    helper.assertValueEqual(tile.movingDiscs(0), 0, "discs still moving after power loss");
                    // Text, as a Display Link or name tag writes it, replaces the media.
                    board.text(1, "NEW");
                    helper.assertValueEqual(board.controller().mediaFrameCount(), 0, "media frames after new text");
                })
                .thenSucceed();
    }

    @GameTest(templateNamespace = MOD_ID, template = GameTestKit.EMPTY)
    public static void displaysDropAsPlainBlocks(GameTestHelper helper) {
        var board = Board.build(helper, 4, 2, 32);
        board.text(1, "DROP");
        board.tile(0, 0).setDye(DyeColor.RED);
        var tile = board.tile(3, 1);
        var drops = Block.getDrops(tile.getBlockState(), helper.getLevel(), tile.getBlockPos(), tile);
        helper.assertValueEqual(drops.size(), 1, "stacks dropped");
        var drop = drops.getFirst();
        helper.assertTrue(drop.is(ModBlocks.FLIP_DOT_DISPLAY.asItem()), "It didn't drop a display");
        helper.assertTrue(drop.getComponentsPatch().isEmpty(), "The dropped display kept its colors or content");
        helper.assertValueEqual(drop.getMaxStackSize(), 64, "stack size of dropped displays");
        helper.succeed();
    }

    /** What the dedicated server must know about the display, from its data and from Create. */
    @GameTest(templateNamespace = MOD_ID, template = GameTestKit.EMPTY)
    public static void serverAndCreateKnowTheDisplay(GameTestHelper helper) {
        helper.assertTrue(
                helper.getLevel()
                        .getRecipeManager()
                        .byKey(ModBlocks.FLIP_DOT_DISPLAY.getId())
                        .isPresent(),
                "The crafting recipe isn't loaded");
        helper.assertTrue(
                DisplayTarget.BY_BLOCK.get(ModBlocks.FLIP_DOT_DISPLAY.get())
                        == ModDisplayTargets.FLIP_DOT_DISPLAY.get(),
                "Display Links don't know the display");
        // Like Create's Display Board, which it matches in speed.
        helper.assertValueEqual(
                BlockStressValues.getImpact(ModBlocks.FLIP_DOT_DISPLAY.get()),
                BlockStressValues.getImpact(AllBlocks.DISPLAY_BOARD.get()),
                "stress impact");
        helper.succeed();
    }

    private static int rgb(DyeColor dye) {
        return dye.getTextureDiffuseColor() & 0xFFFFFF;
    }

    /** Three frames for a 4 by 2 board: every tile shows 1, then 2, then 4. */
    private static long[] threeFrames() {
        long[] frames = new long[24];
        Arrays.fill(frames, 0, 8, 1);
        Arrays.fill(frames, 8, 16, 2);
        Arrays.fill(frames, 16, 24, 4);
        return frames;
    }

    private static ItemStack clipboard(String text) {
        var stack = AllBlocks.CLIPBOARD.asStack();
        stack.set(
                AllDataComponents.CLIPBOARD_CONTENT,
                ClipboardContent.EMPTY.setPages(List.of(List.of(new ClipboardEntry(false, Component.literal(text))))));
        return stack;
    }

    /**
     * A board facing north, turned by a creative motor through a cogwheel at its right end, as the board fixture
     * builds it. Tile {@code (x, y)} is {@code x} tiles left of the right end, seen from the front, and {@code y} up.
     */
    private record Board(GameTestHelper helper, int width, int height) {
        private static final BlockPos MOTOR = new BlockPos(0, 0, 2);

        static Board build(GameTestHelper helper, int width, int height, int rpm) {
            for (int x = 0; x < width; x++)
                for (int y = 0; y < height; y++) helper.setBlock(new BlockPos(1 + x, y, 1), display());
            helper.setBlock(
                    MOTOR.north(),
                    AllBlocks.COGWHEEL.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.Z));
            GameTestKit.creativeMotor(helper, MOTOR, Direction.NORTH, rpm);
            return new Board(helper, width, height);
        }

        static BlockState display() {
            return ModBlocks.FLIP_DOT_DISPLAY
                    .get()
                    .defaultBlockState()
                    .setValue(FlipDotBlock.HORIZONTAL_FACING, Direction.NORTH);
        }

        BlockPos pos(int x, int y) {
            return new BlockPos(1 + x, y, 1);
        }

        FlipDotBlockEntity tile(int x, int y) {
            return helper.getBlockEntity(pos(x, y));
        }

        FlipDotBlockEntity controller() {
            return tile(0, 0).assembly().controller();
        }

        void forEachTile(Consumer<FlipDotBlockEntity> action) {
            for (int x = 0; x < width; x++) for (int y = 0; y < height; y++) action.accept(tile(x, y));
        }

        void speed(int rpm) {
            GameTestKit.setSpeed(helper, MOTOR, rpm);
        }

        /** Writes a row of text, as a name tag on any of its tiles does. */
        void text(int y, String text) {
            tile(0, y).setTextAtBlock(text);
        }

        void assertSpeed(int x, int y, float rpm) {
            helper.assertValueEqual(Math.abs(tile(x, y).getSpeed()), rpm, "speed of tile " + x + ", " + y);
        }

        /** Checks that a tile has finished flipping to {@code dots}. */
        void assertShows(int x, int y, long dots) {
            helper.assertValueEqual(tile(x, y).pendingBits(), dots, "queued dots on tile " + x + ", " + y);
            helper.assertValueEqual(tile(x, y).displayedBits(), dots, "shown dots on tile " + x + ", " + y);
        }
    }
}
