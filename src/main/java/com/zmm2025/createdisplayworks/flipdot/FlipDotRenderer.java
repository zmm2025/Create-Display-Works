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

public class FlipDotRenderer extends KineticBlockEntityRenderer<FlipDotBlockEntity> {
    private static final ResourceLocation WHITE = ResourceLocation.fromNamespaceAndPath("create_display_works", "textures/block/disc.png");
    private static final float[][][] PLATES = {
        geometry(-.5625f,.5625f,-.8125f,-.5625f), geometry(-.8125f,.8125f,-.5625f,.5625f), geometry(-.5625f,.5625f,.5625f,.8125f)
    };
    private static final float[][] NORMALS = {{0,0,-1},{0,0,1},{-1,0,0},{1,0,0},{0,1,0},{0,-1,0}};
    private final Quaternionf rotation = new Quaternionf();
    public FlipDotRenderer(BlockEntityRendererProvider.Context context) { super(context); }
    @Override protected void renderSafe(FlipDotBlockEntity be, float partial, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        // This block uses its BER even when Flywheel is active; no duplicate visual is registered.
        renderRotatingBuffer(be, CachedBuffers.partialFacingVertical(AllPartialModels.SHAFTLESS_COGWHEEL,
            be.getBlockState(), be.getBlockState().getValue(FlipDotBlock.HORIZONTAL_FACING)), pose, buffers.getBuffer(RenderType.solid()), light);
        pose.pushPose();
        pose.translate(.5, .5, .5);
        pose.mulPose(Axis.YP.rotationDegrees(180 - be.getBlockState().getValue(FlipDotBlock.HORIZONTAL_FACING).toYRot()));
        pose.translate(-.5, -.5, -.5);
        VertexConsumer vertices = buffers.getBuffer(RenderType.entityCutout(WHITE));
        for (int row = 0; row < 8; row++) for (int col = 0; col < 8; col++) {
            pose.pushPose();
            pose.translate((15 - col * 2) / 16f, (15 - row * 2) / 16f, 4.05f / 16);
            pose.mulPose(rotation.rotationAxis((float)Math.toRadians(be.angle(row * 8 + col, partial)), .70710677f, .70710677f, 0));
            for (float[][] plate : PLATES) for (int face = 0; face < 6; face++) {
                float[] normal = NORMALS[face];
                quad(pose,vertices,plate[face],normal[0],normal[1],normal[2],face==0?be.discColor():face==1?be.backColor():0x2F3130,light,overlay);
            }
            pose.popPose();
        }
        pose.popPose();
    }
    private static float[][] geometry(float l, float r, float b, float t) {
        l/=16; r/=16; b/=16; t/=16; float z=.0625f/16;
        return new float[][]{{l,b,-z,l,t,-z,r,t,-z,r,b,-z},{r,b,z,r,t,z,l,t,z,l,b,z},
            {l,b,z,l,t,z,l,t,-z,l,b,-z},{r,b,-z,r,t,-z,r,t,z,r,b,z},
            {l,t,-z,l,t,z,r,t,z,r,t,-z},{l,b,z,l,b,-z,r,b,-z,r,b,z}};
    }
    private static void quad(PoseStack pose, VertexConsumer out, float[] points, float nx, float ny, float nz, int color, int light, int overlay) {
        for(int i=0;i<4;i++) out.addVertex(pose.last(),points[i*3],points[i*3+1],points[i*3+2])
            .setColor((color>>16)&255,(color>>8)&255,color&255,255).setUv(i<2?0:1,i==0||i==3?1:0)
            .setOverlay(overlay).setLight(light).setNormal(pose.last(),nx,ny,nz);
    }
}
