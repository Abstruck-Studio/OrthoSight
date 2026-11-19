package org.abstruck.ortho_sight.mixin;

import net.minecraft.client.renderer.SectionOcclusionGraph;
import org.abstruck.ortho_sight.client.OrthoClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Forces the visible-section set to be recomputed every frame while the orthographic view is active.
 *
 * <p>{@code LevelRenderer#setupRender} refills its {@code visibleSections} list - the only source of drawn
 * chunk geometry - in {@code applyFrustum}, and that runs only when this flag is set or when the <b>camera</b>
 * rotation changed by two degrees. This camera's rotation is pinned (45 degrees pitch, yaw locked to
 * {@code orbit}), so the rotation gate never fires: walking leaves the drawn section set frozen in world
 * space until the camera crosses an eight block cell, at which point it is recomputed abruptly. That is a
 * whole-screen change from one step to the next - entire regions of the world appear or vanish while nothing
 * about the world changed.
 */
@Mixin(value = SectionOcclusionGraph.class, remap = false)
public class SectionOcclusionGraphMixin {

    @Inject(method = "consumeFrustumUpdate", at = @At("RETURN"), cancellable = true, remap = false)
    private void orthosight$recomputeEveryFrame(CallbackInfoReturnable<Boolean> cir) {
        if (OrthoClient.active) {
            cir.setReturnValue(true);
        }
    }
}
