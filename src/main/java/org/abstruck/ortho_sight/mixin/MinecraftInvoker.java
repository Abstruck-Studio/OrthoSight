package org.abstruck.ortho_sight.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Gives the mod access to the vanilla click handlers.
 *
 * <p>In the orthographic view the mouse is deliberately <b>not</b> grabbed (the pointer is used to click
 * things on screen), and vanilla gates its own click handling on {@code mouseHandler.isMouseGrabbed()}.
 * So the mod drives these directly, using the hit result it computes from the cursor.
 */
@Mixin(Minecraft.class)
public interface MinecraftInvoker {

    @Invoker("startAttack")
    boolean orthosight$startAttack();

    @Invoker("startUseItem")
    void orthosight$startUseItem();

    @Invoker("continueAttack")
    void orthosight$continueAttack(boolean held);
}
