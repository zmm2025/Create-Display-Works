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
    public static void registerLifecycle(){
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent e)->{
            UUID id=e.getEntity().getUUID();UPLOADS.remove(id);EDIT_REQUESTS.remove(id);IMPORT_REQUESTS.remove(id);
        });
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.server.ServerStoppedEvent e)->{
            UPLOADS.clear();EDIT_REQUESTS.clear();IMPORT_REQUESTS.clear();
        });
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.tick.ServerTickEvent.Post e)->{
            if(e.getServer().getTickCount()%20==0)UPLOADS.values().removeIf(u->u.expires<System.nanoTime());
        });
    }
    private static boolean allow(Map<UUID,Long> requests,ServerPlayer player){
        long now=System.nanoTime(),previous=requests.getOrDefault(player.getUUID(),0L);
        if(previous!=0&&now-previous<1_000_000_000L)return false;
        requests.put(player.getUUID(),now);return true;
    }
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
    private static final class Upload {
        final BlockPos pos;final net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension;final int w,h;final byte[] data;final long expires;int received;
        Upload(Part p,ServerPlayer player){pos=p.pos;dimension=player.level().dimension();w=p.columns;h=p.rows;data=new byte[p.total];expires=System.nanoTime()+60_000_000_000L;}
    }
    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar=event.registrar("5");
        registrar.playToClient(Open.TYPE,Open.CODEC,(p,c)->openScreen.accept(p));
        registrar.playToClient(Status.TYPE,Status.CODEC,(p,c)->updateScreen.accept(p));
        registrar.playBidirectional(Part.TYPE,Part.CODEC,(p,c)->{if(c.player() instanceof ServerPlayer player)receive(p,player);else receiveEditor.accept(p);});
        registrar.playToServer(Control.TYPE,Control.CODEC,(p,c)->{
            var be=editable((ServerPlayer)c.player(),p.pos);if(be==null)return;var group=be.assembly();if(group==null)return;
            if(p.action.equals("edit")){if(!allow(EDIT_REQUESTS,(ServerPlayer)c.player())){
                PacketDistributor.sendToPlayer((ServerPlayer)c.player(),new Status(p.pos,"Loading media",true,-2,false));return;
            }var clip=group.controller().mediaForEditing();if(clip!=null)sendParts(p.pos,clip,part->PacketDistributor.sendToPlayer((ServerPlayer)c.player(),part));return;}
            if(p.action.equals("clear")){group.controller().clearMedia();for(int r=0;r<group.height();r++)group.controller().setLine(group,r,"");}
            else group.controller().mediaControl(p.action);
            PacketDistributor.sendToPlayer((ServerPlayer)c.player(),new Status(p.pos,
                p.action.equals("status")?group.controller().mediaFilename()+" | "+group.controller().mediaFrameCount()+" frames. 10 FPS; rotation controls disc motion.":"Display updated: "+p.action,group.controller().mediaLoops(),group.controller().mediaFrameCount(),group.controller().mediaPlaybackEnabled()));
        });
    }
    private static FlipDotBlockEntity editable(ServerPlayer player,BlockPos pos) {
        if(!player.mayBuild() || player.isSpectator() || player.distanceToSqr(pos.getX()+.5,pos.getY()+.5,pos.getZ()+.5)>64
            || !player.level().hasChunkAt(pos) || !player.level().mayInteract(player,pos))return null;
        return player.level().getBlockEntity(pos) instanceof FlipDotBlockEntity be?be:null;
    }
    private static void receive(Part p,ServerPlayer player) {
        UPLOADS.values().removeIf(u->u.expires<System.nanoTime());
        var be=editable(player,p.pos);if(be==null){UPLOADS.remove(player.getUUID());PacketDistributor.sendToPlayer(player,new Status(p.pos,"Move closer to the display to apply media.",true));return;}
        var group=be.assembly();
        if(group==null||p.columns!=group.width()||p.rows!=group.height()||group.width()*group.height()>MediaClip.MAX_TILES||p.total<1||p.total>EditableMedia.MAX_BYTES
            ||p.data.length<1||p.offset<0||p.offset>p.total-p.data.length){reject(player,p.pos,"The board changed or the upload was invalid. Import it again.");return;}
        if(p.offset==0) {
            if(!allow(IMPORT_REQUESTS,player)){reject(player,p.pos,"Please wait a moment before importing again.");return;}
            long reserved=UPLOADS.entrySet().stream().filter(e->!e.getKey().equals(player.getUUID())).mapToLong(e->(long)e.getValue().data.length).sum();
            if(reserved+p.total>MediaLimits.UPLOAD_MIB.get()*1024L*1024){PacketDistributor.sendToPlayer(player,new Status(p.pos,"The server import queue is full. Try again shortly.",true));return;}
            UPLOADS.put(player.getUUID(),new Upload(p,player));
        }
        var upload=UPLOADS.get(player.getUUID());
        if(upload==null||upload.dimension!=player.level().dimension()||!upload.pos.equals(p.pos)||upload.w!=p.columns||upload.h!=p.rows||upload.data.length!=p.total||upload.received!=p.offset){reject(player,p.pos,"Upload interrupted. Import it again.");return;}
        System.arraycopy(p.data,0,upload.data,p.offset,p.data.length);upload.received+=p.data.length;
        if(upload.received==p.total) {
            UPLOADS.remove(player.getUUID());
            try {
                var clip=MediaClip.fromArchive(upload.data);
                if(clip.columns()!=group.width()||clip.rows()!=group.height())throw new IllegalArgumentException("Board dimensions changed");
                group.controller().installMedia(clip);
                boolean still=clip.count()==1;
                player.displayClientMessage(still?Component.translatable("message.create_display_works.media_loaded_image")
                    :Component.translatable("message.create_display_works.media_loaded",clip.count()),true);
                PacketDistributor.sendToPlayer(player,new Status(p.pos,still?"Saved 1 frame.":"Saved "+clip.count()+" frames.",true));
            }catch(IllegalArgumentException e){PacketDistributor.sendToPlayer(player,new Status(p.pos,"Unable to save this media. Import it again.",true));}

        }
    }
    public static void upload(BlockPos pos,MediaClip clip) {sendParts(pos,clip,PacketDistributor::sendToServer);}
    private static void sendParts(BlockPos pos,MediaClip clip,Consumer<Part> send) {
        byte[] bytes=clip.archive();
        for(int offset=0;offset<bytes.length;offset+=16384)
            send.accept(new Part(pos,clip.columns(),clip.rows(),bytes.length,offset,Arrays.copyOfRange(bytes,offset,Math.min(offset+16384,bytes.length))));
    }
}
