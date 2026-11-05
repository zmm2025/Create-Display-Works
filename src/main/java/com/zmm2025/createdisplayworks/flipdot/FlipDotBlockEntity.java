package com.zmm2025.createdisplayworks.flipdot;

import java.util.*;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;

import com.zmm2025.createdisplayworks.ModContent;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.state.BlockState;
import com.zmm2025.createdisplayworks.media.MediaClip;
import com.zmm2025.createdisplayworks.media.MediaStore;

public class FlipDotBlockEntity extends KineticBlockEntity {
    public static java.util.function.Consumer<FlipDotBlockEntity> clientTick=be->{};
    private static final int BROADCAST_DELAY=1;
    private boolean clientStartPending;
    /** Client boards whose flip starts on the next frame. */
    private static final Set<FlipDotBlockEntity> PENDING_STARTS=Collections.newSetFromMap(new WeakHashMap<>());
    private double clientLag;
    private boolean soundController;
    private long displayed, pending, flipStarted;
    private int duration = 1;
    private DyeColor dye = DyeColor.WHITE, backDye = DyeColor.BLACK;
    private boolean paletteSet;
    private final DiscMotion motion = new DiscMotion();
    private boolean restoreClock;
    private int playbackDelay;
    private long lastTick = Long.MIN_VALUE;
    private boolean wasPowered;
    private int boardWidth, boardHeight;
    /** This tile's {@link BoardCells cell} on its board when it last formed. */
    private int boardCell=BoardCells.NONE;
    /** On the controller: every cell of its board when it last formed, sorted. */
    private int[] memberCells=new int[0];
    private String rowText = "";
    private boolean hasText, bitmapMode;
    private FlipDotAssembly assembly;
    private long lastAssemblyScan = Long.MIN_VALUE / 2;
    // Playback frames only. The editable source lives in the saved archive, which is built once and reused for every
    // save and editor request instead of being rebuilt each time.
    private MediaClip media;
    /**
     * Saved inline only when the store couldn't write {@link #mediaId}'s file. Otherwise the archive, with the editable
     * source, is read from the store when the media screen asks for it.
     */
    private byte[] mediaArchive;
    /** The {@link MediaStore} id of {@link #media}, or null if it couldn't be stored and has to be saved inline. */
    private String mediaId;
    private int mediaFrame;
    private boolean mediaPlaying, mediaLoop=true;

