package com.zmm2025.createdisplayworks.media;

import com.mojang.logging.LogUtils;
import com.simibubi.create.Create;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.graph.DimensionPalette;
import com.zmm2025.createdisplayworks.flipdot.FlipDotBlock;
import com.zmm2025.createdisplayworks.flipdot.FlipDotBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import java.io.IOException;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Keeps media clips in the world's data folder, so a board's chunk only stores a short reference instead of the
 * whole clip. Files are named after a hash of their contents, so they're written once and identical clips share one.
 * <p>
 * Boards claim the clip they show and release it when they're removed or their media is cleared. Contraptions that
 * carry boards claim their clips too, so they're kept while the contraption is unloaded. When the world saves, clips
 * that nothing claims and that no train or online player holds count as unused, and {@link MediaRetention} deletes
 * them only once they outgrow their share of the disk. Waiting for the save keeps a crash from leaving saved boards
 * pointing at a deleted file. If a release is missed, the file is kept rather than lost.
 */
public final class MediaStore {
    private static final Pattern ID=Pattern.compile("[0-9a-f]{32}");
    private static final String FOLDER="data/create_display_works/media", SUFFIX=".bin";
    private MediaStore(){}
    public static void registerLifecycle() {
        NeoForge.EVENT_BUS.addListener((LevelEvent.Save e)->{
            if(e.getLevel() instanceof ServerLevel level && level.dimension()==Level.OVERWORLD)deleteUnclaimed(level.getServer());
        });
        // A contraption that unloads keeps its claim, filed under the chunk it's saved in. One that's destroyed or
        // disassembled drops it; disassembled boards claim their clips again where they're placed.
        NeoForge.EVENT_BUS.addListener((EntityLeaveLevelEvent e)->{
            if(!(e.getLevel() instanceof ServerLevel level) || !(e.getEntity() instanceof AbstractContraptionEntity contraption))return;
            var claims=Claims.of(level.getServer());
            var reason=contraption.getRemovalReason();
            if(reason!=null && reason.shouldDestroy()){if(claims.contraptions.remove(contraption.getUUID())!=null)claims.setDirty();}
            else claims.carry(level,contraption);
        });
    }
    /** Writes the clip if it isn't stored yet and returns its id, or null if it couldn't be written. */
    public static String save(MinecraftServer server,MediaClip clip) {return save(server,clip.archive());}
    /** Like {@link #save(MinecraftServer,MediaClip)}, for an archive that's already built. */
    public static String save(MinecraftServer server,byte[] bytes) {
        String id;
        try{id=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes),0,16);}
        catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
        Path file=file(server,id);
        try {
            if(Files.size(file)==bytes.length)return id;
        }catch(IOException ignored){}
        try {
            Files.createDirectories(file.getParent());
            Path temp=Files.createTempFile(file.getParent(),id,".tmp");
            Files.write(temp,bytes);
            Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
            return id;
        }catch(IOException e){LogUtils.getLogger().error("Unable to store flip-dot media {}",file,e);return null;}
    }
    /** Returns the stored clip, or null if the id is invalid or its file is missing or damaged. */
    public static MediaClip load(String id) {
        byte[] bytes=read(id);if(bytes==null)return null;
        try{return MediaClip.fromArchive(bytes);}
        catch(IllegalArgumentException e){LogUtils.getLogger().error("Unable to read flip-dot media {}",id,e);return null;}
    }
    /** Returns the stored archive's bytes, or null if the id is invalid or its file is missing or unreadable. */
    public static byte[] read(String id) {
        MinecraftServer server=ServerLifecycleHooks.getCurrentServer();
        if(server==null||!ID.matcher(id).matches())return null;
        Path file=file(server,id);
        try{return Files.readAllBytes(file);}
        catch(NoSuchFileException e){LogUtils.getLogger().warn("Flip-dot media {} is missing",file);return null;}
        catch(IOException e){LogUtils.getLogger().error("Unable to read flip-dot media {}",file,e);return null;}
    }
    /** Records that the board at this position shows the clip. Claiming again is harmless. */
    public static void claim(ServerLevel level,BlockPos pos,String id) {
        var claims=Claims.of(level.getServer());
        if(claims.boards.computeIfAbsent(id,k->new HashSet<>()).add(key(level,pos)))claims.setDirty();
    }
    /** Records that the board at this position no longer shows the clip. */
    public static void release(ServerLevel level,BlockPos pos,String id) {
        var claims=Claims.of(level.getServer());
        var boards=claims.boards.get(id);
        if(boards==null||!boards.remove(key(level,pos)))return;
        if(boards.isEmpty())claims.boards.remove(id);
        claims.setDirty();
    }
    private static void deleteUnclaimed(MinecraftServer server) {
        var claims=Claims.of(server);
        dropStaleClaims(server,claims);
        updateContraptionClaims(server,claims);
        Path folder=server.getWorldPath(LevelResource.ROOT).resolve(FOLDER);
        if(Files.isDirectory(folder))try(Stream<Path> files=Files.list(folder)) {
            Map<String,Path> stored=new HashMap<>();Map<String,Long> sizes=new HashMap<>();
            for(Path file:files.toList()) {
                String name=file.getFileName().toString();
                if(!name.endsWith(SUFFIX))continue;
                String id=name.substring(0,name.length()-SUFFIX.length());
                stored.put(id,file);sizes.put(id,Files.size(file));
            }
            Set<String> referenced=new HashSet<>(claims.boards.keySet());
            referenced.addAll(claims.untracked);
            claims.contraptions.values().forEach(carried->referenced.addAll(carried.media()));
            collectHeldMedia(server,referenced);
            var waiting=new HashMap<>(claims.unreferencedSince);
            for(String id:MediaRetention.excess(sizes,referenced,claims.unreferencedSince,server.overworld().getGameTime(),MediaLimits.UNUSED_MIB.get()*1024L*1024))
                Files.deleteIfExists(stored.get(id));
            if(!waiting.equals(claims.unreferencedSince))claims.setDirty();
        }catch(IOException e){LogUtils.getLogger().error("Unable to clean up flip-dot media in {}",folder,e);}
        // This runs after the overworld saved its data, so save the claims now. Otherwise this save's changes would
        // wait for the next one, and be lost if the server is stopping.
        if(claims.isDirty())server.overworld().getDataStorage().save();
    }
    /**
     * Drops claims whose board is loaded but no longer shows that clip. That catches boards that left without releasing
     * their claim, such as ones a contraption picked up, once their old position is loaded again.
     */
    private static void dropStaleClaims(MinecraftServer server,Claims claims) {
        boolean changed=false;
        for(var entry=claims.boards.entrySet().iterator();entry.hasNext();) {
            var claim=entry.next();
            for(var boards=claim.getValue().iterator();boards.hasNext();) {
                String[] key=boards.next().split(" ");
                ServerLevel level=server.getLevel(ResourceKey.create(Registries.DIMENSION,ResourceLocation.parse(key[0])));
                BlockPos pos=BlockPos.of(Long.parseLong(key[1]));
                if(level==null||!level.hasChunkAt(pos))continue;
                if(level.getBlockEntity(pos) instanceof FlipDotBlockEntity board&&claim.getKey().equals(board.mediaId()))continue;
                boards.remove();changed=true;
            }
            if(claim.getValue().isEmpty())entry.remove();
        }
        if(changed)claims.setDirty();
    }
    /**
     * Files every loaded contraption that carries boards with media under its current chunk. Drops the claims of
     * contraptions that are gone: their chunk is loaded but they aren't in it.
     */
    private static void updateContraptionClaims(MinecraftServer server,Claims claims) {
        Set<UUID> loaded=new HashSet<>();
        for(ServerLevel level:server.getAllLevels())for(Entity entity:level.getAllEntities())
            if(entity instanceof AbstractContraptionEntity contraption){loaded.add(entity.getUUID());claims.carry(level,contraption);}
        boolean changed=claims.contraptions.entrySet().removeIf(entry->{
            if(loaded.contains(entry.getKey()))return false;
            ServerLevel level=server.getLevel(entry.getValue().dimension());
            return level==null || level.areEntitiesLoaded(entry.getValue().chunk());
        });
        if(changed)claims.setDirty();
    }
    /**
     * Adds clips held where no claim reaches: on trains whose carriages aren't loaded, and in online players' data,
     * such as a board carried with Carry On or a board item with its block data.
     */
    private static void collectHeldMedia(MinecraftServer server,Set<String> ids) {
        var dimensions=new DimensionPalette();
        for(Train train:Create.RAILWAYS.trains.values())for(Carriage carriage:train.carriages)
            if(carriage.anyAvailableEntity()==null)collectMediaIds(carriage.write(dimensions,server.registryAccess()),ids);
        for(var player:server.getPlayerList().getPlayers())collectMediaIds(player.saveWithoutId(new CompoundTag()),ids);
    }
    /** Adds every board media id found anywhere in saved data. */
    static void collectMediaIds(Tag tag,Set<String> ids) {
        if(tag instanceof CompoundTag compound) {
            if(compound.get("MediaId") instanceof StringTag id && ID.matcher(id.getAsString()).matches())ids.add(id.getAsString());
            for(String key:compound.getAllKeys())collectMediaIds(compound.get(key),ids);
        }else if(tag instanceof ListTag list)for(Tag element:list)collectMediaIds(element,ids);
    }
    /** The clips shown by the boards a contraption carries. */
    private static Set<String> mediaOn(AbstractContraptionEntity entity) {
        var contraption=entity.getContraption();
        if(contraption==null)return Set.of();
        Set<String> ids=new HashSet<>();
        for(var block:contraption.getBlocks().values()) {
            if(!(block.state().getBlock() instanceof FlipDotBlock) || block.nbt()==null)continue;
            String id=block.nbt().getString("MediaId");
            if(ID.matcher(id).matches())ids.add(id);
        }
        return ids;
    }
    private static String key(ServerLevel level,BlockPos pos){return level.dimension().location()+" "+pos.asLong();}
    private static Path file(MinecraftServer server,String id) {
        return server.getWorldPath(LevelResource.ROOT).resolve(FOLDER).resolve(id+SUFFIX);
    }
    /** A contraption's clips, filed under the chunk it was last seen in. */
    private record Carried(ResourceKey<Level> dimension,long chunk,Set<String> media){}
    /** Which boards and contraptions claim each clip, saved with the overworld. */
    private static final class Claims extends SavedData {
        final Map<String,Set<String>> boards=new HashMap<>();
        final Map<UUID,Carried> contraptions=new HashMap<>();
        /** When each clip nothing referenced was first noticed, in overworld game time. See {@link MediaRetention}. */
        final Map<String,Long> unreferencedSince=new HashMap<>();
        /**
         * Files stored before this world tracked claims. Their boards claim them as their chunks load, so they're only
         * cleaned up from the next session on.
         */
        final Set<String> untracked=new HashSet<>();
        static Claims of(MinecraftServer server) {
            return server.overworld().getDataStorage().computeIfAbsent(new SavedData.Factory<>(()->fresh(server),Claims::read,null),"create_display_works_media");
        }
        private static Claims fresh(MinecraftServer server) {
            var claims=new Claims();
            try(Stream<Path> files=Files.list(server.getWorldPath(LevelResource.ROOT).resolve(FOLDER))) {
                files.map(f->f.getFileName().toString()).filter(n->n.endsWith(SUFFIX)).forEach(n->claims.untracked.add(n.substring(0,n.length()-SUFFIX.length())));
            }catch(IOException ignored){}
            claims.setDirty();
            return claims;
        }
        /** Records which clips a contraption carries now, and where. */
        void carry(ServerLevel level,AbstractContraptionEntity contraption) {
            var media=mediaOn(contraption);
            UUID uuid=contraption.getUUID();
            var carried=media.isEmpty()?null:new Carried(level.dimension(),contraption.chunkPosition().toLong(),media);
            var previous=carried==null?contraptions.remove(uuid):contraptions.put(uuid,carried);
            if(!Objects.equals(previous,carried))setDirty();
        }
        private static Claims read(CompoundTag tag,HolderLookup.Provider registries) {
            var claims=new Claims();
            for(String id:tag.getAllKeys()) {
                if(!ID.matcher(id).matches())continue;
                var boards=new HashSet<String>();
                for(Tag board:tag.getList(id,Tag.TAG_STRING))boards.add(board.getAsString());
                if(!boards.isEmpty())claims.boards.put(id,boards);
            }
            var contraptions=tag.getCompound("Contraptions");
            for(String uuid:contraptions.getAllKeys())try {
                var entry=contraptions.getCompound(uuid);var media=new HashSet<String>();
                for(Tag id:entry.getList("Media",Tag.TAG_STRING))media.add(id.getAsString());
                var dimension=ResourceKey.create(Registries.DIMENSION,ResourceLocation.parse(entry.getString("Dimension")));
                if(!media.isEmpty())claims.contraptions.put(UUID.fromString(uuid),new Carried(dimension,entry.getLong("Chunk"),media));
            }catch(IllegalArgumentException ignored){}
            var waiting=tag.getCompound("Unreferenced");
            for(String id:waiting.getAllKeys())if(ID.matcher(id).matches())claims.unreferencedSince.put(id,waiting.getLong(id));
            return claims;
        }
        @Override public CompoundTag save(CompoundTag tag,HolderLookup.Provider registries) {
            boards.forEach((id,keys)->{var list=new ListTag();for(String key:keys)list.add(StringTag.valueOf(key));tag.put(id,list);});
            var carried=new CompoundTag();
            contraptions.forEach((uuid,entry)->{
                var media=new ListTag();for(String id:entry.media())media.add(StringTag.valueOf(id));
                var saved=new CompoundTag();
                saved.putString("Dimension",entry.dimension().location().toString());saved.putLong("Chunk",entry.chunk());saved.put("Media",media);
                carried.put(uuid.toString(),saved);
            });
            tag.put("Contraptions",carried);
            var waiting=new CompoundTag();unreferencedSince.forEach(waiting::putLong);tag.put("Unreferenced",waiting);
            return tag;
        }
    }
}
