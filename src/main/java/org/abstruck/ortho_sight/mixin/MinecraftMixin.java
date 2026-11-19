package org.abstruck.ortho_sight.mixin;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.abstruck.ortho_sight.client.OrthoClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Takes over the F5 perspective key: instead of cycling the three vanilla camera types we cycle four views,
 * the last one being the orthographic view.
 *
 * <p>The very first {@code KeyMapping.consumeClick()} call in {@code Minecraft#handleKeybinds} is the
 * {@code keyTogglePerspective} one (verified against the 1.21.1 sources), hence {@code ordinal = 0}.
 */
@Mixin(value = Minecraft.class, remap = false)
public abstract class MinecraftMixin {

    @Redirect(method = "handleKeybinds",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/KeyMapping;consumeClick()Z", ordinal = 0),
            remap = false)
    private boolean orthosight$cyclePerspective(KeyMapping mapping) {
        boolean clicked = mapping.consumeClick();
        Minecraft mc = Minecraft.getInstance();
        if (mapping == mc.options.keyTogglePerspective) {
            if (clicked) {
                OrthoClient.cycleView();
            }
            // Swallow the click: we handled the view change ourselves.
            return false;
        }
        return clicked;
    }
}
