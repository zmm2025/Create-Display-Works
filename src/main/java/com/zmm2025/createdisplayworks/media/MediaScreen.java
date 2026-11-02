package com.zmm2025.createdisplayworks.media;

import com.simibubi.create.foundation.gui.AllGuiTextures;
import com.simibubi.create.foundation.gui.AllIcons;
import com.simibubi.create.foundation.gui.widget.IconButton;
import com.simibubi.create.foundation.gui.widget.ScrollInput;
import net.createmod.catnip.gui.AbstractSimiScreen;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

public final class MediaScreen extends AbstractSimiScreen {
    public static final net.minecraft.client.resources.model.ModelResourceLocation PREVIEW_MODEL=net.minecraft.client.resources.model.ModelResourceLocation.standalone(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("create_display_works","block/flip_dot_preview"));
    private static final int RIGHT=166, RIGHT_WIDTH=57;

    private static final ExecutorService WORKER=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"flip-dot-media");t.setDaemon(true);return t;});
    private final BlockPos pos;
    private final int columns,rows;
    private EditBox source;
    private IconButton file,load,confirm,play,repeat,invertButton,ditherButton,clear;
    private final List<IconButton> sizing=new ArrayList<>();
    private ScrollInput balance,length;
    private MediaClip preview;
    private EditableMedia original;
    private byte[] originalPixels, incoming;
    private int received;
    private boolean requestedEditor;
    private EditableMedia renderedSource;
    private MediaSettings renderedSettings;
    private int renderedFrame=-1;
    private long[] renderedBits;
    private String sourceValue="";
    private Component status=Component.empty(),errorDetail,progress,errorHint=text("empty.check_source");
    private int cutoff=128,seconds=60,savedFrames,previewTicks,saveTicks,editorRetryTicks;
    private boolean invert,dither,busy,dirty,fresh,playing=true,loop=true,saving;
    private MediaDecoder.Scale scale=MediaDecoder.Scale.STRETCH;
    private Future<?> job;

    public MediaScreen(BlockPos pos,int columns,int rows){super(Component.translatable("screen.create_display_works.media"));this.pos=pos;this.columns=columns;this.rows=rows;}
    public MediaScreen(BlockPos pos,int columns,int rows,String name,long[] thumbnail,int frames,boolean playing,boolean loop){
        this(pos,columns,rows);savedFrames=frames;if(frames>1)seconds=Math.min(60,(frames+MediaClip.FPS-1)/MediaClip.FPS);this.playing=playing;this.loop=loop;if(frames>0)status=text("status.on_display");
        if(thumbnail.length==columns*rows)preview=new MediaClip(columns,rows,thumbnail,name);
    }
    private static net.minecraft.network.chat.MutableComponent text(String key,Object... args){return Component.translatable("screen.create_display_works.media."+key,args);}
    /** Clips without a real file name store one of these fixed names; show them in the player's language. */
    private static Component mediaName(String filename){
        return filename.isEmpty()||filename.equals(MediaClip.UNNAMED)?text("name.imported"):filename.equals(MediaClip.YOUTUBE)?text("name.youtube"):Component.literal(filename);
    }
    private IconButton icon(int x,int y,net.createmod.catnip.gui.element.ScreenElement glyph,String key,boolean hint,Runnable action){
        var button=new IconButton(guiLeft+x,guiTop+y,glyph);
        button.withCallback(action);button.setToolTip(text(key));
        if(hint)button.getToolTip().add(text(key+".hint").withStyle(net.minecraft.ChatFormatting.GRAY));
        addRenderableWidget(button);return button;
    }
    @Override protected void init(){
        setWindowSize(256,video()?226:202);super.init();clearWidgets();sizing.clear();
        source=new EditBox(font,guiLeft+32,guiTop+31,142,12,text("source")) {
            @Override public void renderWidget(GuiGraphics g,int mouseX,int mouseY,float partial){
                if(preview!=null&&!isFocused()&&!dirty)g.drawString(font,net.minecraft.locale.Language.getInstance().getVisualOrder(font.substrByWidth(mediaName(preview.filename()),getWidth())),getX(),getY()+2,0xF3EBDE,false);
                else if(getValue().isEmpty())g.drawString(font,text("source.hint"),getX(),getY()+2,0xD8D8D8,false);
                else super.renderWidget(g,mouseX,mouseY,partial);
            }
        };
        source.setBordered(false);source.setTextColor(0xF3EBDE);source.setMaxLength(2048);
        source.setHint(text("source.hint"));source.setValue(sourceValue);
        source.setResponder(value->{sourceValue=value;dirty=true;errorDetail=null;status=text("status.load");syncControls();});
        source.setTooltip(Tooltip.create(text("source.tooltip")));
        addRenderableWidget(source);
        load=icon(178,27,AllIcons.I_MTD_SCAN,"load",true,this::importPreview);
        file=icon(205,27,AllIcons.I_OPEN_FOLDER,"choose_file",true,this::chooseFile);
        balance=new ScrollInput(guiLeft+RIGHT,guiTop+74,RIGHT_WIDTH,16).withRange(0,256).setState(cutoff)
            .titled(text("threshold")).addHint(text("threshold.hint"))
            .format(v->text("percent",Math.round(v/255f*100))).calling(v->{cutoff=v;changed();});
        addRenderableWidget(balance);
        invertButton=icon(RIGHT+7,100,AllIcons.I_FLIP,"invert",true,()->{invert=!invert;changed();});
        ditherButton=icon(RIGHT+32,100,MediaIcons.DITHER,"dither",true,()->{dither=!dither;changed();});
        net.createmod.catnip.gui.element.ScreenElement[] glyphs={MediaIcons.STRETCH,MediaIcons.FIT,MediaIcons.FILL};
        String[] names={"stretch","fit","fill"};
        for(int i=0;i<3;i++){int index=i;sizing.add(icon(RIGHT+(i==2?39:i*19),136,glyphs[i],names[i],true,()->{scale=MediaDecoder.Scale.values()[index];changed();}));}
        play=icon(28,166,AllIcons.I_PAUSE,"pause",false,()->{playing=!playing;if(!fresh)control(playing?"play":"pause");syncControls();});
        repeat=icon(50,166,AllIcons.I_REFRESH,"loop",true,()->{loop=!loop;if(!fresh)control("loop");syncControls();});
        addRenderableWidget(new com.simibubi.create.foundation.gui.widget.TooltipArea(guiLeft+44,guiTop+footerY(),18,18).withTooltip(helpText()));
        clear=icon(22,footerY(),AllIcons.I_TRASH,"clear",true,()->{control("clear");preview=null;original=null;originalPixels=null;renderedSource=null;renderedSettings=null;renderedBits=null;savedFrames=0;fresh=false;dirty=false;status=text("status.cleared");init();syncControls();});
        confirm=icon(214,footerY(),AllIcons.I_CONFIRM,"apply",false,this::applyPreview);
        length=new ScrollInput(guiLeft+RIGHT+20,guiTop+167,37,16).withRange(1,61).setState(seconds)
            .titled(text("length")).addHint(text("length.hint"))
            .format(v->text("seconds",v)).calling(v->{seconds=v;changed();});addRenderableWidget(length);
        syncControls();if(preview==null)setInitialFocus(source);else setFocused(null);
        control("status");
        if(savedFrames>0&&!requestedEditor&&original==null){requestedEditor=true;control("edit");}
    }
    private int footerY(){return video()?196:172;}
    private boolean video(){return original!=null?original.count()>1:fresh?preview!=null&&preview.count()>1:savedFrames>1;}
    private MediaSettings settings(){return new MediaSettings(cutoff,invert,dither,scale,seconds);}
    private void changed(){
        errorDetail=null;
        if(original!=null){fresh=true;status=text("status.ready");int count=original.frameCount(settings());previewTicks=Math.min(previewTicks,Math.max(0,count*2-1));}
        syncControls();
    }
    private void syncControls(){
        if(load==null)return;
        source.setEditable(!busy&&!saving);file.active=!busy&&!saving;load.active=!busy&&!saving&&!sourceValue.isBlank();
        balance.active=invertButton.active=ditherButton.active=!busy&&!saving&&original!=null;
        invertButton.green=invert;ditherButton.green=dither;
        for(int i=0;i<sizing.size();i++){sizing.get(i).green=scale.ordinal()==i;sizing.get(i).active=balance.active;}
        play.visible=repeat.visible=length.visible=video();
        play.active=repeat.active=!busy&&!saving;length.active=!busy&&!saving&&original!=null;
        play.setIcon(playing?AllIcons.I_PAUSE:AllIcons.I_PLAY);play.setToolTip(text(playing?"pause":"play"));repeat.green=loop;
        confirm.active=!busy&&!saving&&fresh&&!dirty&&preview!=null;confirm.green=false;
        confirm.setToolTip(text("apply"));clear.active=!busy&&!saving&&(preview!=null||savedFrames>0);
    }
    private static net.minecraft.network.chat.MutableComponent helpLine(String label,Component value){
        return Component.empty().append(text("help.label",text("help."+label)).withStyle(net.minecraft.ChatFormatting.GRAY,net.minecraft.ChatFormatting.BOLD))
            .append(value.copy().withStyle(net.minecraft.ChatFormatting.WHITE));
    }
    private List<Component> helpText(){return List.of(
        helpLine("size",text("help.size.value",columns*8,rows*8)),
        helpLine("playback",text("help.playback.value")),
        helpLine("duration",text("help.duration.value")),
        helpLine("file_limit",text("help.file_limit.value")),
        helpLine("images",Component.literal("PNG, JPEG, HEIC/HEIF")),
        helpLine("other_images",Component.literal("ICO, WebP, SVG")),
        helpLine("animations",Component.literal("GIF, APNG")),
        helpLine("videos",Component.literal("MP4, MOV, MKV, WebM")),
        helpLine("links",Component.literal("YouTube")).append(text("help.links.note").withStyle(net.minecraft.ChatFormatting.GRAY,net.minecraft.ChatFormatting.ITALIC)));
    }
    private void chooseFile(){
        if(busy||saving)return;
        try(var stack=org.lwjgl.system.MemoryStack.stackPush()){
            String[] extensions={"*.png","*.jpg","*.jpeg","*.heic","*.heif","*.ico","*.webp","*.svg","*.gif","*.apng","*.mp4","*.mov","*.mkv","*.webm"};
            var filters=stack.mallocPointer(extensions.length);for(String extension:extensions)filters.put(stack.UTF8(extension));filters.flip();
            String chosen=org.lwjgl.util.tinyfd.TinyFileDialogs.tinyfd_openFileDialog(text("file_dialog.title").getString(),"",filters,text("file_dialog.filter").getString(),false);
            if(chosen!=null){setSource(chosen);importPreview();}
        }catch(Exception e){showError(e);syncControls();}
    }
    private void control(String action){PacketDistributor.sendToServer(new MediaNetwork.Control(pos,action));}
    public void importPreview(){
        if(busy||sourceValue.isBlank())return;
        busy=true;status=text("status.loading");errorDetail=null;progress=null;syncControls();
        String input=sourceValue.strip();int duration=60,threshold=cutoff;boolean inverted=invert,ordered=dither;var sizingMode=scale;
        job=WORKER.submit(()->{
            try{
                var clip=MediaDecoder.decode(input,columns,rows,duration,threshold,inverted,sizingMode,ordered,minecraft.gameDirectory.toPath().resolve("create_display_works/media-cache"),
                    step->minecraft.execute(()->{if(minecraft.screen==this&&busy)progress=text("progress."+step);}));
                byte[] pixels=clip.editable()==null?null:clip.editable().pixels();
                minecraft.execute(()->{if(minecraft.screen!=this)return;preview=clip;original=clip.editable();originalPixels=pixels;fresh=true;dirty=false;busy=false;playing=true;previewTicks=0;status=text("status.ready");init();setFocused(null);syncControls();});
            }catch(Exception exception){minecraft.execute(()->{if(minecraft.screen!=this)return;busy=false;showError(exception);syncControls();});}
        });
    }
    private void showError(Exception error){
        String message=error.getMessage()==null?"":error.getMessage().toLowerCase(Locale.ROOT);
        status=text("status.load_failed");errorHint=text("empty.check_source");
        if(error instanceof MediaImportException known){
            // Only some failures are the source's fault; the rest point at the connection or the details below.
            errorHint=switch(known.reason()){
                case NETWORK,TOOLS_DOWNLOAD->text("empty.check_connection");
                case TOOLS_BLOCKED,UNSUPPORTED,CACHE->text("empty.see_status");
                default->errorHint;
            };
            // The player sees a short hint; the log keeps the cause for bug reports.
            if(known.reason()!=MediaImportException.Reason.NOT_FOUND&&known.reason()!=MediaImportException.Reason.TOO_LARGE)com.mojang.logging.LogUtils.getLogger().warn("Media import failed: {}",known.getMessage());
            errorDetail=switch(known.reason()){
                case NOT_FOUND->text("error.not_found");
                case TOO_LARGE->text("error.too_large");
                case TIMED_OUT->text("error.timed_out");
                case NETWORK->text("error.network",known.detail());
                case TOOLS_DOWNLOAD->text("error.tools_download",known.detail());
                case TOOLS_BLOCKED->text("error.tools_blocked");
                case UNSUPPORTED->text("error.unsupported",known.detail());
                case CACHE->text("error.cache",known.detail());
            };
        }
        else errorDetail=text(message.contains("youtube")?"error.youtube":"error.unreadable");
    }
    public void applyPreview(){
        if(busy||saving||!confirm.active)return;
        if(!fresh){onClose();return;}
        saving=true;saveTicks=0;status=text("status.saving");syncControls();
        var selected=settings();var sourceMedia=original;var current=preview;
        job=WORKER.submit(()->{
            try{var rendered=sourceMedia==null?current:sourceMedia.render(columns,rows,current.filename(),selected);
                minecraft.execute(()->{if(minecraft.screen==this)MediaNetwork.upload(pos,rendered);});
            }catch(Exception e){minecraft.execute(()->{saving=false;showError(e);syncControls();});}
        });
    }
    public void updateStatus(MediaNetwork.Status update){
        if(!update.pos().equals(pos))return;
        if(update.frames()==-2){editorRetryTicks=20;return;}
        boolean wasVideo=video();
        if(saving&&update.frames()<0){saving=false;if(update.message().equals(MediaNetwork.SAVED)){if(!loop)control("loop");if(!playing)control("pause");onClose();return;}status=text("status.save_failed");errorDetail=Component.translatable(update.message());}
        if(update.frames()>=0){savedFrames=update.frames();if(!fresh){loop=update.loop();playing=update.playing();}}
        if(wasVideo!=video())init();
        syncControls();
    }
    public void receiveEditor(MediaNetwork.Part part){
        if(!part.pos().equals(pos)||fresh||busy||part.columns()!=columns||part.rows()!=rows||part.total()<1||part.total()>EditableMedia.MAX_BYTES||part.offset()<0||part.offset()>part.total()-part.data().length)return;
        if(part.offset()==0){incoming=new byte[part.total()];received=0;}
        if(incoming==null||incoming.length!=part.total()||received!=part.offset())return;
        System.arraycopy(part.data(),0,incoming,received,part.data().length);received+=part.data().length;PacketDistributor.sendToServer(new MediaNetwork.Ack(pos,received));
        if(received==incoming.length){
            byte[] archive=incoming;incoming=null;busy=true;status=text("status.loading");syncControls();
            job=WORKER.submit(()->{
                try{var clip=MediaClip.fromArchive(archive);byte[] pixels=clip.editable()==null?null:clip.editable().pixels();
                    minecraft.execute(()->{if(minecraft.screen!=this)return;
                        preview=clip;original=clip.editable();originalPixels=pixels;var settings=clip.settings();
                        cutoff=settings.threshold();invert=settings.invert();dither=settings.dither();scale=settings.scale();seconds=settings.seconds();
                        busy=false;dirty=false;status=text(original==null?"status.reimport":"status.on_display");init();syncControls();
                    });
                }catch(Exception e){minecraft.execute(()->{if(minecraft.screen!=this)return;busy=false;showError(e);syncControls();});}
            });
        }
    }
    public int previewClock(){return previewTicks;}
    public boolean editableReady(){return original!=null&&!busy;}
    public void setSource(String value){source.setValue(value);source.setCursorPosition(0);source.setHighlightPos(0);}
    public boolean previewReady(){return fresh&&preview!=null&&!busy&&!dirty;}
    public String importStatus(){return status.getString();}
    @Override public void onFilesDrop(List<java.nio.file.Path> paths){if(!paths.isEmpty()&&!busy&&!saving){setSource(paths.getFirst().toString());importPreview();}}
    @Override public boolean keyPressed(int key,int scan,int modifiers){
        if((key==257||key==335)&&source.isFocused()){importPreview();return true;}
        if(isPaste(key,modifiers)&&!busy&&!saving&&pasteMedia())return true;
        return super.keyPressed(key,scan,modifiers);
    }
    /** Cmd+V on macOS, Ctrl+V elsewhere, read from the event's modifiers like the rest of this key event. */
    private static boolean isPaste(int key,int modifiers){
        int shortcut=net.minecraft.client.Minecraft.ON_OSX?org.lwjgl.glfw.GLFW.GLFW_MOD_SUPER:org.lwjgl.glfw.GLFW.GLFW_MOD_CONTROL;
        return key==org.lwjgl.glfw.GLFW.GLFW_KEY_V&&(modifiers&(shortcut|org.lwjgl.glfw.GLFW.GLFW_MOD_SHIFT|org.lwjgl.glfw.GLFW.GLFW_MOD_ALT))==shortcut;
    }
    /** Loads a copied image or file. Copied text still pastes into the source box, or loads directly when the box isn't focused. */
    private boolean pasteMedia(){
        ClipboardMedia.Copied copied;
        try{copied=ClipboardMedia.read();}catch(Exception e){showError(e);syncControls();return true;}
        if(copied==null){
            String text=minecraft.keyboardHandler.getClipboard().strip();
            if(source.isFocused()||text.isEmpty()||text.contains("\n"))return false;
            setSource(text);importPreview();return true;
        }
        if(copied.file()!=null){setSource(copied.file().toString());importPreview();return true;}
        busy=true;status=text("status.loading");errorDetail=null;syncControls();
        Path folder=minecraft.gameDirectory.toPath().resolve("create_display_works/media-cache/clipboard");
        job=WORKER.submit(()->{
            try{Path file=copied.save(folder);minecraft.execute(()->{if(minecraft.screen!=this)return;busy=false;setSource(file.toString());importPreview();});}
            catch(Exception e){minecraft.execute(()->{if(minecraft.screen!=this)return;busy=false;showError(e);syncControls();});}
        });
        return true;
    }
    @Override public void tick(){
        super.tick();
        if(editorRetryTicks>0&&--editorRetryTicks==0&&original==null&&!fresh&&!busy)control("edit");
        if(saving&&++saveTicks>600){saving=false;status=text("status.save_failed");errorDetail=text("error.upload_timed_out");syncControls();}
        if(!playing||busy)return;
        int count=original!=null?original.frameCount(settings()):preview!=null?preview.count():1;
        if(++previewTicks>=count*2)previewTicks=loop?0:count*2-1;
    }
    @Override public void removed(){if(job!=null&&!job.isDone())job.cancel(true);}
    private void well(GuiGraphics g,int x,int y,int w,int h){
        x+=guiLeft;y+=guiTop;g.fill(x-1,y-1,x+w+1,y+h+1,0xFF373737);g.fill(x,y,x+w,y+h,0xFF8B8B8B);g.fill(x,y+h,x+w+1,y+h+1,0xFFFFFFFF);g.fill(x+w,y,x+w+1,y+h+1,0xFFC6C6C6);
    }
    @Override protected void renderWindow(GuiGraphics g,int mouseX,int mouseY,float partial){
        if(video())AllGuiTextures.SCHEDULE.render(g,guiLeft,guiTop);
        else {
            g.blit(AllGuiTextures.SCHEDULE.location,guiLeft,guiTop,0,0,256,166);
            g.blit(AllGuiTextures.SCHEDULE.location,guiLeft,guiTop+166,0,190,256,36);
        }
        g.drawString(font,title,guiLeft+126-font.width(title)/2,guiTop+4,0x303030,false);
        well(g,28,27,148,18);well(g,RIGHT+1,74,RIGHT_WIDTH-2,16);well(g,27,56,132,99);
        g.drawString(font,text("threshold"),guiLeft+RIGHT,guiTop+61,0xF3EBDE,false);
        g.drawCenteredString(font,text("percent",Math.round(cutoff/255f*100)),guiLeft+RIGHT+RIGHT_WIDTH/2,guiTop+78,0xF3EBDE);
        g.drawString(font,text("framing"),guiLeft+RIGHT,guiTop+125,0xF3EBDE,false);
        drawPreview(g);
        if(width>350){
            g.flush();g.pose().pushPose();g.pose().translate(guiLeft+282,guiTop+windowHeight-39,100);g.pose().scale(34,-34,34);
            g.pose().mulPose(com.mojang.math.Axis.XP.rotationDegrees(20));g.pose().mulPose(com.mojang.math.Axis.YP.rotationDegrees(150));
            com.mojang.blaze3d.platform.Lighting.setupFor3DItems();
            minecraft.getItemRenderer().render(new net.minecraft.world.item.ItemStack(com.zmm2025.createdisplayworks.ModContent.FLIP_DOT.get()),
                net.minecraft.world.item.ItemDisplayContext.NONE,false,g.pose(),g.bufferSource(),15728880,
                net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY,minecraft.getModelManager().getModel(PREVIEW_MODEL));
            g.flush();g.pose().popPose();

        }
        if(video()){
            well(g,RIGHT+21,167,35,16);MediaIcons.CLOCK.render(g,guiLeft+RIGHT,guiTop+167);
            g.drawCenteredString(font,text("seconds",seconds),guiLeft+RIGHT+38,guiTop+171,0xF3EBDE);
        }
        // Schedule has a baked-in slot here; erase it for a truly unframed help glyph.
        g.fill(guiLeft+20,guiTop+footerY()-1,guiLeft+41,guiTop+footerY()+19,0xFFC6C6C6);
        MediaIcons.INFO.render(g,guiLeft+45,guiTop+footerY()+1);
        var statusLabel=net.minecraft.locale.Language.getInstance().getVisualOrder(font.substrByWidth(status,132));
        g.drawString(font,statusLabel,guiLeft+202-font.width(statusLabel),guiTop+footerY()+5,errorDetail==null?0x575F50:0xA3423E,false);
    }
    private void drawPreview(GuiGraphics g){
        int px=guiLeft+29,py=guiTop+58,pw=128,ph=95;
        int front=0xF9FFFE,back=0x1D1D21;
        if(minecraft.level!=null&&minecraft.level.getBlockEntity(pos) instanceof com.zmm2025.createdisplayworks.flipdot.FlipDotBlockEntity tile){front=tile.discColor();back=tile.backColor();}
        g.fill(px,py,px+pw,py+ph,0xFF242424);
        if(busy){
            AllIcons.I_REFRESH.render(g,px+56,py+30);
            // First imports download helper programs for a while, so say what's happening and how much.
            var lines=font.split(progress==null?text("status.loading"):progress,pw-8);
            for(int i=0;i<Math.min(3,lines.size());i++)g.drawCenteredString(font,lines.get(i),px+64,py+54+i*10,0xD3C6BA);
            return;
        }
        if(preview==null){AllIcons.I_SCHEMATIC.render(g,px+56,py+27);g.drawCenteredString(font,text(errorDetail==null?"empty.title":"status.load_failed"),px+64,py+51,0xD3C6BA);g.drawCenteredString(font,errorDetail==null?text("empty.subtitle"):errorHint,px+64,py+63,0x999999);return;}
        int w=columns*8,h=rows*8;float pixel=Math.min(pw/(float)w,ph/(float)h);int dw=Math.round(w*pixel),dh=Math.round(h*pixel);px+=(pw-dw)/2;py+=(ph-dh)/2;
        g.fill(px,py,px+dw,py+dh,0xFF000000|back);
        int count=original==null?preview.count():original.frameCount(settings());
        int frame=previewTicks/2;frame=loop?frame%count:Math.min(frame,count-1);long[] bits;
        if(original==null)bits=preview.frame(frame);
        else {
            var selected=settings();
            if(renderedSource!=original||renderedFrame!=frame||!selected.equals(renderedSettings)){
                renderedBits=original.frame(originalPixels,frame,columns,rows,selected);renderedSource=original;renderedFrame=frame;renderedSettings=selected;
            }
            bits=renderedBits;
        }
        for(int y=0;y<h;y++)for(int x=0;x<w;x++)if((bits[y/8*columns+x/8]&(1L<<(y%8*8+x%8)))!=0)
            g.fill(px+Math.round(x*pixel),py+Math.round(y*pixel),px+Math.round((x+1)*pixel),py+Math.round((y+1)*pixel),0xFF000000|front);
    }
    @Override protected void renderWindowForeground(GuiGraphics g,int mouseX,int mouseY,float partial){
        super.renderWindowForeground(g,mouseX,mouseY,partial);
        if(errorDetail!=null&&mouseX>=guiLeft+68&&mouseX<guiLeft+218&&mouseY>=guiTop+footerY()-2&&mouseY<guiTop+footerY()+22)g.renderTooltip(font,font.split(errorDetail,180),mouseX,mouseY);
        if(mouseX>=guiLeft+27&&mouseX<guiLeft+159&&mouseY>=guiTop+56&&mouseY<guiTop+155)
            g.renderTooltip(font,helpText(),java.util.Optional.empty(),mouseX,mouseY);
    }
}
