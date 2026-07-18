package dev.solomon.solomon.network;

import dev.solomon.solomon.Solomon;
import dev.solomon.solomon.server.SunBeamManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Sent by the client exactly once per sun beam, at the moment it erupts (a beam cancelled during
 * targeting never erupts, so it never sends this). The client only picks the where and the when;
 * everything about the damage — cadence, amount, duration, validation — is owned by
 * {@link SunBeamManager} on the server from here on.
 */
public record SunBeamStartPayload(Vec3 pos) implements CustomPacketPayload {

    public static final Type<SunBeamStartPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Solomon.MODID, "sun_beam_start"));

    public static final StreamCodec<FriendlyByteBuf, SunBeamStartPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.DOUBLE, payload -> payload.pos().x,
            ByteBufCodecs.DOUBLE, payload -> payload.pos().y,
            ByteBufCodecs.DOUBLE, payload -> payload.pos().z,
            (x, y, z) -> new SunBeamStartPayload(new Vec3(x, y, z)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(SunBeamStartPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            SunBeamManager.startBeam(player, payload.pos());
        }
    }
}
