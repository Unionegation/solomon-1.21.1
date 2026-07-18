package dev.solomon.solomon.mixin.client;

import dev.solomon.solomon.SolomonClient;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures the sunlight effects' pre-water depth snapshot at the exact moment the translucent
 * (water) chunk layer is about to draw: everything opaque has written depth by then — terrain,
 * entities, players, block entities, solid particles, and crucially Iris's *batched* entity
 * geometry, which flushes after the AFTER_BLOCK_ENTITIES event and so was missing from a snapshot
 * taken there (the light drew over mobs and players under shaderpacks). Water and clouds come
 * after this point, so they stay out of the snapshot.
 *
 * The {@code popPush("translucent")} profiler call exists in both the Fabulous and the normal
 * branch of {@code renderLevel}; this injects at both, and only the live branch runs per frame.
 */
@Mixin(LevelRenderer.class)
public class LevelRendererMixin {

    @Inject(method = "renderLevel", at = @At(value = "INVOKE_STRING",
            target = "Lnet/minecraft/util/profiling/ProfilerFiller;popPush(Ljava/lang/String;)V",
            args = "ldc=translucent"))
    private void solomon$captureDepthBeforeWater(CallbackInfo ci) {
        SolomonClient.captureSunlightDepth();
    }
}
