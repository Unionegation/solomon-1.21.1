package dev.solomon.solomon.client;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;
import dev.solomon.solomon.Solomon;
import dev.solomon.solomon.entity.SunDragon;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FastColor;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Renders the sun dragon as a serpent of sunlight in the same visual language as the sunrip beam
 * ({@code SunBeamEffect}): an alpha-blended gold base body (so the yellow reads even against the
 * bright sky, where pure additive light washes out to white) under nested additive
 * {@link RenderType#dragonRays()} shells — a small near-white core, a gold mid shell, and a faint
 * orange halo. Unlike the beam, the dragon does not shimmer: it holds a steady glow.
 *
 * Nothing is drawn from {@link #render}: none of these passes write depth, and the entity pass
 * runs before water/clouds, which would then paint over the light. Instead {@code SolomonClient}
 * drives {@link #renderBody} and {@link #renderGlow} during the AFTER_LEVEL stage (same pattern
 * as the beam), in a strict global order: every dragon's translucent gold body first, then
 * every additive pass (the sunrip's shells and the dragons' glow). Additive blending commutes, so
 * once all the alpha-blended gold is down, no additive light can end up buried "beneath" a body —
 * which is exactly what happened when the beam's white core drew before a dragon's body.
 *
 * Placement: the head at the entity's position plus {@link SunDragon#BODY_SEGMENTS} body cubes
 * strung back along the position trail by arc length, each oriented to the trail tangent so the
 * body follows the head's exact path like a snake.
 */
public class SunDragonRenderer extends EntityRenderer<SunDragon> {
    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(Solomon.MODID, "textures/entity/sun_dragon.png");

    // Size and body layout all come from SunDragon.SCALE and friends, so the model, hitbox,
    // and flight path stay proportioned no matter the scale. The solid body draws at MODEL_SCALE;
    // the shell stack grows it back up so the outermost halo lands exactly at SunDragon.SCALE.
    private static final float MODEL_SCALE = SunDragon.MODEL_SCALE;
    /** Trail samples are feet positions; lift every segment to the hitbox center. */
    private static final double Y_LIFT = 0.22 * MODEL_SCALE;

    /**
     * One nested glow shell, mirroring the beam's shell stack. Width scales the segment's
     * cross-section (local X/Y); length scales it along the body axis (local Z) — shells narrower
     * than the body keep full length so they still overlap segment-to-segment instead of forming
     * a dashed line.
     */
    private record GlowPass(float width, float length, float red, float green, float blue, float alpha) {}

    /** Opaque-ish gold underlay drawn with normal alpha blending + depth write. */
    private static final GlowPass BASE_PASS = new GlowPass(1.0F, 1.0F, 1.0F, 0.78F, 0.25F, 0.85F);

    private static final GlowPass[] GLOW_PASSES = {
            new GlowPass(0.7F, 1.0F, 1.0F, 1.0F, 0.95F, 0.80F),
            new GlowPass(1.2F, 1.2F, 1.0F, 0.85F, 0.45F, 0.45F),
            new GlowPass(SunDragon.OUTER_GLOW, SunDragon.OUTER_GLOW, 1.0F, 0.55F, 0.15F, 0.20F),
    };

    /** A resolved segment: which part to draw, where, facing which way, at what taper. */
    private record Placement(ModelPart part, Vec3 offset, Vec3 dir, float taper) {}

    private final ModelPart head;
    private final ModelPart body;

    public SunDragonRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.shadowRadius = 0.0F;
        SunDragonModel model = new SunDragonModel(context.bakeLayer(SunDragonModel.LAYER));
        this.head = model.head;
        this.body = model.body;
    }

    // The default render() (nametag only) is inherited; the body and glow are drawn by
    // renderBody/renderGlow below, called from SolomonClient's AFTER_WEATHER stage handler.

    /** Render type of the body pass, exposed so SolomonClient can flush it before any additive light. */
    public static RenderType bodyRenderType() {
        return DragonRenderTypes.SUN_DRAGON_BODY;
    }

    /** A frame's resolved geometry: camera-relative head position plus every segment placement. */
    private record Layout(Vec3 headPos, List<Placement> placements) {}

    /**
     * The alpha-blended gold body pass. Buffers into {@link #bodyRenderType()}; the caller flushes
     * that batch (for every dragon at once) before any additive pass is buffered.
     */
    public void renderBody(SunDragon dragon, PoseStack poseStack, Vec3 cameraPos, float partialTick) {
        Layout layout = this.computeLayout(dragon, partialTick);
        QuadsToTriangles buffer = new QuadsToTriangles(
                Minecraft.getInstance().renderBuffers().bufferSource().getBuffer(DragonRenderTypes.SUN_DRAGON_BODY));
        poseStack.pushPose();
        poseStack.translate(layout.headPos.x - cameraPos.x, layout.headPos.y - cameraPos.y,
                layout.headPos.z - cameraPos.z);
        for (Placement p : layout.placements) {
            renderSegment(poseStack, buffer, p, BASE_PASS);
        }
        poseStack.popPose();
    }

    /**
     * The nested additive glow shells, buffered into {@link RenderType#dragonRays()} alongside the
     * sunrip's shells; the caller flushes that batch after every dragon and beam has buffered.
     */
    public void renderGlow(SunDragon dragon, PoseStack poseStack, Vec3 cameraPos, float partialTick) {
        Layout layout = this.computeLayout(dragon, partialTick);
        QuadsToTriangles buffer = new QuadsToTriangles(
                Minecraft.getInstance().renderBuffers().bufferSource().getBuffer(RenderType.dragonRays()));
        poseStack.pushPose();
        poseStack.translate(layout.headPos.x - cameraPos.x, layout.headPos.y - cameraPos.y,
                layout.headPos.z - cameraPos.z);
        for (GlowPass pass : GLOW_PASSES) {
            for (Placement p : layout.placements) {
                renderSegment(poseStack, buffer, p, pass);
            }
        }
        poseStack.popPose();
    }

    /** Resolves the head plus body-segment placements along the trail for this frame. */
    private Layout computeLayout(SunDragon dragon, float partialTick) {
        Vec3[] points = new Vec3[SunDragon.TRAIL_LENGTH - 1];
        for (int k = 0; k < points.length; k++) {
            points[k] = dragon.getTrailPoint(k, partialTick);
        }
        Vec3 headPos = points[0];

        List<Placement> placements = new ArrayList<>(SunDragon.BODY_SEGMENTS + 1);
        placements.add(new Placement(this.head, Vec3.ZERO, points[0].subtract(points[2]), 1.0F));

        // Walk backward along the trail, dropping a segment each time the accumulated arc length
        // reaches the next target distance. Targets are increasing, so one monotonic walk suffices.
        double walked = 0.0;
        double target = SunDragon.HEAD_GAP;
        int k = 0;
        for (int i = 0; i < SunDragon.BODY_SEGMENTS; i++) {
            float taper = 1.0F - (1.0F - SunDragon.TAIL_SCALE) * i / (SunDragon.BODY_SEGMENTS - 1);
            while (k < points.length - 2 && walked + points[k].distanceTo(points[k + 1]) < target) {
                walked += points[k].distanceTo(points[k + 1]);
                k++;
            }
            double step = points[k].distanceTo(points[k + 1]);
            double frac = step < 1.0E-6 ? 0.0 : Mth.clamp((target - walked) / step, 0.0, 1.0);
            Vec3 segPos = points[k].lerp(points[k + 1], frac);
            Vec3 dir = points[Math.max(k - 1, 0)].subtract(points[k + 1]);
            placements.add(new Placement(this.body, segPos.subtract(headPos), dir, taper));
            target += SunDragon.SEGMENT_SPACING * taper;
        }

        return new Layout(headPos, placements);
    }

    private static void renderSegment(PoseStack poseStack, QuadsToTriangles buffer, Placement placement,
            GlowPass pass) {
        // The body dims toward the tail, echoing the beam's fade-to-nothing gradient.
        float alpha = Math.min(1.0F, pass.alpha * (0.45F + 0.55F * placement.taper));
        int color = FastColor.ARGB32.color((int) (alpha * 255.0F), (int) (pass.red * 255.0F),
                (int) (pass.green * 255.0F), (int) (pass.blue * 255.0F));
        float crossScale = MODEL_SCALE * placement.taper * pass.width;
        float lengthScale = MODEL_SCALE * placement.taper * pass.length;

        poseStack.pushPose();
        poseStack.translate(placement.offset.x, placement.offset.y + Y_LIFT, placement.offset.z);
        Vec3 dir = placement.dir;
        if (dir.lengthSqr() > 1.0E-6) {
            float yaw = (float) Mth.atan2(dir.x, dir.z);
            float pitch = (float) -Mth.atan2(dir.y, dir.horizontalDistance());
            poseStack.mulPose(Axis.YP.rotation(yaw));
            poseStack.mulPose(Axis.XP.rotation(pitch));
        }
        // Local Z points along the trail after the rotations above, so cross-section and
        // along-body length scale independently.
        poseStack.scale(crossScale, crossScale, lengthScale);
        placement.part.render(poseStack, buffer, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, color);
        buffer.finishQuad();
        poseStack.popPose();
    }

    @Override
    public ResourceLocation getTextureLocation(SunDragon dragon) {
        return TEXTURE;
    }

    /**
     * Home of the base-body render type: position+color triangles with regular alpha blending,
     * depth-tested but explicitly NOT depth-writing (COLOR_WRITE). Skipping the depth write is what
     * lets the additive glow — including the white core nested inside the body — shine through the
     * gold instead of being z-culled by it; it also matches the weather stage, where vanilla holds
     * the global depth mask off anyway. Sorted on upload so overlapping translucent segments blend
     * in a stable order.
     */
    private static final class DragonRenderTypes extends RenderType {
        private DragonRenderTypes(String name, VertexFormat format, VertexFormat.Mode mode, int bufferSize,
                boolean affectsCrumbling, boolean sortOnUpload, Runnable setupState, Runnable clearState) {
            super(name, format, mode, bufferSize, affectsCrumbling, sortOnUpload, setupState, clearState);
        }

        static final RenderType SUN_DRAGON_BODY = create("solomon_sun_dragon_body",
                DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.TRIANGLES, 1536, false, true,
                CompositeState.builder()
                        .setShaderState(POSITION_COLOR_SHADER)
                        .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                        .setWriteMaskState(COLOR_WRITE)
                        .createCompositeState(false));
    }

    /**
     * Adapts quad-emitting geometry (ModelPart cubes) onto triangle-mode position+color buffers
     * (dragonRays and the base body type). Buffers four vertices, then emits them as two triangles
     * preserving winding; UV/overlay/light/normal data is dropped since the target formats have none.
     */
    private static final class QuadsToTriangles implements VertexConsumer {
        private final VertexConsumer delegate;
        private final float[] x = new float[4];
        private final float[] y = new float[4];
        private final float[] z = new float[4];
        private final int[] color = new int[4];
        private int count;

        QuadsToTriangles(VertexConsumer delegate) {
            this.delegate = delegate;
        }

        @Override
        public VertexConsumer addVertex(float px, float py, float pz) {
            if (this.count == 4) {
                this.emitQuad();
            }
            this.x[this.count] = px;
            this.y[this.count] = py;
            this.z[this.count] = pz;
            this.color[this.count] = 0xFFFFFFFF;
            this.count++;
            return this;
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            this.color[this.count - 1] = FastColor.ARGB32.color(alpha, red, green, blue);
            return this;
        }

        /** Must be called after each ModelPart render: the final quad has no following vertex to flush it. */
        void finishQuad() {
            if (this.count == 4) {
                this.emitQuad();
            }
            this.count = 0;
        }

        private void emitQuad() {
            this.emitTriangle(0, 1, 2);
            this.emitTriangle(2, 3, 0);
            this.count = 0;
        }

        private void emitTriangle(int a, int b, int c) {
            for (int i : new int[] {a, b, c}) {
                this.delegate.addVertex(this.x[i], this.y[i], this.z[i]);
                int argb = this.color[i];
                this.delegate.setColor(FastColor.ARGB32.red(argb), FastColor.ARGB32.green(argb),
                        FastColor.ARGB32.blue(argb), FastColor.ARGB32.alpha(argb));
            }
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setNormal(float nx, float ny, float nz) {
            return this;
        }
    }
}
