package dev.solomon.solomon.client;

import dev.solomon.solomon.Solomon;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.resources.ResourceLocation;

/**
 * Bakes the sun dragon's head and one reusable body-segment part. Unlike vanilla entity models
 * these boxes are authored +Y-up / +Z-forward, because {@code SunDragonRenderer} renders the parts
 * directly (once per segment) without the usual upside-down LivingEntityRenderer flip.
 */
public class SunDragonModel {
    public static final ModelLayerLocation LAYER = new ModelLayerLocation(
            ResourceLocation.fromNamespaceAndPath(Solomon.MODID, "sun_dragon"), "main");

    public final ModelPart head;
    public final ModelPart body;

    public SunDragonModel(ModelPart root) {
        this.head = root.getChild("head");
        this.body = root.getChild("body");
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();

        PartDefinition head = root.addOrReplaceChild("head", CubeListBuilder.create()
                .texOffs(0, 0).addBox(-3.5F, -3.0F, -4.0F, 7.0F, 6.0F, 8.0F)
                .texOffs(0, 14).addBox(-2.5F, -2.5F, 4.0F, 5.0F, 4.0F, 5.0F)
                .texOffs(30, 0).addBox(-1.0F, 3.0F, -3.0F, 2.0F, 2.0F, 5.0F),
                PartPose.ZERO);

        head.addOrReplaceChild("horn_right", CubeListBuilder.create()
                .texOffs(44, 0).addBox(-0.5F, -0.5F, -6.0F, 1.0F, 1.0F, 6.0F),
                PartPose.offsetAndRotation(2.5F, 2.5F, -3.5F, 0.55F, -0.3F, 0.0F));
        head.addOrReplaceChild("horn_left", CubeListBuilder.create()
                .texOffs(44, 0).addBox(-0.5F, -0.5F, -6.0F, 1.0F, 1.0F, 6.0F),
                PartPose.offsetAndRotation(-2.5F, 2.5F, -3.5F, 0.55F, 0.3F, 0.0F));
        head.addOrReplaceChild("whisker_right", CubeListBuilder.create()
                .texOffs(44, 8).addBox(-0.25F, -0.25F, 0.0F, 0.5F, 0.5F, 5.0F),
                PartPose.offsetAndRotation(2.0F, -0.5F, 8.5F, 0.0F, 0.9F, 0.0F));
        head.addOrReplaceChild("whisker_left", CubeListBuilder.create()
                .texOffs(44, 8).addBox(-0.25F, -0.25F, 0.0F, 0.5F, 0.5F, 5.0F),
                PartPose.offsetAndRotation(-2.0F, -0.5F, 8.5F, 0.0F, -0.9F, 0.0F));

        root.addOrReplaceChild("body", CubeListBuilder.create()
                .texOffs(0, 32).addBox(-3.5F, -3.5F, -3.5F, 7.0F, 7.0F, 7.0F)
                .texOffs(28, 32).addBox(-0.5F, 3.5F, -2.5F, 1.0F, 3.0F, 5.0F),
                PartPose.ZERO);

        return LayerDefinition.create(mesh, 64, 64);
    }
}
