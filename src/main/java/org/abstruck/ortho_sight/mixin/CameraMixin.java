package org.abstruck.ortho_sight.mixin;

import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.abstruck.ortho_sight.client.OrthoClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Places the camera on the fixed 42 degree orbit around the player and locks its rotation, so mouse
 * movement turns the player (vanilla behaviour) but never the camera. Left/right camera rotation is done
 * with Z / C instead (see {@link OrthoClient}).
 */
@Mixin(value = Camera.class, remap = false)
public abstract class CameraMixin {

    @Shadow(remap = false)
    protected abstract void setPosition(double x, double y, double z);

    @Shadow(remap = false)
    protected abstract void setRotation(float yaw, float pitch);

    @Inject(method = "setup", at = @At("RETURN"), remap = false)
    private void orthosight$orthographicCamera(net.minecraft.world.level.BlockGetter level, Entity entity,
                                               boolean detached, boolean mirrored, float partialTick,
                                               CallbackInfo ci) {
        // Deliberately not gated on `detached`: if anything ever forces a first person camera type while
        // the orthographic view is selected we still want a valid, centred camera instead of a broken view.
        if (!OrthoClient.active) {
            return;
        }
        Vec3 origin = OrthoClient.originOf(entity, partialTick);
        Vec3 eye = OrthoClient.placeCamera(origin, level, partialTick);
        setPosition(eye.x, eye.y, eye.z);
        setRotation(OrthoClient.orbit, OrthoClient.PITCH);
    }
}
