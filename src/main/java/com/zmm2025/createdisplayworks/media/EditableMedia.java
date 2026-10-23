package com.zmm2025.createdisplayworks.media;

import java.io.*;
import java.util.zip.*;

/** Bounded, aspect-preserving 8-bit luminance and alpha frames at the display's fixed frame rate. */
public final class EditableMedia {
    public static final int MAX_PIXELS=16384, MAX_EDGE=256, MAX_BYTES=24*1024*1024;
    private final int width,height,count;
    private final byte[] packed;
    public EditableMedia(int width,int height,int count,byte[] packed) {
        if(width<1||height<1||width>MAX_EDGE||height>MAX_EDGE||width*height>MAX_PIXELS||count<1||count>MediaClip.MAX_FRAMES||packed.length>MAX_BYTES)
            throw new IllegalArgumentException("Invalid editable media");
        this.width=width;this.height=height;this.count=count;this.packed=packed;
    }
    public int width(){return width;} public int height(){return height;} public int count(){return count;}
    public static EditableMedia fromRgba(int w,int h,byte[] rgba) {
        if(rgba.length%4!=0)throw new IllegalArgumentException("Invalid RGBA pixels");
        byte[] pixels=new byte[rgba.length/2];
        for(int i=0,j=0;i<rgba.length;i+=4,j+=2){pixels[j]=(byte)Math.round(Monochrome.luminance(rgba[i]&255,rgba[i+1]&255,rgba[i+2]&255));pixels[j+1]=rgba[i+3];}
        return fromPixels(w,h,pixels);
    }
    public static EditableMedia fromPixels(int w,int h,byte[] pixels) {
        if(w<1||h<1||w>MAX_EDGE||h>MAX_EDGE||w*h>MAX_PIXELS||pixels.length%(w*h*2)!=0)throw new IllegalArgumentException("Invalid source pixels");
        try(var bytes=new ByteArrayOutputStream()){
            try(var out=new DeflaterOutputStream(bytes)){out.write(pixels);}
            return new EditableMedia(w,h,pixels.length/(w*h*2),bytes.toByteArray());
        }catch(IOException e){throw new UncheckedIOException(e);}
    }
    /** Validate untrusted archives without retaining a second uncompressed source. */
    public void validate() {
        try(var in=new InflaterInputStream(new ByteArrayInputStream(packed))){
            long expected=(long)width*height*count*2;
            in.skipNBytes(expected);
            if(in.read()!=-1)throw new IllegalArgumentException("Excess editable frame data");
        }catch(IOException e){throw new IllegalArgumentException("Invalid editable frame data",e);}
    }
    public byte[] pixels() {
        int size=width*height*count*2;
        try(var in=new InflaterInputStream(new ByteArrayInputStream(packed))){
            byte[] result=in.readNBytes(size);if(result.length!=size||in.read()!=-1)throw new IllegalArgumentException("Invalid editable frame data");return result;
        }catch(IOException e){throw new IllegalArgumentException("Invalid editable frame data",e);}
    }
    public long[] frame(byte[] pixels,int frame,int columns,int rows,MediaSettings settings) {
        int w=columns*8,h=rows*8;byte[] rgba=new byte[w*h*4];
        double sx=w/(double)width,sy=h/(double)height;
        if(settings.scale()!=MediaDecoder.Scale.STRETCH)sx=sy=settings.scale()==MediaDecoder.Scale.FIT?Math.min(sx,sy):Math.max(sx,sy);
        double ox=(w-width*sx)/2,oy=(h-height*sy)/2;
        for(int y=0;y<h;y++)for(int x=0;x<w;x++){
            double u=(x+.5-ox)/sx-.5,v=(y+.5-oy)/sy-.5;
            if(u<-.5||v<-.5||u>=width-.5||v>=height-.5)continue;
            // Sample premultiplied luminance so transparent edges cannot introduce halos.
            int x0=(int)Math.floor(u),y0=(int)Math.floor(v);double fx=u-x0,fy=v-y0,alpha=0,light=0;
            for(int j=0;j<2;j++)for(int i=0;i<2;i++){
                int index=(frame*width*height+Math.clamp(y0+j,0,height-1)*width+Math.clamp(x0+i,0,width-1))*2;
                double weight=(i==0?1-fx:fx)*(j==0?1-fy:fy),a=(pixels[index+1]&255)*weight;
                alpha+=a;light+=(pixels[index]&255)*a;
            }
            int p=(y*w+x)*4;byte gray=(byte)Math.round(alpha==0?0:light/alpha);
            rgba[p]=rgba[p+1]=rgba[p+2]=gray;rgba[p+3]=(byte)Math.round(alpha);
        }
        return Monochrome.convert(rgba,w,h,settings.threshold(),settings.invert(),settings.dither());
    }
    public int frameCount(MediaSettings settings){return Math.min(count,settings.seconds()*MediaClip.FPS);}
    public MediaClip render(int columns,int rows,String filename,MediaSettings settings) {
        byte[] pixels=pixels();int tiles=columns*rows,n=frameCount(settings);long[] bits=new long[tiles*n];
        for(int f=0;f<n;f++){
            if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException();
            System.arraycopy(frame(pixels,f,columns,rows,settings),0,bits,f*tiles,tiles);
        }
        return new MediaClip(columns,rows,bits,filename,this,settings);
    }
    public void write(DataOutput out)throws IOException{out.writeInt(width);out.writeInt(height);out.writeInt(count);out.writeInt(packed.length);out.write(packed);}
    public static EditableMedia read(DataInput in)throws IOException{
        int w=in.readInt(),h=in.readInt(),n=in.readInt(),size=in.readInt();if(size<1||size>MAX_BYTES)throw new IOException("Invalid editable media size");
        byte[] bytes=new byte[size];in.readFully(bytes);return new EditableMedia(w,h,n,bytes);
    }
}
