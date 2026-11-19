package org.abstruck.ortho_sight.skill;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.abstruck.ortho_sight.OrthoSight;

/**
 * Blink request: "I am walking in this direction, check if I can step onto something".
 *
 * <p>The walking direction has to travel with the packet. A {@code ServerPlayer}'s delta movement is driven by
 * the positions the client sends, not by the client's input, so the server cannot derive it. Only the
 * direction is taken from the client; the destination is always recomputed on the server, so a modified client
 * cannot choose where it lands.
 */
public record BlinkRequest(float dirX, float dirZ) implements CustomPacketPayload {

    public static final Type<BlinkRequest> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(OrthoSight.MODID, "blink"));

    public static final StreamCodec<FriendlyByteBuf, BlinkRequest> CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeFloat(payload.dirX());
                buffer.writeFloat(payload.dirZ());
            },
            buffer -> new BlinkRequest(buffer.readFloat(), buffer.readFloat()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Client side helper. */
    public static void send(float dirX, float dirZ) {
        PacketDistributor.sendToServer(new BlinkRequest(dirX, dirZ));
    }

    @EventBusSubscriber(modid = OrthoSight.MODID)
    public static final class Handler {

        private Handler() {
        }

        @SubscribeEvent
        public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
            // Version 2: the payload now carries the walking direction.
            event.registrar("2").playToServer(TYPE, CODEC, Handler::onBlinkRequest);
        }

        private static void onBlinkRequest(BlinkRequest request, IPayloadContext context) {
            if (context.player() instanceof ServerPlayer player) {
                BlinkSkill.handleRequest(player, request.dirX(), request.dirZ());
            }
        }
    }
}