    public FlipDotBlockEntity(BlockPos pos, BlockState state) {
        super(ModContent.FLIP_DOT_ENTITY.get(), pos, state);
        setLazyTickRate(20);
    }
    @Override public void addBehaviours(List<BlockEntityBehaviour> behaviours) {}
    public FlipDotAssembly assembly() {
        if(level==null)return null;
        // Wait until the whole board as it last formed is loaded, so half of it doesn't look like a smaller board.
        if(boardCell!=BoardCells.NONE) {
            Direction right=right();
            BlockPos controllerPos=FlipDotAssembly.offset(worldPosition,right,BoardCells.inverse(boardCell));
            if(!level.hasChunkAt(controllerPos))return null;
            if(level.getBlockEntity(controllerPos) instanceof FlipDotBlockEntity controller)
                for(int member:controller.memberCells)if(!level.hasChunkAt(FlipDotAssembly.offset(controllerPos,right,member)))return null;
        }
        return FlipDotAssembly.find(level, worldPosition);
    }
    private Direction right(){return FlipDotAssembly.right(getBlockState().getValue(FlipDotBlock.HORIZONTAL_FACING));}
    @Override public void initialize() {
        super.initialize();
        // Displays saved before connected borders load with every border showing.
        if (level == null || level.isClientSide) return;
        BlockState connected = FlipDotBlock.connect(getBlockState(), level, worldPosition);
        if (connected != getBlockState()) level.setBlock(worldPosition, connected, net.minecraft.world.level.block.Block.UPDATE_ALL);
    }
    @Override public void lazyTick() {
        super.lazyTick();
        if (level == null || level.isClientSide) return;
        if (assembly != null && !assembly.controller().isRemoved() && level.getGameTime() - assembly.controller().lastAssemblyScan < 20) return;
        FlipDotAssembly found = assembly();
        if (found == null) { assembly = null; return; }
        // Existing panels donate their palette to new panels. When two boards join,
        // the uppermost, then leftmost established panel wins deterministically.
        var donor = found.tiles().stream().filter(t -> t.paletteSet)
            .min(Comparator.<FlipDotBlockEntity>comparingInt(t -> found.row(t.worldPosition))
                .thenComparingInt(t -> found.column(t.worldPosition))).orElse(found.controller());
        for (FlipDotBlockEntity tile : found.tiles()) {
            tile.assembly = found;
            boolean controllerFlag=tile==found.controller();if(tile.soundController!=controllerFlag){tile.soundController=controllerFlag;tile.sendData();}
            tile.applyPalette(donor.backDye, donor.dye, donor.paletteSet);
        }
        var controller = found.controller();
        int[] cells=found.tiles().stream().mapToInt(t->found.cell(t.worldPosition)).toArray();
        rearrange(found,cells);
        for (FlipDotBlockEntity tile : found.tiles()) if (tile != controller && tile.media != null) tile.clearMedia();
        int[] members=cells.clone();Arrays.sort(members);
        if(controller.memberCells.length>0 && !Arrays.equals(controller.memberCells,members))controller.clearMedia();
        controller.memberCells=members;
        for(int i=0;i<cells.length;i++){var tile=found.tiles().get(i);tile.boardCell=cells[i];if(tile!=controller)tile.memberCells=new int[0];}
        if (controller.boardWidth != 0 && (controller.boardWidth != found.width() || controller.boardHeight != found.height())) controller.clearMedia();
        controller.boardWidth = found.width(); controller.boardHeight = found.height();
        found.controller().lastAssemblyScan = level.getGameTime();
        found.controller().rasterize(found);
    }
    /**
     * When a whole board was turned upside down or mirrored, for example by a bearing or a pasted copy, its tiles swap
     * places. Moves each cell's text, frame and media to the tile now in that cell, so the board keeps showing the same
     * picture the right way up.
     */
    private static void rearrange(FlipDotAssembly found,int[] cells) {
        var tiles=found.tiles();
        int[] previous=tiles.stream().mapToInt(t->t.boardCell).toArray();
        FlipDotBlockEntity oldController=null;
        for(int i=0;i<previous.length;i++)if(previous[i]==BoardCells.ORIGIN)oldController=tiles.get(i);
        if(oldController==null)return;
        if(BoardCells.reshaped(previous,cells,oldController.memberCells)){keepLines(tiles,cells,previous);return;}
        int[] source=BoardCells.rearrangement(previous,cells,oldController.memberCells);
        if(source==null)return;
        record Content(long pending,String rowText,boolean hasText,boolean bitmapMode){}
        var content=tiles.stream().map(t->new Content(t.pending,t.rowText,t.hasText,t.bitmapMode)).toList();
        for(int i=0;i<tiles.size();i++) {
            var tile=tiles.get(i);var moved=content.get(source[i]);
            tile.pending=moved.pending;tile.rowText=moved.rowText;tile.hasText=moved.hasText;tile.bitmapMode=moved.bitmapMode;tile.setChanged();
        }
        var controller=found.controller();
        if(oldController!=controller){controller.memberCells=oldController.memberCells.clone();oldController.moveMediaTo(controller);}
    }
    /** A quarter turn changes the board's shape, so its picture can't follow, but its lines of text keep their order. */
    private static void keepLines(List<FlipDotBlockEntity> tiles,int[] cells,int[] previous) {
        Map<Integer,String> lines=new HashMap<>();
        for(int i=0;i<tiles.size();i++)if(tiles.get(i).hasText)lines.putIfAbsent(BoardCells.row(previous[i]),tiles.get(i).rowText);
        if(lines.isEmpty())return;
        for(int i=0;i<tiles.size();i++) {
            var tile=tiles.get(i);String line=lines.get(BoardCells.row(cells[i]));
            tile.hasText=line!=null;tile.rowText=line==null?"":line;tile.setChanged();
        }
    }
    private void moveMediaTo(FlipDotBlockEntity target) {
        if(media==null)return;
        target.clearMedia();
        target.media=media;target.mediaId=mediaId;target.mediaArchive=mediaArchive;target.mediaFrame=mediaFrame;
        target.mediaPlaying=mediaPlaying;target.mediaLoop=mediaLoop;target.playbackDelay=playbackDelay;
        target.claimMedia();target.setChanged();
        clearMedia();
    }
    private void rasterize(FlipDotAssembly group) {
        String[] lines = new String[group.height()];
        int[] firstColumn = new int[group.height()]; Arrays.fill(firstColumn, Integer.MAX_VALUE); Arrays.fill(lines, "");
        for (FlipDotBlockEntity tile : group.tiles()) {
            int row = group.row(tile.worldPosition), col = group.column(tile.worldPosition);
            if (tile.hasText && col < firstColumn[row]) { firstColumn[row] = col; lines[row] = tile.rowText; }
        }
        int[][] glyphs = new int[group.height()][];
        for (FlipDotBlockEntity tile : group.tiles()) {
            int row = group.row(tile.worldPosition);
            if (tile.bitmapMode) continue;
            if (firstColumn[row] != Integer.MAX_VALUE && (!tile.hasText || !tile.rowText.equals(lines[row]))) {
                tile.hasText = true; tile.rowText = lines[row]; tile.setChanged();
            }
            if (glyphs[row] == null) glyphs[row] = DotMatrix.glyphs(lines[row]);
            long bits = DotMatrix.tile(glyphs[row], group.column(tile.worldPosition), group.width() * 8);
            if (tile.pending != bits) { tile.pending = bits; tile.setChanged(); }
        }
    }
    public void setTextAtBlock(String text) {
        FlipDotAssembly group = assembly();
        if (group != null) group.controller().setLine(group, group.row(worldPosition), text);
    }
    public void setLine(FlipDotAssembly group, int row, String text) {
        if (row < 0 || row >= group.height()) return;
        group.controller().clearMedia();
        String bounded = text.codePoints().limit(256).collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
        for (FlipDotBlockEntity tile : group.tiles()) {
            tile.assembly = group;
            if (group.row(tile.worldPosition) != row) continue;
            tile.rowText = bounded; tile.hasText = true; tile.bitmapMode = false;
            tile.setChanged();
        }
        group.controller().rasterize(group);
    }
    /** Queues one row-major 64-bit mask per tile, using the same powered, atomic refresh as text. */
    public boolean submitFrame(long[] masks, int columns, int rows) {
        if (level == null || level.isClientSide) return false;
        FlipDotAssembly group = assembly();
        if (group == null || columns != group.width() || rows != group.height() || masks.length != columns * rows) return false;
        group.controller().clearMedia();
        for (FlipDotBlockEntity tile : group.tiles()) {
            tile.assembly = group; tile.bitmapMode = true; tile.hasText = false; tile.rowText = "";
            tile.pending = masks[group.row(tile.worldPosition) * columns + group.column(tile.worldPosition)];
            tile.setChanged();
        }
        return true;
    }
    public void installMedia(MediaClip clip) {installMedia(clip,null);}
    /** {@code archive}, when given, must be {@code clip}'s archive; it's reused if the source needs no shrinking. */
    public void installMedia(MediaClip clip,byte[] archive) {
        var group=assembly();if(group==null||group.width()!=clip.columns()||group.height()!=clip.rows())return;
        for(var tile:group.tiles()){tile.clearMedia();tile.assembly=group;}
        storeMedia(clip,archive);claimMedia();mediaFrame=0;mediaPlaying=clip.count()>1;mediaLoop=true;playbackDelay=0;
        // A still image is an ordinary latest target, including while unpowered.
        queueMediaFrame(group,media.frame(0));
        setChanged();
    }
    private void claimMedia(){if(mediaId!=null && level instanceof net.minecraft.server.level.ServerLevel server)MediaStore.claim(server,worldPosition,mediaId);}
    private void releaseMedia(){if(mediaId!=null && level instanceof net.minecraft.server.level.ServerLevel server)MediaStore.release(server,worldPosition,mediaId);}
    // Boards loaded from disk get their level after reading their data, and copies made with /clone read it afterwards.
    @Override public void setLevel(net.minecraft.world.level.Level level){super.setLevel(level);claimMedia();}
    @Override public void destroy(){super.destroy();releaseMedia();}
    public boolean isMediaPlaying(){return media!=null && mediaPlaying && wasPowered;}
    public void clearMedia(){com.zmm2025.createdisplayworks.media.MediaLimits.release(this);releaseMedia();media=null;mediaId=null;mediaArchive=null;mediaFrame=0;mediaPlaying=false;setChanged();}
    /** Stores the clip with its editable source shrunk to what this board can show, and keeps only playback in memory. */
    private void storeMedia(MediaClip clip,byte[] archive) {
        var compact=clip.withCompactSource();
        byte[] bytes=compact==clip&&archive!=null?archive:compact.archive();
        media=compact.withoutSource();mediaId=MediaStore.save(level.getServer(),bytes);mediaArchive=mediaId==null?bytes:null;
    }
    public void mediaControl(String action) {
        switch(action){case "play" -> mediaPlaying=true;case "pause" -> mediaPlaying=false;case "restart" -> {mediaFrame=0;playbackDelay=0;mediaPlaying=true;}case "loop" -> mediaLoop=!mediaLoop;default -> {return;}}
        setChanged();sendData();
    }
    /** The saved archive, with the editable source, for the media screen. */
    public byte[] mediaArchive(){return mediaId!=null?MediaStore.read(mediaId):mediaArchive;}
    public String mediaId(){return mediaId;}
    public String mediaFilename(){return media==null?"":media.filename();}
    public long[] mediaThumbnail(){return media==null?new long[0]:media.frame(0);}
    public int mediaFrameCount(){return media==null?0:media.count();}
    public boolean mediaPlaybackEnabled(){return mediaPlaying;}
    public boolean mediaLoops(){return mediaLoop;}
    private void queueMediaFrame(FlipDotAssembly group,long[] frame) {
        for(var tile:group.tiles()) {
            tile.pending=frame[group.row(tile.worldPosition)*group.width()+group.column(tile.worldPosition)];
            tile.bitmapMode=true;tile.hasText=false;tile.rowText="";tile.setChanged();
        }
    }
    @Override public void tick() {
        super.tick();
        if (level == null) return;
        long now=clock();
        if(restoreClock){motion.restore(motion.snapshot(0),displayed,motion.speed(),now);restoreClock=false;}
        if(level.isClientSide){if(soundController)clientTick.accept(this);return;}
        // Tick gaps are unloaded time, not animation time.
        if(lastTick!=Long.MIN_VALUE && now-lastTick>1) {
            motion.restore(motion.snapshot(lastTick),displayed,motion.speed(),now);sendData();
            assembly=null;
        }
        lastTick=now;
        if(assembly==null || assembly.controller()!=this)return;
        float rpm=Float.MAX_VALUE;boolean powered=true;
        for(var tile:assembly.tiles()) {
            if(tile.isRemoved() || !level.hasChunkAt(tile.worldPosition) || level.getBlockEntity(tile.worldPosition)!=tile){assembly=null;return;}
            rpm=Math.min(rpm,Math.abs(tile.getSpeed()));powered&=tile.isSpeedRequirementFulfilled();
        }
        if(!powered) {wasPowered=false;playbackDelay=0;com.zmm2025.createdisplayworks.media.MediaLimits.release(this);return;}
        // If power returns before the old flip settles, finish it before advancing video.
        boolean settling=!wasPowered && assembly.tiles().stream().anyMatch(t->t.motion.moving(now)>0);
        if(settling)return;
        wasPowered=true;
        if(media!=null && mediaPlaying && com.zmm2025.createdisplayworks.media.MediaLimits.acquire(this,assembly.tiles().size()) && playbackDelay--<=0) {
            if(media.columns()!=assembly.width() || media.rows()!=assembly.height()){clearMedia();return;}
            queueMediaFrame(assembly,media.frame(mediaFrame));
            if(++mediaFrame>=media.count()){mediaFrame=0;if(!mediaLoop)mediaPlaying=false;}
            playbackDelay=20/MediaClip.FPS-1;setChanged();
        }
        for(var tile:assembly.tiles()) {
            if(tile.displayed==tile.pending && tile.motion.speed()==rpm)continue;
            tile.displayed=tile.pending;tile.flipStarted=now;
            tile.duration=(int)Math.ceil(DiscMotion.durationTicks(rpm));
            tile.motion.retarget(tile.displayed,rpm,now);
            tile.setChanged();tile.sendData();
        }
    }
    private void applyPalette(DyeColor back,DyeColor front,boolean established) {
        if(backDye==back && dye==front && paletteSet==established)return;
        backDye=back;dye=front;paletteSet=established;setChanged();sendData();
    }
    public boolean setDye(DyeColor value) {
        var group=assembly();if(group==null)return false;
        var controller=group.controller();
        DyeColor front=controller.dye,back=controller.backDye;
        if(value==front || value==back){DyeColor swap=front;front=back;back=swap;}else front=value;
        for(var tile:group.tiles())tile.applyPalette(back,front,true);
        return true;
    }
    public void showVirtualFrame(long bits,float rpm) {
        if(!isVirtual())return;
        motion.retarget(bits,rpm,clock());displayed=pending=bits;
    }
    public int discColor() { return dye.getTextureDiffuseColor() & 0xFFFFFF; }
    public int backColor() { return backDye.getTextureDiffuseColor() & 0xFFFFFF; }
    public long displayedBits() { return displayed; }
    public long pendingBits() { return pending; }
    public long flipStarted() { return flipStarted; }
    public int flipDuration() { return duration; }
    public int movingDiscs(float partial) { return motion.moving(clock()+partial); }
    public float discSpeed() { return motion.speed(); }
    public float angle(int index,float partial) { return motion.angle(index,clock()+partial); }
    public boolean discsSettled(float partial) { return motion.settled(clock()+partial); }
    /**
     * The clock disc motion runs on. Ponder scenes keep their own time: opening Ponder pauses a singleplayer world, which
     * stops level time, and scenes can be rewound and replayed.
     */
    private long clock() { return isVirtual() ? net.createmod.catnip.animation.AnimationTickHolder.getTicks(level) : level.getGameTime(); }
    public int motionVersion() { return motion.version(); }
    /**
     * Update packets are built in the server tick after a flip starts, and they reach the client later still. A 256 RPM
     * flip lasts one tick, so replaying it on the server's clock would show it already finished. Up to
     * {@link #BROADCAST_DELAY} ticks of that delay start the flip on the client's clock instead; anything older (a
     * packet sent mid-flip or after it settled) keeps its place in the motion.
     */
    private double clientMotionTime(CompoundTag tag) {
        double start=tag.getDouble("MotionTime"),sent=tag.contains("SentTime")?tag.getDouble("SentTime"):start;
        clientLag=Math.max(0,sent-start-BROADCAST_DELAY);
        if(level==null||!level.isClientSide)return sent-clientLag;
        clientStartPending=true;PENDING_STARTS.add(this);
        return level.getGameTime()-clientLag;
    }
    /**
     * Packets are handled partway through a client tick, so the flip restarts on the next frame, using that frame's
     * exact time. {@link #startPendingClientMotion} does this before each frame, so boards that aren't drawn, because
     * they're off screen or a culling mod hid them, don't wait until they come into view.
     */
    public void startClientMotion(float partial) {
        if(!clientStartPending||level==null)return;
        clientStartPending=false;
        motion.reanchor(level.getGameTime()+partial-clientLag);
    }
    /** Starts every flip that arrived since the last frame. Call before each frame with the partial tick it draws. */
    public static void startPendingClientMotion(float partial) {
        if(PENDING_STARTS.isEmpty())return;
        for(var board:PENDING_STARTS)board.startClientMotion(partial);
        PENDING_STARTS.clear();
    }
    @Override protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        tag.putLong("Dots", displayed); tag.putLong("PendingDots", pending);
        tag.putLong("FlipStarted", flipStarted); tag.putInt("FlipDuration", duration);
        tag.putInt("Dye", dye.getId());tag.putInt("BackDye",backDye.getId());tag.putBoolean("PaletteSet",paletteSet);
        tag.putBoolean("SoundController",soundController);tag.putInt("BoardWidth",boardWidth);tag.putInt("BoardHeight",boardHeight);
        double time=level==null?0:clock();
        // Clients get the angles from the start of the current flip, not the angles at send time.
        float[] angles=motion.snapshot(clientPacket?motion.anchor():time);int[] encoded=new int[64];
        for(int i=0;i<64;i++)encoded[i]=Float.floatToIntBits(angles[i]);
        tag.putIntArray("Angles",encoded);tag.putFloat("MotionRPM",motion.speed());
        tag.putDouble("MotionTime",clientPacket?motion.anchor():0);if(clientPacket)tag.putDouble("SentTime",time);
        tag.putInt("PlaybackDelay",playbackDelay);tag.putBoolean("WasPowered",wasPowered);
        if(boardCell!=BoardCells.NONE)tag.putInt("BoardCell",boardCell);
        tag.putIntArray("MemberCells",memberCells);
        tag.putString("RowText", rowText); tag.putBoolean("HasText", hasText); tag.putBoolean("BitmapMode", bitmapMode);
        if(!clientPacket && media!=null){if(mediaId!=null)tag.putString("MediaId",mediaId);else tag.putByteArray("MediaArchive",mediaArchive);tag.putInt("MediaFrame",mediaFrame);tag.putBoolean("MediaPlaying",mediaPlaying);tag.putBoolean("MediaLoop",mediaLoop);}
    }
    @Override protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        displayed = tag.getLong("Dots");
        pending = tag.contains("PendingDots") ? tag.getLong("PendingDots") : displayed;
        flipStarted = tag.getLong("FlipStarted"); duration = Math.max(1, tag.getInt("FlipDuration"));
        dye = tag.contains("Dye") ? DyeColor.byId(tag.getInt("Dye")) : DyeColor.WHITE;
        backDye=tag.contains("BackDye")?DyeColor.byId(tag.getInt("BackDye")):DyeColor.BLACK;
        if(backDye==dye)backDye=dye==DyeColor.BLACK?DyeColor.WHITE:DyeColor.BLACK;
        soundController=tag.getBoolean("SoundController");paletteSet=tag.getBoolean("PaletteSet");boardWidth=tag.getInt("BoardWidth");boardHeight=tag.getInt("BoardHeight");
        int[] encoded=tag.getIntArray("Angles");float[] angles=new float[64];
        for(int i=0;i<64;i++)angles[i]=encoded.length==64?Float.intBitsToFloat(encoded[i]):((displayed&(1L<<i))==0?180:0);
        motion.restore(angles,displayed,tag.getFloat("MotionRPM"),clientPacket?clientMotionTime(tag):0);
        boardCell=tag.contains("BoardCell")&&BoardCells.valid(tag.getInt("BoardCell"))?tag.getInt("BoardCell"):BoardCells.NONE;memberCells=tag.getIntArray("MemberCells");
        // Older saves link tiles by absolute position. Those are still right where the board was saved.
        if(!tag.contains("BoardCell") && tag.contains("KnownController")) {
            BlockPos controller=BlockPos.of(tag.getLong("KnownController"));Direction right=right();
            boardCell=FlipDotAssembly.cell(worldPosition,controller,right);
            memberCells=Arrays.stream(tag.getLongArray("KnownMembers")).mapToInt(m->FlipDotAssembly.cell(BlockPos.of(m),controller,right)).sorted().toArray();
        }
        if(memberCells.length>FlipDotAssembly.MAX_TILES || Arrays.stream(memberCells).anyMatch(c->!BoardCells.valid(c)))memberCells=new int[0];
        restoreClock=!clientPacket;wasPowered=tag.getBoolean("WasPowered");playbackDelay=Math.clamp(tag.getInt("PlaybackDelay"),0,20/MediaClip.FPS);
        String text = tag.getString("RowText"); rowText = text.substring(0, Math.min(512, text.length())); hasText = tag.getBoolean("HasText"); bitmapMode = tag.getBoolean("BitmapMode");
        if(!clientPacket){media=null;mediaId=null;mediaArchive=null;
            // Older saves, and boards whose clip couldn't be stored, keep the clip inline. It moves to the store on load.
            if(tag.contains("MediaId")){mediaId=tag.getString("MediaId");var clip=MediaStore.load(mediaId);if(clip==null)mediaId=null;else media=clip.withoutSource();}
            else if(tag.contains("MediaArchive"))try{
                byte[] archive=tag.getByteArray("MediaArchive");media=MediaClip.fromArchive(archive).withoutSource();
                var server=net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();if(server!=null)mediaId=MediaStore.save(server,archive);
                if(mediaId==null)mediaArchive=archive;
            }catch(IllegalArgumentException ignored){}
            claimMedia();
            if(media!=null){mediaFrame=Math.floorMod(tag.getInt("MediaFrame"),media.count());mediaPlaying=tag.getBoolean("MediaPlaying");mediaLoop=tag.getBoolean("MediaLoop");}else mediaPlaying=false;}
    }
}
