# Solomon — project guide

A small **Minecraft 1.21.1 / NeoForge** mod. Adds one item, the **Sun Spear**, a Barched-based
spear that, on a keybind, summons a pillar of sunlight ("sunrip") that deals sustained damage in a
column. In addition, adds an entity 'sun dragon'.

## Stack & versions
- Minecraft `1.21.1`, NeoForge `21.1.238` (see [gradle.properties](gradle.properties)).
- Java 21 toolchain (Mojang ships 21 for 1.21.1). Mojmap + Parchment `2024.11.17`.
- Mixins via `solomon.mixins.json`, plus MixinExtras (`@WrapOperation` etc.).
- mod id `solomon`, base package `dev.solomon.solomon`, group `dev.solomon.solomon`.

## Build / run
- `./gradlew compileJava` — fast correctness check (source of truth; the IDE language server
  often shows every `net.minecraft.*` import as "cannot be resolved" because it lacks the Gradle
  classpath — **ignore those**, trust the Gradle build).
- `./gradlew build` — full build. `./gradlew runClient` / `runServer` / `runData` — run configs.
- Mod metadata is a **template**: [src/main/templates/META-INF/neoforge.mods.toml](src/main/templates/META-INF/neoforge.mods.toml),
  expanded into `build/generated/...` by the `generateModMetadata` task (property substitution from
  gradle.properties). There is no checked-in `META-INF/` under `src/main/resources`. The template
  already registers `[[mixins]] config="solomon.mixins.json"`.

## Dependency: Barched (key gotcha)
`implementation "curse.maven:barched-1432818:8339807"` (Modrinth id `SrRpGi9n`; Modrinth was down so
we pull from Curse). Requires Architectury + Cloth Config at runtime. Barched provides `SpearItem`
and the `Item$PropertiesBridge.spear(...)` bridge (package `zzik2.barched`), which the Sun Spear uses.

**Barched's client model-swap mixins are hardcoded to its own seven vanilla-namespace spears**
(`WOODEN..NETHERITE`) via literal `is(Barched$Items.X_SPEAR)` if-chains and a fixed model-load list —
there is **no registry/tag dispatch** for model resolution. So `solomon:sun_spear` can never be picked
up by them, and we must **replicate** those mixins for our own item (mirrors the BarchedExtraSpears
addon pattern). The one real hook Barched exposes is the `minecraft:spears` item tag
(`Barched$ItemTags.SPEARS`), which drives only the spear *pose/animation*, not model resolution — we
join it via [data/minecraft/tags/item/spears.json](src/main/resources/data/minecraft/tags/item/spears.json).

## Source map (`src/main/java/dev/solomon/solomon/`)
- **[Solomon.java](src/main/java/dev/solomon/solomon/Solomon.java)** — main `@Mod` (common). Registers
  the `SUN_SPEAR` item, `SUNRIP_SOUND`, the `SUNRIP_DAMAGE` `ResourceKey<DamageType>`, the network
  payloads, the creative-tab insertion, and `SunBeamManager`'s game-bus listeners (server tick +
  server stopped). Holds the spear `Tier`/attributes.
