package dev.solomon.solomon;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.platform.InputConstants;
import dev.solomon.solomon.client.SunBeamEffect;
import dev.solomon.solomon.client.SunDragonModel;
import dev.solomon.solomon.client.SunDragonRenderer;
import dev.solomon.solomon.entity.SunDragon;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;

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

    private final List<SunBeamEffect> activeBeams = new ArrayList<>();
    private boolean beamKeyWasDown;

    public SolomonClient(IEventBus modEventBus) {
        modEventBus.addListener(this::registerKeyMappings);
        modEventBus.addListener(this::registerEntityRenderers);
        modEventBus.addListener(this::registerLayerDefinitions);
        NeoForge.EVENT_BUS.addListener(this::onClientTick);
        NeoForge.EVENT_BUS.addListener(this::onRenderLevelStage);
    }

    private void registerKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(SUN_BEAM_KEY);
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
    }

    // AFTER_WEATHER runs once water AND clouds have drawn (and written depth), so the depth-less
    // additive sunrip/dragon light is properly occluded by both instead of being painted over.
    private void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_WEATHER) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(true);
        Vec3 cameraPos = event.getCamera().getPosition();

        for (SunBeamEffect beam : this.activeBeams) {
            beam.render(event.getPoseStack(), cameraPos, partialTick);
        }

        // Sun dragons draw here rather than in the entity pass for the same depth-ordering reason;
        // SunDragonRenderer's render() is left to the nametag default.
        if (minecraft.level != null) {
            for (Entity entity : minecraft.level.entitiesForRendering()) {
                if (entity instanceof SunDragon dragon
                        && minecraft.getEntityRenderDispatcher().getRenderer(dragon) instanceof SunDragonRenderer renderer) {
                    renderer.renderGlow(dragon, event.getPoseStack(), cameraPos, partialTick);
                }
            }
        }
    }
}
