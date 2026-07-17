package dev.solomon.solomon.network;

import dev.solomon.solomon.Solomon;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Sent by the client once per damage pulse while a sun beam is erupting. Rather than a single 50-point
 * hit, the client fires {@link #DAMAGE_PULSES} evenly spaced pulses over the beam's erupted lifetime,
 * so an entity only takes the full 50 if it stays in the column the whole time. Each pulse damages
 * every living entity inside the beam column for {@link #DAMAGE_PER_PULSE}.
 */
public record SunBeamDamagePayload(Vec3 pos) implements CustomPacketPayload {

    public static final Type<SunBeamDamagePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Solomon.MODID, "sun_beam_damage"));

    public static final StreamCodec<FriendlyByteBuf, SunBeamDamagePayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.DOUBLE, payload -> payload.pos().x,
            ByteBufCodecs.DOUBLE, payload -> payload.pos().y,
            ByteBufCodecs.DOUBLE, payload -> payload.pos().z,
            (x, y, z) -> new SunBeamDamagePayload(new Vec3(x, y, z)));

    private static final float TOTAL_DAMAGE = 50.0F;
    // The sunrip damage type is tagged minecraft:bypasses_cooldown, so it ignores the vanilla 0.5s
    // hurt-immunity window and can tick every game tick for a smooth, sustained drain. The client fires
    // one pulse per tick over the beam's erupted lifetime (~7.3s); DAMAGE_PULSES is that tick count, so
    // an entity present for the whole beam accumulates exactly TOTAL_DAMAGE. Raise PULSE_INTERVAL_TICKS
    // (and lower DAMAGE_PULSES to match) if the every-tick hurt flash/sound feels too busy.
    public static final int PULSE_INTERVAL_TICKS = 1;
    public static final int DAMAGE_PULSES = 146;
    private static final float DAMAGE_PER_PULSE = TOTAL_DAMAGE / DAMAGE_PULSES;
    // Matches the visuals in SunBeamEffect: outer shell ~2.6 radius, -3 to +48 blocks vertically
    private static final double RADIUS = 3.0;
    private static final double BOTTOM = -3.0;
    private static final double HEIGHT = 48.0;
    // Beam raycast range is 64; allow generous slack before rejecting the position as bogus
    private static final double MAX_CAST_DISTANCE_SQR = 96.0 * 96.0;

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(SunBeamDamagePayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        Vec3 pos = payload.pos();
        if (!player.isHolding(Solomon.SUN_SPEAR.get()) || player.position().distanceToSqr(pos) > MAX_CAST_DISTANCE_SQR) {
            return;
        }

        Holder<DamageType> damageType = player.level().registryAccess()
                .registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(Solomon.SUNRIP_DAMAGE);
        DamageSource source = new SunBeamDamageSource(damageType, player);

        AABB column = new AABB(pos.x - RADIUS, pos.y + BOTTOM, pos.z - RADIUS,
                pos.x + RADIUS, pos.y + HEIGHT, pos.z + RADIUS);
        for (LivingEntity entity : player.level().getEntitiesOfClass(LivingEntity.class, column,
                entity -> entity != player && entity.isAlive())) {
            double dx = entity.getX() - pos.x;
            double dz = entity.getZ() - pos.z;
            if (dx * dx + dz * dz <= RADIUS * RADIUS) {
                entity.hurt(source, DAMAGE_PER_PULSE);
            }
        }
    }
}