- **[SolomonClient.java](src/main/java/dev/solomon/solomon/SolomonClient.java)** — `@Mod(dist=CLIENT)`.
  Owns the `SUN_BEAM_KEY` keybind, the list of `activeBeams`, client tick + render hooks, and the
  in-hand/inventory `ModelResourceLocation`s. Pressing the key raycasts (range 64) and spawns a beam;
  releasing during targeting cancels it. Also registers the sun dragon renderer + model layer.
  **Owns the sunlight depth trick**: `captureSunlightDepth()` (static, called by
  `LevelRendererMixin` right before the translucent/water chunk layer renders) blits the bound
  framebuffer's depth — terrain, entities, players, solid particles, **including Iris's batched
  entity geometry**, which flushes only after `AFTER_BLOCK_ENTITIES`, hence the mixin instead of
  that event — into a `preWaterDepth` snapshot target (falling back to the main target when
  Fabulous leaves FBO 0 bound there). The effects draw at **`AFTER_LEVEL`** (GameRenderer, after
  renderLevel fully returns — i.e. after the Fabulous transparency composite and any Veil/Iris
  renderLevel-tail hooks, so nothing can composite water/clouds over the light), after blitting the
  snapshot back over the bound framebuffer's depth. Terrain/entities occlude the light; water and
  clouds never do. No depth restore: vanilla clears depth for hand rendering right after the event.
  If a blit fails (format mismatch under exotic pipelines) it silently no-ops and the draw falls
  back to the live depth buffer. `AFTER_LEVEL` supplies an identity pose stack, so the handler
  multiplies `event.getModelViewMatrix()` into it before rendering. Draw order is strict: all
  dragon gold bodies (translucent) first, then all additive light (beam shells + dragon glows) —
  additive commutes, so nothing can bury light "beneath" a body.
- **entity/[SunDragon.java](src/main/java/dev/solomon/solomon/entity/SunDragon.java)** — decorative
  multi-segment Chinese dragon (`solomon:sun_dragon`, spawn egg in the spawn-eggs tab). Server-side
  movement has two decoupled modes: the default **idle curl** (layered-sine orbit around the spawn
  anchor; anchor + `curlTime` persisted in NBT) and the **helix attack** (`startHelixAttack`):
  the dragon spawns behind and to the caster's right (`INTRO_BEHIND`/`INTRO_SIDE`, so the body
  doesn't materialize in the caster's face), sweeps a quadratic Bezier past the caster's side to a
  point `INTRO_FRONT` ahead of them (clamped to half the target distance), then the head corkscrews
  around the straight front-point→target line (`HELIX_RADIUS`/`HELIX_WIND_RATE`/`HELIX_RAMP`, all
  SCALE-derived; radius ramps 0→full→0 so it leaves the front point and converges exactly onto the
  target), overshoots straight past the target until the tail arrives (`BODY_LENGTH`), then keeps flying
  straight while it **fades out** over `FADE_TICKS` (server flips the synced `DATA_FADING` flag,
  both sides count `fadeTime`, server discards at the end; `getFadeAlpha(partialTick)` drives the
  renderer's body/glow alpha and the ambient loop's volume) instead of popping; helix + intro +
  fade state persisted in NBT. The helix is parametrized by **axial
  distance** and stepped by arc length each tick (finite-difference `|dP/ds|`), so the head moves
  at a constant `HELIX_SPEED` throughout — no slowdown at the radius ramps. Knobs: `HELIX_SPEED`
  (duration), `HELIX_WIND_RATE` (radians per axial block — winds are tied to distance, not time),
  `HELIX_RADIUS`; radius × wind rate = circling:forward speed ratio at full radius. The client records a
  per-tick position ring buffer (`TRAIL_LENGTH = 256`) that `getTrailPoint` samples smoothly (the
  server never reads the trail, so it skips the bookkeeping).
- **client/[SunDragonModel.java](src/main/java/dev/solomon/solomon/client/SunDragonModel.java)** &
  **[SunDragonRenderer.java](src/main/java/dev/solomon/solomon/client/SunDragonRenderer.java)** —
  head + one reusable body-cube part (authored **+Y-up/+Z-forward**, no vanilla model flip); renderer
  strings the tapering body segments along the trail by arc length, oriented to the trail tangent.
  Rendered in the sunrip's visual language: an alpha-blended gold base pass (custom POSITION_COLOR
  render type, depth-tested but explicitly **non-depth-writing** so the glow — including the white
  core nested inside the body — shines through instead of being z-culled; keeps the yellow visible
  against the sky) under three nested additive `RenderType.dragonRays()` glow shells (small white
  core / gold / faint orange halo) with the beam's two-sine shimmer — a `QuadsToTriangles` adapter
  re-emits ModelPart quads as position+color triangles. Split into `renderBody(...)` /
  `renderGlow(...)`, both driven by SolomonClient's **AFTER_LEVEL** stage handler (bodies for all
  dragons first, then all additive light; batches flushed by the caller), NOT from the entity
  renderer's `render()`, which is left to the nametag default. Occlusion comes from SolomonClient's
  pre-water depth snapshot (see above): terrain hides the dragon, water/clouds don't. Size/layout constants (`SCALE`,
  `BODY_SEGMENTS`, spacing, taper) live on `SunDragon` so the model, hitbox, curl path, and culling
  box all scale together. Entity type uses `updateInterval(1)` so the client trail stays smooth.
