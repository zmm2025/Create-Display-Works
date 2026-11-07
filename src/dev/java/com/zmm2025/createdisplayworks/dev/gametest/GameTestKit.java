package com.zmm2025.createdisplayworks.dev.gametest;

import com.mojang.authlib.GameProfile;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;

/**
 * What every block's GameTests share: the test structure, kinetic power, players and saving. Positions are relative to
 * the test's structure, as {@link GameTestHelper} takes them. See {@link DevGameTests} for how to add tests.
 */
public final class GameTestKit {
    /**
     * An empty structure, 9 blocks along X, 4 high and 4 along Z, in which tests build what they need. It lives in
     * {@code src/dev/resources/data/create_display_works/structure/gametest/empty.nbt}; a test that needs more room
     * can use a larger empty structure next to it.
     */
    public static final String EMPTY = "gametest/empty";

    private GameTestKit() {}

    /** Places a creative motor with its shaft facing {@code facing}, turning at {@code rpm}. */
    public static void creativeMotor(GameTestHelper helper, BlockPos pos, Direction facing, int rpm) {
        helper.setBlock(pos, AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(CreativeMotorBlock.FACING, facing));
        setSpeed(helper, pos, rpm);
    }

    /** Changes a creative motor's speed, as scrolling its value does. Create ignores data edits on a running network. */
    public static void setSpeed(GameTestHelper helper, BlockPos motor, int rpm) {
        CreativeMotorBlockEntity be = helper.getBlockEntity(motor);
        be.generatedSpeed.setValue(rpm);
        be.updateGeneratedRotation();
    }

    /** A server player in {@code mode}, standing at {@code pos}. It isn't in the player list, so no client sees it. */
    public static ServerPlayer player(GameTestHelper helper, GameType mode, BlockPos pos) {
        var player = new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), "test-player"));
        player.setGameMode(mode);
        Vec3 at = helper.absoluteVec(Vec3.atBottomCenterOf(pos));
        player.moveTo(at.x, at.y, at.z, 0, 0);
        return player;
    }

    /** Right-clicks the middle of a block with {@code stack} in the main hand. */
    public static InteractionResult use(
            GameTestHelper helper, ServerPlayer player, ItemStack stack, BlockPos pos, Direction face) {
        return use(helper, player, stack, pos, face, Vec3.atCenterOf(pos));
    }

    /**
     * Right-clicks a block with {@code stack} in the main hand, at {@code hit}, through the same server code a player's
     * click runs: events, the block's own handling, then the item's.
     */
    public static InteractionResult use(
            GameTestHelper helper, ServerPlayer player, ItemStack stack, BlockPos pos, Direction face, Vec3 hit) {
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        var result = new BlockHitResult(helper.absoluteVec(hit), face, helper.absolutePos(pos), false);
        return player.gameMode.useItemOn(player, helper.getLevel(), stack, InteractionHand.MAIN_HAND, result);
    }

    /** Saves a block entity the way a world save does and loads that into a new one, which isn't in the world. */
    @SuppressWarnings("unchecked")
    public static <T extends BlockEntity> T reload(GameTestHelper helper, T be) {
        var registries = helper.getLevel().registryAccess();
        var copy = BlockEntity.loadStatic(
                be.getBlockPos(), be.getBlockState(), be.saveWithFullMetadata(registries), registries);
        helper.assertTrue(copy != null && copy.getClass() == be.getClass(), "Saved block entity didn't load");
        return (T) copy;
    }
}
