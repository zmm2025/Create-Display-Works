package com.zmm2025.createdisplayworks.flipdot;

import java.util.List;
import com.simibubi.create.api.behaviour.display.DisplayTarget;
import com.simibubi.create.content.redstone.displayLink.DisplayLinkContext;
import com.simibubi.create.content.redstone.displayLink.target.DisplayTargetStats;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.phys.AABB;

public class FlipDotDisplayTarget extends DisplayTarget {
    @Override public void acceptText(int line, List<MutableComponent> text, DisplayLinkContext context) {
        FlipDotAssembly group = FlipDotAssembly.find(context.level(), context.getTargetPos());
        if (group == null) return;
        for (int i = 0; i < text.size() && line + i < group.height(); i++) {
            if (i > 0 && isReserved(line + i, group.controller(), context)) break;
            if (i == 0) reserve(line, group.controller(), context);
            group.controller().setLine(group, line + i, text.get(i).getString());
        }
    }
    @Override public DisplayTargetStats provideStats(DisplayLinkContext context) {
        FlipDotAssembly group = FlipDotAssembly.find(context.level(), context.getTargetPos());
        return new DisplayTargetStats(group == null ? 1 : group.height(), group == null ? 1 : Math.max(1, group.width() * 8 / 6), this);
    }
    @Override public AABB getMultiblockBounds(LevelAccessor level, BlockPos pos) {
        FlipDotAssembly group = FlipDotAssembly.find(level, pos);
        AABB result = new AABB(pos);
        if (group != null) for (FlipDotBlockEntity tile : group.tiles()) result = result.minmax(new AABB(tile.getBlockPos()));
        return result;
    }
}
