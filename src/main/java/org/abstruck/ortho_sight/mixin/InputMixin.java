package org.abstruck.ortho_sight.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.client.player.LocalPlayer;
import org.abstruck.ortho_sight.client.OrthoClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Remaps WASD from "player relative" to "what you see on screen".
 *
 * <p>Vanilla turns {@code (leftImpulse, forwardImpulse)} into a world direction with the player's own yaw
 * ({@code Entity.getInputVector}). In the orthographic view the camera has its own yaw ({@code orbit}), so we
 * pre-rotate the raw input by {@code orbit - playerYaw}; vanilla then applies the player yaw and the result
 * is exactly camera-relative movement: W is screen-up, A is screen-left, and so on.
 */
@Mixin(value = KeyboardInput.class, remap = false)
public class InputMixin {

    @Inject(method = "tick", at = @At("RETURN"), remap = false)
    private void orthosight$screenRelativeMovement(boolean slowDown, float factor, CallbackInfo ci) {
        if (!OrthoClient.active) {
            return;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        KeyboardInput self = (KeyboardInput) (Object) this;
        float strafe = self.leftImpulse;
        float forward = self.forwardImpulse;
        if (strafe == 0.0F && forward == 0.0F) {
            return;
        }
        double angle = Math.toRadians(OrthoClient.orbit - player.getYRot());
        float cos = (float) Math.cos(angle);
        float sin = (float) Math.sin(angle);
        self.leftImpulse = strafe * cos - forward * sin;
        self.forwardImpulse = forward * cos + strafe * sin;
    }
}
