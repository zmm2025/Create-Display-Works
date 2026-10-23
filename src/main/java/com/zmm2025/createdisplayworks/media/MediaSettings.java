package com.zmm2025.createdisplayworks.media;

public record MediaSettings(int threshold, boolean invert, boolean dither, MediaDecoder.Scale scale, int seconds) {
    public static final MediaSettings DEFAULT=new MediaSettings(128,false,false,MediaDecoder.Scale.STRETCH,60);
    public MediaSettings {
        if(threshold<0||threshold>255||seconds<1||seconds>60||scale==null)throw new IllegalArgumentException("Invalid media settings");
    }
}
