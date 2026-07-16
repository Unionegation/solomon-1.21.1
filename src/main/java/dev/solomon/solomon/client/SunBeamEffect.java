package dev.solomon.solomon.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.solomon.solomon.Solomon;
import dev.solomon.solomon.network.SunBeamDamagePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Matrix4f;

/**
 * A pillar of sunlight whose size follows the amplitude envelope of the sunrip sound:
 * silence, a very fast rise, a long sustain, then a fade back down to nothing.
 */
public class SunBeamEffect {

    // Envelope timing measured from sunrip.wav (7.931s total)
    private static final float ATTACK_END_SECONDS = 0.719F;
    // The beam stays invisible until just before the attack peak, then snaps up over ~0.12s
    private static final float GROW_START_SECONDS = 0.6F;
    private static final float SUSTAIN_END_SECONDS = 5.488F;
    private static final float TOTAL_SECONDS = 7.931F;
    // Quick shrink used when the key is released before the sound finishes
    private static final float RELEASE_FADE_SECONDS = 0.35F;

    private static final float HEIGHT = 48.0F;
    // The beam reaches a few blocks below the targeted point so it doesn't float on ledges/walls
    private static final float BOTTOM = -3.0F;
    private static final int SEGMENTS = 40;
    private static final float TWO_PI = (float) (Math.PI * 2.0);

    private final Vec3 position;
    private final SoundInstance sound;

    private int ticks;
    private float releaseTime = -1.0F;
    private float releaseEnvelope;
    private float releaseTargeting;
    private boolean finished;
    private boolean damageSent;

    public SunBeamEffect(Vec3 position) {
        this.position = position;
        // Attenuation.NONE keeps the sound at full volume no matter how far away the beam spawns
        this.sound = new SimpleSoundInstance(Solomon.SUNRIP_SOUND.get().getLocation(), SoundSource.PLAYERS, 1.0F, 1.0F,
                SoundInstance.createUnseededRandom(), false, 0, SoundInstance.Attenuation.NONE,
                position.x, position.y, position.z, false);
        Minecraft.getInstance().getSoundManager().play(this.sound);
    }

    public void tick() {
        this.ticks++;
        float now = this.time(0.0F);
        // The eruption is the commit point: tell the server to apply the beam's damage
        if (!this.damageSent && !this.finished && this.releaseTime < 0.0F && now >= GROW_START_SECONDS) {
            this.damageSent = true;
            PacketDistributor.sendToServer(new SunBeamDamagePayload(this.position));
        }
        if (now >= this.endTime()) {
            this.discard();
        }
    }

    /** Called when the keybind is let go early: stop the sound and shrink out quickly. */
    public void release() {
        if (this.finished || this.releaseTime >= 0.0F) {
            return;
        }
        float now = this.time(0.0F);
        if (now >= SUSTAIN_END_SECONDS) {
            return; // already in the natural fade; let it finish
        }
        this.releaseTime = now;
        this.releaseEnvelope = this.envelope(now);
        this.releaseTargeting = this.targetingAlpha(now);
        Minecraft.getInstance().getSoundManager().stop(this.sound);
    }

    /** Immediately ends the effect and stops the sound. */
    public void discard() {
        this.finished = true;
        Minecraft.getInstance().getSoundManager().stop(this.sound);
    }

    public boolean isFinished() {
        return this.finished;
    }

    /** True while still in the thin targeting phase — the only window where releasing the key cancels. */
    public boolean isTargeting() {
        return !this.finished && this.releaseTime < 0.0F && this.time(0.0F) < GROW_START_SECONDS;
    }

    private float time(float partialTick) {
        return (this.ticks + partialTick) / 20.0F;
    }

    private float endTime() {
        return this.releaseTime >= 0.0F ? this.releaseTime + RELEASE_FADE_SECONDS : TOTAL_SECONDS;
    }

