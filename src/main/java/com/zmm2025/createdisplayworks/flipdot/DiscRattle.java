package com.zmm2025.createdisplayworks.flipdot;

import com.zmm2025.createdisplayworks.ModContent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import java.lang.ref.WeakReference;
import java.util.*;

/** One attenuated loop per board; the sole sample is modulated by physical disc motion. */
public final class DiscRattle extends AbstractTickableSoundInstance {
    // The sound only weakly holds its board, so entries the sound engine dropped don't keep old levels alive.
    private static final Map<FlipDotBlockEntity,DiscRattle> SOUNDS=new WeakHashMap<>();
    private static final int SILENT_TICKS=4;
    private final WeakReference<FlipDotBlockEntity> board;
    private int silent;
    private DiscRattle(FlipDotBlockEntity board) {
        super(ModContent.RATTLE.get(),SoundSource.BLOCKS,RandomSource.create());
        this.board=new WeakReference<>(board);looping=true;delay=0;volume=0;
        x=board.getBlockPos().getX()+.5;y=board.getBlockPos().getY()+.5;z=board.getBlockPos().getZ()+.5;
    }
    public static void update(FlipDotBlockEntity board) {
        var sound=SOUNDS.get(board);
        var sounds=Minecraft.getInstance().getSoundManager();
        // Level changes, resource reloads and muting drop sounds without stopping them, so ask the engine.
        if(sound!=null&&!sound.isStopped()&&sounds.isActive(sound))return;
        sound=new DiscRattle(board);sound.silent=SILENT_TICKS;sound.tick();
        if(sound.isStopped())return;
        SOUNDS.put(board,sound);sounds.play(sound);
    }
    @Override public boolean canStartSilent(){return true;}
    @Override public void tick() {
        var board=this.board.get();
        if(board==null||board.isRemoved()||board.getLevel()!=Minecraft.getInstance().level){stop();if(board!=null)SOUNDS.remove(board);return;}
        var group=board.assembly();
        if(group==null||group.controller()!=board){stop();SOUNDS.remove(board);return;}
        int moving=0;float speed=0;
        // Count discs that moved at any point in the last tick: a 256 RPM flip lasts exactly one tick,
        // so sampling only the current instant usually finds every disc already settled.
        for(var tile:group.tiles()){int count=tile.movingDiscs(-1);moving+=count;if(count>0)speed=Math.max(speed,tile.discSpeed());}
        // Short gaps between video frames keep the loop alive instead of restarting it.
        if(moving==0){if(++silent>SILENT_TICKS){stop();SOUNDS.remove(board);}else volume=0;return;}
        silent=0;
        pitch=Math.clamp(.5f+(float)Math.sqrt(speed/256f)*1.5f,.5f,2);
        volume=Math.clamp((float)Math.sqrt(moving/256f)*.25f,.025f,.65f);
        var center=board.getBlockPos().relative(group.right(),group.width()/2).below(group.height()/2);
        x=center.getX()+.5;y=center.getY()+.5;z=center.getZ()+.5;
    }
}
