package com.zmm2025.createdisplayworks.flipdot;

import java.util.function.Consumer;
import com.simibubi.create.AllPartialModels;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityVisual;
import com.simibubi.create.content.kinetics.base.RotatingInstance;
import com.simibubi.create.foundation.render.AllInstanceTypes;
import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.material.CardinalLightingMode;
import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.material.Transparency;
import dev.engine_room.flywheel.api.material.WriteMask;
import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.api.visual.DynamicVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.instance.TransformedInstance;
import dev.engine_room.flywheel.lib.material.CutoutShaders;
import dev.engine_room.flywheel.lib.material.SimpleMaterial;
import dev.engine_room.flywheel.lib.memory.MemoryBlock;
import dev.engine_room.flywheel.lib.model.Models;
import dev.engine_room.flywheel.lib.model.SimpleQuadMesh;
import dev.engine_room.flywheel.lib.model.SingleMeshModel;
import dev.engine_room.flywheel.lib.util.RendererReloadCache;
import dev.engine_room.flywheel.lib.vertex.FullVertexView;
import dev.engine_room.flywheel.lib.visual.SimpleDynamicVisual;
import net.createmod.catnip.math.AngleHelper;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;
import org.joml.Matrix4f;

/**
 * Instanced flip-dot rendering: one instance per disc, plus the cogwheel. Discs only get new transforms while they
 * move, so a settled board costs nothing per frame. Motion blur copies only exist while a nearby board moves.
 */
public class FlipDotVisual extends KineticBlockEntityVisual<FlipDotBlockEntity> implements SimpleDynamicVisual {
    private static final Material MATERIAL = SimpleMaterial.builder().texture(FlipDotRenderer.WHITE).mipmap(false)
        .cutout(CutoutShaders.ONE_TENTH).cardinalLightingMode(CardinalLightingMode.ENTITY).build();
    /**
     * Like the renderer's translucent layer, copies show both faces, so the far side's colour shows through. Flywheel
     * doesn't sort translucent faces, so copies skip depth writes to blend whatever order they draw in.
     */
    private static final Material GHOST_MATERIAL = SimpleMaterial.builder().texture(FlipDotRenderer.WHITE).mipmap(false)
        .transparency(Transparency.TRANSLUCENT).backfaceCulling(false).writeMask(WriteMask.COLOR)
        .cardinalLightingMode(CardinalLightingMode.ENTITY).build();
    /** One mesh per front and back colour pair, with the colours baked in so each disc needs a single instance. */
    private static final RendererReloadCache<Long, Model> DISCS = new RendererReloadCache<>(palette -> discModel(palette, 6, MATERIAL));
    /** Motion blur copies skip the edge faces, like the renderer's. */
    private static final RendererReloadCache<Long, Model> GHOSTS = new RendererReloadCache<>(palette -> discModel(palette, 2, GHOST_MATERIAL));
    private final RotatingInstance cog;
    private final TransformedInstance[] discs = new TransformedInstance[64];
    /** Ticks a board keeps its motion blur copies after its last blurred frame, so playback doesn't recreate them each flip. */
    private static final int GHOST_LINGER_TICKS = 40;
    /** Motion blur copies, grown as needed and hidden when unused. Kept while the board keeps flipping. */
    private final java.util.ArrayList<TransformedInstance> ghosts = new java.util.ArrayList<>();
    private int ghostCount, ghostsShown, light;
    private long lastBlur;
    private final float[] shown = new float[64];
    private final Matrix4f board = new Matrix4f();
    private long palette;
    private int shownVersion;
    private boolean shownSettled;

