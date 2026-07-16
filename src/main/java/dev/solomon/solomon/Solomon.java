package dev.solomon.solomon;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
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
import net.neoforged.neoforge.common.SimpleTier;
import dev.solomon.solomon.network.SunBeamDamagePayload;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
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

    public static final DeferredHolder<SoundEvent, SoundEvent> SUNRIP_SOUND = SOUND_EVENTS.register("sunrip",
            () -> SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(MODID, "sunrip")));

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
        modEventBus.addListener(this::addCreative);
        modEventBus.addListener(this::registerPayloads);
    }

    private void registerPayloads(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToServer(SunBeamDamagePayload.TYPE, SunBeamDamagePayload.STREAM_CODEC, SunBeamDamagePayload::handle);
    }

    private void addCreative(BuildCreativeModeTabContentsEvent event) {
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
