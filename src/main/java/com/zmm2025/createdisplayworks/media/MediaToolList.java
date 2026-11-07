package com.zmm2025.createdisplayworks.media;

import com.google.gson.*;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Which yt-dlp and Deno releases YouTube imports download. The list is media-tools.json in this mod's GitHub repo,
 * which a workflow keeps up to date, so a yt-dlp fix for a YouTube change reaches players without a mod release. The
 * copy built into the jar is the fallback. A list can only point at the tools' official GitHub releases, and every
 * download is still checked against the hash the list gives.
 */
final class MediaToolList {
    /** Released mods read this address, so the file has to stay there and keep {@link #SCHEMA}. */
    static final URI SOURCE=URI.create("https://raw.githubusercontent.com/zmm2025/Create-Display-Works/main/media-tools.json");
    /** The format this mod reads. Lists with any other schema are ignored, so a new format needs a new file. */
    static final int SCHEMA=1;
    /** The last list fetched from GitHub, kept in the media cache. */
    static final String CACHE_FILE="media-tools.json";
    private static final String BUNDLED="/create_display_works/media-tools.json";
    /** How long a fetched list is used before checking GitHub again. A failed YouTube import checks sooner. */
    private static final Duration FRESH=Duration.ofHours(6);
    /** After a failed fetch, imports in the next few minutes don't wait for GitHub again. */
    private static final Duration RETRY=Duration.ofMinutes(2);
    private static final int MAX_BYTES=64*1024;
    private static final Pattern SHA256=Pattern.compile("[0-9a-f]{64}"),PLATFORM=Pattern.compile("[a-z0-9_-]{1,40}");
    private static final Map<URI,Long> FAILED=new ConcurrentHashMap<>();
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(MediaToolList.class);
    private static volatile MediaToolList bundled;

    enum Tool {
        YT_DLP("yt-dlp","youtube-","\\d{4}\\.\\d{2}\\.\\d{2}(\\.\\d{1,3})?","https://github.com/yt-dlp/yt-dlp/releases/download/","","[A-Za-z0-9][A-Za-z0-9._-]{0,99}",false),
        DENO("deno","deno-","\\d{1,4}\\.\\d{1,4}\\.\\d{1,4}","https://github.com/denoland/deno/releases/download/","v","deno-[A-Za-z0-9_-]{1,80}\\.zip",true);
        final String key,folderPrefix,releases,tagPrefix;
        final Pattern version,asset;
        /** Whether the download is a zip archive that holds the program, rather than the program itself. */
        final boolean zipped;
        Tool(String key,String folderPrefix,String version,String releases,String tagPrefix,String asset,boolean zipped) {
            this.key=key;this.folderPrefix=folderPrefix;this.version=Pattern.compile(version);this.releases=releases;
            this.tagPrefix=tagPrefix;this.asset=Pattern.compile(asset);this.zipped=zipped;
        }
        /** Whether the address is one of this release's files on the tool's own GitHub releases page. */
        boolean official(String version,String url) {
            String prefix=releases+tagPrefix+version+"/";
            return url.startsWith(prefix)&&asset.matcher(url.substring(prefix.length())).matches();
        }
        String executable(boolean windows){return windows?key+".exe":key;}
        /** The release a folder from {@link #folder} holds. */
        String version(String folder){return folder.substring(folderPrefix.length(),folder.indexOf('-',folderPrefix.length()));}
        /**
         * The cache folder for this release on this platform, such as youtube-2026.10.26-yt-dlp.exe or
         * deno-2.9.7-x86_64-pc-windows-msvc. These match the names earlier versions of the mod used.
         */
        String folder(String version,Download download) {
            String file=download.file();
            return folderPrefix+version+"-"+(zipped?file.substring(folderPrefix.length(),file.length()-".zip".length()):file);
        }
        /** Folders of other releases of this tool for the same platform, which {@code folder} supersedes. */
        List<Path> others(Path cache,String folder) {
            String platform=folder.substring(folder.indexOf('-',folderPrefix.length())+1);
            try(var entries=Files.list(cache)) {
                return entries.filter(entry->{
                    String name=entry.getFileName().toString();
                    if(name.equals(folder)||!name.startsWith(folderPrefix)||!name.endsWith("-"+platform)||!Files.isDirectory(entry))return false;
                    return version.matcher(name.substring(folderPrefix.length(),name.length()-platform.length()-1)).matches();
                }).toList();
            }catch(IOException|UncheckedIOException e){return List.of();}
        }
    }
    record Download(String url,String sha256,long size) {
        String file(){return url.substring(url.lastIndexOf('/')+1);}
    }
    record Release(String version,Map<String,Download> downloads) {}

    private final Map<Tool,Release> releases;
    private MediaToolList(Map<Tool,Release> releases){this.releases=releases;}
    Release release(Tool tool){return releases.get(tool);}
    /** The tool's download for {@code platform}, such as windows-x86_64, or null when the list has none for it. */
    Download download(Tool tool,String platform){return releases.get(tool).downloads().get(platform);}

