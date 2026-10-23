package com.zmm2025.createdisplayworks.media;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.security.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.zip.ZipInputStream;

/** Client-side bounded download and decoding; audio and excess source frames are discarded. */
public final class MediaDecoder {
    private static final String FORMATS="png_pipe,jpeg_pipe,gif,mov,matroska,webm,webp_pipe,bmp_pipe,image2,image2pipe,apng,ico,webp";
    private static final String VERSION="8.0.1-1.5.13";
    private static final long MAX_BYTES=100L*1024*1024;
    private static final java.util.concurrent.ScheduledExecutorService TIMEOUTS=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"media-download-timeout");t.setDaemon(true);return t;});
    private static final Map<String,String> HASHES=Map.of(
        "windows-x86_64","cb570de36a555156e22cac3ddb21be9051e6b3df05142a29445773ea0e7fedb9",
        "linux-x86_64","01da6cce8fb8686abd4a0be940edafcc199255cc97b37b28c49d4f696c1d9ef3",
        "linux-arm64","2810546c9c82154ba9a389cdbdb1135db138604b04735806caa8b3cd5211aa50",
        "macosx-x86_64","87ada23333d9bbb392e47ae52f5fbf41a4a6fd10817b18dd081a2f3f7dc9e956",
        "macosx-arm64","57b4cb109b60e246e0d55734bf83c9883bf9424df42816fa06af945549dba094");
    private MediaDecoder() {}
    private static String filename(String source) {
        if(YouTubeImport.matches(source))return "YouTube video";
        try {return Path.of(source.startsWith("http")?URI.create(source).getPath():source).getFileName().toString();}
        catch(Exception e){return "Imported media";}
    }
    private static HttpClient http() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).followRedirects(HttpClient.Redirect.NORMAL).build();
    }
    static void download(URI uri, Path target) throws Exception {
        if(!Set.of("https","http").contains(uri.getScheme()) || uri.getUserInfo()!=null)throw new IOException("Use a direct HTTP or HTTPS media link.");
        try(var client=http()) {
            var response=client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(60)).header("User-Agent","Create-Display-Works").GET().build(),HttpResponse.BodyHandlers.ofInputStream());
            try(var input=response.body();var output=Files.newOutputStream(target)) {
                var timeout=TIMEOUTS.schedule(()->{try{input.close();}catch(IOException ignored){}},90,TimeUnit.SECONDS);
                try {
                if(response.statusCode()!=200)throw new IOException("Download returned HTTP "+response.statusCode());
                byte[] buffer=new byte[16384];long total=0;long deadline=System.nanoTime()+Duration.ofSeconds(90).toNanos();
                for(int n;(n=input.read(buffer))!=-1;) {
                    if((total+=n)>MAX_BYTES || System.nanoTime()>deadline)throw new IOException("Media exceeds 100 MB or the download time limit.");
                    if(Thread.currentThread().isInterrupted())throw new InterruptedIOException();
                    output.write(buffer,0,n);
                }
                }finally{timeout.cancel(false);}
            }
        }
    }
    static Path decoder(Path cache,Consumer<String> status) throws Exception {
        String os=System.getProperty("os.name").toLowerCase(Locale.ROOT),arch=System.getProperty("os.arch");
        String platform=(os.contains("win")?"windows":os.contains("mac")?"macosx":"linux")+"-"+(arch.equals("aarch64")||arch.equals("arm64")?"arm64":"x86_64");
        if(!HASHES.containsKey(platform))throw new IOException("No bundled decoder is available for "+platform);
        Path root=cache.resolve("ffmpeg-"+VERSION+"-"+platform),exe=root.resolve(os.contains("win")?"ffmpeg.exe":"ffmpeg");
        if(Files.exists(root.resolve("verified")) && Files.isRegularFile(exe))return exe;
        status.accept("Setting up media decoder, about 20-30 MB on first use...");
        Files.createDirectories(root);Path archive=Files.createTempFile(cache,"decoder-",".jar");
        try {
            download(URI.create("https://repo.maven.apache.org/maven2/org/bytedeco/ffmpeg/"+VERSION+"/ffmpeg-"+VERSION+"-"+platform+".jar"),archive);
            String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(archive)));
            if(!hash.equals(HASHES.get(platform)))throw new IOException("Decoder checksum did not match.");
            String prefix="org/bytedeco/ffmpeg/"+platform+"/";
            try(var zip=new ZipInputStream(Files.newInputStream(archive))) {
                for(var e=zip.getNextEntry();e!=null;e=zip.getNextEntry()) {
                    if(e.isDirectory() || !e.getName().startsWith(prefix))continue;
                    Path output=root.resolve(e.getName().substring(prefix.length())).normalize();
                    if(!output.getParent().equals(root))throw new IOException("Invalid decoder archive path.");
                    Files.copy(zip,output,StandardCopyOption.REPLACE_EXISTING);
                }
            }
            if(!os.contains("win") && !exe.toFile().setExecutable(true))throw new IOException("Cannot make the decoder executable.");
            Files.writeString(root.resolve("verified"),hash);
            return exe;
        }finally{Files.deleteIfExists(archive);}
    }
    public static MediaClip decode(String source,int columns,int rows,int seconds,int threshold,boolean invert,Path cache,Consumer<String> status) throws Exception {
        return decode(source,columns,rows,seconds,threshold,invert,Scale.STRETCH,false,cache,status);
    }
    public enum Scale { STRETCH, FIT, FILL }
    public static MediaClip decode(String source,int columns,int rows,int seconds,int threshold,boolean invert,Scale scale,boolean dither,Path cache,Consumer<String> status) throws Exception {
        cache=cache.toAbsolutePath().normalize();
        if(columns<1||rows<1||columns>32||rows>32||columns*rows>MediaClip.MAX_TILES)throw new IllegalArgumentException("Media canvases support at most 256 blocks.");
        if(seconds<1 || seconds>60 || threshold<0 || threshold>255)throw new IllegalArgumentException("Invalid import settings");
        Files.createDirectories(cache);Path temp=Files.createTempDirectory(cache,"import-");
        Process process=null;
        try {
            Path input=temp.resolve("input"),raw=temp.resolve("frames.raw"),errors=temp.resolve("decode.txt");
            status.accept("Loading media...");
            if(YouTubeImport.matches(source)) YouTubeImport.download(source,seconds,cache,temp,input,decoder(cache,status),status);
            else if(source.startsWith("https://") || source.startsWith("http://"))download(URI.create(source),input);
            else {Path file=Path.of(source);if(!Files.isRegularFile(file)||Files.size(file)>MAX_BYTES)throw new IOException("Choose a media file smaller than 100 MB.");Files.copy(file,input);}
            Path executable=decoder(cache,status);status.accept("Converting to "+columns*8+" x "+rows*8+" dots...");
            int w=columns*8,h=rows*8;
            byte[] header;try(var in=Files.newInputStream(input)){header=in.readNBytes(65536);}
            String brand=new String(header,java.nio.charset.StandardCharsets.ISO_8859_1);
            if(brand.stripLeading().startsWith("<") && brand.contains("<svg")) {
                SvgRasterizer.convert(input,temp.resolve("svg.png"));input=temp.resolve("svg.png");
                header=Files.readAllBytes(input);brand=new String(header,java.nio.charset.StandardCharsets.ISO_8859_1);
            }
            if(brand.startsWith("RIFF") && brand.contains("ANIM"))return WebpAnimation.decode(input,columns,rows,seconds,threshold,invert,scale,dither,filename(source));
            boolean still=header.length>2 && ((header[0]==(byte)0x89 && !brand.contains("acTL")) || header[0]==(byte)0xFF || brand.contains("heic") || brand.contains("heix") || brand.contains("mif1") || brand.contains("avif") || brand.startsWith("BM") || (header[0]==0 && header[1]==0 && header[2]==1) || (brand.startsWith("RIFF") && !brand.contains("ANIM")));
            // Probe the first decoded frame, including rotation, using the same bounded decoder.
            Path first=temp.resolve("first.png");
            String bound="scale=w='min(256,iw)':h='min(256,ih)':force_original_aspect_ratio=decrease,scale=w='max(1,trunc(iw*min(1,sqrt(16384/(iw*ih)))))':h='max(1,trunc(ih*min(1,sqrt(16384/(iw*ih)))))'";
            var probe=new ProcessBuilder(executable.toString(),"-nostdin","-hide_banner","-loglevel","error","-y","-max_alloc","67108864",
                "-protocol_whitelist","file,pipe","-format_whitelist",FORMATS,"-threads","2","-i",input.toString(),"-an","-sn","-dn","-vf",bound,"-frames:v","1","-threads","2",first.toString());
            probe.environment().put("LD_LIBRARY_PATH",executable.getParent().toString());probe.environment().put("DYLD_LIBRARY_PATH",executable.getParent().toString());
            process=probe.redirectError(errors.toFile()).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            if(!process.waitFor(30,TimeUnit.SECONDS)||process.exitValue()!=0)throw new IOException("Cannot read this media.");
            var firstImage=javax.imageio.ImageIO.read(first.toFile());if(firstImage==null)throw new IOException("Cannot read this image.");
            w=firstImage.getWidth();h=firstImage.getHeight();firstImage.flush();
            String filter=(still?"":"fps="+MediaClip.FPS+":eof_action=pass,")+"format=rgba,scale="+w+":"+h+":flags=area,format=rgba";
            var builder=new ProcessBuilder(executable.toAbsolutePath().toString(),"-nostdin","-hide_banner","-loglevel","error","-y","-max_alloc","67108864",
                "-protocol_whitelist","file,pipe","-format_whitelist",FORMATS,
                "-threads","2","-i",input.toAbsolutePath().toString(),"-an","-sn","-dn","-t",Integer.toString(seconds),"-vf",filter,
                "-frames:v",Integer.toString(still?1:seconds*MediaClip.FPS),"-threads","2","-f","rawvideo",raw.toAbsolutePath().toString());
            builder.environment().put("LD_LIBRARY_PATH",executable.getParent().toAbsolutePath().toString());
            builder.environment().put("DYLD_LIBRARY_PATH",executable.getParent().toAbsolutePath().toString());
            process=builder.redirectError(errors.toFile()).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            if(!process.waitFor(90,TimeUnit.SECONDS))throw new IOException("Media conversion exceeded 90 seconds.");
            if(process.exitValue()!=0 || !Files.exists(raw) || Files.size(raw)==0){
                System.err.println("Flip-dot decoder: "+Files.readString(errors));
                throw new IOException("Cannot decode this media. Check the file format and try a direct media link or a public YouTube video.");
            }
            long size=Files.size(raw);if(size>(long)w*h*4*MediaClip.MAX_FRAMES || size%(w*h*4)!=0)throw new IOException("Decoded media exceeds the frame limit.");
            byte[] pixels=Files.readAllBytes(raw);var original=EditableMedia.fromRgba(w,h,pixels);
            status.accept("Ready: "+original.count()+" frames");
            return original.render(columns,rows,filename(source),new MediaSettings(threshold,invert,dither,scale,seconds));
        }finally {
            if(process!=null && process.isAlive())process.destroyForcibly().waitFor();
            try(var files=Files.list(temp)){for(Path file:files.toList())Files.deleteIfExists(file);}Files.deleteIfExists(temp);
        }
    }
}
