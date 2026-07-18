package dev.solomon.solomon;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.solomon.solomon.client.SunBeamEffect;
import dev.solomon.solomon.client.SunDragonModel;
import dev.solomon.solomon.client.SunDragonRenderer;
import dev.solomon.solomon.entity.SunDragon;
import dev.solomon.solomon.network.SunDragonAttackPayload;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;

// This class will not load on dedicated servers. Accessing client side code from here is safe.
@Mod(value = Solomon.MODID, dist = Dist.CLIENT)
public class SolomonClient {
    // The flat sprite model used in the GUI/on the ground, and the full-size model used in hand.
    // The in-hand model is not tied to a registered item id, so ModelBakeryMixin loads it explicitly
    // and ItemRendererMixin swaps between the two (mirrors BarchedExtraSpears' BarchedESClient).
    public static final ModelResourceLocation SUN_SPEAR_MODEL =
            ModelResourceLocation.inventory(ResourceLocation.fromNamespaceAndPath(Solomon.MODID, "sun_spear"));
    public static final ModelResourceLocation SUN_SPEAR_IN_HAND_MODEL =
            ModelResourceLocation.inventory(ResourceLocation.fromNamespaceAndPath(Solomon.MODID, "sun_spear_in_hand"));

    private static final double SUN_BEAM_RANGE = 64.0;

    public static final KeyMapping SUN_BEAM_KEY = new KeyMapping(
            "key.solomon.sun_beam", InputConstants.KEY_R, "key.categories.solomon");
    public static final KeyMapping SUN_DRAGON_KEY = new KeyMapping(
            "key.solomon.sun_dragon_attack", InputConstants.KEY_G, "key.categories.solomon");

    /** Set in the constructor; lets the LevelRendererMixin depth-capture hook reach the instance. */
    private static SolomonClient instance;

    private final List<SunBeamEffect> activeBeams = new ArrayList<>();
    private boolean beamKeyWasDown;

    /**
     * Per-frame copy of the depth buffer taken at AFTER_BLOCK_ENTITIES — after terrain, mobs, and
     * block entities have written depth, but before water and clouds have. Before the sunlight
     * effects draw (at AFTER_LEVEL), this snapshot is blitted back over the live depth buffer, so
     * hills and walls still occlude the light while water surfaces and the cloud layer never do.
     */
    private RenderTarget preWaterDepth;

    public SolomonClient(IEventBus modEventBus) {
        instance = this;
        modEventBus.addListener(this::registerKeyMappings);
        modEventBus.addListener(this::registerEntityRenderers);
        modEventBus.addListener(this::registerLayerDefinitions);
        NeoForge.EVENT_BUS.addListener(this::onClientTick);
        NeoForge.EVENT_BUS.addListener(this::onRenderLevelStage);
    }

