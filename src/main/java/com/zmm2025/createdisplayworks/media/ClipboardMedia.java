package com.zmm2025.createdisplayworks.media;

import java.io.*;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Reads copied files and image data from the system clipboard, which GLFW only exposes as text. */
public final class ClipboardMedia {
    private static final long MAX_BYTES=100L*1024*1024;
    private static final String OS=System.getProperty("os.name").toLowerCase(Locale.ROOT);
    private static final java.util.regex.Pattern IMAGE_SOURCE=java.util.regex.Pattern.compile("<img\\b[^>]*?\\bsrc\\s*=\\s*[\"']([^\"']+)[\"']",java.util.regex.Pattern.CASE_INSENSITIVE);
    private ClipboardMedia() {}

    /** A copied file, or image bytes and a display name that still have to be written to disk with {@link #save}. */
    public record Copied(Path file,String name,String extension,byte[] data) {
        public Path save(Path folder) throws IOException {
            if(file!=null)return file;
            byte[] bytes=data;String type=extension;
            // The bundled decoder only accepts a whitelist of formats, which leaves out TIFF.
            if(type.equals("tiff")) {
                var image=javax.imageio.ImageIO.read(new ByteArrayInputStream(bytes));
                if(image==null)throw new IOException("Cannot read this image.");
                var out=new ByteArrayOutputStream();javax.imageio.ImageIO.write(image,"png",out);bytes=out.toByteArray();type="png";
            }
            Files.createDirectories(folder);
            // Only the latest paste is needed, and names vary from paste to paste.
            try(var old=Files.list(folder)){for(Path stale:old.toList())try{Files.deleteIfExists(stale);}catch(IOException locked){}}
            Path target=folder.resolve(name+"."+type);Files.write(target,bytes);return target;
        }
    }

