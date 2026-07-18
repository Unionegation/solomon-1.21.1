package dev.solomon.solomon.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Decorative Chinese-dragon-style serpent. The server flies the single entity (which is just the
 * head) along a layered-sine curl around its spawn point; every tick both sides record the head
 * position into a ring-buffer trail, and {@code SunDragonRenderer} strings the body segments along
 * that trail by arc length so the body follows the head exactly like a snake.
 */
public class SunDragon extends Mob {
    /**
     * Whole-dragon scale, the single size knob: the render size, hitbox, and the curl path
     * (orbit radius, breathing, height swing) all derive from it, so the flight path stays
     * proportioned to the body and per-segment bend angles don't change with size.
     * SCALE is the size of the OUTERMOST glow halo; the solid body is SCALE / OUTER_GLOW.
     */
    public static final float SCALE = 8.0F;
    /** Scale multiplier of the renderer's outermost glow shell (largest GLOW_PASSES entry). */
    public static final float OUTER_GLOW = 1.6F;
    /** Base scale of the solid body model, sized so the outer halo lands exactly at SCALE. */
    public static final float MODEL_SCALE = SCALE / OUTER_GLOW;

    // Body layout, shared with SunDragonRenderer (and used here for culling/trail sizing).
    public static final int BODY_SEGMENTS = 48;
    /** Arc-length gap between segment centers at full taper; shrinks toward the tail. */
    public static final double SEGMENT_SPACING = 0.38 * MODEL_SCALE;
    /** Head center to first body segment; shorter than the skull's rear extent so it tucks under the horns. */
    public static final double HEAD_GAP = 0.32 * MODEL_SCALE;
    public static final float TAIL_SCALE = 0.4F;
    /** Approximate head-to-tail arc length (taper averages (1+TAIL_SCALE)/2). */
    public static final double BODY_LENGTH =
            HEAD_GAP + SEGMENT_SPACING * BODY_SEGMENTS * (1.0 + TAIL_SCALE) / 2.0;

    /**
     * Trail samples kept (1 per tick). Linear speed scales with SCALE just like BODY_LENGTH, so the
     * tick coverage needed is size-independent: ~BODY_LENGTH / minSpeed ≈ 13.1 / 0.08 ≈ 165 ticks
     * at the tightest point of the radius breathing.
     */
    public static final int TRAIL_LENGTH = 256;

    // Helix-attack path, all SCALE-derived like the curl so the corkscrew stays proportioned to
    // the body. The dragon flies from its caster along a helix wound around the caster→target
    // line, spiraling in to hit the target exactly (radius ramps 0→full→0 along the line).
    public static final double HELIX_RADIUS = 0.9 * SCALE;
    /** Blocks advanced along the caster→target axis per tick. */
    public static final double HELIX_AXIAL_SPEED = 0.16 * SCALE;
    /** Radians swept around the axis per tick. */
    public static final double HELIX_ANGULAR_SPEED = 0.22;
    /** Axial distance over which the helix radius ramps in at the caster / out at the target. */
    public static final double HELIX_RAMP = 2.0 * SCALE;

    private final Vec3[] trail = new Vec3[TRAIL_LENGTH];
    /** Index of the newest trail sample, or -1 until the first tick fills the buffer. */
    private int trailHead = -1;

    // Center of the curl. Chosen at spawn so the parametric path passes through the spawn point,
    // and persisted (along with curlTime) so the dragon doesn't jump on chunk reload.
    private Vec3 anchor;
    private int curlTime;

    // Helix-attack state; helixTime < 0 means the dragon is in its idle curl instead. Attack
    // dragons fly the helix once, overshoot the target far enough for the tail to arrive, then
    // discard themselves.
    private Vec3 helixStart;
    private Vec3 helixTarget;
    private int helixTime = -1;

