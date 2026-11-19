package org.abstruck.ortho_sight.mixin;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;
import org.abstruck.ortho_sight.client.OrthoClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Last line of defence for "you can only touch what you can see".
 *
 * <p>{@link OrthoClient} already filters the cursor pick, but the pick is a snapshot and other code paths can
 * hand a block position to the game mode directly. Refusing the action here makes it impossible to mine or
 * use a block that is hidden by the occlusion rule, or whose surface towards the camera was never meshed.
 *
 * <p>Signatures must match {@code MultiPlayerGameMode} exactly - {@code useItemOn} takes a
 * {@code LocalPlayer}, not a {@code Player}.
 */
@Mixin(value = MultiPlayerGameMode.class, remap = false)
public class GameModeMixin {

    @Inject(method = "startDestroyBlock", at = @At("HEAD"), cancellable = true, remap = false)
    private void orthosight$startDestroyBlock(BlockPos pos, Direction face, CallbackInfoReturnable<Boolean> cir) {
        if (!OrthoClient.isBlockUsable(pos, face)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "continueDestroyBlock", at = @At("HEAD"), cancellable = true, remap = false)
    private void orthosight$continueDestroyBlock(BlockPos pos, Direction face,
            CallbackInfoReturnable<Boolean> cir) {
        if (!OrthoClient.isBlockUsable(pos, face)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true, remap = false)
    private void orthosight$useItemOn(LocalPlayer player, InteractionHand hand, BlockHitResult hit,
            CallbackInfoReturnable<InteractionResult> cir) {
        if (!OrthoClient.isBlockUsable(hit.getBlockPos(), hit.getDirection())) {
            cir.setReturnValue(InteractionResult.CONSUME);
        }
    }
}
