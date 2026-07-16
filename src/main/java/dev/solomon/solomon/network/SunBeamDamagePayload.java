package dev.solomon.solomon.network;

import dev.solomon.solomon.Solomon;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Sent by the client at the moment a sun beam erupts (the commit point after targeting).
 * The server damages every living entity inside the beam column.
 */
public record SunBeamDamagePayload(Vec3 pos) implements CustomPacketPayload {

    public static final Type<SunBeamDamagePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Solomon.MODID, "sun_beam_damage"));

    public static final StreamCodec<FriendlyByteBuf, SunBeamDamagePayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.DOUBLE, payload -> payload.pos().x,
            ByteBufCodecs.DOUBLE, payload -> payload.pos().y,
            ByteBufCodecs.DOUBLE, payload -> payload.pos().z,
            (x, y, z) -> new SunBeamDamagePayload(new Vec3(x, y, z)));

    private static final float DAMAGE = 50.0F;
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

        AABB column = new AABB(pos.x - RADIUS, pos.y + BOTTOM, pos.z - RADIUS,
                pos.x + RADIUS, pos.y + HEIGHT, pos.z + RADIUS);
        for (LivingEntity entity : player.level().getEntitiesOfClass(LivingEntity.class, column,
                entity -> entity != player && entity.isAlive())) {
            double dx = entity.getX() - pos.x;
            double dz = entity.getZ() - pos.z;
            if (dx * dx + dz * dz <= RADIUS * RADIUS) {
                entity.hurt(player.damageSources().indirectMagic(player, player), DAMAGE);
            }
        }
    }
}