    public SunDragon(EntityType<? extends SunDragon> type, Level level) {
        super(type, level);
        this.noPhysics = true;
        this.setNoGravity(true);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 40.0)
                .add(Attributes.MOVEMENT_SPEED, 0.3)
                .add(Attributes.FOLLOW_RANGE, 16.0);
    }

    /**
     * Head offset from the anchor at time t (in ticks). All distances are multiplied by SCALE so
     * the orbit grows with the dragon; angular speed stays fixed, so linear speed (and thus how
     * many trail ticks the body spans) scales along with it.
     */
    private static Vec3 curlOffset(double t) {
        double angle = t * 0.045;
        double radius = (2.9 + 1.1 * Math.sin(t * 0.021)) * SCALE;
        double y = (0.7 * Math.sin(t * 0.09) + 0.4 * Math.sin(t * 0.157 + 1.3)) * SCALE;
        return new Vec3(radius * Math.cos(angle), y, radius * Math.sin(angle));
    }

    /**
     * Puts this dragon into helix-attack mode: it flies from {@code start} to {@code target} along
     * a helix wound around the straight line between them, then discards itself once the tail has
     * reached the target. Call before adding the entity to the level.
     */
    public void startHelixAttack(Vec3 start, Vec3 target) {
        this.helixStart = start;
        this.helixTarget = target;
        this.helixTime = 0;
        this.setPos(helixPos(0.0));
    }

    public boolean isHelixAttacking() {
        return this.helixTime >= 0;
    }

    /**
     * Head position at helix time t (in ticks). The path advances along the start→target axis at
     * {@link #HELIX_AXIAL_SPEED} while sweeping {@link #HELIX_ANGULAR_SPEED} around it; the radius
     * ramps 0→{@link #HELIX_RADIUS}→0 over {@link #HELIX_RAMP} blocks at each end, so the head
     * leaves exactly from the start point and converges exactly onto the target. Past the target
     * the radius stays 0 and the path continues straight along the axis (the overshoot that lets
     * the body finish arriving).
     */
    private Vec3 helixPos(double t) {
        Vec3 line = this.helixTarget.subtract(this.helixStart);
        double length = line.length();
        Vec3 axis = length > 1.0E-4 ? line.scale(1.0 / length) : new Vec3(0.0, -1.0, 0.0);
        // Radial frame around the axis; fall back when the axis is (near) vertical.
        Vec3 ref = Math.abs(axis.y) > 0.99 ? new Vec3(1.0, 0.0, 0.0) : new Vec3(0.0, 1.0, 0.0);
        Vec3 u = axis.cross(ref).normalize();
        Vec3 v = axis.cross(u);

        double s = t * HELIX_AXIAL_SPEED;
        double ramp = Math.min(HELIX_RAMP, length / 2.0);
        double radius = HELIX_RADIUS
                * Mth.clamp(s / ramp, 0.0, 1.0)
                * Mth.clamp((length - s) / ramp, 0.0, 1.0);
        double angle = t * HELIX_ANGULAR_SPEED;
        return this.helixStart
                .add(axis.scale(s))
                .add(u.scale(radius * Math.cos(angle)))
                .add(v.scale(radius * Math.sin(angle)));
    }

    private void tickHelixAttack() {
        this.helixTime++;
        this.flyTo(helixPos(this.helixTime));
        // Head is BODY_LENGTH past the target ⇒ the tail has just arrived; the whole overshoot
        // continues straight along the axis (usually into the terrain the target sits on).
        if (this.helixTime * HELIX_AXIAL_SPEED > this.helixTarget.distanceTo(this.helixStart) + BODY_LENGTH) {
            this.discard();
        }
    }

    private void tickIdleCurl() {
        if (this.anchor == null) {
            // Offset the anchor so the curl at t=0 lands exactly on the spawn position (no first-tick jump).
            this.anchor = this.position().subtract(curlOffset(0.0));
        }
        this.curlTime++;
        this.flyTo(this.anchor.add(curlOffset(this.curlTime)));
    }

    /** Moves the head to {@code next} and faces it along the movement direction. */
    private void flyTo(Vec3 next) {
        Vec3 delta = next.subtract(this.position());
        this.setPos(next);
        this.setDeltaMovement(Vec3.ZERO);
        if (delta.lengthSqr() > 1.0E-6) {
            float yaw = (float) (Mth.atan2(delta.x, delta.z) * Mth.RAD_TO_DEG);
            this.setYRot(yaw);
            this.yBodyRot = yaw;
            this.yHeadRot = yaw;
            this.setXRot((float) (-Mth.atan2(delta.y, delta.horizontalDistance()) * Mth.RAD_TO_DEG));
        }
    }

    @Override
    public void tick() {
        super.tick();

        if (!this.level().isClientSide) {
            if (this.isHelixAttacking()) {
                this.tickHelixAttack();
            } else {
                this.tickIdleCurl();
            }
        }

        // Record the (server-authoritative or client-lerped) head position into the trail.
        Vec3 pos = this.position();
        if (this.trailHead < 0) {
            for (int i = 0; i < TRAIL_LENGTH; i++) {
                this.trail[i] = pos;
            }
            this.trailHead = 0;
        } else {
            this.trailHead = (this.trailHead + 1) % TRAIL_LENGTH;
            this.trail[this.trailHead] = pos;
        }
    }

    /** Trail sample {@code stepsBack} whole ticks behind the newest one. */
    private Vec3 trailSample(int stepsBack) {
        if (this.trailHead < 0) {
            return this.position();
        }
        int clamped = Math.min(stepsBack, TRAIL_LENGTH - 1);
        return this.trail[Math.floorMod(this.trailHead - clamped, TRAIL_LENGTH)];
    }

    /**
     * Smooth trail position {@code ticksBack} ticks behind the current render time. At
     * {@code ticksBack == 0} this equals the entity's interpolated render position.
     */
    public Vec3 getTrailPoint(double ticksBack, float partialTick) {
        double index = ticksBack + (1.0 - partialTick);
        int i0 = (int) Math.floor(index);
        double frac = index - i0;
        return trailSample(i0).lerp(trailSample(i0 + 1), frac);
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    protected void doPush(net.minecraft.world.entity.Entity entity) {
    }

    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return false;
    }

    @Override
    public AABB getBoundingBoxForCulling() {
        // The rendered body trails well behind the head's hitbox; widen the cull box so segments
        // don't vanish when the head leaves the frustum.
        return this.getBoundingBox().inflate(BODY_LENGTH);
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (this.anchor != null) {
            tag.putDouble("AnchorX", this.anchor.x);
            tag.putDouble("AnchorY", this.anchor.y);
            tag.putDouble("AnchorZ", this.anchor.z);
        }
        tag.putInt("CurlTime", this.curlTime);
        if (this.isHelixAttacking()) {
            tag.putDouble("HelixStartX", this.helixStart.x);
            tag.putDouble("HelixStartY", this.helixStart.y);
            tag.putDouble("HelixStartZ", this.helixStart.z);
            tag.putDouble("HelixTargetX", this.helixTarget.x);
            tag.putDouble("HelixTargetY", this.helixTarget.y);
            tag.putDouble("HelixTargetZ", this.helixTarget.z);
            tag.putInt("HelixTime", this.helixTime);
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("AnchorX")) {
            this.anchor = new Vec3(tag.getDouble("AnchorX"), tag.getDouble("AnchorY"), tag.getDouble("AnchorZ"));
        }
        this.curlTime = tag.getInt("CurlTime");
        if (tag.contains("HelixTime")) {
            this.helixStart = new Vec3(tag.getDouble("HelixStartX"), tag.getDouble("HelixStartY"), tag.getDouble("HelixStartZ"));
            this.helixTarget = new Vec3(tag.getDouble("HelixTargetX"), tag.getDouble("HelixTargetY"), tag.getDouble("HelixTargetZ"));
            this.helixTime = tag.getInt("HelixTime");
        }
    }
}
