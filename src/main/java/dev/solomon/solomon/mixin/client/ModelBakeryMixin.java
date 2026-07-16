package dev.solomon.solomon.mixin.client;

import dev.solomon.solomon.SolomonClient;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.util.profiling.ProfilerFiller;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

// Mirrors BarchedExtraSpears' ModelBakeryMixin: the in-hand model has no registered item id,
// so it is never loaded automatically and must be requested explicitly.
@Mixin(value = ModelBakery.class, priority = 900)
public abstract class ModelBakeryMixin {

    @Shadow protected abstract void loadSpecialItemModelAndDependencies(ModelResourceLocation modelResourceLocation);

    @Inject(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/profiling/ProfilerFiller;popPush(Ljava/lang/String;)V", ordinal = 1))
    private void solomon$init(BlockColors blockColors, ProfilerFiller profilerFiller, Map map, Map map2, CallbackInfo ci) {
        this.loadSpecialItemModelAndDependencies(SolomonClient.SUN_SPEAR_IN_HAND_MODEL);
    }
}