    /** Reads a list, refusing it whole if anything in it is malformed or points anywhere but the official releases. */
    static MediaToolList parse(String json) throws IOException {
        try {
            JsonObject root=JsonParser.parseString(json).getAsJsonObject();
            if(!root.has("schema")||root.get("schema").getAsInt()!=SCHEMA)throw new IOException("Unsupported schema "+root.get("schema"));
            JsonObject tools=root.getAsJsonObject("tools");
            var releases=new EnumMap<Tool,Release>(Tool.class);
            for(Tool tool:Tool.values()) {
                JsonObject entry=tools.getAsJsonObject(tool.key);
                if(entry==null)throw new IOException("No "+tool.key+" release");
                String version=entry.get("version").getAsString();
                if(!tool.version.matcher(version).matches())throw new IOException("Invalid "+tool.key+" version "+version);
                var downloads=new LinkedHashMap<String,Download>();
                for(var platform:entry.getAsJsonObject("downloads").entrySet()) {
                    JsonObject file=platform.getValue().getAsJsonObject();
                    var download=new Download(file.get("url").getAsString(),file.get("sha256").getAsString(),file.get("size").getAsLong());
                    if(!PLATFORM.matcher(platform.getKey()).matches())throw new IOException("Invalid platform "+platform.getKey());
                    if(!tool.official(version,download.url()))throw new IOException("Not an official "+tool.key+" "+version+" download: "+download.url());
                    if(!SHA256.matcher(download.sha256()).matches())throw new IOException("Invalid SHA-256 for "+download.url());
                    if(download.size()<=0||download.size()>MediaTools.DOWNLOAD_BYTES)throw new IOException("Invalid size for "+download.url());
                    downloads.put(platform.getKey(),download);
                }
                if(downloads.isEmpty())throw new IOException("No "+tool.key+" downloads");
                releases.put(tool,new Release(version,Map.copyOf(downloads)));
            }
            return new MediaToolList(releases);
        }catch(RuntimeException e){throw new IOException("Malformed media tool list: "+e,e);}
    }
    /** The list this jar was built with. */
    static MediaToolList bundled() {
        if(bundled==null)try(var input=MediaToolList.class.getResourceAsStream(BUNDLED)) {
            if(input==null)throw new IOException(BUNDLED+" is missing");
            bundled=parse(new String(input.readAllBytes(),StandardCharsets.UTF_8));
        }catch(IOException e){throw new IllegalStateException("The built-in media tool list is broken",e);}
        return bundled;
    }
    /** The last list fetched from GitHub, however old, or null if there's none or it's damaged. */
    static MediaToolList cached(Path cache) {
        try{return parse(Files.readString(cache.resolve(CACHE_FILE),StandardCharsets.UTF_8));}
        catch(IOException e){return null;}
    }
    /** GitHub's current list: the copy fetched in the last few hours, or a new one. Null when it can't be fetched or is invalid. */
    static MediaToolList online(Path cache,URI source,Consumer<String> status) throws InterruptedException {
        Path file=cache.resolve(CACHE_FILE);
        try {
            var cached=cached(cache);
            if(cached!=null&&System.currentTimeMillis()-Files.getLastModifiedTime(file).toMillis()<FRESH.toMillis())return cached;
        }catch(IOException ignored){}
        Long failed=FAILED.get(source);
        if(failed!=null&&System.nanoTime()-failed<RETRY.toNanos())return null;
        status.accept("youtube_tools_check");
        try {
            String json=fetch(source);
            var list=parse(json);
            Path temp=Files.createTempFile(cache,"media-tools-",".tmp");
            try{Files.writeString(temp,json,StandardCharsets.UTF_8);Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING);}
            finally{Files.deleteIfExists(temp);}
            FAILED.remove(source);
            return list;
        }catch(IOException e) {
            FAILED.put(source,System.nanoTime());
            LOG.warn("Using the installed or built-in media tools, because the version list couldn't be used: {}",e.toString());
            return null;
        }
    }
    /** Makes the next YouTube import check GitHub for a newer list, after yt-dlp failed in a way an update could fix. */
    static void expire(Path cache) {
        try{Files.setLastModifiedTime(cache.resolve(CACHE_FILE),FileTime.fromMillis(0));}catch(IOException ignored){}
    }
    private static String fetch(URI source) throws IOException,InterruptedException {
        try(var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NORMAL).build()) {
            var request=HttpRequest.newBuilder(source).timeout(Duration.ofSeconds(10)).header("User-Agent","Create-Display-Works").GET().build();
            var response=client.send(request,HttpResponse.BodyHandlers.ofInputStream());
            try(var input=response.body()) {
                if(response.statusCode()!=200)throw new IOException(source+" returned HTTP "+response.statusCode());
                byte[] body=input.readNBytes(MAX_BYTES+1);
                if(body.length>MAX_BYTES)throw new IOException(source+" is larger than a media tool list can be");
                return new String(body,StandardCharsets.UTF_8);
            }
        }
    }
    /** Compares dotted version numbers, such as 2026.10.26 and 2026.10.26.1. */
    static int compare(String a,String b) {
        String[] x=a.split("\\."),y=b.split("\\.");
        for(int i=0;i<Math.max(x.length,y.length);i++) {
            int c=Integer.compare(i<x.length?Integer.parseInt(x[i]):-1,i<y.length?Integer.parseInt(y[i]):-1);
            if(c!=0)return c;
        }
        return 0;
    }
}
