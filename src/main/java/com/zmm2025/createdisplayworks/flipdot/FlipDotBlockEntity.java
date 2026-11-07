package com.zmm2025.createdisplayworks.flipdot;

import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.zmm2025.createdisplayworks.media.MediaClip;
import com.zmm2025.createdisplayworks.media.MediaDisplay;
import com.zmm2025.createdisplayworks.media.MediaPlayback;
import java.util.*;
import net.createmod.catnip.animation.AnimationTickHolder;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

public class FlipDotBlockEntity extends KineticBlockEntity implements MediaDisplay {
    private static final int BROADCAST_DELAY = 1;
    private boolean clientStartPending;
    /** Client boards whose flip starts on the next frame. */
    private static final Set<FlipDotBlockEntity> PENDING_STARTS = Collections.newSetFromMap(new WeakHashMap<>());

    private double clientLag;
    private boolean soundController;
    private long displayed, pending, flipStarted;
    private int duration = 1;
    private DyeColor dye = DyeColor.WHITE, backDye = DyeColor.BLACK;
    private boolean paletteSet;
    private final DiscMotion motion = new DiscMotion();
    private boolean restoreClock;
    private long lastTick = Long.MIN_VALUE;
    private boolean wasPowered;
    private int boardWidth, boardHeight;
    /** This tile's {@link BoardCells cell} on its board when it last formed. */
    private int boardCell = BoardCells.NONE;
    /** On the controller: every cell of its board when it last formed, sorted. */
    private int[] memberCells = new int[0];

    private String rowText = "";
    private boolean hasText, bitmapMode;
    private FlipDotAssembly assembly;
    private long lastAssemblyScan = Long.MIN_VALUE / 2;
    /** Only the controller's plays. It shows its frames on the board the controller found last. */
    private final MediaPlayback playback = new MediaPlayback(this, frame -> {
        if (assembly != null) queueMediaFrame(assembly, frame);
    });

    public FlipDotBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
        setLazyTickRate(20);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {}

    public FlipDotAssembly assembly() {
        if (level == null) return null;
        // Wait until the whole board as it last formed is loaded, so half of it doesn't look like a smaller board.
        if (boardCell != BoardCells.NONE) {
            Direction right = right();
            BlockPos controllerPos = FlipDotAssembly.offset(worldPosition, right, BoardCells.inverse(boardCell));
            if (!level.hasChunkAt(controllerPos)) return null;
            if (level.getBlockEntity(controllerPos) instanceof FlipDotBlockEntity controller)
                for (int member : controller.memberCells)
                    if (!level.hasChunkAt(FlipDotAssembly.offset(controllerPos, right, member))) return null;
        }
        return FlipDotAssembly.find(level, worldPosition);
    }

    private Direction right() {
        return FlipDotAssembly.right(getBlockState().getValue(FlipDotBlock.HORIZONTAL_FACING));
    }

    @Override
    public void lazyTick() {
        super.lazyTick();
        if (level == null || level.isClientSide) return;
        if (assembly != null
                && !assembly.controller().isRemoved()
                && level.getGameTime() - assembly.controller().lastAssemblyScan < 20) return;
        FlipDotAssembly found = assembly();
        if (found == null) {
            assembly = null;
            return;
        }
        // Existing panels donate their palette to new panels. When two boards join,
        // the uppermost, then leftmost established panel wins deterministically.
        var donor = found.tiles().stream()
                .filter(t -> t.paletteSet)
                .min(Comparator.<FlipDotBlockEntity>comparingInt(t -> found.row(t.worldPosition))
                        .thenComparingInt(t -> found.column(t.worldPosition)))
                .orElse(found.controller());
        for (FlipDotBlockEntity tile : found.tiles()) {
            tile.assembly = found;
            boolean controllerFlag = tile == found.controller();
            if (tile.soundController != controllerFlag) {
                tile.soundController = controllerFlag;
                tile.sendData();
            }
            tile.applyPalette(donor.backDye, donor.dye, donor.paletteSet);
        }
        var controller = found.controller();
        int[] cells = found.tiles().stream()
                .mapToInt(t -> found.cell(t.worldPosition))
                .toArray();
        clearIfTurned(found.tiles(), cells);
        for (FlipDotBlockEntity tile : found.tiles())
            if (tile != controller && tile.playback.hasMedia()) tile.playback.clear();
        int[] members = cells.clone();
        Arrays.sort(members);
        if (controller.memberCells.length > 0 && !Arrays.equals(controller.memberCells, members))
            controller.playback.clear();
        controller.memberCells = members;
        for (int i = 0; i < cells.length; i++) {
            var tile = found.tiles().get(i);
            tile.boardCell = cells[i];
            if (tile != controller) tile.memberCells = new int[0];
        }
        if (controller.boardWidth != 0
                && (controller.boardWidth != found.width() || controller.boardHeight != found.height()))
            controller.playback.clear();
        controller.boardWidth = found.width();
        controller.boardHeight = found.height();
        found.controller().lastAssemblyScan = level.getGameTime();
        found.controller().rasterize(found);
    }
    /**
     * A board that was turned or flipped, for example by a bearing or a mirrored paste, clears, like Create's display
     * boards. Turning about the vertical axis keeps every tile's cell, so that doesn't count.
     */
    private static void clearIfTurned(List<FlipDotBlockEntity> tiles, int[] cells) {
        int[] previous = tiles.stream().mapToInt(t -> t.boardCell).toArray();
        for (int i = 0; i < previous.length; i++)
            if (previous[i] == BoardCells.ORIGIN && BoardCells.turned(previous, cells, tiles.get(i).memberCells)) {
                for (var tile : tiles) {
                    tile.playback.clear();
                    tile.hasText = false;
                    tile.rowText = "";
                    tile.bitmapMode = false;
                    tile.setChanged();
                }
                return;
            }
    }

