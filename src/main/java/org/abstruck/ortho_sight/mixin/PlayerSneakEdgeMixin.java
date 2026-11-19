package org.abstruck.ortho_sight.mixin;

import net.minecraft.world.entity.player.Player;
import org.abstruck.ortho_sight.client.OrthoClient;
import org.abstruck.ortho_sight.illusion.IllusionSupport;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Suspends vanilla's sneak edge guard only while a step of the illusion is pending.
 *
 * <p>{@code Player#maybeBackOffFromEdge} clamps the movement delta whenever {@code isStayingOnGroundSurface()}
 * is true, i.e. while sneaking, which stops the player a few centimetres before every ledge. Crossing needs
 * that last centimetre: the illusion engages when the player is no longer supported, and its search window is
 * much wider then (it can reach surfaces both above and below). The guard is only lifted while
 * {@link IllusionSupport#armed()}, which requires that the next cell genuinely has no normal ground but does
 * have an illusion surface - so crouch-hovering at an ordinary ledge is untouched.
 *
 * <p>Full build only: the client-only release ships no gameplay code at all, so this file and its mixin
 * config are excluded there (see build.gradle and ortho_sight_full.mixins.json).
 */
@Mixin(value = Player.class, remap = false)
public class PlayerSneakEdgeMixin {

    @Inject(method = "isStayingOnGroundSurface", at = @At("HEAD"), cancellable = true, remap = false)
    private void orthosight$suspendSneakGuard(CallbackInfoReturnable<Boolean> cir) {
        if (OrthoClient.active && IllusionSupport.armed()) {
            cir.setReturnValue(false);
        }
    }
}