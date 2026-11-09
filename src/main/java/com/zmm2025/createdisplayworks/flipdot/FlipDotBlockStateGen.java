package com.zmm2025.createdisplayworks.flipdot;

import static com.zmm2025.createdisplayworks.flipdot.FlipDotBlock.DOWN;
import static com.zmm2025.createdisplayworks.flipdot.FlipDotBlock.HORIZONTAL_FACING;
import static com.zmm2025.createdisplayworks.flipdot.FlipDotBlock.LEFT;
import static com.zmm2025.createdisplayworks.flipdot.FlipDotBlock.RIGHT;
import static com.zmm2025.createdisplayworks.flipdot.FlipDotBlock.UP;

import com.tterrag.registrate.providers.DataGenContext;
import com.tterrag.registrate.providers.RegistrateBlockstateProvider;
import java.util.List;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.neoforged.neoforge.client.model.generators.MultiPartBlockStateBuilder;

/**
 * The display's connected casing: the body, a border on each side no display continues, and posts where displays
 * meet. The models are hand-made in {@code models/block}; this only says which ones each state shows.
 */
public final class FlipDotBlockStateGen {
    private static final List<Direction> FACINGS =
            List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST);
    private static final List<BooleanProperty> SIDES = List.of(LEFT, RIGHT, UP, DOWN);

    public static void generate(DataGenContext<Block, FlipDotBlock> c, RegistrateBlockstateProvider p) {
        var builder = p.getMultipartBuilder(c.get());
        String name = c.getName();
        for (Direction facing : FACINGS) {
            part(builder, p, name, facing).end();
            for (BooleanProperty side : SIDES)
                part(builder, p, name + "_" + side.getName(), facing)
                        .condition(side, false)
                        .end();
        }
        for (Direction facing : FACINGS) {
            for (BooleanProperty side : SIDES)
                part(builder, p, name + "_posts_" + side.getName(), facing)
                        .condition(side, true)
                        .end();
            for (BooleanProperty vertical : List.of(UP, DOWN))
                for (BooleanProperty horizontal : List.of(LEFT, RIGHT))
                    part(builder, p, name + "_posts_" + vertical.getName() + "_" + horizontal.getName(), facing)
                            .condition(vertical, true)
                            .condition(horizontal, true)
                            .end();
        }
    }

    private static MultiPartBlockStateBuilder.PartBuilder part(
            MultiPartBlockStateBuilder builder, RegistrateBlockstateProvider p, String model, Direction facing) {
        return builder.part()
                .modelFile(p.models().getExistingFile(p.modLoc("block/" + model)))
                .rotationY(((int) facing.toYRot() + 180) % 360)
                .addModel()
                .condition(HORIZONTAL_FACING, facing);
    }
}