- **client/[SunBeamEffect.java](src/main/java/dev/solomon/solomon/client/SunBeamEffect.java)** — one
  beam instance: sound-driven envelope (attack/sustain/fade timed to `sunrip.ogg`, 7.931s), the
  `dragonRays` shell rendering, and the **one-shot eruption send** — `SunBeamStartPayload` fired
  exactly once when the beam erupts (see flow below).
- **network/[SunBeamStartPayload.java](src/main/java/dev/solomon/solomon/network/SunBeamStartPayload.java)**
  — `CustomPacketPayload` client→server, sent once per beam at eruption; `handle` just forwards to
  `SunBeamManager.startBeam`.
- **server/[SunBeamManager.java](src/main/java/dev/solomon/solomon/server/SunBeamManager.java)** —
  server-authoritative damage schedule: validates the start (holding spear, distance sane), then on
  `ServerTickEvent.Post` runs each active beam's pulses itself. Owns the damage constants
  (`TOTAL_DAMAGE`, `DAMAGE_PULSES`, `PULSE_INTERVAL_TICKS`, `DAMAGE_PER_PULSE`) and the column
  geometry (`RADIUS`/`BOTTOM`/`HEIGHT`). Clears all beams on `ServerStoppedEvent`.
- **network/[SunDragonAttackPayload.java](src/main/java/dev/solomon/solomon/network/SunDragonAttackPayload.java)**
  — `CustomPacketPayload` client→server for the dragon attack (client `SUN_DRAGON_KEY`, default G,
  raycasts a target like the beam). Server validates (holding spear, distance sane) and spawns a
  `SunDragon` at the caster's eyes in helix-attack mode. No damage yet — purely visual.
- **server/[SunBeamDamageSource.java](src/main/java/dev/solomon/solomon/server/SunBeamDamageSource.java)**
  — `DamageSource` subclass; overrides `getLocalizedDeathMessage` to pick one of three random death
  messages (`death.attack.sunrip.1..3`; `%1$s`=victim, `%2$s`=caster).
- **mixin/[LivingEntityMixin.java](src/main/java/dev/solomon/solomon/mixin/LivingEntityMixin.java)**
  (common) — injects head of `playHurtSound`; for **non-players only**, throttles the sunrip hurt
  sound to once per `SUNRIP_HURT_SOUND_INTERVAL` (10) ticks. Players keep the per-tick sound.
- **mixin/client/[ItemRendererMixin.java](src/main/java/dev/solomon/solomon/mixin/client/ItemRendererMixin.java)**
  & **[ModelBakeryMixin.java](src/main/java/dev/solomon/solomon/mixin/client/ModelBakeryMixin.java)** —
  replicate Barched's flat-sprite ↔ in-hand-model swap and explicit in-hand model load, for our item
  (see Barched gotcha above). Priority 900.
- **mixin/client/[LevelRendererMixin.java](src/main/java/dev/solomon/solomon/mixin/client/LevelRendererMixin.java)** —
  injects at `popPush("translucent")` in `renderLevel` (both Fabulous and normal branches) and calls
  `SolomonClient.captureSunlightDepth()` — the only spot where all opaque depth (incl. Iris-batched
  entities) is flushed but water hasn't rendered.

