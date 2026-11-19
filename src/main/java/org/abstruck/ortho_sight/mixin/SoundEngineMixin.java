package org.abstruck.ortho_sight.mixin;

import com.mojang.blaze3d.audio.ListenerTransform;
import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.world.phys.Vec3;
import org.abstruck.ortho_sight.client.OrthoClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Keeps audio where the player is instead of where the orthographic camera is.
 *
 * <p>Vanilla builds the sound listener transform from the main camera. In the orthographic view that camera
 * sits 14-28 blocks above and behind the player, so everything the player does (mining, footsteps, placing
 * blocks) is attenuated by that distance and essentially inaudible. Here the listener keeps the camera's
 * orientation - so left/right still match the screen - but moves to the player's ears, i.e. first person
 * audio.
 */
@Mixin(value = SoundEngine.class, remap = false)
public class SoundEngineMixin {

    @Redirect(method = "updateSource",
            at = @At(value = "NEW",
                    target = "(Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;)Lcom/mojang/blaze3d/audio/ListenerTransform;"),
            remap = false)
    private ListenerTransform orthosight$listenerAtPlayer(Vec3 position, Vec3 look, Vec3 up) {
        if (OrthoClient.active) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                return new ListenerTransform(mc.player.getEyePosition(1.0F), look, up);
            }
        }
        return new ListenerTransform(position, look, up);
    }
}
