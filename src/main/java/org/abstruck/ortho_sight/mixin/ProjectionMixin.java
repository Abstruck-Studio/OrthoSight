package org.abstruck.ortho_sight.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.abstruck.ortho_sight.client.OrthoClient;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Swaps the perspective projection for an orthographic one while the orthographic view is selected.
 *
 * <p>{@code halfH = zoom / 2} exactly, so the visible world height is {@code zoom} blocks and the mouse
 * wheel controls the picture size.
 */
@Mixin(value = GameRenderer.class, remap = false)
public class ProjectionMixin {

    @Inject(method = "getProjectionMatrix", at = @At("RETURN"), cancellable = true, remap = false)
    private void orthosight$orthographicProjection(double fov, CallbackInfoReturnable<Matrix4f> cir) {
        if (!OrthoClient.active) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        float aspect = (float) mc.getWindow().getWidth() / Math.max(1, mc.getWindow().getHeight());
        float halfH = OrthoClient.effectiveHalfHeight();
        float halfW = halfH * aspect;
        // Never look deeper than the loaded / fogged world: a 512 block slab reaches past both, and the outer
        // part of it renders as un-loaded background - a hard band that looks like more black.
        float far = Math.min(OrthoClient.FAR, mc.options.getEffectiveRenderDistance() * 16.0F);
        cir.setReturnValue(new Matrix4f().setOrtho(-halfW, halfW, -halfH, halfH, OrthoClient.NEAR, far));
    }
}