## Sun beam feature flow (how damage works)
1. Client keybind → `SunBeamEffect` spawns, plays `sunrip.ogg`, renders the pillar.
2. At eruption (`now >= GROW_START`, ~0.6s) the effect sends **one** `SunBeamStartPayload`.
   Cancelling during targeting means it never erupts, so it never sends.
3. Server `SunBeamManager` validates the start (holding spear, position sane), then runs the whole
   schedule itself on `ServerTickEvent.Post`: **one pulse per game tick** (`PULSE_INTERVAL_TICKS = 1`,
   `DAMAGE_PULSES = 146` ≈ the ~7.3s erupted window), each pulse damaging every `LivingEntity` inside
   the column (`RADIUS = 3`) for `DAMAGE_PER_PULSE = 150/146`. Full presence ⇒ exactly **150** total.
   Each pulse re-validates the caster (online, same dimension, holding spear, within range); a failed
   check skips that pulse but the schedule keeps running (re-equipping the spear resumes damage);
   a disconnected caster cancels the beam.
4. The `solomon:sunrip` damage type ([data/solomon/damage_type/sunrip.json](src/main/resources/data/solomon/damage_type/sunrip.json))
   is tagged into three vanilla damage-type tags:
   - `bypasses_cooldown` → **ignores hurt-immunity i-frames**, so it can tick every game tick smoothly.
   - `bypasses_armor` → preserves the old indirect-magic armor-ignoring behavior.
   - `no_knockback` → so per-tick hits don't fling targets out of the fixed column.

**Authority split**: the client owns the *cosmetics and the trigger* (beam position via raycast,
eruption timing, visuals, sound); the server owns *all damage* (cadence, amount, duration,
per-pulse validation). A modified client can therefore only choose where/when to start a beam —
never how much damage it deals. The dragon attack follows the same pattern (client raycasts,
server spawns and flies the entity).

### Tuning knobs
- Damage total / cadence: `TOTAL_DAMAGE`, `DAMAGE_PULSES`, `PULSE_INTERVAL_TICKS` in
  `SunBeamManager` (keep `DAMAGE_PULSES ≈ windowTicks / PULSE_INTERVAL_TICKS` so the total stays
  `TOTAL_DAMAGE`).
- Column size: `RADIUS`/`BOTTOM`/`HEIGHT` in `SunBeamManager` (must match the visuals in `SunBeamEffect`).
- Non-player hurt-sound rate: `SUNRIP_HURT_SOUND_INTERVAL` in `LivingEntityMixin`.
- Death messages: `death.attack.sunrip.1..3` in [en_us.json](src/main/resources/assets/solomon/lang/en_us.json)
  (change the count → also update `MESSAGE_VARIANTS` in `SunBeamDamageSource`).

## Resources (`src/main/resources/`)
- `assets/solomon/` — `lang/en_us.json`, item models (`sun_spear`, `sun_spear_in_hand`,
  `sun_dragon_spawn_egg`), textures (incl. procedurally generated
  `textures/entity/sun_dragon.png` gold-scale skin), `sounds.json` + `sounds/sunrip.ogg` +
  `sounds/sundragonlaunch.ogg` (played server-side at the caster on dragon launch) +
  `sounds/ambientsundrag.ogg` (quiet loop following every dragon: client-side
  `SunDragonAmbientSound`, started by SolomonClient on `EntityJoinLevelEvent`, stops itself when
  the dragon is removed).
- `data/solomon/damage_type/sunrip.json` — the custom damage type.
- `data/minecraft/tags/damage_type/{bypasses_cooldown,bypasses_armor,no_knockback}.json` — merge
  `solomon:sunrip` into vanilla tags (`replace:false`).
- `data/minecraft/tags/item/spears.json` — adds `sun_spear` to Barched's `minecraft:spears` tag.
- `data/{minecraft,c}/tags/item/...` — misc item tags (piglin_loved, melee weapons).
- `solomon.mixins.json` — mixin config: `client` = the two model mixins + `LevelRendererMixin`
  (pre-water depth capture for the sunlight effects); `mixins` = `LivingEntityMixin`.
