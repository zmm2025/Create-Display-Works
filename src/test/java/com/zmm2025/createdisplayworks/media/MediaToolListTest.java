package com.zmm2025.createdisplayworks.media;

import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import com.zmm2025.createdisplayworks.media.MediaToolList.Tool;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class MediaToolListTest {
    private static final List<String> PLATFORMS=List.of("windows-x86_64","linux-x86_64","linux-arm64","macosx-x86_64","macosx-arm64");
    private static final Map<String,String> YT_DLP_ASSETS=Map.of("windows-x86_64","yt-dlp.exe","linux-x86_64","yt-dlp_linux","linux-arm64","yt-dlp_linux_aarch64","macosx-x86_64","yt-dlp_macos","macosx-arm64","yt-dlp_macos");
    private static final Map<String,String> DENO_TARGETS=Map.of("windows-x86_64","x86_64-pc-windows-msvc","linux-x86_64","x86_64-unknown-linux-gnu","linux-arm64","aarch64-unknown-linux-gnu","macosx-x86_64","x86_64-apple-darwin","macosx-arm64","aarch64-apple-darwin");
    private static final long MB=1024*1024;
    @TempDir Path cache;
    private HttpServer server;
    private final AtomicReference<byte[]> served=new AtomicReference<>();
    private final AtomicInteger requests=new AtomicInteger();

    @BeforeEach void serve() throws IOException {
        server=HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(),0),0);
        server.createContext("/",exchange->{
            requests.incrementAndGet();
            byte[] body=served.get();
            exchange.sendResponseHeaders(body==null?404:200,body==null?-1:body.length);
            if(body!=null)exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }
    @AfterEach void stop(){server.stop(0);}
    private URI url(String path){return URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/"+path);}

    /** A valid list with yt-dlp {@code ytDlp} and Deno {@code deno}, with a 10 MB download of each for every platform. */
    private static JsonObject list(String ytDlp,String deno) {
        var tools=new JsonObject();
        for(Tool tool:Tool.values()) {
            String version=tool==Tool.YT_DLP?ytDlp:deno;
            var downloads=new JsonObject();
            for(String platform:PLATFORMS) {
                var file=new JsonObject();
                file.addProperty("url",tool==Tool.YT_DLP?"https://github.com/yt-dlp/yt-dlp/releases/download/"+version+"/"+YT_DLP_ASSETS.get(platform)
                    :"https://github.com/denoland/deno/releases/download/v"+version+"/deno-"+DENO_TARGETS.get(platform)+".zip");
                file.addProperty("sha256","0123456789abcdef".repeat(4));file.addProperty("size",10*MB);
                downloads.add(platform,file);
            }
            var release=new JsonObject();release.addProperty("version",version);release.add("downloads",downloads);
            tools.add(tool.key,release);
        }
        var root=new JsonObject();root.addProperty("schema",1);root.add("tools",tools);
        return root;
    }
    private static String change(UnaryOperator<JsonObject> edit){return edit.apply(list("2026.10.26","2.9.7")).toString();}
    private static JsonObject ytWindows(JsonObject root){return root.getAsJsonObject("tools").getAsJsonObject("yt-dlp").getAsJsonObject("downloads").getAsJsonObject("windows-x86_64");}
    /** Installs a fake release in {@code folder}, named for this computer's platform the way the mod would. */
    private void install(String folder) throws IOException {
        Path root=Files.createDirectories(cache.resolve(folder));
        boolean windows=MediaDecoder.platform().startsWith("windows");
        for(Tool tool:Tool.values())if(folder.startsWith(tool.folderPrefix))Files.writeString(root.resolve(tool.executable(windows)),"program");
        MediaTools.seal(root);
    }

    @Test void theBuiltInListCoversEveryPlatformWithADecoder() throws IOException {
        var bundled=MediaToolList.bundled();
        for(Tool tool:Tool.values())for(String platform:PLATFORMS)assertNotNull(bundled.download(tool,platform),tool.key+" "+platform);
        // The repo's copy is what GitHub serves, and it has to be one the mod accepts.
        var repo=MediaToolList.parse(Files.readString(Path.of("media-tools.json")));
        assertEquals(repo.release(Tool.YT_DLP).version(),bundled.release(Tool.YT_DLP).version());
    }
    @Test void listsMayAddFieldsLaterMods() throws IOException {
        var list=MediaToolList.parse(change(root->{root.addProperty("notes","x");ytWindows(root).addProperty("signature","y");return root;}));
        assertEquals("2026.10.26",list.release(Tool.YT_DLP).version());
        assertEquals(10*MB,list.download(Tool.YT_DLP,"windows-x86_64").size());
        assertNull(list.download(Tool.YT_DLP,"windows-arm64"));
    }
    @Test void invalidListsAreRefusedWhole() {
        Map<String,String> invalid=new LinkedHashMap<>();
        invalid.put("newer schema",change(root->{root.addProperty("schema",2);return root;}));
        invalid.put("no schema",change(root->{root.remove("schema");return root;}));
        invalid.put("no Deno",change(root->{root.getAsJsonObject("tools").remove("deno");return root;}));
        invalid.put("no downloads",change(root->{root.getAsJsonObject("tools").getAsJsonObject("deno").add("downloads",new JsonObject());return root;}));
        invalid.put("version name",change(root->{root.getAsJsonObject("tools").getAsJsonObject("yt-dlp").addProperty("version","latest");return root;}));
        for(var url:List.of("https://github.com/someone/yt-dlp/releases/download/2026.10.26/yt-dlp.exe",
                "http://github.com/yt-dlp/yt-dlp/releases/download/2026.10.26/yt-dlp.exe",
                "https://github.com/yt-dlp/yt-dlp/releases/download/2026.08.19/yt-dlp.exe",
                "https://github.com/yt-dlp/yt-dlp/releases/download/2026.10.26/../yt-dlp.exe",
                "https://github.com/yt-dlp/yt-dlp/releases/download/2026.10.26/sub/yt-dlp.exe",
                "https://github.com.evil.example/yt-dlp/yt-dlp/releases/download/2026.10.26/yt-dlp.exe",
                "https://objects.githubusercontent.com/yt-dlp.exe"))
            invalid.put(url,change(root->{ytWindows(root).addProperty("url",url);return root;}));
        invalid.put("Deno download that isn't a zip",change(root->{root.getAsJsonObject("tools").getAsJsonObject("deno").getAsJsonObject("downloads").getAsJsonObject("linux-x86_64")
            .addProperty("url","https://github.com/denoland/deno/releases/download/v2.9.7/deno");return root;}));
        invalid.put("short hash",change(root->{ytWindows(root).addProperty("sha256","abc");return root;}));
        invalid.put("uppercase hash",change(root->{ytWindows(root).addProperty("sha256","0123456789ABCDEF".repeat(4));return root;}));
        invalid.put("empty download",change(root->{ytWindows(root).addProperty("size",0);return root;}));
        invalid.put("huge download",change(root->{ytWindows(root).addProperty("size",MediaTools.DOWNLOAD_BYTES+1);return root;}));
        invalid.put("no hash",change(root->{ytWindows(root).remove("sha256");return root;}));
        invalid.put("malformed",change(root->root).substring(10));
        invalid.put("array","[]");
        invalid.put("HTML","<html>Sign in to continue</html>");
        for(var entry:invalid.entrySet())assertThrows(IOException.class,()->MediaToolList.parse(entry.getValue()),entry.getKey());
    }
    @Test void fetchedListsAreCachedForAWhile() throws Exception {
        served.set(change(root->root).getBytes(StandardCharsets.UTF_8));
        var steps=new ArrayList<String>();
        assertEquals("2026.10.26",MediaToolList.online(cache,url("list.json"),steps::add).release(Tool.YT_DLP).version());
        assertEquals(List.of("youtube_tools_check"),steps);
        assertTrue(Files.isRegularFile(cache.resolve(MediaToolList.CACHE_FILE)));
        MediaToolList.online(cache,url("list.json"),steps::add);
        assertEquals(1,requests.get(),"a fresh copy is used without asking GitHub again");
        // After yt-dlp failed, the next import checks for a newer list.
        served.set(list("2026.11.02","2.9.7").toString().getBytes(StandardCharsets.UTF_8));
        MediaToolList.expire(cache);
        assertEquals("2026.11.02",MediaToolList.online(cache,url("list.json"),s->{}).release(Tool.YT_DLP).version());
        assertEquals(2,requests.get());
    }
    @Test void invalidOrMissingListsAreIgnored() throws Exception {
        Files.writeString(cache.resolve(MediaToolList.CACHE_FILE),change(root->root));
        MediaToolList.expire(cache);
        served.set(change(root->{ytWindows(root).addProperty("url","https://example.com/yt-dlp.exe");return root;}).getBytes(StandardCharsets.UTF_8));
        assertNull(MediaToolList.online(cache,url("bad.json"),s->{}));
        assertEquals("2026.10.26",MediaToolList.cached(cache).release(Tool.YT_DLP).version(),"a bad list never replaces the last good one");
        assertNull(MediaToolList.online(cache,url("bad.json"),s->{}));
        assertEquals(1,requests.get(),"a failed fetch isn't retried right away");
        served.set(null);
        assertNull(MediaToolList.online(cache,url("missing.json"),s->{}));
        int port;try(var socket=new ServerSocket(0)){port=socket.getLocalPort();}
        assertNull(MediaToolList.online(cache,URI.create("http://127.0.0.1:"+port+"/list.json"),s->{}));
    }
    @Test void withoutGitHubTheBuiltInListIsUsed() throws Exception {
        var plan=YouTubeImport.plan(cache,null,"windows-x86_64",s->{});
        assertEquals(MediaToolList.bundled().release(Tool.YT_DLP).version(),plan.get(Tool.YT_DLP).version());
        assertFalse(plan.get(Tool.YT_DLP).installed());
        assertFalse(plan.update(),"nothing is installed yet, so this is a first download");
        long bytes=0;for(Tool tool:Tool.values())bytes+=MediaToolList.bundled().download(tool,"windows-x86_64").size();
        assertEquals(Math.round(bytes/(double)MB),plan.megabytes());
        assertThrows(MediaImportException.class,()->YouTubeImport.plan(cache,null,"freebsd-riscv64",s->{}));
    }
    @Test void aNewerListedReleaseIsAnUpdate() throws Exception {
        var bundled=MediaToolList.bundled();
        for(Tool tool:Tool.values())install(tool.folder(bundled.release(tool).version(),bundled.download(tool,"windows-x86_64")));
        served.set(list("2099.01.01",bundled.release(Tool.DENO).version()).toString().getBytes(StandardCharsets.UTF_8));
        var plan=YouTubeImport.plan(cache,url("list.json"),"windows-x86_64",s->{});
        var yt=plan.get(Tool.YT_DLP);
        assertEquals("2099.01.01",yt.version());
        assertFalse(yt.installed());assertTrue(yt.replaces());
        assertTrue(plan.get(Tool.DENO).installed());
        assertTrue(plan.update(),"the player already has YouTube tools, so the popup offers an update");
        assertEquals(10,plan.megabytes());
    }
    @Test void offlineGamesKeepTheReleaseTheyHave() throws Exception {
        // The last fetched list named an older yt-dlp than the built-in one, and it's installed.
        var stale=MediaToolList.parse(list("2020.01.01","1.0.0").toString());
        Files.writeString(cache.resolve(MediaToolList.CACHE_FILE),list("2020.01.01","1.0.0").toString());MediaToolList.expire(cache);
        install(Tool.YT_DLP.folder("2020.01.01",stale.download(Tool.YT_DLP,"windows-x86_64")));
        int port;try(var socket=new ServerSocket(0)){port=socket.getLocalPort();}
        var plan=YouTubeImport.plan(cache,URI.create("http://127.0.0.1:"+port+"/list.json"),"windows-x86_64",s->{});
        assertEquals("2020.01.01",plan.get(Tool.YT_DLP).version());
        assertTrue(plan.get(Tool.YT_DLP).installed());
        // Neither Deno is installed, so it takes the newer one, the built-in.
        assertEquals(MediaToolList.bundled().release(Tool.DENO).version(),plan.get(Tool.DENO).version());
    }
    @Test void withoutAUsableListTheInstalledReleaseIsKept() throws Exception {
        // GitHub's list is invalid and nothing names the installed release, which is older than the built-in one.
        install("youtube-2026.01.01-yt-dlp.exe");
        served.set("{\"schema\":1}".getBytes(StandardCharsets.UTF_8));
        var plan=YouTubeImport.plan(cache,url("list.json"),"windows-x86_64",s->{});
        var yt=plan.get(Tool.YT_DLP);
        assertEquals("2026.01.01",yt.version());assertTrue(yt.installed());assertNull(yt.download());
        assertEquals(Math.round(MediaToolList.bundled().download(Tool.DENO,"windows-x86_64").size()/(double)MB),plan.megabytes(),"only Deno is missing");
    }
    @Test void badDownloadsAreRefusedAndKeepTheOldRelease() throws Exception {
        String exe=Tool.YT_DLP.executable(MediaDecoder.platform().startsWith("windows"));
        install("youtube-2026.08.19-yt-dlp.exe");install("youtube-2026.08.19-yt-dlp_linux");
        Files.createDirectories(cache.resolve("ffmpeg-8.0.1-1.5.13-windows-x86_64"));
        byte[] program="new program".getBytes(StandardCharsets.UTF_8);served.set(program);
        String hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(program));
        var download=new MediaToolList.Download(url("2026.10.26/yt-dlp.exe").toString(),"0".repeat(64),program.length);
        var bad=new YouTubeImport.Choice(Tool.YT_DLP,"2026.10.26",download,"youtube-2026.10.26-yt-dlp.exe",false,true);
        var error=assertThrows(MediaImportException.class,()->YouTubeImport.verified(cache,bad,s->{}));
        assertEquals(MediaImportException.Reason.TOOLS_DOWNLOAD,error.reason());
        assertFalse(Files.exists(cache.resolve("youtube-2026.10.26-yt-dlp.exe")),"a failed download leaves no folder behind");
        assertTrue(Files.isRegularFile(cache.resolve("youtube-2026.08.19-yt-dlp.exe/verified")),"the old release stays until a new one is verified");
        // The import itself goes on with the release the update would have replaced.
        assertEquals(cache.resolve("youtube-2026.08.19-yt-dlp.exe/"+exe),YouTubeImport.usable(cache,bad,s->{}));

        var steps=new ArrayList<String>();
        var good=new YouTubeImport.Choice(Tool.YT_DLP,"2026.10.26",new MediaToolList.Download(download.url(),hash,program.length),"youtube-2026.10.26-yt-dlp.exe",false,true);
        Path installed=YouTubeImport.verified(cache,good,steps::add);
        assertEquals("new program",Files.readString(installed));
        assertEquals(List.of("youtube_tools_update"),steps);
        assertFalse(Files.exists(cache.resolve("youtube-2026.08.19-yt-dlp.exe")),"the superseded release is removed");
        assertTrue(Files.exists(cache.resolve("youtube-2026.08.19-yt-dlp_linux")),"other platforms' copies are left alone");
        assertTrue(Files.exists(cache.resolve("ffmpeg-8.0.1-1.5.13-windows-x86_64")));
    }
    @Test void versionsCompareByNumber() {
        assertTrue(MediaToolList.compare("2026.10.26","2026.08.19")>0);
        assertTrue(MediaToolList.compare("2026.10.26.1","2026.10.26")>0);
        assertTrue(MediaToolList.compare("2.10.0","2.9.7")>0);
        assertEquals(0,MediaToolList.compare("2.9.7","2.9.7"));
    }
}
