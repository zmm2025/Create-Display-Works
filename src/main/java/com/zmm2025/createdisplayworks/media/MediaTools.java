package com.zmm2025.createdisplayworks.media;

import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The helper programs media import downloads on first use: ffmpeg, and yt-dlp with deno for YouTube. Each lives in its
 * own cache folder with a manifest of its files' SHA-256 hashes, so a partial download, a file that security software
 * removed or a corrupted copy is downloaded again instead of failing every import.
 */
final class MediaTools {
    /** Helpers are larger than media and only download once, so slow connections get longer than media links do. */
    private static final Duration DOWNLOAD_LIMIT=Duration.ofMinutes(10);
    private static final long DOWNLOAD_BYTES=200L*1024*1024;
    private static final String MANIFEST="verified";
    /** Folders whose files matched their manifest this session; checking them again on every import would be wasted work. */
    private static final Set<Path> CHECKED=ConcurrentHashMap.newKeySet();
    private MediaTools() {}

    /** The helper's path if its folder still holds exactly the files it was sealed with, otherwise null. */
    static Path ready(Path root,String executable) {
        Path exe=root.resolve(executable),manifest=root.resolve(MANIFEST);
        if(!Files.isRegularFile(exe)||!Files.isRegularFile(manifest))return null;
        if(CHECKED.contains(root))return exe;
        try {
            List<String> lines=Files.readAllLines(manifest);
            boolean intact=lines.stream().anyMatch(line->line.endsWith(" "+executable));
            for(String line:lines) {
                int split=line.indexOf(' ');
                Path file=split<0?null:root.resolve(line.substring(split+1)).normalize();
                if(file==null||!root.equals(file.getParent())||!Files.isRegularFile(file)||!sha256(file).equals(line.substring(0,split))){intact=false;break;}
            }
            if(intact){CHECKED.add(root);return exe;}
        }catch(IOException ignored){}
        invalidate(root);
        return null;
    }
    /** Records the hashes of everything now in the folder, which marks the helper as ready. */
    static void seal(Path root) throws IOException {
        var lines=new ArrayList<String>();
        try(var files=Files.list(root)) {
            for(Path file:files.sorted().toList()) {
                String name=file.getFileName().toString();
                if(Files.isRegularFile(file)&&!name.equals(MANIFEST)&&!name.endsWith(".tmp"))lines.add(sha256(file)+" "+name);
            }
        }
        Files.write(root.resolve(MANIFEST),lines);
        CHECKED.add(root);
    }
    /** Checks the folder's files again on the next import, after a helper failed in a way a damaged copy could explain. */
    static void recheck(Path root){CHECKED.remove(root);}
    private static void invalidate(Path root){
        CHECKED.remove(root);
        try{Files.deleteIfExists(root.resolve(MANIFEST));}catch(IOException ignored){}
    }
    /** Downloads a pinned helper archive and checks its hash. Any failure is reported as a helper download problem. */
    static void fetch(String url,Path target,String digest) throws Exception {
        URI uri=URI.create(url);
        try {
            MediaDecoder.download(uri,target,DOWNLOAD_BYTES,DOWNLOAD_LIMIT);
            // A filtering proxy can answer with its own page instead of the file, so a mismatch is a download problem too.
            if(!sha256(target).equals(digest))throw new IOException("Checksum did not match");
        }catch(Exception e) {
            if(e instanceof InterruptedException||Thread.currentThread().isInterrupted())throw e;
            throw new MediaImportException(MediaImportException.Reason.TOOLS_DOWNLOAD,"Cannot download "+url+": "+e,uri.getHost(),e);
        }
    }
    /** Starts a helper. If the OS refuses to run it, the copy is discarded so the next import downloads a fresh one. */
    static Process start(ProcessBuilder builder,Path root) throws IOException {
        try{return builder.start();}
        catch(IOException e) {
            invalidate(root);
            throw new MediaImportException(MediaImportException.Reason.TOOLS_BLOCKED,"Cannot start "+builder.command().getFirst()+": "+e.getMessage(),"",e);
        }
    }
    static String sha256(Path file) throws IOException {
        try(var input=Files.newInputStream(file)) {
            var digest=MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[65536];
            for(int n;(n=input.read(buffer))!=-1;)digest.update(buffer,0,n);
            return HexFormat.of().formatHex(digest.digest());
        }catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
}