    /**
     * Size/intensity multiplier over time, tracking the sound: 0 at the start, ~1 (with a wobble)
     * during the sustain, and 0 again by the end.
     */
    private float envelope(float t) {
        if (this.releaseTime >= 0.0F && t >= this.releaseTime) {
            float fade = (t - this.releaseTime) / RELEASE_FADE_SECONDS;
            return fade >= 1.0F ? 0.0F : this.releaseEnvelope * (1.0F - easeInOutCubic(fade));
        }
        if (t <= GROW_START_SECONDS) {
            return 0.0F;
        }
        if (t < ATTACK_END_SECONDS) {
            return easeOutQuart((t - GROW_START_SECONDS) / (ATTACK_END_SECONDS - GROW_START_SECONDS));
        }
        if (t < SUSTAIN_END_SECONDS) {
            return 1.0F + this.oscillation(t - ATTACK_END_SECONDS);
        }
        if (t < TOTAL_SECONDS) {
            float fade = (t - SUSTAIN_END_SECONDS) / (TOTAL_SECONDS - SUSTAIN_END_SECONDS);
            float wobble = 1.0F + this.oscillation(t - ATTACK_END_SECONDS) * (1.0F - fade);
            return wobble * (1.0F - easeInOutCubic(fade));
        }
        return 0.0F;
    }

    /** Two offset sine waves so the sustain shimmer doesn't read as a metronome. */
    private float oscillation(float t) {
        return 0.10F * Mth.sin(t * TWO_PI * 2.3F) + 0.05F * Mth.sin(t * TWO_PI * 3.7F + 1.3F);
    }

    /**
     * Alpha of the thin targeting beam shown between the start of the sound and the eruption:
     * fades in almost immediately, then hands off to the main beam as it grows.
     */
    private float targetingAlpha(float t) {
        if (this.releaseTime >= 0.0F && t >= this.releaseTime) {
            float fade = (t - this.releaseTime) / RELEASE_FADE_SECONDS;
            return fade >= 1.0F ? 0.0F : this.releaseTargeting * (1.0F - fade);
        }
        if (t <= 0.0F) {
            return 0.0F;
        }
        if (t < GROW_START_SECONDS) {
            return Math.min(1.0F, t / 0.15F);
        }
        if (t < ATTACK_END_SECONDS) {
            return 1.0F - (t - GROW_START_SECONDS) / (ATTACK_END_SECONDS - GROW_START_SECONDS);
        }
        return 0.0F;
    }

    private static float easeOutQuart(float x) {
        float inv = 1.0F - x;
        return 1.0F - inv * inv * inv * inv;
    }

    private static float easeInOutCubic(float x) {
        if (x < 0.5F) {
            return 4.0F * x * x * x;
        }
        float inv = -2.0F * x + 2.0F;
        return 1.0F - inv * inv * inv / 2.0F;
    }

    public void render(PoseStack poseStack, Vec3 cameraPos, float partialTick) {
        if (this.finished) {
            return;
        }
        float t = this.time(partialTick);
        float env = this.envelope(t);
        float targeting = this.targetingAlpha(t);
        if (env <= 0.0F && targeting <= 0.0F) {
            return;
        }

        // The static vanilla dragon-rays render type (additive lightning blend, no depth write) is
        // intercepted natively by Iris, so the beam renders correctly under shaderpacks with no
        // compat mod or runtime shader patching involved.
        RenderType renderType = RenderType.dragonRays();
        MultiBufferSource.BufferSource bufferSource = Minecraft.getInstance().renderBuffers().bufferSource();
        VertexConsumer consumer = bufferSource.getBuffer(renderType);

        poseStack.pushPose();
        poseStack.translate(this.position.x - cameraPos.x, this.position.y - cameraPos.y, this.position.z - cameraPos.z);
        Matrix4f matrix = poseStack.last().pose();

        // Thin pencil beam marking where the eruption is about to happen
        if (targeting > 0.0F) {
            this.drawShell(consumer, matrix, 0.1F, 1.0F, 0.95F, 0.8F, 0.85F * targeting);
            this.drawShell(consumer, matrix, 0.3F, 1.0F, 0.8F, 0.4F, 0.2F * targeting);
        }

        // Additive nested shells: the overlap saturates the core to near-white
        if (env > 0.0F) {
            float alpha = Math.min(1.0F, env * 1.2F);
            this.drawShell(consumer, matrix, 0.7F * env, 1.0F, 1.0F, 0.95F, 0.9F * alpha);
            this.drawShell(consumer, matrix, 1.5F * env, 1.0F, 0.85F, 0.45F, 0.5F * alpha);
            this.drawShell(consumer, matrix, 2.6F * env, 1.0F, 0.55F, 0.15F, 0.22F * alpha);
            // Radial gradient disc standing in for light spilling onto the ground
            this.drawGroundGlow(consumer, matrix, 5.0F * env, 1.0F, 0.8F, 0.4F, 0.45F * alpha);
        }

        poseStack.popPose();
        bufferSource.endBatch(renderType);
    }

