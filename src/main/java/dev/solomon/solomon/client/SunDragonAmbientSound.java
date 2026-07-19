package dev.solomon.solomon.client;

import dev.solomon.solomon.Solomon;
import dev.solomon.solomon.entity.SunDragon;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;

/**
 * Quiet looping ambience that follows a sun dragon's head from the moment it
 * appears on the client
 * until it despawns (or leaves tracking range, which removes the client entity
 * the same way).
 * Started by SolomonClient's EntityJoinLevelEvent listener; stops itself once
 * the dragon is gone.
 */
public class SunDragonAmbientSound extends AbstractTickableSoundInstance {
    private static final float VOLUME = 4F;

    private final SunDragon dragon;

    public SunDragonAmbientSound(SunDragon dragon) {
        super(Solomon.SUN_DRAGON_AMBIENT_SOUND.get(), SoundSource.NEUTRAL, SoundInstance.createUnseededRandom());
        this.dragon = dragon;
        this.looping = true;
        this.delay = 0;
        this.volume = VOLUME;
        this.x = dragon.getX();
        this.y = dragon.getY();
        this.z = dragon.getZ();
    }

    @Override
    public void tick() {
        if (this.dragon.isRemoved()) {
            this.stop();
            return;
        }
        // Track the despawn fade so the loop dims out with the body instead of cutting off.
        this.volume = VOLUME * this.dragon.getFadeAlpha(0.0F);
        this.x = this.dragon.getX();
        this.y = this.dragon.getY();
        this.z = this.dragon.getZ();
    }
}
