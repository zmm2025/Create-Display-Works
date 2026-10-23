package com.zmm2025.createdisplayworks.media;

import com.twelvemonkeys.imageio.plugins.webp.WebPImageReaderSpi;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import javax.imageio.stream.FileImageInputStream;

/** WebP timing/composition, with frame pixels decoded by the maintained ImageIO reader. */
final class WebpAnimation {
    private record Frame(int x,int y,int w,int h,int duration,int flags) {}
    private static int u24(RandomAccessFile in) throws IOException {return in.readUnsignedByte()|in.readUnsignedByte()<<8|in.readUnsignedByte()<<16;}
    static MediaClip decode(Path input,int columns,int rows,int seconds,int threshold,boolean invert,MediaDecoder.Scale scale,boolean dither,String name) throws Exception {
        var frames=new ArrayList<Frame>();int width=0,height=0,background=0;
        try(var in=new RandomAccessFile(input.toFile(),"r")) {
            in.seek(12);
            while(in.getFilePointer()+8<=in.length()) {
                byte[] id=new byte[4];in.readFully(id);String chunk=new String(id,java.nio.charset.StandardCharsets.US_ASCII);
                long size=Integer.toUnsignedLong(Integer.reverseBytes(in.readInt())),start=in.getFilePointer(),end=start+size+(size&1);
                if(end>in.length())throw new IOException("Truncated WebP chunk.");
                if(chunk.equals("VP8X")&&size>=10){in.skipBytes(4);width=u24(in)+1;height=u24(in)+1;}
                if(chunk.equals("ANIM")&&size>=6)background=Integer.reverseBytes(in.readInt());
                if(chunk.equals("ANMF")&&size>=16){frames.add(new Frame(u24(in)*2,u24(in)*2,u24(in)+1,u24(in)+1,Math.max(1,u24(in)),in.readUnsignedByte()));if(frames.size()>10000)throw new IOException("Too many WebP animation frames.");}
                in.seek(end);
            }
        }
        if(width<1||height<1||(long)width*height>16_777_216||frames.isEmpty())throw new IOException("WebP canvas exceeds the decoder memory limit.");
        var reader=new WebPImageReaderSpi().createReaderInstance();
        double bound=Math.min(1,Math.min(256.0/Math.max(width,height),Math.sqrt(16384.0/((double)width*height))));
        int w=Math.max(1,(int)(width*bound)),h=Math.max(1,(int)(height*bound));var pixels=new ByteArrayOutputStream();int count=0,time=0;
        var canvas=new BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB);
        var graphics=canvas.createGraphics();graphics.setComposite(AlphaComposite.Src);graphics.setColor(new Color(background,true));graphics.fillRect(0,0,width,height);
        long deadline=System.nanoTime()+90_000_000_000L;
        try(var stream=new FileImageInputStream(input.toFile())) {
            reader.setInput(stream);
            for(int index=0;index<frames.size()&&count<seconds*MediaClip.FPS;index++) {
                if(Thread.currentThread().isInterrupted())throw new InterruptedIOException();
                if(System.nanoTime()>deadline)throw new IOException("WebP conversion timed out.");
                Frame frame=frames.get(index);
                if((long)frame.x+frame.w>width||(long)frame.y+frame.h>height)throw new IOException("Invalid WebP frame bounds.");
                BufferedImage decoded=reader.read(index);
                graphics.setComposite((frame.flags&2)!=0?AlphaComposite.Src:AlphaComposite.SrcOver);graphics.drawImage(decoded,frame.x,frame.y,null);
                while(count*1000/MediaClip.FPS<Math.min(seconds*1000,time+frame.duration)) {
                    var resized=new BufferedImage(w,h,BufferedImage.TYPE_INT_ARGB);var g=resized.createGraphics();
                    try {
                        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                        g.drawImage(canvas,0,0,w,h,null);
                    }finally{g.dispose();}
                    byte[] rgba=new byte[w*h*4];
                    for(int y=0;y<h;y++)for(int x=0;x<w;x++){int color=resized.getRGB(x,y),i=(y*w+x)*4;rgba[i]=(byte)(color>>16);rgba[i+1]=(byte)(color>>8);rgba[i+2]=(byte)color;rgba[i+3]=(byte)(color>>>24);}
                    for(int i=0;i<rgba.length;i+=4){pixels.write((int)Math.round(Monochrome.luminance(rgba[i]&255,rgba[i+1]&255,rgba[i+2]&255)));pixels.write(rgba[i+3]&255);}count++;

                }
                if((frame.flags&1)!=0){graphics.setComposite(AlphaComposite.Src);graphics.setColor(new Color(background,true));graphics.fillRect(frame.x,frame.y,frame.w,frame.h);}
                time+=frame.duration;
            }
        }finally{reader.dispose();graphics.dispose();}
        return EditableMedia.fromPixels(w,h,pixels.toByteArray()).render(columns,rows,name,new MediaSettings(threshold,invert,dither,scale,seconds));
    }
}
