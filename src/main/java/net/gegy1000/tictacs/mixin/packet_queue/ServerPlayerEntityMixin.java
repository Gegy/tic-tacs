package net.gegy1000.tictacs.mixin.packet_queue;

import net.gegy1000.tictacs.QueuingConnection;
import net.minecraft.network.Packet;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ServerPlayerEntity.class)
public class ServerPlayerEntityMixin {
    @Redirect(
            method = "sendInitialChunkPackets",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/network/ServerPlayNetworkHandler;sendPacket(Lnet/minecraft/network/Packet;)V"
            )
    )
    private void sendInitialChunkPacket(ServerPlayNetworkHandler network, Packet<?> packet) {
        QueuingConnection.enqueueSend(network, packet);
    }

    @Redirect(
            method = "sendUnloadChunkPacket",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/network/ServerPlayNetworkHandler;sendPacket(Lnet/minecraft/network/Packet;)V"
            )
    )
    private void sendUnloadChunkPacket(ServerPlayNetworkHandler network, Packet<?> packet) {
        QueuingConnection.enqueueSend(network, packet);
    }
}
