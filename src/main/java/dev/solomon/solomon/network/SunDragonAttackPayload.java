package dev.solomon.solomon.network;

import dev.solomon.solomon.Solomon;
import dev.solomon.solomon.entity.SunDragon;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Sent by the client when the sun-dragon attack keybind fires. The server spawns a {@link SunDragon}
 * at the caster's eyes in helix-attack mode: it corkscrews around the caster→target line, converges
 * onto the target, and discards itself once its tail has arrived. Purely visual for now — no damage.
 */
public record SunDragonAttackPayload(Vec3 target) implements CustomPacketPayload {

    public static final Type<SunDragonAttackPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Solomon.MODID, "sun_dragon_attack"));

    public static final StreamCodec<FriendlyByteBuf, SunDragonAttackPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.DOUBLE, payload -> payload.target().x,
            ByteBufCodecs.DOUBLE, payload -> payload.target().y,
            ByteBufCodecs.DOUBLE, payload -> payload.target().z,
            (x, y, z) -> new SunDragonAttackPayload(new Vec3(x, y, z)));

    // Targeting raycast range is 64; allow generous slack before rejecting the position as bogus
    private static final double MAX_CAST_DISTANCE_SQR = 96.0 * 96.0;

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(SunDragonAttackPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        Vec3 target = payload.target();
        if (!player.isHolding(Solomon.SUN_SPEAR.get()) || player.position().distanceToSqr(target) > MAX_CAST_DISTANCE_SQR) {
            return;
        }

        SunDragon dragon = Solomon.SUN_DRAGON.get().create(player.serverLevel());
        if (dragon == null) {
            return;
        }
        dragon.startHelixAttack(player.getEyePosition(), target);
        player.serverLevel().addFreshEntity(dragon);
        // Variable-range event: volume > 1 extends how far the roar carries, fitting the dragon's size.
        player.serverLevel().playSound(null, player.getX(), player.getY(), player.getZ(),
                Solomon.SUN_DRAGON_LAUNCH_SOUND.get(), SoundSource.PLAYERS, 3.0F, 1.0F);
    }
}