    private void rasterize(FlipDotAssembly group) {
        String[] lines = new String[group.height()];
        int[] firstColumn = new int[group.height()];
        Arrays.fill(firstColumn, Integer.MAX_VALUE);
        Arrays.fill(lines, "");
        for (FlipDotBlockEntity tile : group.tiles()) {
            int row = group.row(tile.worldPosition), col = group.column(tile.worldPosition);
            if (tile.hasText && col < firstColumn[row]) {
                firstColumn[row] = col;
                lines[row] = tile.rowText;
            }
        }
        int[][] glyphs = new int[group.height()][];
        for (FlipDotBlockEntity tile : group.tiles()) {
            int row = group.row(tile.worldPosition);
            if (tile.bitmapMode) continue;
            if (firstColumn[row] != Integer.MAX_VALUE && (!tile.hasText || !tile.rowText.equals(lines[row]))) {
                tile.hasText = true;
                tile.rowText = lines[row];
                tile.setChanged();
            }
            if (glyphs[row] == null) glyphs[row] = DotMatrix.glyphs(lines[row]);
            long bits = DotMatrix.tile(glyphs[row], group.column(tile.worldPosition), group.width() * 8);
            if (tile.pending != bits) {
                tile.pending = bits;
                tile.setChanged();
            }
        }
    }

    public void setTextAtBlock(String text) {
        FlipDotAssembly group = assembly();
        if (group != null) group.controller().setLine(group, group.row(worldPosition), text);
    }

    public void setLine(FlipDotAssembly group, int row, String text) {
        if (row < 0 || row >= group.height()) return;
        group.controller().playback.clear();
        String bounded = text.codePoints()
                .limit(256)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString();
        for (FlipDotBlockEntity tile : group.tiles()) {
            tile.assembly = group;
            if (group.row(tile.worldPosition) != row) continue;
            tile.rowText = bounded;
            tile.hasText = true;
            tile.bitmapMode = false;
            tile.setChanged();
        }
        group.controller().rasterize(group);
    }
    /** Queues one row-major 64-bit mask per tile, using the same powered, atomic refresh as text. */
    public boolean submitFrame(long[] masks, int columns, int rows) {
        if (level == null || level.isClientSide) return false;
        FlipDotAssembly group = assembly();
        if (group == null || columns != group.width() || rows != group.height() || masks.length != columns * rows)
            return false;
        group.controller().playback.clear();
        for (FlipDotBlockEntity tile : group.tiles()) {
            tile.assembly = group;
            tile.bitmapMode = true;
            tile.hasText = false;
            tile.rowText = "";
            tile.pending = masks[group.row(tile.worldPosition) * columns + group.column(tile.worldPosition)];
            tile.setChanged();
        }
        return true;
    }

    @Override
    public Canvas mediaCanvas() {
        var group = assembly();
        return group == null ? null : mediaCanvas(group);
    }

    Canvas mediaCanvas(FlipDotAssembly group) {
        return new Canvas(group.width(), group.height(), group.controller().playback);
    }

    @Override
    public MediaPlayback mediaPlayback() {
        return playback;
    }

    @Override
    public void installMedia(MediaClip clip, byte[] archive) {
        var group = assembly();
        if (group == null || group.width() != clip.columns() || group.height() != clip.rows()) return;
        for (var tile : group.tiles()) {
            tile.playback.clear();
            tile.assembly = group;
        }
        group.controller().playback.install(clip, archive);
    }

    @Override
    public void clearDisplay() {
        var group = assembly();
        if (group == null) return;
        group.controller().playback.clear();
        for (int row = 0; row < group.height(); row++) group.controller().setLine(group, row, "");
    }

    @Override
    public int litColor() {
        return discColor();
    }

