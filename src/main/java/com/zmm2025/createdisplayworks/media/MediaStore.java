package com.zmm2025.createdisplayworks.media;

import com.mojang.logging.LogUtils;
import com.zmm2025.createdisplayworks.flipdot.FlipDotBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.common.NeoForge;
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
 * Boards claim the clip they show and release it when they're removed or their media is cleared. When the world saves,
 * clip files no board claims are deleted. Waiting for the save keeps a crash from leaving saved boards pointing at a
 * deleted file. If a release is missed, the file is kept rather than lost.
 */
public final class MediaStore {
    private static final Pattern ID=Pattern.compile("[0-9a-f]{32}");
    private static final String FOLDER="data/create_display_works/media", SUFFIX=".bin";
    private MediaStore(){}
    public static void registerLifecycle() {
        NeoForge.EVENT_BUS.addListener((LevelEvent.Save e)->{
            if(e.getLevel() instanceof ServerLevel level && level.dimension()==Level.OVERWORLD)deleteUnclaimed(level.getServer());
        });
    }
    /** Writes the clip if it isn't stored yet and returns its id, or null if it couldn't be written. */
    public static String save(MinecraftServer server,MediaClip clip) {
        byte[] bytes=clip.archive();
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
        MinecraftServer server=ServerLifecycleHooks.getCurrentServer();
        if(server==null||!ID.matcher(id).matches())return null;
        Path file=file(server,id);
        try{return MediaClip.fromArchive(Files.readAllBytes(file));}
        catch(NoSuchFileException e){LogUtils.getLogger().warn("Flip-dot media {} is missing",file);return null;}
        catch(IOException|IllegalArgumentException e){LogUtils.getLogger().error("Unable to read flip-dot media {}",file,e);return null;}
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
        Path folder=server.getWorldPath(LevelResource.ROOT).resolve(FOLDER);
        if(!Files.isDirectory(folder))return;
        try(Stream<Path> files=Files.list(folder)) {
            for(Path file:files.toList()) {
                String name=file.getFileName().toString();
                if(!name.endsWith(SUFFIX))continue;
                String id=name.substring(0,name.length()-SUFFIX.length());
                if(claims.boards.containsKey(id)||claims.untracked.contains(id))continue;
                Files.deleteIfExists(file);
            }
        }catch(IOException e){LogUtils.getLogger().error("Unable to clean up flip-dot media in {}",folder,e);}
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
    private static String key(ServerLevel level,BlockPos pos){return level.dimension().location()+" "+pos.asLong();}
    private static Path file(MinecraftServer server,String id) {
        return server.getWorldPath(LevelResource.ROOT).resolve(FOLDER).resolve(id+SUFFIX);
    }
    /** Which boards claim each clip, saved with the overworld. */
    private static final class Claims extends SavedData {
        final Map<String,Set<String>> boards=new HashMap<>();
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
        private static Claims read(CompoundTag tag,HolderLookup.Provider registries) {
            var claims=new Claims();
            for(String id:tag.getAllKeys()) {
                var boards=new HashSet<String>();
                for(Tag board:tag.getList(id,Tag.TAG_STRING))boards.add(board.getAsString());
                if(!boards.isEmpty())claims.boards.put(id,boards);
            }
            return claims;
        }
        @Override public CompoundTag save(CompoundTag tag,HolderLookup.Provider registries) {
            boards.forEach((id,keys)->{var list=new ListTag();for(String key:keys)list.add(StringTag.valueOf(key));tag.put(id,list);});
            return tag;
        }
    }
}
