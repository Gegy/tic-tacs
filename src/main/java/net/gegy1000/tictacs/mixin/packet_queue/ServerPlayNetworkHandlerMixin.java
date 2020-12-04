package net.gegy1000.tictacs.mixin.packet_queue;

import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import net.gegy1000.tictacs.QueuingConnection;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.Packet;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import org.jetbrains.annotations.Nullable;

@Mixin(ServerPlayNetworkHandler.class)
public class ServerPlayNetworkHandlerMixin implements QueuingConnection {
    @Shadow
    @Final
    public ClientConnection connection;

    @Override
    public void enqueueSend(Packet<?> packet, @Nullable GenericFutureListener<? extends Future<? super Void>> callback) {
        ((QueuingConnection) this.connection).enqueueSend(packet, callback);
    }
}
