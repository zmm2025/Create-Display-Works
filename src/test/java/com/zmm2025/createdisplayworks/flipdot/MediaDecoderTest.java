package com.zmm2025.createdisplayworks.flipdot;

import com.zmm2025.createdisplayworks.media.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.junit.jupiter.api.Assertions.*;

class MediaDecoderTest {
    @Test void transparencyDitheringAndCompression() {
        byte[] rgba=new byte[8*8*4];java.util.Arrays.fill(rgba,(byte)255);
        rgba[3]=0;
        long[] normal=Monochrome.convert(rgba,8,8,128,false,false);
        assertEquals(-2L,normal[0]);assertEquals(0,Monochrome.convert(rgba,8,8,128,true,false)[0]);
        for(int i=0;i<64;i++){rgba[i*4]=rgba[i*4+1]=rgba[i*4+2]=(byte)128;rgba[i*4+3]=(byte)255;}
        long dither=Monochrome.convert(rgba,8,8,128,false,true)[0];assertEquals(32,Long.bitCount(dither));
        var clip=new MediaClip(1,1,new long[]{0,1,-1,dither},"test.mp4");
        var restored=MediaClip.unpack(1,1,4,clip.packed(),clip.filename());assertArrayEquals(clip.frames(),restored.frames());
    }
    @Test @EnabledIfSystemProperty(named="flipdot.youtubeCheck",matches="true")
    void publicYoutubeImport() throws Exception {
        var clip=MediaDecoder.decode("https://www.youtube.com/watch?v=FtutLA63Cp8",1,1,2,128,false,Path.of("build/media-import-test"),System.out::println);
        assertEquals(20,clip.count());
    }
    @Test void clipBoundsRejectMalformedUploads(){
        assertThrows(IllegalArgumentException.class,()->new MediaClip(0,1,new long[1]));
        assertThrows(IllegalArgumentException.class,()->new MediaClip(32,32,new long[1024]));
        assertThrows(IllegalArgumentException.class,()->new MediaClip(8,6,new long[49]));
        assertThrows(IllegalArgumentException.class,()->new MediaClip(1,1,new long[601]));
    }
    @Test @EnabledIfSystemProperty(named="flipdot.mediaChecks",matches="true")
    void importsAllRequestedFormatsWithTheReleaseDecoder() throws Exception {
        for(String extension:new String[]{"png","jpg","jpeg","heic","heif","ico","webp","svg","gif","apng","mp4","mov","mkv","webm"}) {
            var clip=MediaDecoder.decode(Path.of("build/media-decoder-check/sample."+extension).toAbsolutePath().toString(),8,6,2,128,false,Path.of("build/media-import-test"),System.out::println);
            assertTrue(clip.count()>0,extension);assertEquals(48,clip.frame(0).length);
            if(extension.equals("png")||extension.equals("jpg")||extension.equals("heic")||extension.equals("heif")||extension.equals("svg")||extension.equals("ico")||extension.equals("webp")||extension.equals("jpeg"))assertEquals(1,clip.count(),extension);
            System.out.println("MEDIA_FORMAT_PASS "+extension+" frames="+clip.count());
        }
        var animated=MediaDecoder.decode(Path.of("build/media-decoder-check/animated.webp").toAbsolutePath().toString(),8,6,2,128,false,Path.of("build/media-import-test"),System.out::println);
        assertEquals(20,animated.count(),"animated WebP");
        var remote=MediaDecoder.decode("https://huggingface.co/spaces/Nick088/Bad-Apple-Video/resolve/main/bad-apple.mp4",8,6,11,128,false,Path.of("./build/media-import-dot-path-test"),System.out::println);
        assertEquals(110,remote.count(),"direct URL movie");
    }
}
