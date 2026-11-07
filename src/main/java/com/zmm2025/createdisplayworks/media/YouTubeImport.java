package com.zmm2025.createdisplayworks.media;

import com.google.gson.*;
import com.zmm2025.createdisplayworks.media.MediaToolList.Tool;
import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.zip.ZipInputStream;

/** Public, finite YouTube videos only. Never reads browser cookies or user tool configuration. */
final class YouTubeImport {
    private static final long LIMIT=100L*1024*1024;
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(YouTubeImport.class);
    static boolean matches(String source) {
        try {String host=URI.create(source).getHost();return host!=null && Set.of("youtube.com","www.youtube.com","m.youtube.com","youtu.be","www.youtube-nocookie.com").contains(host.toLowerCase(Locale.ROOT));}
        catch(IllegalArgumentException e){return false;}
    }
    /** One tool release an import uses, and the cache folder it's installed in. {@code download} is null for an installed release no list names. */
    record Choice(Tool tool,String version,MediaToolList.Download download,String folder,boolean installed,boolean replaces) {}
    /** The yt-dlp and Deno releases an import uses. */
    record Plan(List<Choice> tools) {
        Choice get(Tool tool){return tools.stream().filter(c->c.tool()==tool).findFirst().orElseThrow();}
        /** About how many megabytes still have to be downloaded, or 0 when every release is installed. */
        int megabytes() {
            long bytes=tools.stream().filter(c->!c.installed()).mapToLong(c->c.download().size()).sum();
            return bytes==0?0:(int)Math.max(1,Math.round(bytes/(1024.0*1024)));
        }
        /** Whether every download replaces an older release the player already has, instead of installing a tool. */
        boolean update(){return tools.stream().anyMatch(c->!c.installed())&&tools.stream().allMatch(c->c.installed()||c.replaces());}
    }
    /** Whether the player installed YouTube tools before, which means they already agreed to download them. */
    static boolean usedBefore(Path cache) {
        try(var entries=Files.list(cache)) {
            return entries.anyMatch(entry->Arrays.stream(Tool.values()).anyMatch(tool->entry.getFileName().toString().startsWith(tool.folderPrefix))
                &&Files.isRegularFile(entry.resolve(MediaTools.MANIFEST)));
        }catch(IOException|UncheckedIOException e){return false;}
    }
    /** Picks each tool's release for this computer, checking GitHub's list first when {@code online}. */
    static Plan plan(Path cache,boolean online,Consumer<String> status) throws Exception {
        return plan(cache,online?MediaToolList.SOURCE:null,MediaDecoder.platform(),status);
    }
    /**
     * Uses GitHub's list from {@code source} when it can be fetched. Otherwise, so an offline game or a blocked GitHub
     * doesn't download anything it doesn't need, it keeps the newest release that's installed, or else downloads the
     * newer of the last fetched list's and the built-in list's releases.
     */
    static Plan plan(Path cache,URI source,String platform,Consumer<String> status) throws Exception {
        var fresh=source==null?null:MediaToolList.online(cache,source,status);
        var lists=new ArrayList<MediaToolList>();
        lists.add(fresh!=null?fresh:MediaToolList.cached(cache));lists.add(MediaToolList.bundled());lists.removeIf(Objects::isNull);
        var choices=new ArrayList<Choice>();
        for(Tool tool:Tool.values()) {
            var options=new ArrayList<Choice>();
            for(var list:lists) {
                var download=list.download(tool,platform);
                if(download==null)continue;
                String version=list.release(tool).version(),folder=tool.folder(version,download);
                boolean replaces=tool.others(cache,folder).stream().anyMatch(other->Files.isRegularFile(other.resolve(MediaTools.MANIFEST)));
                options.add(new Choice(tool,version,download,folder,Files.isRegularFile(cache.resolve(folder).resolve(MediaTools.MANIFEST)),replaces));
            }
            if(options.isEmpty())throw new MediaImportException(MediaImportException.Reason.UNSUPPORTED,"No "+tool.key+" download exists for "+platform,platform,null);
            if(fresh==null)options.addAll(installed(cache,tool,options.getFirst().folder()));
            choices.add(fresh!=null?options.getFirst():options.stream().max(Comparator.comparing(Choice::installed).thenComparing(Choice::version,MediaToolList::compare)).orElseThrow());
        }
        return new Plan(List.copyOf(choices));
    }
    /** Intact releases of the tool installed for the same platform as {@code folder}, other than that one, newest first. */
    private static List<Choice> installed(Path cache,Tool tool,String folder) {
        String executable=tool.executable(MediaDecoder.platform().startsWith("windows"));
        return tool.others(cache,folder).stream().filter(path->MediaTools.ready(path,executable)!=null).map(path->{
            String name=path.getFileName().toString();
            return new Choice(tool,tool.version(name),null,name,true,false);
        }).sorted(Comparator.comparing(Choice::version,MediaToolList::compare).reversed()).toList();
    }
    /**
     * The release to run. When an update can't be downloaded, for example because of a bad hash, the release it would
     * replace still works for most videos, so the import goes on with that one.
     */
    static Path usable(Path cache,Choice choice,Consumer<String> status) throws Exception {
        try{return verified(cache,choice,status);}
        catch(MediaImportException e) {
            if(e.reason()!=MediaImportException.Reason.TOOLS_DOWNLOAD)throw e;
            var older=installed(cache,choice.tool(),choice.folder());
            if(older.isEmpty())throw e;
            LOG.warn("Keeping {} {}, because {} {} couldn't be installed: {}",choice.tool().key,older.getFirst().version(),choice.tool().key,choice.version(),e.getMessage());
            return verified(cache,older.getFirst(),status);
        }
    }
    /** Downloads and checks the release unless its folder already holds it, then removes the releases it replaces. */
    static Path verified(Path cache,Choice choice,Consumer<String> status) throws Exception {
        boolean windows=MediaDecoder.platform().startsWith("windows");
        Path root=cache.resolve(choice.folder()),exe=root.resolve(choice.tool().executable(windows));
        Path ready=MediaTools.ready(root,exe.getFileName().toString());
        if(ready!=null)return ready;
        if(choice.download()==null)throw new IOException(choice.folder()+" is no longer intact");
        status.accept(choice.replaces()?"youtube_tools_update":"youtube_tools");
        Files.createDirectories(root);Path archive=root.resolve("download.tmp");
        try {
            MediaTools.fetch(choice.download().url(),archive,choice.download().sha256());
            if(choice.tool().zipped)try(var zip=new ZipInputStream(Files.newInputStream(archive))) {
                boolean found=false;
                for(var e=zip.getNextEntry();e!=null;e=zip.getNextEntry())if(e.getName().equals(exe.getFileName().toString())){Files.copy(zip,exe,StandardCopyOption.REPLACE_EXISTING);found=true;break;}
                if(!found)throw new IOException("JavaScript runtime was missing from its archive.");
            } else Files.copy(archive,exe,StandardCopyOption.REPLACE_EXISTING);
            if(!windows&&!exe.toFile().setExecutable(true))throw new IOException("Cannot run YouTube helper.");
            MediaTools.seal(root);
        }catch(Exception e) {
            // Nothing usable was installed, so don't leave an empty folder behind.
            MediaDecoder.deleteTree(root);
            throw e;
        }finally{MediaDecoder.deleteTree(archive);}
        // The new release is verified, so the ones it replaces only take up space.
        for(Path old:choice.tool().others(cache,choice.folder()))MediaDecoder.deleteTree(old);
        return exe;
    }
    /** Words yt-dlp uses when it couldn't reach YouTube at all, as opposed to YouTube refusing the video. */
    private static final List<String> NETWORK_ERRORS=List.of("TransportError","ProxyError","Unable to connect","Failed to resolve","getaddrinfo","timed out","CERTIFICATE_VERIFY_FAILED","SSLError","Connection refused","Network is unreachable");
    private static void run(List<String> command,Path cache,Path temp,Path output,Path ffmpeg) throws Exception {
        Path errors=temp.resolve("youtube-errors.txt");
        var builder=new ProcessBuilder(command).redirectOutput(output.toFile()).redirectError(errors.toFile());
        builder.environment().put("LD_LIBRARY_PATH",ffmpeg.getParent().toString());
        builder.environment().put("DYLD_LIBRARY_PATH",ffmpeg.getParent().toString());
        Process process=MediaTools.start(builder,Path.of(command.getFirst()).getParent());long deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(3);
        try {
            while(!process.waitFor(200,TimeUnit.MILLISECONDS)) {
                if(Thread.currentThread().isInterrupted())throw new InterruptedException();
                if(System.nanoTime()>deadline)throw new MediaImportException(MediaImportException.Reason.TIMED_OUT,"YouTube import timed out. Try a direct media file.");
                long size=0;try(var files=Files.list(temp)){for(Path file:files.toList())if(Files.isRegularFile(file))size+=Files.size(file);}
                if(size>LIMIT)throw new MediaImportException(MediaImportException.Reason.TOO_LARGE,"YouTube import exceeds 100 MB.");
            }
            if(process.exitValue()!=0) {
                String log=Files.isRegularFile(errors)?Files.readString(errors,java.nio.charset.StandardCharsets.UTF_8):"";
                if(NETWORK_ERRORS.stream().anyMatch(log::contains))throw new MediaImportException(MediaImportException.Reason.NETWORK,"Cannot reach YouTube: "+log.strip(),"youtube.com",null);
                // A YouTube change can break yt-dlp until a newer one comes out, so the next import checks for one.
                MediaToolList.expire(cache);
                throw new IOException("YouTube could not provide this video. It may require sign-in, be restricted, or be unavailable. Try a direct media file.");
            }
        }finally{process.descendants().forEach(ProcessHandle::destroyForcibly);if(process.isAlive())process.destroyForcibly().waitFor();}
    }
    /** Imports the first {@code seconds} of the video with the tool releases {@code tools}, which the player agreed to. */
    static void download(String source,int seconds,Path cache,Path temp,Path destination,Path ffmpeg,Plan tools,Consumer<String> status) throws Exception {
        URI uri=URI.create(source);
        if(!Set.of("https","http").contains(uri.getScheme())||uri.getUserInfo()!=null)throw new IOException("Use a public YouTube video link.");
        Path yt=usable(cache,tools.get(Tool.YT_DLP),status);
        Path deno=usable(cache,tools.get(Tool.DENO),status);
        var base=new ArrayList<>(List.of(yt.toString(),"--ignore-config","--no-playlist","--no-cache-dir","--no-progress","--no-warnings","--socket-timeout","20","--retries","2","--extractor-retries","2","--js-runtimes","deno:"+deno,"--ffmpeg-location",ffmpeg.toString()));
        var metadata=new ArrayList<>(base);metadata.addAll(List.of("--skip-download","--dump-single-json","--",source));
        status.accept("youtube_check");Path json=temp.resolve("youtube.json");run(metadata,cache,temp,json,ffmpeg);
        JsonObject info=JsonParser.parseString(Files.readString(json)).getAsJsonObject();
        String live=info.has("live_status")&&!info.get("live_status").isJsonNull()?info.get("live_status").getAsString():"not_live";
        if(!Set.of("not_live","was_live").contains(live)||!info.has("duration")||info.get("duration").isJsonNull()||info.get("duration").getAsDouble()<=0)throw new IOException("Only recorded videos are supported. Live and upcoming streams cannot be imported.");
        var download=new ArrayList<>(base);
        download.addAll(List.of("-f","bv*[height<=480]/b[height<=480]/worst","--download-sections","*0-"+seconds,"--max-filesize","100M","--no-part","--no-mtime","-o",destination.toString(),"--",source));
        status.accept("youtube_download");run(download,cache,temp,temp.resolve("youtube-output.txt"),ffmpeg);
        if(!Files.isRegularFile(destination)||Files.size(destination)>LIMIT)throw new IOException("YouTube video was unavailable or exceeded 100 MB.");
    }
}
