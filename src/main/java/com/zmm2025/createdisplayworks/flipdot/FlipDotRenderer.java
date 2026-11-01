package com.zmm2025.createdisplayworks.flipdot;

import com.mojang.blaze3d.vertex.*;
import com.mojang.math.Axis;
import com.simibubi.create.AllPartialModels;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;
import net.createmod.catnip.render.CachedBuffers;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import org.joml.Quaternionf;

/**
 * Draws flip-dot displays where Flywheel can't, such as with Flywheel's backend off, under a shader pack without
 * Flywheel support, and in Ponder scenes. Otherwise {@link FlipDotVisual} draws them and this renderer doesn't run.
 */
public class FlipDotRenderer extends KineticBlockEntityRenderer<FlipDotBlockEntity> {
    static final ResourceLocation WHITE = ResourceLocation.fromNamespaceAndPath("create_display_works", "textures/block/disc.png");
    static final float[][][] PLATES = {
        geometry(-.5625f,.5625f,-.8125f,-.5625f), geometry(-.8125f,.8125f,-.5625f,.5625f), geometry(-.5625f,.5625f,.5625f,.8125f)
    };
    static final float[][] NORMALS = {{0,0,-1},{0,0,1},{-1,0,0},{1,0,0},{0,1,0},{0,-1,0}};
    static final int EDGE = 0x2F3130;
    private final Quaternionf rotation = new Quaternionf();
    public FlipDotRenderer(BlockEntityRendererProvider.Context context) { super(context); }
    @Override protected void renderSafe(FlipDotBlockEntity be, float partial, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        be.startClientMotion(partial);
        renderRotatingBuffer(be, CachedBuffers.partialFacingVertical(AllPartialModels.SHAFTLESS_COGWHEEL,
            be.getBlockState(), be.getBlockState().getValue(FlipDotBlock.HORIZONTAL_FACING)), pose, buffers.getBuffer(RenderType.solid()), light);
        pose.pushPose();
        pose.translate(.5, .5, .5);
        pose.mulPose(Axis.YP.rotationDegrees(180 - be.getBlockState().getValue(FlipDotBlock.HORIZONTAL_FACING).toYRot()));
        pose.translate(-.5, -.5, -.5);
        VertexConsumer vertices = buffers.getBuffer(RenderType.entityCutout(WHITE));
        for (int row = 0; row < 8; row++) for (int col = 0; col < 8; col++)
            disc(be, pose, vertices, row, col, be.angle(row * 8 + col, partial), 255, 6, light, overlay);
        if (DiscMotionBlur.active(be, partial)) DiscMotionBlur.render(this, be, partial, pose, buffers.getBuffer(RenderType.entityTranslucent(WHITE)), light, overlay);
        pose.popPose();
    }
    /** Draws the first {@code faces} faces of each plate: front and back come first, then the four thin edges. */
    void disc(FlipDotBlockEntity be, PoseStack pose, VertexConsumer vertices, int row, int col, float angle, int alpha, int faces, int light, int overlay) {
        pose.pushPose();
        pose.translate((15 - col * 2) / 16f, (15 - row * 2) / 16f, 4.5f / 16);
        pose.mulPose(rotation.rotationAxis((float)Math.toRadians(angle), .70710677f, .70710677f, 0));
        for (float[][] plate : PLATES) for (int face = 0; face < faces; face++) {
            float[] normal = NORMALS[face];
            quad(pose,vertices,plate[face],normal[0],normal[1],normal[2],face==0?be.discColor():face==1?be.backColor():EDGE,alpha,light,overlay);
        }
        pose.popPose();
    }
    private static float[][] geometry(float l, float r, float b, float t) {
        l/=16; r/=16; b/=16; t/=16; float z=.0625f/16;
        return new float[][]{{l,b,-z,l,t,-z,r,t,-z,r,b,-z},{r,b,z,r,t,z,l,t,z,l,b,z},
            {l,b,z,l,t,z,l,t,-z,l,b,-z},{r,b,-z,r,t,-z,r,t,z,r,b,z},
            {l,t,-z,l,t,z,r,t,z,r,t,-z},{l,b,z,l,b,-z,r,b,-z,r,b,z}};
    }
    private static void quad(PoseStack pose, VertexConsumer out, float[] points, float nx, float ny, float nz, int color, int alpha, int light, int overlay) {
        for(int i=0;i<4;i++) out.addVertex(pose.last(),points[i*3],points[i*3+1],points[i*3+2])
            .setColor((color>>16)&255,(color>>8)&255,color&255,alpha).setUv(i<2?0:1,i==0||i==3?1:0)
            .setOverlay(overlay).setLight(light).setNormal(pose.last(),nx,ny,nz);
    }
}
