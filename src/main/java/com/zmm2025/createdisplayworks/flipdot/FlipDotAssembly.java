package com.zmm2025.createdisplayworks.flipdot;

import java.util.*;
import net.minecraft.core.*;
import net.minecraft.world.level.LevelAccessor;

/** A bounded, loaded, coplanar connected component. Missing cells remain empty. */
public record FlipDotAssembly(List<FlipDotBlockEntity> tiles, FlipDotBlockEntity controller, Direction right,
        int left, int top, int width, int height) {
    public static final int MAX_TILES = 256;
    public static final int MAX_SPAN = 32;
    public static FlipDotAssembly find(LevelAccessor level, BlockPos start) {
        if (!(level.getBlockEntity(start) instanceof FlipDotBlockEntity first)) return null;
        Direction facing = first.getBlockState().getValue(FlipDotBlock.HORIZONTAL_FACING);
        Direction right = facing.getCounterClockWise();
        List<FlipDotBlockEntity> tiles = new ArrayList<>();
        Set<BlockPos> seen = new HashSet<>(); ArrayDeque<BlockPos> queue = new ArrayDeque<>(); queue.add(start);
        while (!queue.isEmpty()) {
            BlockPos pos = queue.removeFirst();
            if (!seen.add(pos) || !level.hasChunkAt(pos)) continue;
            if (!(level.getBlockEntity(pos) instanceof FlipDotBlockEntity be) || be.getBlockState().getValue(FlipDotBlock.HORIZONTAL_FACING) != facing) continue;
            tiles.add(be);
            // Oversized arrays do not partially update or elect conflicting controllers.
            if (tiles.size() > MAX_TILES) return null;
            for (Direction d : new Direction[]{right, right.getOpposite(), Direction.UP, Direction.DOWN}) queue.add(pos.relative(d));
        }
        int left = tiles.stream().mapToInt(be -> coordinate(be.getBlockPos(), right)).min().orElse(0);
        int max = tiles.stream().mapToInt(be -> coordinate(be.getBlockPos(), right)).max().orElse(0);
        int top = tiles.stream().mapToInt(be -> be.getBlockPos().getY()).max().orElse(0);
        int bottom = tiles.stream().mapToInt(be -> be.getBlockPos().getY()).min().orElse(0);
        if (max - left >= MAX_SPAN || top - bottom >= MAX_SPAN) return null;
        FlipDotBlockEntity controller = tiles.stream().min(Comparator.<FlipDotBlockEntity>comparingInt(be -> -be.getBlockPos().getY())
            .thenComparingInt(be -> coordinate(be.getBlockPos(), right))).orElseThrow();
        return new FlipDotAssembly(List.copyOf(tiles), controller, right, left, top, max - left + 1, top - bottom + 1);
    }
    private static int coordinate(BlockPos pos, Direction direction) { return pos.getX() * direction.getStepX() + pos.getZ() * direction.getStepZ(); }
    public int column(BlockPos pos) { return coordinate(pos, right) - left; }
    public int row(BlockPos pos) { return top - pos.getY(); }
}
