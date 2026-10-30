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
    private BlockPos knownController;
    private long[] knownMembers=new long[0];
    private String rowText = "";
    private boolean hasText, bitmapMode;
    private FlipDotAssembly assembly;
    private long lastAssemblyScan = Long.MIN_VALUE / 2;
    private MediaClip media;
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
        if(knownController!=null) {
            if(!level.hasChunkAt(knownController))return null;
            if(level.getBlockEntity(knownController) instanceof FlipDotBlockEntity controller)
                for(long member:controller.knownMembers)if(!level.hasChunkAt(BlockPos.of(member)))return null;
        }
        return FlipDotAssembly.find(level, worldPosition);
    }
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
            if (tile != found.controller() && tile.media != null) tile.clearMedia();
        }
        var controller = found.controller();
        long[] members=found.tiles().stream().mapToLong(t->t.worldPosition.asLong()).sorted().toArray();
        if(controller.knownMembers.length>0 && !Arrays.equals(controller.knownMembers,members))controller.clearMedia();
        controller.knownMembers=members;
        for(var tile:found.tiles())tile.knownController=controller.worldPosition;
        if (controller.boardWidth != 0 && (controller.boardWidth != found.width() || controller.boardHeight != found.height())) controller.clearMedia();
        controller.boardWidth = found.width(); controller.boardHeight = found.height();
        found.controller().lastAssemblyScan = level.getGameTime();
        found.controller().rasterize(found);
    }
    private void rasterize(FlipDotAssembly group) {
        String[] lines = new String[group.height()];
        int[] firstColumn = new int[group.height()]; Arrays.fill(firstColumn, Integer.MAX_VALUE); Arrays.fill(lines, "");
        for (FlipDotBlockEntity tile : group.tiles()) {
            int row = group.row(tile.worldPosition), col = group.column(tile.worldPosition);
            if (tile.hasText && col < firstColumn[row]) { firstColumn[row] = col; lines[row] = tile.rowText; }
        }
        for (FlipDotBlockEntity tile : group.tiles()) {
            int row = group.row(tile.worldPosition);
            if (tile.bitmapMode) continue;
            if (firstColumn[row] != Integer.MAX_VALUE && (!tile.hasText || !tile.rowText.equals(lines[row]))) {
                tile.hasText = true; tile.rowText = lines[row]; tile.setChanged();
            }
            long bits = DotMatrix.tile(lines[row], group.column(tile.worldPosition), group.width() * 8);
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
    public void installMedia(MediaClip clip) {
        var group=assembly();if(group==null||group.width()!=clip.columns()||group.height()!=clip.rows())return;
        for(var tile:group.tiles()){tile.clearMedia();tile.assembly=group;}
        media=clip;mediaId=MediaStore.save(level.getServer(),clip);claimMedia();mediaFrame=0;mediaPlaying=clip.count()>1;mediaLoop=true;playbackDelay=0;
        // A still image is an ordinary latest target, including while unpowered.
        queueMediaFrame(group,clip.frame(0));
        setChanged();
    }
    private void claimMedia(){if(mediaId!=null && level instanceof net.minecraft.server.level.ServerLevel server)MediaStore.claim(server,worldPosition,mediaId);}
    private void releaseMedia(){if(mediaId!=null && level instanceof net.minecraft.server.level.ServerLevel server)MediaStore.release(server,worldPosition,mediaId);}
    // Boards loaded from disk get their level after reading their data, and copies made with /clone read it afterwards.
    @Override public void setLevel(net.minecraft.world.level.Level level){super.setLevel(level);claimMedia();}
    @Override public void destroy(){super.destroy();releaseMedia();}
    public boolean isMediaPlaying(){return media!=null && mediaPlaying && wasPowered;}
    public void clearMedia(){com.zmm2025.createdisplayworks.media.MediaLimits.release(this);releaseMedia();media=null;mediaId=null;mediaFrame=0;mediaPlaying=false;setChanged();}
    public void mediaControl(String action) {
        switch(action){case "play" -> mediaPlaying=true;case "pause" -> mediaPlaying=false;case "restart" -> {mediaFrame=0;playbackDelay=0;mediaPlaying=true;}case "loop" -> mediaLoop=!mediaLoop;default -> {return;}}
        setChanged();sendData();
    }
    public MediaClip mediaForEditing(){return media;}
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
        long now=level.getGameTime();
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
        motion.retarget(bits,rpm,level.getGameTime());displayed=pending=bits;
    }
    public int discColor() { return dye.getTextureDiffuseColor() & 0xFFFFFF; }
    public int backColor() { return backDye.getTextureDiffuseColor() & 0xFFFFFF; }
    public long displayedBits() { return displayed; }
    public long pendingBits() { return pending; }
    public long flipStarted() { return flipStarted; }
    public int flipDuration() { return duration; }
    public int movingDiscs(float partial) { return motion.moving(level.getGameTime()+partial); }
    public float discSpeed() { return motion.speed(); }
    public float angle(int index,float partial) { return motion.angle(index,level.getGameTime()+partial); }
    public boolean discsSettled(float partial) { return motion.settled(level.getGameTime()+partial); }
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
        clientStartPending=true;
        return level.getGameTime()-clientLag;
    }
    /**
     * Packets are handled partway through a client tick, so the flip restarts on the first frame that draws it, using
     * that frame's exact time.
     */
    public void startClientMotion(float partial) {
        if(!clientStartPending||level==null)return;
        clientStartPending=false;
        motion.reanchor(level.getGameTime()+partial-clientLag);
    }
    @Override protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        tag.putLong("Dots", displayed); tag.putLong("PendingDots", pending);
        tag.putLong("FlipStarted", flipStarted); tag.putInt("FlipDuration", duration);
        tag.putInt("Dye", dye.getId());tag.putInt("BackDye",backDye.getId());tag.putBoolean("PaletteSet",paletteSet);
        tag.putBoolean("SoundController",soundController);tag.putInt("BoardWidth",boardWidth);tag.putInt("BoardHeight",boardHeight);
        double time=level==null?0:level.getGameTime();
        // Clients get the angles from the start of the current flip, not the angles at send time.
        float[] angles=motion.snapshot(clientPacket?motion.anchor():time);int[] encoded=new int[64];
        for(int i=0;i<64;i++)encoded[i]=Float.floatToIntBits(angles[i]);
        tag.putIntArray("Angles",encoded);tag.putFloat("MotionRPM",motion.speed());
        tag.putDouble("MotionTime",clientPacket?motion.anchor():0);if(clientPacket)tag.putDouble("SentTime",time);
        tag.putInt("PlaybackDelay",playbackDelay);tag.putBoolean("WasPowered",wasPowered);
        if(knownController!=null)tag.putLong("KnownController",knownController.asLong());
        tag.putLongArray("KnownMembers",knownMembers);
        tag.putString("RowText", rowText); tag.putBoolean("HasText", hasText); tag.putBoolean("BitmapMode", bitmapMode);
        if(!clientPacket && media!=null){if(mediaId!=null)tag.putString("MediaId",mediaId);else tag.putByteArray("MediaArchive",media.archive());tag.putInt("MediaFrame",mediaFrame);tag.putBoolean("MediaPlaying",mediaPlaying);tag.putBoolean("MediaLoop",mediaLoop);}
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
        knownController=tag.contains("KnownController")?BlockPos.of(tag.getLong("KnownController")):null;knownMembers=tag.getLongArray("KnownMembers");
        if(knownMembers.length>256)knownMembers=new long[0];
        restoreClock=!clientPacket;wasPowered=tag.getBoolean("WasPowered");playbackDelay=Math.clamp(tag.getInt("PlaybackDelay"),0,20/MediaClip.FPS);
        String text = tag.getString("RowText"); rowText = text.substring(0, Math.min(512, text.length())); hasText = tag.getBoolean("HasText"); bitmapMode = tag.getBoolean("BitmapMode");
        if(!clientPacket){media=null;mediaId=null;
            // Older saves, and boards whose clip couldn't be stored, keep the clip inline. It moves to the store on load.
            if(tag.contains("MediaId")){mediaId=tag.getString("MediaId");media=MediaStore.load(mediaId);if(media==null)mediaId=null;}
            else if(tag.contains("MediaArchive"))try{media=MediaClip.fromArchive(tag.getByteArray("MediaArchive"));var server=net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();if(server!=null)mediaId=MediaStore.save(server,media);}catch(IllegalArgumentException ignored){}
            claimMedia();
            if(media!=null){mediaFrame=Math.floorMod(tag.getInt("MediaFrame"),media.count());mediaPlaying=tag.getBoolean("MediaPlaying");mediaLoop=tag.getBoolean("MediaLoop");}else mediaPlaying=false;}
    }
}
