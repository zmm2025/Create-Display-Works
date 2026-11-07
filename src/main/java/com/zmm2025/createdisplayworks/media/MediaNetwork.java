package com.zmm2025.createdisplayworks.media;

import com.zmm2025.createdisplayworks.CreateDisplayWorks;
import com.zmm2025.createdisplayworks.flipdot.*;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import java.util.*;
import java.util.function.Consumer;

public final class MediaNetwork {
    public static Consumer<Open> openScreen = data -> {};
    public static Consumer<Status> updateScreen = data -> {};
    public static Consumer<Part> receiveEditor = data -> {};
    private static final Map<UUID,Upload> UPLOADS=new HashMap<>();
    private static final Map<UUID,Long> EDIT_REQUESTS=new HashMap<>();
    private static final Map<UUID,Long> IMPORT_REQUESTS=new HashMap<>();
    /**
     * The display whose media screen the server opened for each player. The screen's packets only act on that display,
     * so protection that stops a player right-clicking it, such as a land claim, also stops a modified client.
     */
    private static final Map<UUID,Session> SESSIONS=new HashMap<>();
    private record Session(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension,BlockPos pos){}
    /** Status messages are lang keys, so the client shows them in its own language. */
    public static final String SAVED=message("saved");
    private static String message(String name){return "message."+CreateDisplayWorks.MOD_ID+".media."+name;}
    private static final Map<UUID,Transfer> TRANSFERS=new HashMap<>();
    private static final int PART_BYTES=16384, WINDOW_BYTES=16*PART_BYTES;
    private static final long TRANSFER_IDLE_NANOS=30_000_000_000L;
    public static void registerLifecycle(){
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent e)->{
            UUID id=e.getEntity().getUUID();endSession(id);EDIT_REQUESTS.remove(id);IMPORT_REQUESTS.remove(id);
        });
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.server.ServerStoppedEvent e)->{
            SESSIONS.clear();UPLOADS.clear();EDIT_REQUESTS.clear();IMPORT_REQUESTS.clear();TRANSFERS.clear();
        });
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.tick.ServerTickEvent.Post e)->{
            if(e.getServer().getTickCount()%20==0){
                long now=System.nanoTime();UPLOADS.values().removeIf(u->u.expires<now);TRANSFERS.values().removeIf(t->now-t.touched>TRANSFER_IDLE_NANOS);
            }
        });
    }
    private static boolean allow(Map<UUID,Long> requests,ServerPlayer player){
        long now=System.nanoTime(),previous=requests.getOrDefault(player.getUUID(),0L);
        if(previous!=0&&now-previous<1_000_000_000L)return false;
        requests.put(player.getUUID(),now);return true;
    }
    /** Opens the media screen for a display the player just right-clicked, which lets the screen's packets act on it. */
    public static void open(ServerPlayer player,BlockPos pos,FlipDotAssembly group){
        SESSIONS.put(player.getUUID(),new Session(player.level().dimension(),pos.immutable()));
        var controller=group.controller();
        PacketDistributor.sendToPlayer(player,new Open(pos,group.width(),group.height(),controller.mediaFilename(),controller.mediaThumbnail(),
            controller.mediaFrameCount(),controller.mediaPlaybackEnabled(),controller.mediaLoops()));
    }
    private static void endSession(UUID player){SESSIONS.remove(player);UPLOADS.remove(player);TRANSFERS.remove(player);}
    private static void reject(ServerPlayer player,BlockPos pos,String message){
        UPLOADS.remove(player.getUUID());
        PacketDistributor.sendToPlayer(player,new Status(pos,message,true));
    }
    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> type(String path){return new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(CreateDisplayWorks.MOD_ID,path));}
    public record Open(BlockPos pos,int columns,int rows,String filename,long[] thumbnail,int frames,boolean playing,boolean loop) implements CustomPacketPayload {
        public static final Type<Open> TYPE=MediaNetwork.type("media_open");
        public static final StreamCodec<RegistryFriendlyByteBuf,Open> CODEC=StreamCodec.of((b,p)->{b.writeBlockPos(p.pos);b.writeVarInt(p.columns);b.writeVarInt(p.rows);b.writeUtf(p.filename,128);b.writeLongArray(p.thumbnail);b.writeVarInt(p.frames);b.writeBoolean(p.playing);b.writeBoolean(p.loop);},b->new Open(b.readBlockPos(),b.readVarInt(),b.readVarInt(),b.readUtf(128),b.readLongArray(null,256),b.readVarInt(),b.readBoolean(),b.readBoolean()));
        public Type<Open> type(){return TYPE;}
    }
    public record Part(BlockPos pos,int columns,int rows,int total,int offset,byte[] data) implements CustomPacketPayload {
        public static final Type<Part> TYPE=MediaNetwork.type("media_part");
        public static final StreamCodec<RegistryFriendlyByteBuf,Part> CODEC=StreamCodec.of((b,p)->{
            b.writeBlockPos(p.pos);b.writeVarInt(p.columns);b.writeVarInt(p.rows);b.writeVarInt(p.total);b.writeVarInt(p.offset);b.writeByteArray(p.data);
        },b->{BlockPos pos=b.readBlockPos();int w=b.readVarInt(),h=b.readVarInt(),total=b.readVarInt(),offset=b.readVarInt();byte[] data=b.readByteArray(16384);return new Part(pos,w,h,total,offset,data);});
        public Type<Part> type(){return TYPE;}
    }
    public record Control(BlockPos pos,String action) implements CustomPacketPayload {
        public static final Type<Control> TYPE=MediaNetwork.type("media_control");
        public static final StreamCodec<RegistryFriendlyByteBuf,Control> CODEC=StreamCodec.of((b,p)->{b.writeBlockPos(p.pos);b.writeUtf(p.action,16);},b->new Control(b.readBlockPos(),b.readUtf(16)));
        public Type<Control> type(){return TYPE;}
    }
    public record Status(BlockPos pos,String message,boolean loop,int frames,boolean playing) implements CustomPacketPayload {
        public Status(BlockPos pos,String message,boolean loop){this(pos,message,loop,-1,false);}
        public static final Type<Status> TYPE=MediaNetwork.type("media_status");
        public static final StreamCodec<RegistryFriendlyByteBuf,Status> CODEC=StreamCodec.of((b,p)->{b.writeBlockPos(p.pos);b.writeUtf(p.message,256);b.writeBoolean(p.loop);b.writeVarInt(p.frames);b.writeBoolean(p.playing);},b->new Status(b.readBlockPos(),b.readUtf(256),b.readBoolean(),b.readVarInt(),b.readBoolean()));
        public Type<Status> type(){return TYPE;}
    }
    /** The client confirms how much of an editor transfer it has stored, which lets the server send more. */
    public record Ack(BlockPos pos,int received) implements CustomPacketPayload {
        public static final Type<Ack> TYPE=MediaNetwork.type("media_ack");
        public static final StreamCodec<RegistryFriendlyByteBuf,Ack> CODEC=StreamCodec.of((b,p)->{b.writeBlockPos(p.pos);b.writeVarInt(p.received);},b->new Ack(b.readBlockPos(),b.readVarInt()));
        public Type<Ack> type(){return TYPE;}
    }
    /**
     * An editor transfer to one player. At most {@link #WINDOW_BYTES} wait in the connection unconfirmed, so a client
     * that stops reading can't make the server queue whole archives. A new request replaces the old transfer, so a
     * lost confirmation never blocks the editor; an abandoned one expires.
     */
    private static final class Transfer {
        final BlockPos pos;final int w,h;final byte[] data;int sent,acked;long touched=System.nanoTime();
        Transfer(BlockPos pos,int w,int h,byte[] data){this.pos=pos;this.w=w;this.h=h;this.data=data;}
    }
    private static final class Upload {
        final BlockPos pos;final net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension;final int w,h;final byte[] data;final long expires;int received;
        Upload(Part p,ServerPlayer player){pos=p.pos;dimension=player.level().dimension();w=p.columns;h=p.rows;data=new byte[p.total];expires=System.nanoTime()+60_000_000_000L;}
    }
    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar=event.registrar("6");
        registrar.playToClient(Open.TYPE,Open.CODEC,(p,c)->openScreen.accept(p));
        registrar.playToClient(Status.TYPE,Status.CODEC,(p,c)->updateScreen.accept(p));
        registrar.playBidirectional(Part.TYPE,Part.CODEC,(p,c)->{if(c.player() instanceof ServerPlayer player)receive(p,player);else receiveEditor.accept(p);});
        registrar.playToServer(Ack.TYPE,Ack.CODEC,(p,c)->{
            var transfer=TRANSFERS.get(c.player().getUUID());
            if(transfer==null||!transfer.pos.equals(p.pos)||p.received<=transfer.acked||p.received>transfer.sent)return;
            transfer.acked=p.received;transfer.touched=System.nanoTime();
            if(transfer.acked==transfer.data.length)TRANSFERS.remove(c.player().getUUID());else pump(transfer,(ServerPlayer)c.player());
        });
        registrar.playToServer(Control.TYPE,Control.CODEC,(p,c)->{
            if(p.action.equals("close")){var session=SESSIONS.get(c.player().getUUID());if(session!=null&&session.pos.equals(p.pos))endSession(c.player().getUUID());return;}
            var be=editable((ServerPlayer)c.player(),p.pos);if(be==null)return;var group=be.assembly();if(group==null)return;
            if(p.action.equals("edit")){if(!allow(EDIT_REQUESTS,(ServerPlayer)c.player())){
                PacketDistributor.sendToPlayer((ServerPlayer)c.player(),new Status(p.pos,"",true,-2,false));return;
            }var archive=group.controller().mediaArchive();
                if(archive!=null){var transfer=new Transfer(p.pos,group.width(),group.height(),archive);TRANSFERS.put(c.player().getUUID(),transfer);pump(transfer,(ServerPlayer)c.player());}
                return;}
            if(p.action.equals("clear")){group.controller().clearMedia();for(int r=0;r<group.height();r++)group.controller().setLine(group,r,"");}
            else group.controller().mediaControl(p.action);
            PacketDistributor.sendToPlayer((ServerPlayer)c.player(),new Status(p.pos,"",group.controller().mediaLoops(),group.controller().mediaFrameCount(),group.controller().mediaPlaybackEnabled()));
        });
    }
    /** The display at {@code pos}, if its media screen is open for this player and the player may still use it. */
    private static FlipDotBlockEntity editable(ServerPlayer player,BlockPos pos) {
        var session=SESSIONS.get(player.getUUID());
        if(session==null||!session.pos.equals(pos)||session.dimension!=player.level().dimension())return null;
        if(!player.mayBuild() || player.isSpectator() || player.distanceToSqr(pos.getX()+.5,pos.getY()+.5,pos.getZ()+.5)>64
            || !player.level().hasChunkAt(pos) || !player.level().mayInteract(player,pos)
            || !(player.level().getBlockEntity(pos) instanceof FlipDotBlockEntity be)){endSession(player.getUUID());return null;}
        return be;
    }
    private static void receive(Part p,ServerPlayer player) {
        UPLOADS.values().removeIf(u->u.expires<System.nanoTime());
        var be=editable(player,p.pos);if(be==null){UPLOADS.remove(player.getUUID());PacketDistributor.sendToPlayer(player,new Status(p.pos,message("too_far"),true));return;}
        var group=be.assembly();
        if(group==null||p.columns!=group.width()||p.rows!=group.height()||group.width()*group.height()>MediaClip.MAX_TILES||p.total<1||p.total>EditableMedia.MAX_BYTES
            ||p.data.length<1||p.offset<0||p.offset>p.total-p.data.length){reject(player,p.pos,message("upload_invalid"));return;}
        if(p.offset==0) {
            if(!allow(IMPORT_REQUESTS,player)){reject(player,p.pos,message("rate_limited"));return;}
            long reserved=UPLOADS.entrySet().stream().filter(e->!e.getKey().equals(player.getUUID())).mapToLong(e->(long)e.getValue().data.length).sum();
            if(reserved+p.total>MediaLimits.UPLOAD_MIB.get()*1024L*1024){PacketDistributor.sendToPlayer(player,new Status(p.pos,message("queue_full"),true));return;}
            UPLOADS.put(player.getUUID(),new Upload(p,player));
        }
        var upload=UPLOADS.get(player.getUUID());
        if(upload==null||upload.dimension!=player.level().dimension()||!upload.pos.equals(p.pos)||upload.w!=p.columns||upload.h!=p.rows||upload.data.length!=p.total||upload.received!=p.offset){reject(player,p.pos,message("upload_interrupted"));return;}
        System.arraycopy(p.data,0,upload.data,p.offset,p.data.length);upload.received+=p.data.length;
        if(upload.received==p.total) {
            UPLOADS.remove(player.getUUID());
            try {
                var clip=MediaClip.fromArchive(upload.data);
                if(clip.columns()!=group.width()||clip.rows()!=group.height())throw new IllegalArgumentException("Board dimensions changed");
                group.controller().installMedia(clip,upload.data);
                boolean still=clip.count()==1;
                player.displayClientMessage(still?Component.translatable("message.create_display_works.media_loaded_image")
                    :Component.translatable("message.create_display_works.media_loaded",clip.count()),true);
                PacketDistributor.sendToPlayer(player,new Status(p.pos,SAVED,true));
            }catch(IllegalArgumentException e){PacketDistributor.sendToPlayer(player,new Status(p.pos,message("save_failed"),true));}

        }
    }
    public static void upload(BlockPos pos,MediaClip clip) {
        byte[] bytes=clip.archive();
        for(int offset=0;offset<bytes.length;offset+=PART_BYTES)
            PacketDistributor.sendToServer(new Part(pos,clip.columns(),clip.rows(),bytes.length,offset,Arrays.copyOfRange(bytes,offset,Math.min(offset+PART_BYTES,bytes.length))));
    }
    private static void pump(Transfer transfer,ServerPlayer player) {
        while(transfer.sent<transfer.data.length&&transfer.sent-transfer.acked<WINDOW_BYTES){
            int end=Math.min(transfer.sent+PART_BYTES,transfer.data.length);
            PacketDistributor.sendToPlayer(player,new Part(transfer.pos,transfer.w,transfer.h,transfer.data.length,transfer.sent,Arrays.copyOfRange(transfer.data,transfer.sent,end)));
            transfer.sent=end;
        }
    }
}
