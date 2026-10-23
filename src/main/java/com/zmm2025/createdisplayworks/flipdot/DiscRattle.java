package com.zmm2025.createdisplayworks.flipdot;

import com.zmm2025.createdisplayworks.ModContent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import java.util.*;

/** One attenuated loop per board; the sole sample is modulated by physical disc motion. */
public final class DiscRattle extends AbstractTickableSoundInstance {
    private static final Map<FlipDotBlockEntity,DiscRattle> SOUNDS=new WeakHashMap<>();
    private final FlipDotBlockEntity board;
    private DiscRattle(FlipDotBlockEntity board) {
        super(ModContent.RATTLE.get(),SoundSource.BLOCKS,RandomSource.create());
        this.board=board;looping=true;delay=0;volume=0;
        x=board.getBlockPos().getX()+.5;y=board.getBlockPos().getY()+.5;z=board.getBlockPos().getZ()+.5;
    }
    public static void update(FlipDotBlockEntity board) {
        var sound=SOUNDS.get(board);
        if(sound!=null&&!sound.isStopped())return;
        sound=new DiscRattle(board);sound.tick();
        if(sound.isStopped())return;
        SOUNDS.put(board,sound);Minecraft.getInstance().getSoundManager().play(sound);
    }
    @Override public boolean canStartSilent(){return true;}
    @Override public void tick() {
        if(board.isRemoved()||board.getLevel()!=Minecraft.getInstance().level){stop();SOUNDS.remove(board);return;}
        var group=board.assembly();
        if(group==null||group.controller()!=board){stop();SOUNDS.remove(board);return;}
        int moving=0;float speed=0;
        for(var tile:group.tiles()){int count=tile.movingDiscs(0);moving+=count;if(count>0)speed=Math.max(speed,tile.discSpeed());}
        if(moving==0){stop();SOUNDS.remove(board);return;}
        pitch=Math.clamp(.5f+(float)Math.sqrt(speed/256f)*1.5f,.5f,2);
        volume=Math.clamp((float)Math.sqrt(moving/256f)*.25f,.025f,.65f);
        var center=board.getBlockPos().relative(group.right(),group.width()/2).below(group.height()/2);
        x=center.getX()+.5;y=center.getY()+.5;z=center.getZ()+.5;
    }
}
