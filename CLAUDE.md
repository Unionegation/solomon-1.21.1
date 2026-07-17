# Solomon — project guide

A small **Minecraft 1.21.1 / NeoForge** mod. Adds one item, the **Sun Spear**, a Barched-based
spear that, on a keybind, summons a pillar of sunlight ("sunrip") that deals sustained damage in a
column. That sun-beam ability is essentially the whole mod.

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
  payload, and the creative-tab insertion. Holds the spear `Tier`/attributes.
- **[SolomonClient.java](src/main/java/dev/solomon/solomon/SolomonClient.java)** — `@Mod(dist=CLIENT)`.
  Owns the `SUN_BEAM_KEY` keybind, the list of `activeBeams`, client tick + render hooks, and the
  in-hand/inventory `ModelResourceLocation`s. Pressing the key raycasts (range 64) and spawns a beam;
  releasing during targeting cancels it.
- **client/[SunBeamEffect.java](src/main/java/dev/solomon/solomon/client/SunBeamEffect.java)** — one
  beam instance: sound-driven envelope (attack/sustain/fade timed to `sunrip.ogg`, 7.931s), the
  `dragonRays` shell rendering, and **the damage-pulse sender** (see flow below).
- **network/[SunBeamDamagePayload.java](src/main/java/dev/solomon/solomon/network/SunBeamDamagePayload.java)**
  — `CustomPacketPayload` client→server. Server-side `handle` damages living entities in the beam
  column. Owns damage constants (`TOTAL_DAMAGE`, `DAMAGE_PULSES`, `PULSE_INTERVAL_TICKS`,
  `DAMAGE_PER_PULSE`) and the column geometry (`RADIUS`/`BOTTOM`/`HEIGHT`).
- **network/[SunBeamDamageSource.java](src/main/java/dev/solomon/solomon/network/SunBeamDamageSource.java)**
  — `DamageSource` subclass; overrides `getLocalizedDeathMessage` to pick one of three random death
  messages (`death.attack.sunrip.1..3`; `%1$s`=victim, `%2$s`=caster).
- **mixin/[LivingEntityMixin.java](src/main/java/dev/solomon/solomon/mixin/LivingEntityMixin.java)**
  (common) — injects head of `playHurtSound`; for **non-players only**, throttles the sunrip hurt
  sound to once per `SUNRIP_HURT_SOUND_INTERVAL` (10) ticks. Players keep the per-tick sound.
- **mixin/client/[ItemRendererMixin.java](src/main/java/dev/solomon/solomon/mixin/client/ItemRendererMixin.java)**
  & **[ModelBakeryMixin.java](src/main/java/dev/solomon/solomon/mixin/client/ModelBakeryMixin.java)** —
  replicate Barched's flat-sprite ↔ in-hand-model swap and explicit in-hand model load, for our item
  (see Barched gotcha above). Priority 900.

## Sun beam feature flow (how damage works)
1. Client keybind → `SunBeamEffect` spawns, plays `sunrip.ogg`, renders the pillar.
2. At eruption (`now >= GROW_START`, ~0.6s) the effect begins firing `SunBeamDamagePayload` — **one
   pulse per game tick** (`PULSE_INTERVAL_TICKS = 1`, `DAMAGE_PULSES = 146` ≈ the ~7.3s erupted
   window). Cancelling during targeting means it never erupts, so it never pulses.
3. Server `handle` validates (holding spear, position sane), then damages each `LivingEntity` inside
   the column (`RADIUS = 3`) for `DAMAGE_PER_PULSE = 50/146`. Full presence ⇒ exactly **50** total.
4. The `solomon:sunrip` damage type ([data/solomon/damage_type/sunrip.json](src/main/resources/data/solomon/damage_type/sunrip.json))
   is tagged into three vanilla damage-type tags:
   - `bypasses_cooldown` → **ignores hurt-immunity i-frames**, so it can tick every game tick smoothly.
   - `bypasses_armor` → preserves the old indirect-magic armor-ignoring behavior.
   - `no_knockback` → so per-tick hits don't fling targets out of the fixed column.

**Design is deliberately client-authoritative** ("the client is the commit point"): the client owns
beam position, timing, eruption, and pulse cadence; the server only validates and applies damage.
Delivery is reliable, so full-duration presence gives exactly 50.

### Tuning knobs
- Damage total / cadence: `TOTAL_DAMAGE`, `DAMAGE_PULSES`, `PULSE_INTERVAL_TICKS` in
  `SunBeamDamagePayload` (keep `DAMAGE_PULSES ≈ windowTicks / PULSE_INTERVAL_TICKS` so the total stays 50).
- Column size: `RADIUS`/`BOTTOM`/`HEIGHT` in the payload (must match the visuals in `SunBeamEffect`).
- Non-player hurt-sound rate: `SUNRIP_HURT_SOUND_INTERVAL` in `LivingEntityMixin`.
- Death messages: `death.attack.sunrip.1..3` in [en_us.json](src/main/resources/assets/solomon/lang/en_us.json)
  (change the count → also update `MESSAGE_VARIANTS` in `SunBeamDamageSource`).

## Resources (`src/main/resources/`)
- `assets/solomon/` — `lang/en_us.json`, item models (`sun_spear`, `sun_spear_in_hand`), textures,
  `sounds.json` + `sounds/sunrip.ogg`.
- `data/solomon/damage_type/sunrip.json` — the custom damage type.
- `data/minecraft/tags/damage_type/{bypasses_cooldown,bypasses_armor,no_knockback}.json` — merge
  `solomon:sunrip` into vanilla tags (`replace:false`).
- `data/minecraft/tags/item/spears.json` — adds `sun_spear` to Barched's `minecraft:spears` tag.
- `data/{minecraft,c}/tags/item/...` — misc item tags (piglin_loved, melee weapons).
- `solomon.mixins.json` — mixin config: `client` = the two model mixins; `mixins` = `LivingEntityMixin`.
```
