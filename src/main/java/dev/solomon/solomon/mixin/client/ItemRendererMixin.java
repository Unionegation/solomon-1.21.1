package dev.solomon.solomon.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.solomon.solomon.Solomon;
import dev.solomon.solomon.SolomonClient;
import net.minecraft.client.renderer.ItemModelShaper;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Mirrors BarchedExtraSpears' ItemRendererMixin: Barched hardcodes the sprite/in-hand model swap
// to its own spears, so spears added by other mods have to do the same swap themselves.
@Mixin(value = ItemRenderer.class, priority = 900)
public class ItemRendererMixin {

    @Shadow @Final private ItemModelShaper itemModelShaper;

    @Unique private ItemDisplayContext solomon$itemDisplayContext;
    @Unique private ItemStack solomon$itemStack;

    @Inject(method = "render", at = @At("HEAD"))
    private void solomon$captureSignature(ItemStack itemStack, ItemDisplayContext itemDisplayContext, boolean bl, PoseStack poseStack, MultiBufferSource multiBufferSource, int i, int j, BakedModel bakedModel, CallbackInfo ci) {
        this.solomon$itemStack = itemStack;
        this.solomon$itemDisplayContext = itemDisplayContext;
    }

    @ModifyVariable(method = "render", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;pushPose()V", shift = At.Shift.AFTER, ordinal = 0), argsOnly = true, index = 8)
    private BakedModel solomon$render(BakedModel value) {
        boolean bl2 = solomon$itemDisplayContext == ItemDisplayContext.GUI || solomon$itemDisplayContext == ItemDisplayContext.GROUND || solomon$itemDisplayContext == ItemDisplayContext.FIXED;
        if (bl2 && solomon$itemStack.is(Solomon.SUN_SPEAR.get())) {
            return this.itemModelShaper.getModelManager().getModel(SolomonClient.SUN_SPEAR_MODEL);
        }
        return value;
    }

    @WrapOperation(method = "getModel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/ItemModelShaper;getItemModel(Lnet/minecraft/world/item/ItemStack;)Lnet/minecraft/client/resources/model/BakedModel;"))
    private BakedModel solomon$getModel(ItemModelShaper instance, ItemStack itemStack, Operation<BakedModel> original) {
        if (itemStack.is(Solomon.SUN_SPEAR.get())) {
            return this.itemModelShaper.getModelManager().getModel(SolomonClient.SUN_SPEAR_IN_HAND_MODEL);
        }
        return original.call(instance, itemStack);
    }
}
