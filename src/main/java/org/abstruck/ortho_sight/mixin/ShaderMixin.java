package org.abstruck.ortho_sight.mixin;

import com.mojang.blaze3d.shaders.Uniform;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.world.phys.Vec3;
import org.abstruck.ortho_sight.OrthoConfig;
import org.abstruck.ortho_sight.client.OrthoClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Feeds the occlusion volume into the block shaders.
 *
 * <p>{@code apply()} binds the program before uploading uniforms, so setting the values here (at HEAD) is
 * safe: the upload happens later in the same call, on the right program.
 *
 * <p>Everything is expressed in camera relative <b>world</b> space, because the fragment shader snaps each
 * fragment to its block centre first (whole blocks disappear, never half of one).
 *
 * <ul>
 *   <li>{@code OrthoSightCone} = (enabled, player feet height relative to the camera, minimum height above
 *       those feet, player-to-camera distance)</li>
 *   <li>{@code OrthoSightConeOrigin} = (player centre relative to the camera, box radius)</li>
 *   <li>{@code OrthoSightConeAxis} = (unit vector player -> camera, 0)</li>
 * </ul>
 */
@Mixin(value = ShaderInstance.class, remap = false)
public abstract class ShaderMixin {

    @Shadow(remap = false)
    public abstract Uniform getUniform(String name);

    @Inject(method = "apply", at = @At("HEAD"), remap = false)
    private void orthosight$updateOcclusionUniforms(CallbackInfo ci) {
        Uniform slab = getUniform("OrthoSightCone");
        if (slab == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            slab.set(0.0F, 0.0F, 0.0F, 0.0F);
            return;
        }
        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 cameraPos = camera.getPosition();
        // The block-grid snap is needed even when the occlusion rule is off: the shaders use it for the whole
        // block near/far culling, which must keep working independently of the hiding toggle.
        Uniform cell = getUniform("OrthoSightConeCell");
        if (cell != null) {
            cell.set((float) (cameraPos.x - Math.floor(cameraPos.x)),
                    (float) (cameraPos.y - Math.floor(cameraPos.y)),
                    (float) (cameraPos.z - Math.floor(cameraPos.z)),
                    OrthoClient.hideActive()
                            ? (OrthoConfig.debugShowHiddenRegion() ? 1.0F
                                    : (OrthoConfig.ghostHiddenBlocks() ? 0.0F : 2.0F))
                            : 2.0F);
        }
        if (!OrthoClient.hideActive()) {
            slab.set(0.0F, 0.0F, 0.0F, 0.0F);
            return;
        }

        Vec3 playerCentre = OrthoClient.originOf(mc.player, camera.getPartialTickTime()).subtract(cameraPos);
        Vec3 axis = OrthoClient.axis();

        // Interpolated feet, matching the interpolated player centre above. Using the raw tick value here made
        // the cone floor jump by more than half a block at every tick boundary, which pops a ring of blocks
        // in and out of the hidden set (and made the illusion step look like a jump).
        double interpolatedFeetY = OrthoClient.originOf(mc.player, camera.getPartialTickTime()).y
                - mc.player.getBbHeight() * 0.5D;
        slab.set(1.0F,
                (float) (interpolatedFeetY - cameraPos.y),
                (float) OrthoConfig.minHeightAboveFeet(),
                OrthoClient.hideFarBound());

        Uniform origin = getUniform("OrthoSightConeOrigin");
        if (origin != null) {
            origin.set((float) playerCentre.x, (float) playerCentre.y, (float) playerCentre.z,
                    (float) OrthoConfig.boxRadius());
        }
        Uniform direction = getUniform("OrthoSightConeAxis");
        if (direction != null) {
            direction.set((float) axis.x, (float) axis.y, (float) axis.z, (float) OrthoConfig.nearOffset());
        }
    }
}
