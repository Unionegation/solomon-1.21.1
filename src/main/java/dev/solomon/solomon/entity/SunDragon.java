package dev.solomon.solomon.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Decorative Chinese-dragon-style serpent. The server flies the single entity
 * (which is just the
 * head) along a layered-sine curl around its spawn point; every tick the client
 * records the synced
 * head position into a ring-buffer trail, and {@code SunDragonRenderer} strings
 * the body segments
 * along that trail by arc length so the body follows the head exactly like a
 * snake.
 */
public class SunDragon extends Mob {
    /**
     * Whole-dragon scale, the single size knob: the render size, hitbox, and the
     * curl path
     * (orbit radius, breathing, height swing) all derive from it, so the flight
     * path stays
     * proportioned to the body and per-segment bend angles don't change with size.
     * SCALE is the size of the OUTERMOST glow halo; the solid body is SCALE /
     * OUTER_GLOW.
     */
    public static final float SCALE = 8.0F;
    /**
     * Scale multiplier of the renderer's outermost glow shell (largest GLOW_PASSES
     * entry).
     */
    public static final float OUTER_GLOW = 1.6F;
    /**
     * Base scale of the solid body model, sized so the outer halo lands exactly at
     * SCALE.
     */
    public static final float MODEL_SCALE = SCALE / OUTER_GLOW;

    // Body layout, shared with SunDragonRenderer (and used here for culling/trail
    // sizing).
    public static final int BODY_SEGMENTS = 48;
    /**
     * Arc-length gap between segment centers at full taper; shrinks toward the
     * tail.
     */
    public static final double SEGMENT_SPACING = 0.38 * MODEL_SCALE;
    /**
     * Head center to first body segment; shorter than the skull's rear extent so it
     * tucks under the horns.
     */
    public static final double HEAD_GAP = 0.32 * MODEL_SCALE;
    public static final float TAIL_SCALE = 0.4F;
    /** Approximate head-to-tail arc length (taper averages (1+TAIL_SCALE)/2). */
    public static final double BODY_LENGTH = HEAD_GAP + SEGMENT_SPACING * BODY_SEGMENTS * (1.0 + TAIL_SCALE) / 2.0;

    /**
     * Trail samples kept (1 per tick). Linear speed scales with SCALE just like
     * BODY_LENGTH, so the tick coverage needed is size-independent: ~BODY_LENGTH /
     * minSpeed ≈ 13.1 / 0.08 ≈ 165 ticks at the tightest point of the curl's radius
     * breathing; the helix attack flies at a constant HELIX_SPEED, spanning only
     * ~BODY_LENGTH / (0.1·SCALE) ≈ 82 ticks — both fit.
     */
    public static final int TRAIL_LENGTH = 256;

    // Helix-attack path, all SCALE-derived like the curl so the corkscrew stays
    // proportioned to the body. The dragon spawns behind and to the side of its
    // caster, sweeps along a Bezier intro past the caster to a point in front of
    // them, then flies a helix wound around the front-point→target line, spiraling
    // in to hit the target exactly (radius ramps 0→full→0 along the line). The
    // helix is parametrized by axial distance and stepped by arc length each tick,
    // so the head moves at a constant HELIX_SPEED the whole way — no speeding up
    // mid-coil or slowing down where the radius ramps out.
    /** Constant head speed along the whole coil, in blocks per tick. */
    public static final double HELIX_SPEED = 0.1 * SCALE;
    /** Coil radius around the front-point→target axis. */
    public static final double HELIX_RADIUS = 0.6 * SCALE;
    /**
     * Radians wound around the axis per block of axial travel — one full turn every
     * 2π / HELIX_WIND_RATE ≈ 25 blocks. Together with HELIX_RADIUS this sets how
     * curly vs. forward-moving the flight feels: radius × wind rate is the
     * circling:forward speed ratio at full radius (≈ 1.2 here, i.e. slightly more
     * circling than forward motion at the coil's widest).
     */
    public static final double HELIX_WIND_RATE = 2.0 / SCALE;
    /**
     * Axial distance over which the helix radius ramps in at the front point / out
     * at the target.
     */
    public static final double HELIX_RAMP = 2.0 * SCALE;

    // Intro sweep: where the dragon materializes relative to the caster and how it
    // reaches the
    // point in front of them where the coiling starts. Keeps the huge body from
    // bunching up in
    // the caster's view — the trail unspools behind and beside them instead.
    /** Spawn distance behind the caster, along the horizontal cast direction. */
    public static final double INTRO_BEHIND = 1.5 * SCALE;
    /** Spawn distance to the caster's right. */
    public static final double INTRO_SIDE = 1.0 * SCALE;
    /**
     * Distance in front of the caster (toward the target) where the helix begins.
     */
    public static final double INTRO_FRONT = 1.0 * SCALE;
    /** Approximate blocks per tick along the intro sweep. */
    public static final double INTRO_SPEED = 0.8 * SCALE;

    private final Vec3[] trail = new Vec3[TRAIL_LENGTH];
    /**
     * Index of the newest trail sample, or -1 until the first tick fills the
     * buffer.
     */
    private int trailHead = -1;