    private void registerKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(SUN_BEAM_KEY);
        event.register(SUN_DRAGON_KEY);
    }

    private void registerEntityRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(Solomon.SUN_DRAGON.get(), SunDragonRenderer::new);
    }

    private void registerLayerDefinitions(EntityRenderersEvent.RegisterLayerDefinitions event) {
        event.registerLayerDefinition(SunDragonModel.LAYER, SunDragonModel::createBodyLayer);
    }

    private void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            this.activeBeams.forEach(SunBeamEffect::discard);
            this.activeBeams.clear();
            this.beamKeyWasDown = false;
            if (this.preWaterDepth != null) {
                this.preWaterDepth.destroyBuffers();
                this.preWaterDepth = null;
            }
            return;
        }

        this.activeBeams.forEach(SunBeamEffect::tick);
        this.activeBeams.removeIf(SunBeamEffect::isFinished);

        boolean down = SUN_BEAM_KEY.isDown();
        if (down && !this.beamKeyWasDown && minecraft.player.isHolding(Solomon.SUN_SPEAR.get())) {
            // Beams can overlap: each press starts a fresh one even while others are still going
            Vec3 target = minecraft.player.pick(SUN_BEAM_RANGE, 1.0F, false).getLocation();
            this.activeBeams.add(new SunBeamEffect(target));
        } else if (!down) {
            // Letting go only cancels a beam still targeting; erupted beams play out on their own
            this.activeBeams.forEach(beam -> {
                if (beam.isTargeting()) {
                    beam.release();
                }
            });
        }
        this.beamKeyWasDown = down;

        // Dragon attack is fire-and-forget: raycast a target the same way the beam does and let
        // the server spawn the helix-flying dragon (it syncs back like any entity).
        while (SUN_DRAGON_KEY.consumeClick()) {
            if (minecraft.player.isHolding(Solomon.SUN_SPEAR.get())) {
                Vec3 target = minecraft.player.pick(SUN_BEAM_RANGE, 1.0F, false).getLocation();
                PacketDistributor.sendToServer(new SunDragonAttackPayload(target));
            }
        }
    }

    /**
     * Called by {@code LevelRendererMixin} right before the translucent (water) chunk layer
     * renders — the only point where every opaque depth writer has flushed (including Iris's
     * batched entities, which flush after the AFTER_BLOCK_ENTITIES event) but water hasn't drawn.
     */
    public static void captureSunlightDepth() {
        SolomonClient client = instance;
        Minecraft minecraft = Minecraft.getInstance();
        if (client == null || minecraft.level == null) {
            return;
        }
        if (!client.activeBeams.isEmpty() || client.findDragons(minecraft) != null) {
            client.capturePreWaterDepth(minecraft);
        }
    }

    // The sunlight effects draw at AFTER_LEVEL — dispatched from GameRenderer after renderLevel
    // has fully returned, which is after the Fabulous transparency composite and after any
    // renderLevel-tail hooks from pipeline mods (Veil, Iris), so nothing can composite water or
    // clouds over the light afterwards. Occlusion comes from blitting the pre-water depth snapshot
    // (captured by LevelRendererMixin just before the water renders) over the live depth buffer
    // just before drawing: terrain, mobs, and players still hide the light, water and clouds never
    // do. No depth restore is needed — vanilla clears the depth buffer for hand rendering
    // immediately after this event.
    private void onRenderLevelStage(RenderLevelStageEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            return;
        }

        List<SunDragon> dragons = this.findDragons(minecraft);
        if (this.activeBeams.isEmpty() && dragons == null) {
            return;
        }

        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(true);
        Vec3 cameraPos = event.getCamera().getPosition();
        MultiBufferSource.BufferSource bufferSource = minecraft.renderBuffers().bufferSource();
        var dispatcher = minecraft.getEntityRenderDispatcher();

        // AFTER_LEVEL hands us a fresh identity pose stack and no view transform on the global
        // model-view stack (renderLevel popped it), so fold the level's view rotation back in;
        // everything downstream then renders in world space exactly as it did in-level.
        PoseStack poseStack = event.getPoseStack();
        poseStack.pushPose();
        poseStack.mulPose(event.getModelViewMatrix());

        this.overrideDepthWithPreWaterSnapshot(minecraft);

        // Pass 1: every dragon's alpha-blended gold body. All of it must be on screen before any
        // additive light, or the translucent gold covers light that belongs on top of it (the old
        // "beam's white core vanishes behind the dragon" bug).
        if (dragons != null) {
            for (SunDragon dragon : dragons) {
                if (dispatcher.getRenderer(dragon) instanceof SunDragonRenderer renderer) {
                    renderer.renderBody(dragon, poseStack, cameraPos, partialTick);
                }
            }
            bufferSource.endBatch(SunDragonRenderer.bodyRenderType());
        }

        // Pass 2: all additive light. Additive blending commutes, so beam/dragon order within
        // this pass doesn't matter.
        for (SunBeamEffect beam : this.activeBeams) {
            beam.render(poseStack, cameraPos, partialTick);
        }
        if (dragons != null) {
            for (SunDragon dragon : dragons) {
                if (dispatcher.getRenderer(dragon) instanceof SunDragonRenderer renderer) {
                    renderer.renderGlow(dragon, poseStack, cameraPos, partialTick);
                }
            }
            bufferSource.endBatch(RenderType.dragonRays());
        }

        poseStack.popPose();
    }

    /** Visible sun dragons this frame, or null if there are none (the common case, kept cheap). */
    private List<SunDragon> findDragons(Minecraft minecraft) {
        List<SunDragon> dragons = null;
        for (Entity entity : minecraft.level.entitiesForRendering()) {
            if (entity instanceof SunDragon dragon) {
                if (dragons == null) {
                    dragons = new ArrayList<>();
                }
                dragons.add(dragon);
            }
        }
        return dragons;
    }

    /**
     * Blits the currently bound framebuffer's depth into {@link #preWaterDepth}, resizing as
     * needed. Reads from whatever framebuffer is bound — not an assumed vanilla main target — so
     * it keeps working when a pipeline mod (Veil, Iris) has wrapped or replaced the main target.
     * If the formats don't match the blit silently no-ops and the effects degrade gracefully.
     */
    private void capturePreWaterDepth(Minecraft minecraft) {
        RenderTarget main = minecraft.getMainRenderTarget();
        int boundFbo = GlStateManager._getInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        // On Fabulous, the copyDepthFrom right before the capture hook leaves framebuffer 0 bound;
        // the depth actually lives in the main target, so read from there instead.
        int readFbo = boundFbo != 0 ? boundFbo : main.frameBufferId;
        if (this.preWaterDepth == null) {
            this.preWaterDepth = new TextureTarget(main.width, main.height, true, Minecraft.ON_OSX);
        } else if (this.preWaterDepth.width != main.width || this.preWaterDepth.height != main.height) {
            this.preWaterDepth.resize(main.width, main.height, Minecraft.ON_OSX);
        }
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFbo);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, this.preWaterDepth.frameBufferId);
        GlStateManager._glBlitFrameBuffer(0, 0, main.width, main.height,
                0, 0, this.preWaterDepth.width, this.preWaterDepth.height,
                GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST);
        // Creating/resizing the target and blitting both disturb the binding; put it back.
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, boundFbo);
    }

    /**
     * Blits the pre-water snapshot over the depth of whatever framebuffer is currently bound, so
     * the effect draw that follows depth-tests against terrain/mob depth with no water or clouds
     * in it. Deliberately not restored afterwards: this event is the last thing before vanilla
     * clears the depth buffer for hand rendering. With no snapshot (or a failed blit) the draw
     * simply tests against the live depth buffer instead.
     */
    private void overrideDepthWithPreWaterSnapshot(Minecraft minecraft) {
        if (this.preWaterDepth == null) {
            return;
        }
        RenderTarget main = minecraft.getMainRenderTarget();
        int boundFbo = GlStateManager._getInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, this.preWaterDepth.frameBufferId);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, boundFbo);
        GlStateManager._glBlitFrameBuffer(0, 0, this.preWaterDepth.width, this.preWaterDepth.height,
                0, 0, main.width, main.height,
                GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST);
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, boundFbo);
    }
}