    /** Returns the copied media, or null when the clipboard holds only text or nothing this platform can read. */
    public static Copied read() throws IOException {
        try {
            if(OS.contains("mac"))return Mac.read();
            if(OS.contains("win"))return Windows.read();
            return Linux.read();
        }catch(IOException e){throw e;}catch(Throwable unsupported){return null;}
    }
    private static MediaImportException tooLarge(){return new MediaImportException(MediaImportException.Reason.TOO_LARGE,"Media exceeds 100 MB.");}
    private static Copied file(Path path){return new Copied(path,null,null,null);}
    /** {@code url} and {@code html} are where the image was copied from, if the source app says so. */
    private static Copied bytes(String extension,byte[] data,String url,String html) throws IOException {
        if(data==null||data.length==0)return null;
        if(data.length>MAX_BYTES)throw tooLarge();
        String name=name(url);
        if(name==null&&html!=null){var image=IMAGE_SOURCE.matcher(html);if(image.find())name=name(image.group(1).replace("&amp;","&"));}
        return new Copied(null,name!=null?name:net.minecraft.network.chat.Component.translatable("screen.create_display_works.media.name."+(extension.equals("gif")?"pasted_gif":"pasted_image")).getString(),extension,data);
    }
    /** The file name, without its extension, at the end of a copied image's web or file address. */
    static String name(String url){
        if(url==null||url.isBlank())return null;
        try {
            var uri=URI.create(url.strip().lines().findFirst().orElse(""));
            if(uri.getScheme()==null||!Set.of("http","https","file").contains(uri.getScheme().toLowerCase(Locale.ROOT))||uri.getPath()==null)return null;
            String last=uri.getPath().substring(uri.getPath().lastIndexOf('/')+1);
            int dot=last.lastIndexOf('.');if(dot>0)last=last.substring(0,dot);
            last=last.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]","").strip();
            if(last.length()>80)last=last.substring(0,80).strip();
            // Windows can't create files with reserved device names or trailing dots.
            last=last.replaceAll("\\.+$","");
            return last.isEmpty()||last.startsWith(".")||last.matches("(?i)(con|prn|aux|nul|com\\d|lpt\\d)")?null:last;
        }catch(IllegalArgumentException e){return null;}
    }
    static String text(byte[] data){
        if(data==null)return null;
        // Windows clipboard memory ends in a NUL terminator and sometimes padding, so trim those first.
        int end=data.length;while(end>0&&data[end-1]==0)end--;
        // Firefox on Linux offers its clipboard text as UTF-16, the only case with zero bytes inside the text.
        for(int i=0;i<end;i++)if(data[i]==0)return new String(data,0,Math.min(data.length,end+end%2),java.nio.charset.StandardCharsets.UTF_16LE).replace("\uFEFF","");
        return new String(data,0,end,java.nio.charset.StandardCharsets.UTF_8);
    }

    private static final class Mac {
        // Checked in order; file URLs go first because Finder also puts a TIFF of the file's icon on the clipboard.
        // GIF goes before PNG, because apps that copy an animated GIF often add a still PNG of its first frame.
        private static final String[][] TYPES={{"com.compuserve.gif","gif"},{"public.png","png"},{"public.jpeg","jpg"},{"public.heic","heic"},{"public.tiff","tiff"}};
        static Copied read() throws IOException {
            var objc=org.lwjgl.system.macosx.ObjCRuntime.getLibrary();
            long send=objc.getFunctionAddress("objc_msgSend");
            long pool=org.lwjgl.system.JNI.invokeP(objc.getFunctionAddress("objc_autoreleasePoolPush"));
            try(var stack=org.lwjgl.system.MemoryStack.stackPush()) {
                long board=call(send,cls("NSPasteboard"),"generalPasteboard");
                if(board==0)return null;
                long url=call(send,board,"stringForType:",string(send,stack,"public.file-url"));
                if(url!=0) {
                    // Finder copies file reference URLs (file:///.file/id=...), so resolve them to a path first.
                    long path=call(send,call(send,call(send,cls("NSURL"),"URLWithString:",url),"filePathURL"),"path");
                    if(path!=0)return file(Path.of(utf8(send,path)));
                }
                for(String[] type:TYPES) {
                    long data=call(send,board,"dataForType:",string(send,stack,type[0]));
                    if(data==0)continue;
                    long length=call(send,data,"length");
                    if(length>MAX_BYTES)throw tooLarge();
                    byte[] copy=new byte[(int)length];
                    if(length>0)org.lwjgl.system.MemoryUtil.memByteBuffer(call(send,data,"bytes"),(int)length).get(copy);
                    // Browsers also say where the image came from, which gives it a name.
                    return bytes(type[1],copy,utf8(send,call(send,board,"stringForType:",string(send,stack,"public.url"))),
                        utf8(send,call(send,board,"stringForType:",string(send,stack,"public.html"))));
                }
                return null;
            }finally{org.lwjgl.system.JNI.invokePV(pool,objc.getFunctionAddress("objc_autoreleasePoolPop"));}
        }
        private static long cls(String name){return org.lwjgl.system.macosx.ObjCRuntime.objc_getClass(name);}
        private static long call(long send,long target,String selector){
            return target==0?0:org.lwjgl.system.JNI.invokePPP(target,org.lwjgl.system.macosx.ObjCRuntime.sel_getUid(selector),send);
        }
        private static long call(long send,long target,String selector,long argument){
            return target==0||argument==0?0:org.lwjgl.system.JNI.invokePPPP(target,org.lwjgl.system.macosx.ObjCRuntime.sel_getUid(selector),argument,send);
        }
        private static String utf8(long send,long string){
            long chars=call(send,string,"UTF8String");return chars==0?null:org.lwjgl.system.MemoryUtil.memUTF8(chars);
        }
        private static long string(long send,org.lwjgl.system.MemoryStack stack,String value){
            return call(send,cls("NSString"),"stringWithUTF8String:",org.lwjgl.system.MemoryUtil.memAddress(stack.UTF8(value)));
        }
    }

    private static final class Windows {
        private static final int CF_DIB=8,CF_HDROP=15,BI_BITFIELDS=3,BI_ALPHABITFIELDS=6;
        static Copied read() throws IOException {
            if(integer("user32","OpenClipboard",com.sun.jna.Pointer.NULL)==0)return null;
            try {
                var drop=pointer("user32","GetClipboardData",CF_HDROP);
                if(drop!=null) {
                    char[] name=new char[32768];
                    int length=integer("shell32","DragQueryFileW",drop,0,name,name.length);
                    if(length>0)return file(Path.of(new String(name,0,length)));
                }
                // Browsers and most editors also offer a registered "PNG" format, which keeps transparency.
                String html=text(global(integer("user32","RegisterClipboardFormatW",new com.sun.jna.WString("HTML Format"))));
                Copied png=bytes("png",global(integer("user32","RegisterClipboardFormatW",new com.sun.jna.WString("PNG"))),null,html);
                return png!=null?png:bytes("bmp",bitmap(global(CF_DIB)),null,html);
            }finally{integer("user32","CloseClipboard");}
        }
        private static com.sun.jna.Function function(String library,String name){return com.sun.jna.Function.getFunction(library,name,com.sun.jna.Function.ALT_CONVENTION);}
        private static int integer(String library,String name,Object... args){return function(library,name).invokeInt(args);}
        private static com.sun.jna.Pointer pointer(String library,String name,Object... args){return function(library,name).invokePointer(args);}
        private static byte[] global(int format) throws IOException {
            if(format==0)return null;
            var handle=pointer("user32","GetClipboardData",format);
            if(handle==null)return null;
            long size=function("kernel32","GlobalSize").invokeLong(new Object[]{handle});
            if(size>MAX_BYTES)throw tooLarge();
            var data=pointer("kernel32","GlobalLock",handle);
            if(data==null)return null;
            try{return data.getByteArray(0,(int)size);}finally{integer("kernel32","GlobalUnlock",handle);}
        }
        /** Prepends the file header that a clipboard DIB leaves out, so it can be read as a BMP file. */
        private static byte[] bitmap(byte[] dib) {
            if(dib==null||dib.length<40)return null;
            var info=ByteBuffer.wrap(dib).order(ByteOrder.LITTLE_ENDIAN);
            int header=info.getInt(0),bits=info.getShort(14)&0xFFFF,compression=info.getInt(16),used=info.getInt(32);
            int masks=header==40?compression==BI_BITFIELDS?12:compression==BI_ALPHABITFIELDS?16:0:0;
            int colors=used!=0?used:bits<=8?1<<bits:0;
            var file=ByteBuffer.allocate(14+dib.length).order(ByteOrder.LITTLE_ENDIAN);
            file.put((byte)'B').put((byte)'M').putInt(14+dib.length).putInt(0).putInt(14+header+masks+colors*4).put(dib);
            return file.array();
        }
    }

    private static final class Linux {
        private static final String[][] TYPES={{"image/gif","gif"},{"image/png","png"},{"image/jpeg","jpg"},{"image/webp","webp"}};
        static Copied read() throws IOException {
            boolean wayland=System.getenv("WAYLAND_DISPLAY")!=null;
            byte[] listing=run(wayland?List.of("wl-paste","--list-types"):List.of("xclip","-selection","clipboard","-target","TARGETS","-out"));
            if(listing==null)return null;
            var types=new String(listing).lines().map(String::strip).toList();
            if(types.contains("text/uri-list")) {
                byte[] uris=run(paste(wayland,"text/uri-list"));
                if(uris!=null)for(String line:new String(uris).lines().map(String::strip).toList())
                    if(line.startsWith("file://"))return file(Path.of(URI.create(line)));
            }
            String url=types.contains("text/x-moz-url")?text(run(paste(wayland,"text/x-moz-url"))):null;
            String html=types.contains("text/html")?text(run(paste(wayland,"text/html"))):null;
            for(String[] type:TYPES)if(types.contains(type[0]))return bytes(type[1],run(paste(wayland,type[0])),url,html);
            return null;
        }
        private static List<String> paste(boolean wayland,String type){
            return wayland?List.of("wl-paste","--no-newline","--type",type):List.of("xclip","-selection","clipboard","-target",type,"-out");
        }
        /** Returns null when the clipboard tool is missing or fails, so the paste falls back to text. */
        private static byte[] run(List<String> command) throws IOException {
            Process process;
            try{process=new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();}catch(IOException missing){return null;}
            try(var output=process.getInputStream()) {
                byte[] data=output.readNBytes((int)MAX_BYTES+1);
                if(data.length>MAX_BYTES)throw tooLarge();
                return process.waitFor(5,TimeUnit.SECONDS)&&process.exitValue()==0?data:null;
            }catch(InterruptedException e){Thread.currentThread().interrupt();return null;}
            finally{process.destroyForcibly();}
        }
    }
}
