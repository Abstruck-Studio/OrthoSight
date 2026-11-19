package org.abstruck.ortho_sight.mixin;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import com.mojang.blaze3d.vertex.PoseStack;
import org.abstruck.ortho_sight.client.OrthoClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hides block entities (chests, signs, furnaces, ...) that sit in the same slab as the hidden blocks, so a
 * chest embedded in a ceiling does not float in the open after the ceiling around it disappeared.
 */
@Mixin(value = BlockEntityRenderDispatcher.class, remap = false)
public class BlockEntityRenderMixin {

    @Inject(method = "render(Lnet/minecraft/world/level/block/entity/BlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;)V",
            at = @At("HEAD"), cancellable = true, remap = false)
    private <E extends BlockEntity> void orthosight$hideBlockEntity(E blockEntity, float partialTick, PoseStack pose,
                                                                   MultiBufferSource buffer, CallbackInfo ci) {
        BlockPos pos = blockEntity.getBlockPos();
        if (OrthoClient.isHiddenBlockEntity(Vec3.atCenterOf(pos), partialTick)) {
            ci.cancel();
        }
    }
}
