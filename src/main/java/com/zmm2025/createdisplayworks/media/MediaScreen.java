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
    private String sourceValue="",status="",errorDetail="";
    private int cutoff=128,seconds=60,savedFrames,previewTicks,saveTicks,editorRetryTicks;
    private boolean invert,dither,busy,dirty,fresh,playing=true,loop=true,saving;
    private MediaDecoder.Scale scale=MediaDecoder.Scale.STRETCH;
    private Future<?> job;

    public MediaScreen(BlockPos pos,int columns,int rows){super(Component.translatable("screen.create_display_works.media"));this.pos=pos;this.columns=columns;this.rows=rows;}
    public MediaScreen(BlockPos pos,int columns,int rows,String name,long[] thumbnail,int frames,boolean playing,boolean loop){
        this(pos,columns,rows);savedFrames=frames;if(frames>1)seconds=Math.min(60,(frames+MediaClip.FPS-1)/MediaClip.FPS);this.playing=playing;this.loop=loop;if(frames>0)status="On display";
        if(thumbnail.length==columns*rows)preview=new MediaClip(columns,rows,thumbnail,name);
    }
    private static net.minecraft.network.chat.MutableComponent text(String value){return Component.literal(value);}
    private IconButton icon(int x,int y,net.createmod.catnip.gui.element.ScreenElement glyph,String title,String hint,Runnable action){
        var button=new IconButton(guiLeft+x,guiTop+y,glyph);
        button.withCallback(action);button.setToolTip(text(title));
        if(!hint.isEmpty())button.getToolTip().add(text(hint).withStyle(net.minecraft.ChatFormatting.GRAY));
        addRenderableWidget(button);return button;
    }
    @Override protected void init(){
        setWindowSize(256,video()?226:202);super.init();clearWidgets();sizing.clear();
        source=new EditBox(font,guiLeft+32,guiTop+31,142,12,text("Media source")) {
            @Override public void renderWidget(GuiGraphics g,int mouseX,int mouseY,float partial){
                if(preview!=null&&!isFocused()&&!dirty)g.drawString(font,font.plainSubstrByWidth(preview.filename(),getWidth()),getX(),getY()+2,0xF3EBDE,false);
                else if(getValue().isEmpty())g.drawString(font,"Paste a media link",getX(),getY()+2,0xD8D8D8,false);
                else super.renderWidget(g,mouseX,mouseY,partial);
            }
        };
        source.setBordered(false);source.setTextColor(0xF3EBDE);source.setMaxLength(2048);
        source.setHint(text("Paste a media link"));source.setValue(sourceValue);
        source.setResponder(value->{sourceValue=value;dirty=true;errorDetail="";status="Load media";syncControls();});
        source.setTooltip(Tooltip.create(text("Image, video or YouTube link. You can also paste a file path, or drop a file onto this screen.")));
        addRenderableWidget(source);
        load=icon(178,27,AllIcons.I_MTD_SCAN,"Load media","Read the source and preview it on the dots.",this::importPreview);
        file=icon(205,27,AllIcons.I_OPEN_FOLDER,"Choose file","Open an image or video from this computer.",this::chooseFile);
        balance=new ScrollInput(guiLeft+RIGHT,guiTop+74,RIGHT_WIDTH,16).withRange(0,256).setState(cutoff)
            .titled(Component.literal("Threshold")).addHint(Component.literal("Scroll to choose which pixels use the front color."))
            .format(v->text(Math.round(v/255f*100)+"%" )).calling(v->{cutoff=v;changed();});
        addRenderableWidget(balance);
        invertButton=icon(RIGHT+7,100,AllIcons.I_FLIP,"Invert image","Exchange light and dark pixels.",()->{invert=!invert;changed();});
        ditherButton=icon(RIGHT+32,100,MediaIcons.DITHER,"Dithering","Use dot patterns to shade the image.",()->{dither=!dither;changed();});
        net.createmod.catnip.gui.element.ScreenElement[] glyphs={MediaIcons.STRETCH,MediaIcons.FIT,MediaIcons.FILL};
        String[] names={"Stretch","Fit","Fill"},hints={"Use the whole board.","Keep the whole picture. Pad with the back color.","Fill the board. Crop the edges."};
        for(int i=0;i<3;i++){int index=i;sizing.add(icon(RIGHT+(i==2?39:i*19),136,glyphs[i],names[i],hints[i],()->{scale=MediaDecoder.Scale.values()[index];changed();}));}
        play=icon(28,166,AllIcons.I_PAUSE,"Pause","",()->{playing=!playing;if(!fresh)control(playing?"play":"pause");syncControls();});
        repeat=icon(50,166,AllIcons.I_REFRESH,"Loop","Repeat the clip when it finishes.",()->{loop=!loop;if(!fresh)control("loop");syncControls();});
        addRenderableWidget(new com.simibubi.create.foundation.gui.widget.TooltipArea(guiLeft+44,guiTop+footerY(),18,18).withTooltip(helpText()));
        clear=icon(22,footerY(),AllIcons.I_TRASH,"Clear display","Remove the image or video.",()->{control("clear");preview=null;original=null;originalPixels=null;renderedSource=null;renderedSettings=null;renderedBits=null;savedFrames=0;fresh=false;dirty=false;status="Cleared";init();syncControls();});
        confirm=icon(214,footerY(),AllIcons.I_CONFIRM,"Apply to display","",this::applyPreview);
        length=new ScrollInput(guiLeft+RIGHT+20,guiTop+167,37,16).withRange(1,61).setState(seconds)
            .titled(Component.literal("Clip length")).addHint(Component.literal("Seconds to play, starting at the beginning."))
            .format(v->text(v+" s")).calling(v->{seconds=v;changed();});addRenderableWidget(length);
        syncControls();if(preview==null)setInitialFocus(source);else setFocused(null);
        control("status");
        if(savedFrames>0&&!requestedEditor&&original==null){requestedEditor=true;control("edit");}
    }
    private int footerY(){return video()?196:172;}
    private boolean video(){return original!=null?original.count()>1:fresh?preview!=null&&preview.count()>1:savedFrames>1;}
    private MediaSettings settings(){return new MediaSettings(cutoff,invert,dither,scale,seconds);}
    private void changed(){
        errorDetail="";
        if(original!=null){fresh=true;status="Ready";int count=original.frameCount(settings());previewTicks=Math.min(previewTicks,Math.max(0,count*2-1));}
        syncControls();
    }
    private void syncControls(){
        if(load==null)return;
        source.setEditable(!busy&&!saving);file.active=!busy&&!saving;load.active=!busy&&!saving&&!sourceValue.isBlank();
        balance.active=invertButton.active=ditherButton.active=!busy&&!saving&&original!=null;
        invertButton.green=false;ditherButton.green=dither;
        for(int i=0;i<sizing.size();i++){sizing.get(i).green=scale.ordinal()==i;sizing.get(i).active=balance.active;}
        play.visible=repeat.visible=length.visible=video();
        play.active=repeat.active=!busy&&!saving;length.active=!busy&&!saving&&original!=null;
        play.setIcon(playing?AllIcons.I_PAUSE:AllIcons.I_PLAY);play.setToolTip(text(playing?"Pause":"Play"));repeat.green=loop;
        confirm.active=!busy&&!saving&&fresh&&!dirty&&preview!=null;confirm.green=false;
        confirm.setToolTip(text("Apply to display"));clear.active=!busy&&!saving&&(preview!=null||savedFrames>0);
    }
    private static net.minecraft.network.chat.MutableComponent helpLine(String label,String value){
        return Component.empty().append(text(label+": ").withStyle(net.minecraft.ChatFormatting.GRAY,net.minecraft.ChatFormatting.BOLD))
            .append(text(value).withStyle(net.minecraft.ChatFormatting.WHITE));
    }
    private List<Component> helpText(){return List.of(
        helpLine("Size",columns*8+" x "+rows*8+" dots"),
        helpLine("Playback","Silent, up to 10 FPS"),
        helpLine("Duration","First 60 seconds"),
        helpLine("File limit","100 MB"),
        helpLine("Images","PNG, JPEG, HEIC/HEIF"),
        helpLine("Other images","ICO, WebP, SVG"),
        helpLine("Animations","GIF, APNG"),
        helpLine("Videos","MP4, MOV, MKV, WebM"),
        helpLine("Links","YouTube").append(text(" (recorded videos)").withStyle(net.minecraft.ChatFormatting.GRAY,net.minecraft.ChatFormatting.ITALIC)));
    }
    private void chooseFile(){
        if(busy||saving)return;
        try(var stack=org.lwjgl.system.MemoryStack.stackPush()){
            String[] extensions={"*.png","*.jpg","*.jpeg","*.heic","*.heif","*.ico","*.webp","*.svg","*.gif","*.apng","*.mp4","*.mov","*.mkv","*.webm"};
            var filters=stack.mallocPointer(extensions.length);for(String extension:extensions)filters.put(stack.UTF8(extension));filters.flip();
            String chosen=org.lwjgl.util.tinyfd.TinyFileDialogs.tinyfd_openFileDialog("Choose display media","",filters,"Images and videos",false);
            if(chosen!=null){setSource(chosen);importPreview();}
        }catch(Exception e){showError("Unable to open the file chooser");syncControls();}
    }
    private void control(String action){PacketDistributor.sendToServer(new MediaNetwork.Control(pos,action));}
    public void importPreview(){
        if(busy||sourceValue.isBlank())return;
        busy=true;status="Loading media";errorDetail="";syncControls();
        String input=sourceValue.strip();int duration=60,threshold=cutoff;boolean inverted=invert,ordered=dither;var sizingMode=scale;
        job=WORKER.submit(()->{
            try{
                var clip=MediaDecoder.decode(input,columns,rows,duration,threshold,inverted,sizingMode,ordered,minecraft.gameDirectory.toPath().resolve("create_display_works/media-cache"),s->{});
                byte[] pixels=clip.editable()==null?null:clip.editable().pixels();
                minecraft.execute(()->{if(minecraft.screen!=this)return;preview=clip;original=clip.editable();originalPixels=pixels;fresh=true;dirty=false;busy=false;playing=true;previewTicks=0;status="Ready";init();setFocused(null);syncControls();});
            }catch(Exception exception){minecraft.execute(()->{if(minecraft.screen!=this)return;busy=false;showError(exception.getMessage());syncControls();});}
        });
    }
    private void showError(String detail){
        String message=detail==null?"":detail.toLowerCase(Locale.ROOT);
        status="Unable to load";
        errorDetail=message.contains("100 mb")?"Choose a file smaller than 100 MB.":message.contains("timed out")||message.contains("time limit")?"Loading took too long. Try again.":message.contains("youtube")?"Use a public, recorded YouTube video.":"Check the link or file, then try again.";
    }
    public void applyPreview(){
        if(busy||saving||!confirm.active)return;
        if(!fresh){onClose();return;}
        saving=true;saveTicks=0;status="Saving display";syncControls();
        var selected=settings();var sourceMedia=original;var current=preview;
        job=WORKER.submit(()->{
            try{var rendered=sourceMedia==null?current:sourceMedia.render(columns,rows,current.filename(),selected);
                minecraft.execute(()->{if(minecraft.screen==this)MediaNetwork.upload(pos,rendered);});
            }catch(Exception e){minecraft.execute(()->{saving=false;showError(e.getMessage());syncControls();});}
        });
    }
    public void updateStatus(MediaNetwork.Status update){
        if(!update.pos().equals(pos))return;
        if(update.frames()==-2){editorRetryTicks=20;return;}
        boolean wasVideo=video();
        if(saving&&update.frames()<0){saving=false;if(update.message().startsWith("Saved ")){if(!loop)control("loop");if(!playing)control("pause");onClose();return;}status="Unable to save";errorDetail=update.message();}
        if(update.frames()>=0){savedFrames=update.frames();if(!fresh){loop=update.loop();playing=update.playing();}}
        if(wasVideo!=video())init();
        syncControls();
    }
    public void receiveEditor(MediaNetwork.Part part){
        if(!part.pos().equals(pos)||fresh||busy||part.columns()!=columns||part.rows()!=rows||part.total()<1||part.total()>EditableMedia.MAX_BYTES||part.offset()<0||part.offset()>part.total()-part.data().length)return;
        if(part.offset()==0){incoming=new byte[part.total()];received=0;}
        if(incoming==null||incoming.length!=part.total()||received!=part.offset())return;
        System.arraycopy(part.data(),0,incoming,received,part.data().length);received+=part.data().length;
        if(received==incoming.length){
            byte[] archive=incoming;incoming=null;busy=true;status="Loading media";syncControls();
            job=WORKER.submit(()->{
                try{var clip=MediaClip.fromArchive(archive);byte[] pixels=clip.editable()==null?null:clip.editable().pixels();
                    minecraft.execute(()->{if(minecraft.screen!=this)return;
                        preview=clip;original=clip.editable();originalPixels=pixels;var settings=clip.settings();
                        cutoff=settings.threshold();invert=settings.invert();dither=settings.dither();scale=settings.scale();seconds=settings.seconds();
                        busy=false;dirty=false;status=original==null?"Reimport to edit":"On display";init();syncControls();
                    });
                }catch(Exception e){minecraft.execute(()->{if(minecraft.screen!=this)return;busy=false;showError(e.getMessage());syncControls();});}
            });
        }
    }
    public int previewClock(){return previewTicks;}
    public boolean editableReady(){return original!=null&&!busy;}
    public void setSource(String value){source.setValue(value);source.setCursorPosition(0);source.setHighlightPos(0);}
    public boolean previewReady(){return fresh&&preview!=null&&!busy&&!dirty;}
    public String importStatus(){return status;}
    @Override public void onFilesDrop(List<java.nio.file.Path> paths){if(!paths.isEmpty()&&!busy&&!saving){setSource(paths.getFirst().toString());importPreview();}}
    @Override public boolean keyPressed(int key,int scan,int modifiers){if((key==257||key==335)&&source.isFocused()){importPreview();return true;}return super.keyPressed(key,scan,modifiers);}
    @Override public void tick(){
        super.tick();
        if(editorRetryTicks>0&&--editorRetryTicks==0&&original==null&&!fresh&&!busy)control("edit");
        if(saving&&++saveTicks>600){saving=false;status="Unable to save";errorDetail="The server did not finish the upload. Try applying again.";syncControls();}
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
        g.drawString(font,"Threshold",guiLeft+RIGHT,guiTop+61,0xF3EBDE,false);
        g.drawCenteredString(font,Math.round(cutoff/255f*100)+"%",guiLeft+RIGHT+RIGHT_WIDTH/2,guiTop+78,0xF3EBDE);
        g.drawString(font,"Framing",guiLeft+RIGHT,guiTop+125,0xF3EBDE,false);
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
            g.drawCenteredString(font,seconds+" s",guiLeft+RIGHT+38,guiTop+171,0xF3EBDE);
        }
        // Schedule has a baked-in slot here; erase it for a truly unframed help glyph.
        g.fill(guiLeft+20,guiTop+footerY()-1,guiLeft+41,guiTop+footerY()+19,0xFFC6C6C6);
        MediaIcons.INFO.render(g,guiLeft+45,guiTop+footerY()+1);
        String statusLabel=font.plainSubstrByWidth(status,132);
        g.drawString(font,statusLabel,guiLeft+202-font.width(statusLabel),guiTop+footerY()+5,errorDetail.isEmpty()?0x575F50:0xA3423E,false);
    }
    private void drawPreview(GuiGraphics g){
        int px=guiLeft+29,py=guiTop+58,pw=128,ph=95;
        int front=0xF9FFFE,back=0x1D1D21;
        if(minecraft.level!=null&&minecraft.level.getBlockEntity(pos) instanceof com.zmm2025.createdisplayworks.flipdot.FlipDotBlockEntity tile){front=tile.discColor();back=tile.backColor();}
        g.fill(px,py,px+pw,py+ph,0xFF242424);
        if(busy){AllIcons.I_REFRESH.render(g,px+56,py+30);g.drawCenteredString(font,text("Loading media"),px+64,py+54,0xD3C6BA);return;}
        if(preview==null){AllIcons.I_SCHEMATIC.render(g,px+56,py+27);g.drawCenteredString(font,text(errorDetail.isEmpty()?"Add an image":"Unable to load"),px+64,py+51,0xD3C6BA);g.drawCenteredString(font,text(errorDetail.isEmpty()?"or a video":"Check the source"),px+64,py+63,0x999999);return;}
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
        if(!errorDetail.isEmpty()&&mouseX>=guiLeft+68&&mouseX<guiLeft+218&&mouseY>=guiTop+footerY()-2&&mouseY<guiTop+footerY()+22)g.renderTooltip(font,font.split(text(errorDetail),180),mouseX,mouseY);
        if(mouseX>=guiLeft+27&&mouseX<guiLeft+159&&mouseY>=guiTop+56&&mouseY<guiTop+155)
            g.renderTooltip(font,helpText(),java.util.Optional.empty(),mouseX,mouseY);
    }
}
