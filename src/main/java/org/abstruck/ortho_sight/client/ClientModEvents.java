package org.abstruck.ortho_sight.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import org.abstruck.ortho_sight.OrthoSight;

@EventBusSubscriber(modid=OrthoSight.MODID, value=Dist.CLIENT, bus=EventBusSubscriber.Bus.MOD)
public final class ClientModEvents {
    private ClientModEvents() {}

    @SubscribeEvent
    public static void registerKeyMappings(RegisterKeyMappingsEvent event) {
        OrthoClient.register(event);
    }
}
