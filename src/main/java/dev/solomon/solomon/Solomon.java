package dev.solomon.solomon;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Tier;
import net.minecraft.world.item.crafting.Ingredient;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.neoforge.common.DeferredSpawnEggItem;
import net.neoforged.neoforge.common.SimpleTier;
import dev.solomon.solomon.entity.SunDragon;
import dev.solomon.solomon.network.SunBeamStartPayload;
import dev.solomon.solomon.network.SunDragonAttackPayload;
import dev.solomon.solomon.server.SunBeamManager;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import zzik2.barched.bridge.item.Item$PropertiesBridge;
import zzik2.barched.minecraft.world.item.SpearItem;

@Mod(Solomon.MODID)
public class Solomon {
    public static final String MODID = "solomon";

    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);
    public static final DeferredRegister<SoundEvent> SOUND_EVENTS = DeferredRegister.create(Registries.SOUND_EVENT, MODID);
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES = DeferredRegister.create(Registries.ENTITY_TYPE, MODID);

    // Decorative multi-segment dragon; updateInterval(1) keeps the per-tick position sync smooth
    // since the client rebuilds the body trail from the head's synced positions.
    public static final DeferredHolder<EntityType<?>, EntityType<SunDragon>> SUN_DRAGON = ENTITY_TYPES.register("sun_dragon",
            () -> EntityType.Builder.of(SunDragon::new, MobCategory.CREATURE)
                    .sized(0.44F * SunDragon.MODEL_SCALE, 0.44F * SunDragon.MODEL_SCALE)
                    .eyeHeight(0.22F * SunDragon.MODEL_SCALE)
                    .clientTrackingRange(10)
                    .updateInterval(1)
                    .build("sun_dragon"));

    public static final DeferredItem<DeferredSpawnEggItem> SUN_DRAGON_SPAWN_EGG = ITEMS.register("sun_dragon_spawn_egg",
            () -> new DeferredSpawnEggItem(SUN_DRAGON, 0xF7C94C, 0xC96A1E, new Item.Properties()));

    public static final DeferredHolder<SoundEvent, SoundEvent> SUNRIP_SOUND = SOUND_EVENTS.register("sunrip",
            () -> SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(MODID, "sunrip")));

    public static final DeferredHolder<SoundEvent, SoundEvent> SUN_DRAGON_LAUNCH_SOUND = SOUND_EVENTS.register("sundragonlaunch",
            () -> SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(MODID, "sundragonlaunch")));

    // Looping ambience attached to each sun dragon; started client-side (SolomonClient) when the
    // dragon enters tracking range and stopped by the sound instance itself when it despawns.
    public static final DeferredHolder<SoundEvent, SoundEvent> SUN_DRAGON_AMBIENT_SOUND = SOUND_EVENTS.register("sundragonambient",
            () -> SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(MODID, "sundragonambient")));

    // Custom damage type for the sun beam, defined by the datapack file data/solomon/damage_type/sunrip.json.
    // It replaces the old indirectMagic source so the sunrip can carry its own death messages
    // (see SunBeamDamageSource); it is tagged bypasses_armor to keep the armor-ignoring magic behavior.
    public static final ResourceKey<DamageType> SUNRIP_DAMAGE = ResourceKey.create(Registries.DAMAGE_TYPE,
            ResourceLocation.fromNamespaceAndPath(MODID, "sunrip"));

    // Tier values copied from Paradise Lost's GLAZED_GOLD tool material, with gold ingots standing in
    // for golden amber as the repair item.
    public static final Tier SUN_SPEAR_TIER = new SimpleTier(BlockTags.INCORRECT_FOR_IRON_TOOL,
            131, 12.0F, 2.0F, 22, () -> Ingredient.of(Items.GOLD_INGOT));

    // Spear attribute data copied from BarchedExtraSpears' Glazed Gold spear (same as iron):
    // swingSeconds, kineticDamageMultiplier, delaySeconds, damageCondDurationSeconds, damageCondMinSpeed,
    // knockbackCondDurationSeconds, knockbackCondMinSpeed, dismountCondDurationSeconds, dismountCondMinRelativeSpeed
    public static final DeferredItem<SpearItem> SUN_SPEAR = ITEMS.register("sun_spear", () -> new SpearItem(
            SUN_SPEAR_TIER,
            ((Item$PropertiesBridge) new Item.Properties()).spear(
                    SUN_SPEAR_TIER,
                    0.95F,
                    0.95F,
                    0.60F,
                    2.5F,
                    8.0F,
                    6.75F,
                    5.1F,
                    11.25F,
                    4.6F
            )
    ));

    public Solomon(IEventBus modEventBus, ModContainer modContainer) {
        ITEMS.register(modEventBus);
        SOUND_EVENTS.register(modEventBus);
        ENTITY_TYPES.register(modEventBus);
        modEventBus.addListener(this::addCreative);
        modEventBus.addListener(this::registerPayloads);
        modEventBus.addListener(this::registerAttributes);
        // Game-bus (server-side) hooks: SunBeamManager runs the sun beam's damage schedule.
        NeoForge.EVENT_BUS.addListener(SunBeamManager::onServerTick);
        NeoForge.EVENT_BUS.addListener(SunBeamManager::onServerStopped);
    }

    private void registerAttributes(EntityAttributeCreationEvent event) {
        event.put(SUN_DRAGON.get(), SunDragon.createAttributes().build());
    }

    private void registerPayloads(RegisterPayloadHandlersEvent event) {
        event.registrar("2")
                .playToServer(SunBeamStartPayload.TYPE, SunBeamStartPayload.STREAM_CODEC, SunBeamStartPayload::handle)
                .playToServer(SunDragonAttackPayload.TYPE, SunDragonAttackPayload.STREAM_CODEC, SunDragonAttackPayload::handle);
    }

    private void addCreative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.SPAWN_EGGS) {
            event.accept(SUN_DRAGON_SPAWN_EGG);
        }
        if (event.getTabKey() != CreativeModeTabs.COMBAT) return;

        // Barched adds its spears to the combat tab; slot the sun spear in after the golden spear.
        Item goldenSpear = BuiltInRegistries.ITEM.get(ResourceLocation.withDefaultNamespace("golden_spear"));
        if (goldenSpear != Items.AIR) {
            event.insertAfter(new ItemStack(goldenSpear), new ItemStack(SUN_SPEAR.get()), CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
        } else {
            event.accept(SUN_SPEAR);
        }
    }
}