    @Override
    public int unlitColor() {
        return backColor();
    }

    @Override
    public void setLevel(Level level) {
        super.setLevel(level);
        playback.claim();
    }

    @Override
    public void destroy() {
        super.destroy();
        playback.release();
    }

    private void queueMediaFrame(FlipDotAssembly group, long[] frame) {
        for (var tile : group.tiles()) {
            tile.pending = frame[group.row(tile.worldPosition) * group.width() + group.column(tile.worldPosition)];
            tile.bitmapMode = true;
            tile.hasText = false;
            tile.rowText = "";
            tile.setChanged();
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (level == null) return;
        long now = clock();
        if (restoreClock) {
            motion.restore(motion.snapshot(0), displayed, motion.speed(), now);
            restoreClock = false;
        }
        if (level.isClientSide) {
            // Only clients run this, so dedicated servers never load the sound classes DiscRattle uses.
            if (soundController) DiscRattle.update(this);
            return;
        }
        // Tick gaps are unloaded time, not animation time.
        if (lastTick != Long.MIN_VALUE && now - lastTick > 1) {
            motion.restore(motion.snapshot(lastTick), displayed, motion.speed(), now);
            sendData();
            assembly = null;
        }
        lastTick = now;
        if (assembly == null || assembly.controller() != this) return;
        float rpm = Float.MAX_VALUE;
        boolean powered = true;
        for (var tile : assembly.tiles()) {
            if (tile.isRemoved()
                    || !level.hasChunkAt(tile.worldPosition)
                    || level.getBlockEntity(tile.worldPosition) != tile) {
                assembly = null;
                return;
            }
            rpm = Math.min(rpm, Math.abs(tile.getSpeed()));
            powered &= tile.isSpeedRequirementFulfilled();
        }
        if (!powered) {
            wasPowered = false;
            playback.hold();
            return;
        }
        // If power returns before the old flip settles, finish it before advancing video.
        boolean settling = !wasPowered && assembly.tiles().stream().anyMatch(t -> t.motion.moving(now) > 0);
        if (settling) return;
        wasPowered = true;
        if (!playback.tick(assembly.width(), assembly.height(), assembly.tiles().size())) return;
        for (var tile : assembly.tiles()) {
            if (tile.displayed == tile.pending && tile.motion.speed() == rpm) continue;
            tile.displayed = tile.pending;
            tile.flipStarted = now;
            tile.duration = (int) Math.ceil(DiscMotion.durationTicks(rpm));
            tile.motion.retarget(tile.displayed, rpm, now);
            tile.setChanged();
            tile.sendData();
        }
    }

    private void applyPalette(DyeColor back, DyeColor front, boolean established) {
        if (backDye == back && dye == front && paletteSet == established) return;
        backDye = back;
        dye = front;
        paletteSet = established;
        setChanged();
        sendData();
    }

    public boolean setDye(DyeColor value) {
        var group = assembly();
        if (group == null) return false;
        var controller = group.controller();
        DyeColor front = controller.dye, back = controller.backDye;
        if (value == front || value == back) {
            DyeColor swap = front;
            front = back;
            back = swap;
        } else front = value;
        for (var tile : group.tiles()) tile.applyPalette(back, front, true);
        return true;
    }

    public void showVirtualFrame(long bits, float rpm) {
        if (!isVirtual()) return;
        motion.retarget(bits, rpm, clock());
        displayed = pending = bits;
    }

    public int discColor() {
        return dye.getTextureDiffuseColor() & 0xFFFFFF;
    }

    public int backColor() {
        return backDye.getTextureDiffuseColor() & 0xFFFFFF;
    }

    public long displayedBits() {
        return displayed;
    }

    public long pendingBits() {
        return pending;
    }

    public long flipStarted() {
        return flipStarted;
    }

    public int flipDuration() {
        return duration;
    }

    public int movingDiscs(float partial) {
        return motion.moving(clock() + partial);
    }

    public float discSpeed() {
        return motion.speed();
    }

    public float angle(int index, float partial) {
        return motion.angle(index, clock() + partial);
    }

    public boolean discsSettled(float partial) {
        return motion.settled(clock() + partial);
    }
    /**
     * The clock disc motion runs on. Ponder scenes keep their own time: opening Ponder pauses a singleplayer world, which
     * stops level time, and scenes can be rewound and replayed.
     */
    private long clock() {
        return isVirtual() ? AnimationTickHolder.getTicks(level) : level.getGameTime();
    }

    public int motionVersion() {
        return motion.version();
    }
    /**
     * Update packets are built in the server tick after a flip starts, and they reach the client later still. A 256 RPM
     * flip lasts one tick, so replaying it on the server's clock would show it already finished. Up to
     * {@link #BROADCAST_DELAY} ticks of that delay start the flip on the client's clock instead; anything older (a
     * packet sent mid-flip or after it settled) keeps its place in the motion.
     */
    private double clientMotionTime(CompoundTag tag) {
        double start = tag.getDouble("MotionTime"), sent = tag.contains("SentTime") ? tag.getDouble("SentTime") : start;
        clientLag = Math.max(0, sent - start - BROADCAST_DELAY);
        if (level == null || !level.isClientSide) return sent - clientLag;
        clientStartPending = true;
        PENDING_STARTS.add(this);
        return level.getGameTime() - clientLag;
    }
    /**
     * Packets are handled partway through a client tick, so the flip restarts on the next frame, using that frame's
     * exact time. {@link #startPendingClientMotion} does this before each frame, so boards that aren't drawn, because
     * they're off screen or a culling mod hid them, don't wait until they come into view.
     */
    public void startClientMotion(float partial) {
        if (!clientStartPending || level == null) return;
        clientStartPending = false;
        motion.reanchor(level.getGameTime() + partial - clientLag);
    }
    /** Starts every flip that arrived since the last frame. Call before each frame with the partial tick it draws. */
    public static void startPendingClientMotion(float partial) {
        if (PENDING_STARTS.isEmpty()) return;
        for (var board : PENDING_STARTS) board.startClientMotion(partial);
        PENDING_STARTS.clear();
    }

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        tag.putLong("Dots", displayed);
        tag.putLong("PendingDots", pending);
        tag.putLong("FlipStarted", flipStarted);
        tag.putInt("FlipDuration", duration);
        tag.putInt("Dye", dye.getId());
        tag.putInt("BackDye", backDye.getId());
        tag.putBoolean("PaletteSet", paletteSet);
        tag.putBoolean("SoundController", soundController);
        tag.putInt("BoardWidth", boardWidth);
        tag.putInt("BoardHeight", boardHeight);
        double time = level == null ? 0 : clock();
        // Clients get the angles from the start of the current flip, not the angles at send time.
        float[] angles = motion.snapshot(clientPacket ? motion.anchor() : time);
        int[] encoded = new int[64];
        for (int i = 0; i < 64; i++) encoded[i] = Float.floatToIntBits(angles[i]);
        tag.putIntArray("Angles", encoded);
        tag.putFloat("MotionRPM", motion.speed());
        tag.putDouble("MotionTime", clientPacket ? motion.anchor() : 0);
        if (clientPacket) tag.putDouble("SentTime", time);
        playback.write(tag, clientPacket);
        tag.putBoolean("WasPowered", wasPowered);
        if (boardCell != BoardCells.NONE) tag.putInt("BoardCell", boardCell);
        tag.putIntArray("MemberCells", memberCells);
        tag.putString("RowText", rowText);
        tag.putBoolean("HasText", hasText);
        tag.putBoolean("BitmapMode", bitmapMode);
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        displayed = tag.getLong("Dots");
        pending = tag.getLong("PendingDots");
        flipStarted = tag.getLong("FlipStarted");
        duration = Math.max(1, tag.getInt("FlipDuration"));
        dye = DyeColor.byId(tag.getInt("Dye"));
        backDye = DyeColor.byId(tag.getInt("BackDye"));
        if (backDye == dye) backDye = dye == DyeColor.BLACK ? DyeColor.WHITE : DyeColor.BLACK;
        soundController = tag.getBoolean("SoundController");
        paletteSet = tag.getBoolean("PaletteSet");
        boardWidth = tag.getInt("BoardWidth");
        boardHeight = tag.getInt("BoardHeight");
        int[] encoded = tag.getIntArray("Angles");
        float[] angles = new float[64];
        for (int i = 0; i < 64; i++)
            angles[i] =
                    encoded.length == 64 ? Float.intBitsToFloat(encoded[i]) : ((displayed & (1L << i)) == 0 ? 180 : 0);
        motion.restore(angles, displayed, tag.getFloat("MotionRPM"), clientPacket ? clientMotionTime(tag) : 0);
        boardCell = tag.contains("BoardCell") && BoardCells.valid(tag.getInt("BoardCell"))
                ? tag.getInt("BoardCell")
                : BoardCells.NONE;
        memberCells = tag.getIntArray("MemberCells");
        if (memberCells.length > FlipDotAssembly.MAX_TILES
                || Arrays.stream(memberCells).anyMatch(c -> !BoardCells.valid(c))) memberCells = new int[0];
        restoreClock = !clientPacket;
        wasPowered = tag.getBoolean("WasPowered");
        playback.read(tag, clientPacket);
        String text = tag.getString("RowText");
        rowText = text.substring(0, Math.min(512, text.length()));
        hasText = tag.getBoolean("HasText");
        bitmapMode = tag.getBoolean("BitmapMode");
    }
}
