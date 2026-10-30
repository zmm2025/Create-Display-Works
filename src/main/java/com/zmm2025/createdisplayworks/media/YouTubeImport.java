package com.zmm2025.createdisplayworks.media;

import com.google.gson.*;
import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.zip.ZipInputStream;

/** Public, finite YouTube videos only. Never reads browser cookies or user tool configuration. */
final class YouTubeImport {
    private static final long LIMIT=100L*1024*1024;
    static boolean matches(String source) {
        try {String host=URI.create(source).getHost();return host!=null && Set.of("youtube.com","www.youtube.com","m.youtube.com","youtu.be","www.youtube-nocookie.com").contains(host.toLowerCase(Locale.ROOT));}
        catch(IllegalArgumentException e){return false;}
    }
    private static Path verified(Path cache,String name,String url,String digest,boolean zipped) throws Exception {
        Path root=cache.resolve(name),exe=root.resolve(System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")?name.startsWith("deno")?"deno.exe":"yt-dlp.exe":name.startsWith("deno")?"deno":"yt-dlp");
        if(Files.isRegularFile(exe) && Files.isRegularFile(root.resolve("verified")))return exe;
        Files.createDirectories(root);Path archive=root.resolve("download.tmp");
        try {
            MediaDecoder.download(URI.create(url),archive);
            String actual=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(archive)));
            if(!actual.equals(digest))throw new IOException("YouTube helper checksum did not match.");
            if(zipped)try(var zip=new ZipInputStream(Files.newInputStream(archive))) {
                boolean found=false;
                for(var e=zip.getNextEntry();e!=null;e=zip.getNextEntry())if(e.getName().equals(exe.getFileName().toString())){Files.copy(zip,exe,StandardCopyOption.REPLACE_EXISTING);found=true;break;}
                if(!found)throw new IOException("JavaScript runtime was missing from its archive.");
            } else Files.copy(archive,exe,StandardCopyOption.REPLACE_EXISTING);
            if(!System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")&&!exe.toFile().setExecutable(true))throw new IOException("Cannot run YouTube helper.");
            Files.writeString(root.resolve("verified"),actual);return exe;
        }finally{Files.deleteIfExists(archive);}
    }
    private static void run(List<String> command,Path temp,Path output,Path ffmpeg) throws Exception {
        Path errors=temp.resolve("youtube-errors.txt");
        var builder=new ProcessBuilder(command).redirectOutput(output.toFile()).redirectError(errors.toFile());
        builder.environment().put("LD_LIBRARY_PATH",ffmpeg.getParent().toString());
        builder.environment().put("DYLD_LIBRARY_PATH",ffmpeg.getParent().toString());
        Process process=builder.start();long deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(3);
        try {
            while(!process.waitFor(200,TimeUnit.MILLISECONDS)) {
                if(Thread.currentThread().isInterrupted())throw new InterruptedException();
                if(System.nanoTime()>deadline)throw new MediaImportException(MediaImportException.Reason.TIMED_OUT,"YouTube import timed out. Try a direct media file.");
                long size=0;try(var files=Files.list(temp)){for(Path file:files.toList())if(Files.isRegularFile(file))size+=Files.size(file);}
                if(size>LIMIT)throw new MediaImportException(MediaImportException.Reason.TOO_LARGE,"YouTube import exceeds 100 MB.");
            }
            if(process.exitValue()!=0)throw new IOException("YouTube could not provide this video. It may require sign-in, be restricted, or be unavailable. Try a direct media file.");
        }finally{process.descendants().forEach(ProcessHandle::destroyForcibly);if(process.isAlive())process.destroyForcibly().waitFor();}
    }
    static void download(String source,int seconds,Path cache,Path temp,Path destination,Path ffmpeg,Consumer<String> status) throws Exception {
        URI uri=URI.create(source);
        if(!Set.of("https","http").contains(uri.getScheme())||uri.getUserInfo()!=null)throw new IOException("Use a public YouTube video link.");
        String os=System.getProperty("os.name").toLowerCase(Locale.ROOT),arch=System.getProperty("os.arch");
        boolean arm=arch.equals("aarch64")||arch.equals("arm64"),win=os.contains("win"),mac=os.contains("mac");
        String asset=win?"yt-dlp.exe":mac?"yt-dlp_macos":arm?"yt-dlp_linux_aarch64":"yt-dlp_linux";
        String hash=switch(asset){case "yt-dlp.exe"->"66674953fe251b89f4d08c5f0e35e0728679bd67ab3d7d05c0562af101dd3e7a";case "yt-dlp_macos"->"0f192b7ec147ab6288885d6351d9ab67367640029b4377576ef46dd79cf7b202";case "yt-dlp_linux_aarch64"->"b16e4dab368a816cd05d477d698a605a6ae87ccee1c8ffd38fa21d7254141fcc";default->"58162f9bfdc27458ea47bfcb311cf47028f17d8154a8bf7d689861d46399230a";};
        String platform=(arm?"aarch64":"x86_64")+(win?"-pc-windows-msvc":mac?"-apple-darwin":"-unknown-linux-gnu");
        String denoHash=switch(platform){
            case "aarch64-apple-darwin"->"5cd46d6268f6f78f5d88bdc7159d20bd44cdaa4b3303474839f87ec6fe7ae25c";
            case "aarch64-pc-windows-msvc"->"c4c4ac8bfdaa37814bda5c05fc9cdf2154904e2ef8277673a30bdceaaa649807";
            case "aarch64-unknown-linux-gnu"->"c832298b1ad4422481334855f6003e0f54145762c5a134f20a489511d2f65bbf";
            case "x86_64-apple-darwin"->"95daaff11c116a52ad54785e7914c8e9c9cdcaba793c5ed929c74ca2d8e6259a";
            case "x86_64-pc-windows-msvc"->"a0c3101b4158d1dfb7d6a78a7bf0f3de80c96bb423c152beec8beb22786f2238";
            default->"c6527f24f4b16031d3ae4fa9f658d5f11534c8d84ce7dc8502420280919c3490";
        };
        status.accept("Setting up verified YouTube helpers on first use...");
        Path yt=verified(cache,"youtube-2026.08.19-"+asset,"https://github.com/yt-dlp/yt-dlp/releases/download/2026.08.19/"+asset,hash,false);
        Path deno=verified(cache,"deno-2.9.7-"+platform,"https://github.com/denoland/deno/releases/download/v2.9.7/deno-"+platform+".zip",denoHash,true);
        var base=new ArrayList<>(List.of(yt.toString(),"--ignore-config","--no-playlist","--no-cache-dir","--no-progress","--no-warnings","--socket-timeout","20","--retries","2","--extractor-retries","2","--js-runtimes","deno:"+deno,"--ffmpeg-location",ffmpeg.toString()));
        var metadata=new ArrayList<>(base);metadata.addAll(List.of("--skip-download","--dump-single-json","--",source));
        status.accept("Checking YouTube video...");Path json=temp.resolve("youtube.json");run(metadata,temp,json,ffmpeg);
        JsonObject info=JsonParser.parseString(Files.readString(json)).getAsJsonObject();
        String live=info.has("live_status")&&!info.get("live_status").isJsonNull()?info.get("live_status").getAsString():"not_live";
        if(!Set.of("not_live","was_live").contains(live)||!info.has("duration")||info.get("duration").isJsonNull()||info.get("duration").getAsDouble()<=0)throw new IOException("Only recorded videos are supported. Live and upcoming streams cannot be imported.");
        var download=new ArrayList<>(base);
        download.addAll(List.of("-f","bv*[height<=480]/b[height<=480]/worst","--download-sections","*0-"+seconds,"--max-filesize","100M","--no-part","--no-mtime","-o",destination.toString(),"--",source));
        status.accept("Downloading the first "+seconds+" seconds...");run(download,temp,temp.resolve("youtube-output.txt"),ffmpeg);
        if(!Files.isRegularFile(destination)||Files.size(destination)>LIMIT)throw new IOException("YouTube video was unavailable or exceeded 100 MB.");
    }
}