    public FlipDotVisual(VisualizationContext context, FlipDotBlockEntity be, float partial) {
        super(context, be, partial);
        Direction facing = blockState.getValue(FlipDotBlock.HORIZONTAL_FACING);
        cog = instancerProvider().instancer(AllInstanceTypes.ROTATING, Models.partial(AllPartialModels.SHAFTLESS_COGWHEEL)).createInstance();
        // Same orientation as CachedBuffers.partialFacingVertical in the renderer, so the teeth line up the same way.
        cog.setup(be).setPosition(getVisualPosition()).rotation.rotationY((float)Math.toRadians(AngleHelper.horizontalAngle(facing))).rotateX((float)Math.PI / 2);
        cog.setChanged();
        var origin = getVisualPosition();
        board.translation(origin.getX(), origin.getY(), origin.getZ()).translate(.5f, .5f, .5f)
            .rotateY((float)Math.toRadians(180 - facing.toYRot())).translate(-.5f, -.5f, -.5f);
        createDiscs();
        updateLight(partial);
    }
    private void createDiscs() {
        palette = (long)blockEntity.discColor() << 24 | blockEntity.backColor();
        var instancer = instancerProvider().instancer(InstanceTypes.TRANSFORMED, DISCS.get(palette));
        for (int i = 0; i < 64; i++) discs[i] = instancer.createInstance();
        shownSettled = false;
        java.util.Arrays.fill(shown, Float.NaN);
    }
    @Override public void update(float partial) {
        cog.setup(blockEntity).setChanged();
        if (palette == ((long)blockEntity.discColor() << 24 | blockEntity.backColor())) return;
        for (var disc : discs) disc.delete();
        deleteGhosts();
        createDiscs();
        updateLight(partial);
    }
    @Override public void beginFrame(DynamicVisual.Context context) {
        if (shownSettled && shownVersion == blockEntity.motionVersion() || !isVisible(context.frustum())) return;
        float partial = context.partialTick();
        blockEntity.startClientMotion(partial);
        shownVersion = blockEntity.motionVersion();
        boolean blur = DiscMotionBlur.active(blockEntity, partial);
        shownSettled = !blur && ghosts.isEmpty() && blockEntity.discsSettled(partial);
        for (int i = 0; i < 64; i++) {
            float angle = blockEntity.angle(i, partial);
            if (angle == shown[i]) continue;
            shown[i] = angle;
            place(discs[i], i, angle).setChanged();
        }
        long now = blockEntity.getLevel().getGameTime();
        ghostCount = 0;
        if (blur) {
            lastBlur = now;
            DiscMotionBlur.ghosts(blockEntity, partial, (index, angle, alpha) -> place(ghost(ghostCount++), index, angle).color(255, 255, 255, alpha).setChanged());
        }
        for (int i = ghostCount; i < ghostsShown; i++) ghosts.get(i).setZeroTransform().setChanged();
        ghostsShown = ghostCount;
        if (!blur && now - lastBlur > GHOST_LINGER_TICKS) deleteGhosts();
    }
    private TransformedInstance ghost(int index) {
        if (index == ghosts.size()) {
            var ghost = instancerProvider().instancer(InstanceTypes.TRANSFORMED, GHOSTS.get(palette)).createInstance();
            ghost.light(light);
            ghosts.add(ghost);
        }
        return ghosts.get(index);
    }
    private TransformedInstance place(TransformedInstance disc, int index, float angle) {
        int row = index / 8, col = index % 8;
        return disc.setTransform(board).translate((15 - col * 2) / 16f, (15 - row * 2) / 16f, 4.5f / 16)
            .rotate((float)Math.toRadians(angle), .70710677f, .70710677f, 0);
    }
    private void deleteGhosts() {
        for (var ghost : ghosts) ghost.delete();
        ghosts.clear();
        ghostsShown = 0;
    }
    @Override public void updateLight(float partial) {
        relight(cog);
        // The renderer's entity shader reads lightmap texels exactly, but Flywheel filters between them, so aim at texel centres.
        light = computePackedLight() + (8 << 16 | 8);
        for (var disc : discs) disc.light(light).setChanged();
        for (var ghost : ghosts) ghost.light(light).setChanged();
    }
    @Override public void collectCrumblingInstances(Consumer<Instance> consumer) {
        consumer.accept(cog);
        for (var disc : discs) consumer.accept(disc);
    }
    @Override protected void _delete() {
        cog.delete();
        for (var disc : discs) disc.delete();
        deleteGhosts();
    }
    /** Builds the first {@code faces} faces of each plate: front and back come first, then the four thin edges. */
    private static Model discModel(long palette, int faces, Material material) {
        int front = (int)(palette >>> 24), back = (int)palette & 0xFFFFFF;
        float[][][] plates = FlipDotRenderer.PLATES;
        int vertices = plates.length * faces * 4;
        var memory = MemoryBlock.mallocTracked(vertices * FullVertexView.STRIDE);
        var view = new FullVertexView();
        view.ptr(memory.ptr()); view.vertexCount(vertices); view.nativeMemoryOwner(memory);
        int v = 0;
        for (float[][] plate : plates) for (int face = 0; face < faces; face++) {
            int color = face == 0 ? front : face == 1 ? back : FlipDotRenderer.EDGE;
            float[] normal = FlipDotRenderer.NORMALS[face], points = plate[face];
            for (int i = 0; i < 4; i++, v++) {
                view.x(v, points[i * 3]); view.y(v, points[i * 3 + 1]); view.z(v, points[i * 3 + 2]);
                view.r(v, (color >> 16 & 255) / 255f); view.g(v, (color >> 8 & 255) / 255f); view.b(v, (color & 255) / 255f); view.a(v, 1);
                view.u(v, i < 2 ? 0 : 1); view.v(v, i == 0 || i == 3 ? 1 : 0);
                view.overlay(v, OverlayTexture.NO_OVERLAY); view.light(v, 0);
                view.normalX(v, normal[0]); view.normalY(v, normal[1]); view.normalZ(v, normal[2]);
            }
        }
        return new SingleMeshModel(new SimpleQuadMesh(view, "flip_dot_disc"), material);
    }
}
