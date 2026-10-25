package com.zmm2025.createdisplayworks.flipdot;

import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.simibubi.create.content.kinetics.base.HorizontalKineticBlock;
import com.simibubi.create.content.kinetics.simpleRelays.ICogWheel;
import com.simibubi.create.foundation.block.IBE;
import com.zmm2025.createdisplayworks.ModContent;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.*;
import net.minecraft.sounds.*;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.equipment.clipboard.ClipboardEntry;
import net.createmod.catnip.placement.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.material.*;
import static net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED;

public class FlipDotBlock extends HorizontalKineticBlock implements IBE<FlipDotBlockEntity>, ICogWheel, IWrenchable, SimpleWaterloggedBlock {
    /** True when a display facing the same way continues the canvas on that side, which hides the border there. Left and right are as seen from the front. */
    public static final BooleanProperty LEFT = BooleanProperty.create("left"), RIGHT = BooleanProperty.create("right"),
        UP = BlockStateProperties.UP, DOWN = BlockStateProperties.DOWN;
    private static final int PLACEMENT = PlacementHelpers.register(new IPlacementHelper() {
        public java.util.function.Predicate<ItemStack> getItemPredicate() { return s -> s.is(ModContent.FLIP_DOT_ITEM.get()); }
        public java.util.function.Predicate<BlockState> getStatePredicate() { return s -> s.is(ModContent.FLIP_DOT.get()); }
        public PlacementOffset getOffset(Player player, Level level, BlockState state, BlockPos pos, BlockHitResult hit) {
            var directions = IPlacementHelper.orderedByDistanceExceptAxis(pos, hit.getLocation(), state.getValue(HORIZONTAL_FACING).getAxis(),
                d -> level.getBlockState(pos.relative(d)).canBeReplaced());
            return directions.isEmpty() ? PlacementOffset.fail() : PlacementOffset.success(pos.relative(directions.getFirst()),
                s -> s.setValue(HORIZONTAL_FACING, state.getValue(HORIZONTAL_FACING)));
        }
    });
    public FlipDotBlock(Properties properties) { super(properties); registerDefaultState(defaultBlockState().setValue(WATERLOGGED, false)
        .setValue(LEFT, false).setValue(RIGHT, false).setValue(UP, false).setValue(DOWN, false)); }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) { super.createBlockStateDefinition(builder.add(WATERLOGGED, LEFT, RIGHT, UP, DOWN)); }
    @Override public FluidState getFluidState(BlockState state) { return state.getValue(WATERLOGGED) ? Fluids.WATER.getSource(false) : super.getFluidState(state); }
    @Override public BlockState updateShape(BlockState state, Direction direction, BlockState neighbor, LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        if (state.getValue(WATERLOGGED)) level.scheduleTick(pos, Fluids.WATER, Fluids.WATER.getTickDelay(level));
        return connect(super.updateShape(state, direction, neighbor, level, pos, neighborPos), level, pos);
    }
    public static BlockState connect(BlockState state, java.util.function.Predicate<Direction> continues) {
        Direction facing = state.getValue(HORIZONTAL_FACING);
        return state.setValue(LEFT, continues.test(facing.getClockWise())).setValue(RIGHT, continues.test(facing.getCounterClockWise()))
            .setValue(UP, continues.test(Direction.UP)).setValue(DOWN, continues.test(Direction.DOWN));
    }
    static BlockState connect(BlockState state, BlockGetter level, BlockPos pos) {
        return connect(state, d -> {
            BlockState neighbor = level.getBlockState(pos.relative(d));
            return neighbor.getBlock() instanceof FlipDotBlock && neighbor.getValue(HORIZONTAL_FACING) == state.getValue(HORIZONTAL_FACING);
        });
    }
    // Placement helpers and wrenching can set the state without asking for its shape, so check the borders once it lands.
    @Override public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean moving) {
        super.onPlace(state, level, pos, oldState, moving);
        BlockState connected = connect(state, level, pos);
        if (connected != state) level.setBlock(pos, connected, Block.UPDATE_ALL);
    }
    @Override public Direction.Axis getRotationAxis(BlockState state) { return state.getValue(HORIZONTAL_FACING).getAxis(); }
    // Matches Create's Display Board.
    @Override public SpeedLevel getMinimumRequiredSpeedLevel() { return SpeedLevel.MEDIUM; }
    @Override public Class<FlipDotBlockEntity> getBlockEntityClass() { return FlipDotBlockEntity.class; }
    @Override public BlockEntityType<? extends FlipDotBlockEntity> getBlockEntityType() { return ModContent.FLIP_DOT_ENTITY.get(); }
    @Override public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return getRotationAxis(state) == Direction.Axis.Z ? box(0, 0, 3, 16, 16, 13) : box(3, 0, 0, 13, 16, 16);
    }
    @Override public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState behind = context.getLevel().getBlockState(context.getClickedPos().relative(context.getClickedFace().getOpposite()));
        BlockState state = behind.is(this) && (context.getPlayer() == null || !context.getPlayer().isShiftKeyDown())
            ? defaultBlockState().setValue(HORIZONTAL_FACING, behind.getValue(HORIZONTAL_FACING)) : super.getStateForPlacement(context);
        return connect(state.setValue(WATERLOGGED, context.getLevel().getFluidState(context.getClickedPos()).getType() == Fluids.WATER),
            context.getLevel(), context.getClickedPos());
    }
    @Override protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
            Player player, InteractionHand hand, BlockHitResult hit) {
        if (player.isShiftKeyDown() || !player.mayBuild()) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        var helper = PlacementHelpers.get(PLACEMENT);
        if (helper.matchesItem(stack)) return helper.getOffset(player, level, state, pos, hit).placeInWorld(level, (BlockItem)stack.getItem(), player, hand, hit);
        FlipDotBlockEntity be = getBlockEntity(level, pos);
        if (be == null) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        DyeColor dye = DyeColor.getColor(stack);
        if (dye != null) {
            if (!level.isClientSide && be.setDye(dye)) {
                level.playSound(null, pos, SoundEvents.DYE_USE, SoundSource.BLOCKS, 1, 1);
                if (!player.isCreative()) stack.shrink(1);
            }
            return ItemInteractionResult.SUCCESS;
        }
        if (stack.is(Items.NAME_TAG) && stack.has(DataComponents.CUSTOM_NAME)) {
            if (!level.isClientSide) be.setTextAtBlock(stack.get(DataComponents.CUSTOM_NAME).getString());
            return ItemInteractionResult.SUCCESS;
        }
        if (AllBlocks.CLIPBOARD.isIn(stack)) {
            if (!level.isClientSide) {
                var group = be.assembly();
                if (group != null) {
                    var lines = new java.util.ArrayList<String>();
                    for (var entry : ClipboardEntry.getLastViewedEntries(stack))
                        java.util.Collections.addAll(lines, entry.text.getString().split("\\n", -1));
                    if (!lines.isEmpty() && lines.getFirst().equals("[pixels]")) {
                        long[] pixels = PixelPattern.parse(lines.subList(1, lines.size()), group.width(), group.height());
                        if (pixels == null) player.displayClientMessage(net.minecraft.network.chat.Component.translatable("message.create_display_works.invalid_pixels"), true);
                        else be.submitFrame(pixels, group.width(), group.height());
                        return ItemInteractionResult.SUCCESS;
                    }
                    int row = group.row(pos);
                    for (String line : lines) group.controller().setLine(group, row++, line);
                }
            }
            return ItemInteractionResult.SUCCESS;
        }
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }
    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!player.mayBuild() || player.isShiftKeyDown() || !player.getMainHandItem().isEmpty()) return InteractionResult.PASS;
        if (!level.isClientSide && player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) withBlockEntityDo(level, pos, be -> {
            var group=be.assembly();
            if(group!=null && group.width()*group.height()>com.zmm2025.createdisplayworks.media.MediaClip.MAX_TILES){
                player.displayClientMessage(net.minecraft.network.chat.Component.translatable("message.create_display_works.media_canvas_limit"),true);return;
            }
            if(group!=null) net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(serverPlayer,
                new com.zmm2025.createdisplayworks.media.MediaNetwork.Open(pos,group.width(),group.height(),group.controller().mediaFilename(),group.controller().mediaThumbnail(),group.controller().mediaFrameCount(),group.controller().mediaPlaybackEnabled(),group.controller().mediaLoops()));
        });
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}
