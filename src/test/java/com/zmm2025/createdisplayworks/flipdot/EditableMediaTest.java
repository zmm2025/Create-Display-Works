package com.zmm2025.createdisplayworks.flipdot;

import com.zmm2025.createdisplayworks.media.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EditableMediaTest {
    private EditableMedia source(int count){
        byte[] pixels=new byte[16*8*count*2];
        for(int f=0;f<count;f++)for(int y=0;y<8;y++)for(int x=0;x<16;x++){
            int i=(f*128+y*16+x)*2;pixels[i]=(byte)(x*17);pixels[i+1]=(byte)(x==0?0:255);
        }
        return EditableMedia.fromPixels(16,8,pixels);
    }
    @Test void archivePreservesEditableSourceAndControls(){
        var source=source(30);var settings=new MediaSettings(91,true,true,MediaDecoder.Scale.FIT,1);
        var clip=source.render(2,1,"gradient.mp4",settings);
        var restored=MediaClip.fromArchive(clip.archive());
        assertEquals(settings,restored.settings());assertArrayEquals(clip.frames(),restored.frames());
        assertArrayEquals(source.pixels(),restored.editable().pixels());
        assertEquals(10,restored.count());assertEquals(30,restored.editable().count());
        var longer=restored.editable().render(2,1,restored.filename(),new MediaSettings(91,true,true,MediaDecoder.Scale.FIT,3));
        assertEquals(30,longer.count());
    }
    @Test void thresholdInversionDitheringAndFramingRemainEditable(){
        var source=source(1);byte[] pixels=source.pixels();
        var normal=source.frame(pixels,0,2,1,MediaSettings.DEFAULT);
        assertNotEquals(normal[0],source.frame(pixels,0,2,1,new MediaSettings(40,false,false,MediaDecoder.Scale.STRETCH,60))[0]);
        assertNotEquals(normal[0],source.frame(pixels,0,2,1,new MediaSettings(128,false,true,MediaDecoder.Scale.STRETCH,60))[0]);
        var inverted=source.frame(pixels,0,2,1,new MediaSettings(128,true,false,MediaDecoder.Scale.STRETCH,60));
        assertEquals(0,inverted[0]&0x0101010101010101L,"Transparent pixels always use the back color");
        var fit=source.frame(pixels,0,1,1,new MediaSettings(128,false,false,MediaDecoder.Scale.FIT,60));
        var fill=source.frame(pixels,0,1,1,new MediaSettings(128,false,false,MediaDecoder.Scale.FILL,60));
        assertEquals(0,fit[0]&0xFFFF00000000FFFFL,"Fit has back-color padding");assertNotEquals(fit[0],fill[0]);
    }
    @Test void sourcesShrinkToTwiceTheBoardsDotsAndNoFurther(){
        assertArrayEquals(new int[]{22,16},EditableMedia.compactSize(147,110,1,1),"Fill on 8x8 dots still samples two pixels per dot");
        assertArrayEquals(new int[]{64,37},EditableMedia.compactSize(170,96,4,2));
        assertArrayEquals(new int[]{147,110},EditableMedia.compactSize(147,110,16,8),"Large boards keep the whole source");
        var wide=EditableMedia.compactSize(170,96,1,4);
        assertTrue(wide[0]>=16&&wide[1]>=64,"Tall boards keep enough height");
    }
    @Test void shrinkingKeepsFramesAndIsStable(){
        byte[] pixels=new byte[160*90*3*2];
        for(int i=0;i<pixels.length;i+=2){pixels[i]=(byte)(i*7);pixels[i+1]=(byte)255;}
        var full=EditableMedia.fromPixels(160,90,pixels);var small=full.compactFor(1,1);
        assertEquals(3,small.count());assertEquals(29,small.width());assertEquals(16,small.height());
        assertSame(small,small.compactFor(1,1),"Already-shrunk sources are left alone");
        assertSame(full,full.compactFor(16,16));
        byte[] alpha=small.pixels();for(int i=1;i<alpha.length;i+=2)assertEquals((byte)255,alpha[i],"Opaque sources stay opaque");
        assertEquals(small.width(),EditableMedia.fromPixels(160,90,pixels,1,1).width());
    }
    @Test void storedClipsKeepPlaybackAndSettings(){
        var settings=new MediaSettings(91,true,true,MediaDecoder.Scale.FILL,1);
        byte[] pixels=new byte[160*80*10*2];java.util.Arrays.fill(pixels,(byte)200);
        var clip=EditableMedia.fromPixels(160,80,pixels).render(1,1,"wide.mp4",settings);
        var compact=clip.withCompactSource();
        assertArrayEquals(clip.frames(),compact.frames());assertEquals(settings,compact.settings());assertEquals(32,compact.editable().width());
        assertSame(compact,compact.withCompactSource());
        var restored=MediaClip.fromArchive(compact.archive());assertEquals(32,restored.editable().width());
        assertNull(compact.withoutSource().editable());assertArrayEquals(clip.frames(),compact.withoutSource().frames());
    }
    @Test void corruptAndOversizedSourcesAreRejected(){
        assertThrows(IllegalArgumentException.class,()->new EditableMedia(256,256,1,new byte[1]));
        assertThrows(IllegalArgumentException.class,()->new EditableMedia(16,8,601,new byte[1]));
        assertThrows(IllegalArgumentException.class,()->new EditableMedia(16,8,1,new byte[]{1,2,3}).pixels());
        byte[] archive=source(1).render(1,1,"image",MediaSettings.DEFAULT).archive();
        assertThrows(IllegalArgumentException.class,()->MediaClip.fromArchive(java.util.Arrays.copyOf(archive,archive.length-1)));
    }
    @Test void completeArchiveWithCorruptEditableDataIsRejected(){
        var invalid=new EditableMedia(8,8,1,new byte[]{1,2,3});
        var clip=new MediaClip(1,1,new long[]{0},"corrupt.png",invalid,MediaSettings.DEFAULT);
        assertThrows(IllegalArgumentException.class,()->MediaClip.fromArchive(clip.archive()));
    }
    @Test void supplementaryUnicodeFilenamesFitNetworkLimit(){
        var clip=new MediaClip(1,1,new long[]{0},"\uD83D\uDE00".repeat(100));
        assertEquals(128,clip.filename().length());
        assertFalse(Character.isHighSurrogate(clip.filename().charAt(127)));
        assertEquals(clip.filename(),MediaClip.fromArchive(clip.archive()).filename());
    }
}