    // Center of the curl. Chosen at spawn so the parametric path passes through the
    // spawn point,
    // and persisted (along with curlTime) so the dragon doesn't jump on chunk
    // reload.
    private Vec3 anchor;
    private int curlTime;

    // Helix-attack state; helixTime < 0 means the dragon is in its idle curl
    // instead. Attack
    // dragons sweep the intro Bezier, fly the helix once, overshoot the target far
    // enough for
    // the tail to arrive, then discard themselves. helixTime counts from 0 at
    // spawn; the first
    // introTicks of it are the intro sweep.
    private Vec3 introStart;
    private Vec3 introControl;
    private int introTicks;
    private Vec3 helixStart;
    private Vec3 helixTarget;
    private int helixTime = -1;
    /** Axial distance progressed along the helix, advanced by arc length each tick. */
    private double helixS;

    /**
     * Synced flag flipped by the server once the tail reaches the target: instead of vanishing,
     * the dragon keeps flying straight while it fades out over {@link #FADE_TICKS}, then the
     * server discards it. The client counts its own {@link #fadeTime} from the moment the flag
     * arrives and drives the render alpha + ambient-sound volume from it.
     */
    private static final EntityDataAccessor<Boolean> DATA_FADING =
            SynchedEntityData.defineId(SunDragon.class, EntityDataSerializers.BOOLEAN);
    /** Ticks over which an attack dragon fades out once its tail has arrived. */
    public static final int FADE_TICKS = 50;
    private int fadeTime;

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

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_FADING, false);
    }

    public boolean isFading() {
        return this.entityData.get(DATA_FADING);
    }

    /**
     * Render/volume multiplier for the fade-out: 1 while not fading, then 1→0 over
     * {@link #FADE_TICKS}. Smooth across frames via {@code partialTick}.
     */
    public float getFadeAlpha(float partialTick) {
        if (!this.isFading()) {
            return 1.0F;
        }
        return Mth.clamp(1.0F - (this.fadeTime - 1 + partialTick) / FADE_TICKS, 0.0F, 1.0F);
    }

    /**
     * Head offset from the anchor at time t (in ticks). All distances are
     * multiplied by SCALE so
     * the orbit grows with the dragon; angular speed stays fixed, so linear speed
     * (and thus how
     * many trail ticks the body spans) scales along with it.
     */
    private static Vec3 curlOffset(double t) {
        double angle = t * 0.045;
        double radius = (2.9 + 1.1 * Math.sin(t * 0.021)) * SCALE;
        double y = (0.7 * Math.sin(t * 0.09) + 0.4 * Math.sin(t * 0.157 + 1.3)) * SCALE;
        return new Vec3(radius * Math.cos(angle), y, radius * Math.sin(angle));
    }

    /**
     * Puts this dragon into helix-attack mode: it spawns behind and to the right of
     * {@code eye},
     * sweeps a Bezier past the caster's side to a point {@link #INTRO_FRONT} in
     * front of them,
     * then flies a helix wound around the straight front-point→{@code target} line,
     * and discards
     * itself once the tail has reached the target. Call before adding the entity to
     * the level.
     */
    public void startHelixAttack(Vec3 eye, Vec3 target) {
        Vec3 toTarget = target.subtract(eye);
        Vec3 flat = new Vec3(toTarget.x, 0.0, toTarget.z);
        // Horizontal cast direction; arbitrary fallback when the caster aims straight
        // up/down.
        Vec3 forward = flat.lengthSqr() > 1.0E-6 ? flat.normalize() : new Vec3(1.0, 0.0, 0.0);
        Vec3 side = new Vec3(-forward.z, 0.0, forward.x);

        this.introStart = eye.subtract(forward.scale(INTRO_BEHIND)).add(side.scale(INTRO_SIDE));
        this.introControl = eye.add(side.scale(INTRO_SIDE));
        // Never start coiling past the halfway point when the target is close.
        double frontDist = Math.min(INTRO_FRONT, toTarget.length() * 0.5);
        this.helixStart = eye.add(toTarget.normalize().scale(frontDist));
        this.helixTarget = target;
        double introLength = this.introStart.distanceTo(this.introControl)
                + this.introControl.distanceTo(this.helixStart);
        this.introTicks = Math.max(1, (int) Math.round(introLength / INTRO_SPEED));
        this.helixTime = 0;
        this.setPos(introPos(0.0));
    }

    public boolean isHelixAttacking() {
        return this.helixTime >= 0;
    }

    /**
     * Head position at axial distance s (in blocks along the start→target line).
     * The angle winds at {@link #HELIX_WIND_RATE} per axial block; the radius ramps
     * 0→{@link #HELIX_RADIUS}→0 over {@link #HELIX_RAMP} blocks at each end, so the
     * head leaves exactly from the start point and converges exactly onto the
     * target. Past the target the radius stays 0 and the path continues straight
     * along the axis (the overshoot that lets the body finish arriving).
     */
    private Vec3 helixPos(double s) {
        Vec3 line = this.helixTarget.subtract(this.helixStart);
        double length = line.length();
        Vec3 axis = length > 1.0E-4 ? line.scale(1.0 / length) : new Vec3(0.0, -1.0, 0.0);
        // Radial frame around the axis; fall back when the axis is (near) vertical.
        Vec3 ref = Math.abs(axis.y) > 0.99 ? new Vec3(1.0, 0.0, 0.0) : new Vec3(0.0, 1.0, 0.0);
        Vec3 u = axis.cross(ref).normalize();
        Vec3 v = axis.cross(u);

        double ramp = Math.min(HELIX_RAMP, length / 2.0);
        double radius = HELIX_RADIUS
                * Mth.clamp(s / ramp, 0.0, 1.0)
                * Mth.clamp((length - s) / ramp, 0.0, 1.0);
        double angle = s * HELIX_WIND_RATE;
        return this.helixStart
                .add(axis.scale(s))
                .add(u.scale(radius * Math.cos(angle)))
                .add(v.scale(radius * Math.sin(angle)));
    }

    /**
     * Head position during the intro sweep: a quadratic Bezier from the spawn point
     * behind/beside
     * the caster, past their side (control point), to the front point where the
     * helix begins.
     */
    private Vec3 introPos(double t) {
        double u = Mth.clamp(t / this.introTicks, 0.0, 1.0);
        double a = (1.0 - u) * (1.0 - u);
        double b = 2.0 * u * (1.0 - u);
        double c = u * u;
        return this.introStart.scale(a).add(this.introControl.scale(b)).add(this.helixStart.scale(c));
    }

    private void tickHelixAttack() {
        this.helixTime++;
        if (this.helixTime < this.introTicks) {
            this.flyTo(introPos(this.helixTime));
            return;
        }
        // Constant-speed step: advance the axial parameter by whatever amount moves the
        // head HELIX_SPEED blocks along the curve. The local stretch |dP/ds| comes from
        // a finite difference — first-order arc-length reparametrization, plenty for a
        // cosmetic path.
        double eps = 0.1;
        double stretch = helixPos(this.helixS + eps).distanceTo(helixPos(this.helixS)) / eps;
        this.helixS += stretch > 1.0E-4 ? HELIX_SPEED / stretch : HELIX_SPEED;
        this.flyTo(helixPos(this.helixS));
        // Head is BODY_LENGTH past the target ⇒ the tail has just arrived; keep flying
        // straight along the axis while the whole body fades out (tick() runs the fade
        // countdown and discards at the end).
        if (!this.isFading() && this.helixS > this.helixTarget.distanceTo(this.helixStart) + BODY_LENGTH) {
            this.entityData.set(DATA_FADING, true);
        }
    }

    private void tickIdleCurl() {
        if (this.anchor == null) {
            // Offset the anchor so the curl at t=0 lands exactly on the spawn position (no
            // first-tick jump).
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

        // Both sides run the fade countdown (the client from whenever the synced flag arrives,
        // which keeps its render alpha in step); only the server actually removes the entity.
        if (this.isFading()) {
            this.fadeTime++;
            if (!this.level().isClientSide && this.fadeTime >= FADE_TICKS) {
                this.discard();
            }
        }

        // Record the head position into the trail. Only the renderer ever reads it, so
        // the
        // server skips the bookkeeping entirely.
        if (this.level().isClientSide) {
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
     * Smooth trail position {@code ticksBack} ticks behind the current render time.
     * At
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
        // The rendered body trails well behind the head's hitbox; widen the cull box so
        // segments
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
            tag.putDouble("HelixS", this.helixS);
            tag.putBoolean("Fading", this.isFading());
            tag.putInt("FadeTime", this.fadeTime);
            tag.putDouble("IntroStartX", this.introStart.x);
            tag.putDouble("IntroStartY", this.introStart.y);
            tag.putDouble("IntroStartZ", this.introStart.z);
            tag.putDouble("IntroControlX", this.introControl.x);
            tag.putDouble("IntroControlY", this.introControl.y);
            tag.putDouble("IntroControlZ", this.introControl.z);
            tag.putInt("IntroTicks", this.introTicks);
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
            this.helixStart = new Vec3(tag.getDouble("HelixStartX"), tag.getDouble("HelixStartY"),
                    tag.getDouble("HelixStartZ"));
            this.helixTarget = new Vec3(tag.getDouble("HelixTargetX"), tag.getDouble("HelixTargetY"),
                    tag.getDouble("HelixTargetZ"));
            this.helixTime = tag.getInt("HelixTime");
            this.helixS = tag.getDouble("HelixS");
            this.entityData.set(DATA_FADING, tag.getBoolean("Fading"));
            this.fadeTime = tag.getInt("FadeTime");
            // Saves from before the intro sweep existed lack these; introTicks 0 skips
            // straight
            // to the helix, which never dereferences the intro points.
            this.introStart = new Vec3(tag.getDouble("IntroStartX"), tag.getDouble("IntroStartY"),
                    tag.getDouble("IntroStartZ"));
            this.introControl = new Vec3(tag.getDouble("IntroControlX"), tag.getDouble("IntroControlY"),
                    tag.getDouble("IntroControlZ"));
            this.introTicks = tag.getInt("IntroTicks");
        }
    }
}
