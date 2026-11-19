package org.abstruck.ortho_sight.mixin;

import net.minecraft.client.renderer.culling.Frustum;
import org.abstruck.ortho_sight.client.OrthoClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Two fixes for the orthographic view, both about the culling frustum.
 *
 * <h2>1. The freeze</h2>
 * Vanilla {@code Frustum.offsetToFullyIncludeCameraCube} walks the frustum backwards along the view
 * direction until the camera's own 8x8x8 cube is <b>fully inside</b> the frustum:
 *
 * <pre>
 * for (double d5 = ...; this.intersection.intersectAab(...) != -2; this.camZ -= viewVector.z * 4) { ... }
 * </pre>
 *
 * That terminates for a perspective projection but never for an orthographic one: the ortho frustum is only
 * {@code zoom} blocks tall while the camera cube's world-axis-aligned AABB reaches far above the camera, so
 * "completely inside" is unreachable, and each step only moves the camera about 0.0156 blocks. Measured with
 * real joml + the vanilla class: 2,000,000 iterations and 31,253 blocks of drift without ever terminating,
 * and 35 of 48 camera orientations hang. Returning the frustum unchanged is therefore correct and necessary.
 *
 * <h2>2. Stale visible sections</h2>
 * {@code LevelRenderer#setupRender} only rebuilds its visible-section set when the <b>camera yaw</b> changes
 * by two degrees or an async occlusion-graph update lands. This camera's yaw is pinned to {@code orbit}, so
 * walking never refreshes that set (only crossing an eight block camera cell does) - while pressing Z/C
 * refreshes it instantly. That is exactly the reported "geometry only appears when I rotate the camera".
 * Skipping section culling while the ortho view is active makes every rebuild include everything inside the
 * render distance, so the staleness can no longer hide geometry.
 */
@Mixin(value = Frustum.class, remap = false)
public class FrustumMixin {


    @Inject(method = "offsetToFullyIncludeCameraCube", at = @At("HEAD"), cancellable = true, remap = false)
    private void orthosight$skipOffsetForOrthographicView(int step, CallbackInfoReturnable<Frustum> cir) {
        if (OrthoClient.active) {
            cir.setReturnValue((Frustum) (Object) this);
        }
    }
}
