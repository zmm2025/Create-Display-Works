package com.zmm2025.createdisplayworks.media;

public final class Monochrome {
    private static final int[][] BAYER={{0,8,2,10},{12,4,14,6},{3,11,1,9},{15,7,13,5}};
    private Monochrome() {}
    static double luminance(int red,int green,int blue){return .2126*red+.7152*green+.0722*blue;}
    public static long[] convert(byte[] rgba,int w,int h,int threshold,boolean invert,boolean dither) {
        int count=rgba.length/(w*h*4),columns=w/8,tiles=columns*(h/8);
        long[] frames=new long[count*tiles];
        for(int f=0;f<count;f++)for(int y=0;y<h;y++)for(int x=0;x<w;x++) {
            int i=(f*w*h+y*w+x)*4;
            int alpha=Byte.toUnsignedInt(rgba[i+3]);
            if(alpha==0)continue;
            double light=luminance(Byte.toUnsignedInt(rgba[i]),Byte.toUnsignedInt(rgba[i+1]),Byte.toUnsignedInt(rgba[i+2]));
            if(invert)light=255-light;
            light*=alpha/255.0;
            double cutoff=threshold+(dither?(BAYER[y%4][x%4]-7.5)*16:0);
            if(light>=cutoff)frames[f*tiles+y/8*columns+x/8]|=1L<<(y%8*8+x%8);
        }
        return frames;
    }
}
