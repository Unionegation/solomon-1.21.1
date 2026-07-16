package dev.solomon.solomon;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.platform.InputConstants;
import dev.solomon.solomon.client.SunBeamEffect;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
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
        NeoForge.EVENT_BUS.addListener(this::onClientTick);
        NeoForge.EVENT_BUS.addListener(this::onRenderLevelStage);
    }

    private void registerKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(SUN_BEAM_KEY);
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

    private void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS || this.activeBeams.isEmpty()) {
            return;
        }
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(true);
        for (SunBeamEffect beam : this.activeBeams) {
            beam.render(event.getPoseStack(), event.getCamera().getPosition(), partialTick);
        }
    }
}
