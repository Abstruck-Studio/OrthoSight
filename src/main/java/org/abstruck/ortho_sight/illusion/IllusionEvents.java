package org.abstruck.ortho_sight.illusion;

import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.abstruck.ortho_sight.OrthoSight;

/**
 * Drives the screen-space illusion. Lives in its own package so the client-only build can drop the whole
 * feature: it is a gameplay affordance, and the pure client release must not ship it at all.
 */
@EventBusSubscriber(modid = OrthoSight.MODID, value = Dist.CLIENT)
public final class IllusionEvents {

    private static final Logger LOGGER = LoggerFactory.getLogger("ortho_sight/illusion");

    /** One shot diagnostic: proves the handler is registered and firing in a fresh install. */
    private static boolean announced;

    private IllusionEvents() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!announced && minecraft.player != null && minecraft.level != null) {
            announced = true;
            LOGGER.info("[OrthoSight] illusion handler active (enabled={}, requireSneak={})",
                    org.abstruck.ortho_sight.OrthoConfig.illusionEnabled(),
                    org.abstruck.ortho_sight.OrthoConfig.illusionRequireSneak());
        }
        IllusionSupport.tick(minecraft.player);
    }
}