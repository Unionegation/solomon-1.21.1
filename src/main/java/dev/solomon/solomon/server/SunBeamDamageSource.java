package dev.solomon.solomon.server;

import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/**
 * Damage source for the sun beam. Instead of the fixed "killed by magic" line, each death rolls one
 * of three custom messages so kills read differently. The three variants live in the language file
 * under {@code death.attack.sunrip.1} .. {@code death.attack.sunrip.3}; each is passed the victim's
 * name as {@code %1$s} and the caster's name as {@code %2$s} (the second argument is optional to use).
 */
public class SunBeamDamageSource extends DamageSource {

    private static final int MESSAGE_VARIANTS = 3;

    public SunBeamDamageSource(Holder<DamageType> type, Entity attacker) {
        // Passing the caster as both the direct and causing entity makes getEntity() the killer.
        super(type, attacker);
    }

    @Override
    public Component getLocalizedDeathMessage(LivingEntity victim) {
        int variant = victim.getRandom().nextInt(MESSAGE_VARIANTS) + 1;
        String key = "death.attack.sunrip." + variant;
        Entity attacker = this.getEntity();
        if (attacker != null) {
            return Component.translatable(key, victim.getDisplayName(), attacker.getDisplayName());
        }
        return Component.translatable(key, victim.getDisplayName());
    }
}
