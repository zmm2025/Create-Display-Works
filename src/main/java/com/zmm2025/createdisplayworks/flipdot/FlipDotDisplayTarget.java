package com.zmm2025.createdisplayworks.flipdot;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import com.simibubi.create.api.behaviour.display.DisplayTarget;
import com.simibubi.create.content.redstone.displayLink.DisplayLinkContext;
import com.simibubi.create.content.redstone.displayLink.target.DisplayTargetStats;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.phys.AABB;

public class FlipDotDisplayTarget extends DisplayTarget {
    private static final Set<TextColor> UNFILLED = Set.of(TextColor.fromLegacyFormat(ChatFormatting.DARK_RED), TextColor.fromLegacyFormat(ChatFormatting.DARK_GRAY));
    @Override public void acceptText(int line, List<MutableComponent> text, DisplayLinkContext context) {
        FlipDotAssembly group = FlipDotAssembly.find(context.level(), context.getTargetPos());
        if (group == null) return;
        // Line breaks inside a line continue on the rows below, so they don't show up as unknown characters.
        List<String> lines = text.stream().flatMap(component -> Arrays.stream(plain(component).split("\\R", -1))).toList();
        for (int i = 0; i < lines.size() && line + i < group.height(); i++) {
            if (i > 0 && isReserved(line + i, group.controller(), context)) break;
            if (i == 0) reserve(line, group.controller(), context);
            group.controller().setLine(group, line + i, lines.get(i));
        }
    }
    /**
     * The component's text. Create's boiler gauges mark how much of a bar is unfilled only with color, which a board
     * can't show, so those parts draw as dots along the baseline.
     */
    private static String plain(MutableComponent text) {
        var out = new StringBuilder();
        text.visit((style, part) -> {
            out.append(style.getColor() != null && UNFILLED.contains(style.getColor()) && part.chars().allMatch(c -> c == '|') ? part.replace('|', '¦') : part);
            return Optional.empty();
        }, Style.EMPTY);
        return out.toString();
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
