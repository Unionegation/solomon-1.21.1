package dev.solomon.solomon.server;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

import dev.solomon.solomon.Solomon;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Server-authoritative sun-beam damage. The client sends a single {@code SunBeamStartPayload} at
 * the moment a beam erupts; from then on the server runs the whole damage schedule itself —
 * {@link #DAMAGE_PULSES} pulses, one every {@link #PULSE_INTERVAL_TICKS} ticks, matching the
 * client effect's erupted window — so a modified client can never deal more damage than one
 * legitimate beam per start packet. Rather than a single {@link #TOTAL_DAMAGE}-point hit, the
 * pulses drain evenly, so an entity only takes the full amount if it stays in the column the
 * whole time.
 *
 * Each pulse re-validates the caster (online, same dimension, holding the spear, within range);
 * a failed check skips that pulse but keeps the schedule running, mirroring how per-pulse
 * validation behaved when the client drove the cadence — switching items mid-beam pauses the
 * damage, switching back resumes it. A disconnected caster cancels the beam outright.
 */
public final class SunBeamManager {

    private static final float TOTAL_DAMAGE = 150.0F;
    // The sunrip damage type is tagged minecraft:bypasses_cooldown, so it ignores the vanilla 0.5s
    // hurt-immunity window and can tick every game tick for a smooth, sustained drain. The pulse
    // count matches the beam's erupted lifetime (~7.3s of the 7.931s sound), so an entity present
    // for the whole beam accumulates exactly TOTAL_DAMAGE. Raise PULSE_INTERVAL_TICKS (and lower
    // DAMAGE_PULSES to match) if the every-tick hurt flash/sound feels too busy.
    public static final int PULSE_INTERVAL_TICKS = 1;
    public static final int DAMAGE_PULSES = 146;
    private static final float DAMAGE_PER_PULSE = TOTAL_DAMAGE / DAMAGE_PULSES;
    // Matches the visuals in SunBeamEffect: outer shell ~2.6 radius, -3 to +48 blocks vertically
    private static final double RADIUS = 3.0;
    private static final double BOTTOM = -3.0;
    private static final double HEIGHT = 48.0;
    // Beam raycast range is 64; allow generous slack before rejecting the position as bogus
    private static final double MAX_CAST_DISTANCE_SQR = 96.0 * 96.0;

    /** One erupted beam mid-schedule. The column position is fixed for the beam's whole life. */
    private static final class ActiveBeam {
        final UUID casterId;
        final ResourceKey<Level> dimension;
        final Vec3 pos;
        int ticks;
        int pulsesSent;

        ActiveBeam(UUID casterId, ResourceKey<Level> dimension, Vec3 pos) {
            this.casterId = casterId;
            this.dimension = dimension;
            this.pos = pos;
        }
    }

    private static final List<ActiveBeam> beams = new ArrayList<>();

    private SunBeamManager() {
    }

    /** Starts a beam's damage schedule if the start request passes validation; otherwise ignores it. */
    public static void startBeam(ServerPlayer caster, Vec3 pos) {
        if (!caster.isHolding(Solomon.SUN_SPEAR.get())
                || caster.position().distanceToSqr(pos) > MAX_CAST_DISTANCE_SQR) {
            return;
        }
        beams.add(new ActiveBeam(caster.getUUID(), caster.level().dimension(), pos));
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        if (beams.isEmpty()) {
            return;
        }
        MinecraftServer server = event.getServer();
        Iterator<ActiveBeam> iterator = beams.iterator();
        while (iterator.hasNext()) {
            ActiveBeam beam = iterator.next();
            ServerPlayer caster = server.getPlayerList().getPlayer(beam.casterId);
            if (caster == null) {
                iterator.remove();
                continue;
            }
            if (beam.ticks % PULSE_INTERVAL_TICKS == 0) {
                beam.pulsesSent++;
                pulse(beam, caster);
            }
            beam.ticks++;
            if (beam.pulsesSent >= DAMAGE_PULSES) {
                iterator.remove();
            }
        }
    }

    /** Beams must not leak across world loads (singleplayer re-entry reuses the JVM). */
    public static void onServerStopped(ServerStoppedEvent event) {
        beams.clear();
    }

    private static void pulse(ActiveBeam beam, ServerPlayer caster) {
        if (caster.level().dimension() != beam.dimension
                || !caster.isHolding(Solomon.SUN_SPEAR.get())
                || caster.position().distanceToSqr(beam.pos) > MAX_CAST_DISTANCE_SQR) {
            return;
        }

        Holder<DamageType> damageType = caster.level().registryAccess()
                .registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(Solomon.SUNRIP_DAMAGE);
        DamageSource source = new SunBeamDamageSource(damageType, caster);

        Vec3 pos = beam.pos;
        AABB column = new AABB(pos.x - RADIUS, pos.y + BOTTOM, pos.z - RADIUS,
                pos.x + RADIUS, pos.y + HEIGHT, pos.z + RADIUS);
        for (LivingEntity entity : caster.serverLevel().getEntitiesOfClass(LivingEntity.class, column,
                entity -> entity != caster && entity.isAlive())) {
            double dx = entity.getX() - pos.x;
            double dz = entity.getZ() - pos.z;
            if (dx * dx + dz * dz <= RADIUS * RADIUS) {
                entity.hurt(source, DAMAGE_PER_PULSE);
            }
        }
    }
}
