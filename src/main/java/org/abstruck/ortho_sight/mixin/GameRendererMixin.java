package org.abstruck.ortho_sight.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.GameRenderer;
import org.abstruck.ortho_sight.client.OrthoClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps the player exactly in the middle of the screen in the orthographic view.
 *
 * <p>In 1.21.1 the walk/hurt "bob" transforms are multiplied into the <b>projection</b> matrix
 * ({@code GameRenderer.renderLevel}: {@code matrix4f.mul(posestack.last().pose())}), so they translate the
 * whole rendered image. Combined with a fixed ortho camera that makes the player wobble away from the
 * screen centre every step, which the orthographic view must not do.
 */
@Mixin(value = GameRenderer.class, remap = false)
public class GameRendererMixin {

    @Inject(method = "bobView", at = @At("HEAD"), cancellable = true, remap = false)
    private void orthosight$cancelViewBob(PoseStack pose, float partialTick, CallbackInfo ci) {
        if (OrthoClient.active) {
            ci.cancel();
        }
    }

    @Inject(method = "bobHurt", at = @At("HEAD"), cancellable = true, remap = false)
    private void orthosight$cancelHurtBob(PoseStack pose, float partialTick, CallbackInfo ci) {
        if (OrthoClient.active) {
            ci.cancel();
        }
    }

    // The cursor pick runs from OrthoClient#onRenderStage (Stage.AFTER_SKY) instead: GameRenderer#pick is
    // called BEFORE Camera#setup, so hooking it here would always use the previous frame's camera.
}