    /**
     * One vertical cylinder shell, fully opaque at the base and fading to nothing at the top.
     * Dragon rays render as culled triangles, so each wall is emitted with both windings
     * to stay visible from inside and outside the beam.
     */
    private void drawShell(VertexConsumer consumer, Matrix4f matrix, float radius, float red, float green, float blue, float alpha) {
        for (int i = 0; i < SEGMENTS; i++) {
            float angle0 = (float) i / SEGMENTS * TWO_PI;
            float angle1 = (float) (i + 1) / SEGMENTS * TWO_PI;
            float x0 = Mth.cos(angle0) * radius;
            float z0 = Mth.sin(angle0) * radius;
            float x1 = Mth.cos(angle1) * radius;
            float z1 = Mth.sin(angle1) * radius;

            // Outward-facing
            consumer.addVertex(matrix, x0, BOTTOM, z0).setColor(red, green, blue, alpha);
            consumer.addVertex(matrix, x1, BOTTOM, z1).setColor(red, green, blue, alpha);
            consumer.addVertex(matrix, x1, HEIGHT, z1).setColor(red, green, blue, 0.0F);

            consumer.addVertex(matrix, x0, BOTTOM, z0).setColor(red, green, blue, alpha);
            consumer.addVertex(matrix, x1, HEIGHT, z1).setColor(red, green, blue, 0.0F);
            consumer.addVertex(matrix, x0, HEIGHT, z0).setColor(red, green, blue, 0.0F);

            // Inward-facing
            consumer.addVertex(matrix, x0, BOTTOM, z0).setColor(red, green, blue, alpha);
            consumer.addVertex(matrix, x1, HEIGHT, z1).setColor(red, green, blue, 0.0F);
            consumer.addVertex(matrix, x1, BOTTOM, z1).setColor(red, green, blue, alpha);

            consumer.addVertex(matrix, x0, BOTTOM, z0).setColor(red, green, blue, alpha);
            consumer.addVertex(matrix, x0, HEIGHT, z0).setColor(red, green, blue, 0.0F);
            consumer.addVertex(matrix, x1, HEIGHT, z1).setColor(red, green, blue, 0.0F);
        }
    }

    /** A flat additive disc at the beam base, bright in the center and fading to nothing at the rim. */
    private void drawGroundGlow(VertexConsumer consumer, Matrix4f matrix, float radius, float red, float green, float blue, float alpha) {
        float y = 0.05F;
        for (int i = 0; i < SEGMENTS; i++) {
            float angle0 = (float) i / SEGMENTS * TWO_PI;
            float angle1 = (float) (i + 1) / SEGMENTS * TWO_PI;
            float x0 = Mth.cos(angle0) * radius;
            float z0 = Mth.sin(angle0) * radius;
            float x1 = Mth.cos(angle1) * radius;
            float z1 = Mth.sin(angle1) * radius;

            consumer.addVertex(matrix, 0.0F, y, 0.0F).setColor(red, green, blue, alpha);
            consumer.addVertex(matrix, x0, y, z0).setColor(red, green, blue, 0.0F);
            consumer.addVertex(matrix, x1, y, z1).setColor(red, green, blue, 0.0F);

            consumer.addVertex(matrix, 0.0F, y, 0.0F).setColor(red, green, blue, alpha);
            consumer.addVertex(matrix, x1, y, z1).setColor(red, green, blue, 0.0F);
            consumer.addVertex(matrix, x0, y, z0).setColor(red, green, blue, 0.0F);
        }
    }
}
