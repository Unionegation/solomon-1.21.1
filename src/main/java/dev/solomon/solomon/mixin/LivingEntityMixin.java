package dev.solomon.solomon.mixin;

import dev.solomon.solomon.Solomon;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// The sunrip damage bypasses hurt-immunity and ticks every game tick (see SunBeamDamagePayload), which
// makes the vanilla hurt sound machine-gun. Players keep that per-tick feedback; for every other living
// entity the sunrip hurt sound is thinned to once every SUNRIP_HURT_SOUND_INTERVAL ticks, so a mob
// burning in the beam still grunts periodically instead of continuously. Only the sunrip source is
// touched, and only the sound is suppressed — the damage and hurt flash are unaffected.
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {

    private static final int SUNRIP_HURT_SOUND_INTERVAL = 10;

    @Inject(method = "playHurtSound", at = @At("HEAD"), cancellable = true)
    private void solomon$throttleSunripHurtSound(DamageSource source, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (self instanceof Player) {
            return;
        }
        if (source.is(Solomon.SUNRIP_DAMAGE) && self.tickCount % SUNRIP_HURT_SOUND_INTERVAL != 0) {
            ci.cancel();
        }
    }
}
