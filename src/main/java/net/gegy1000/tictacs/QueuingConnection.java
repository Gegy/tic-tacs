package net.gegy1000.tictacs;

import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import net.minecraft.network.Packet;
import net.minecraft.server.network.ServerPlayNetworkHandler;

import org.jetbrains.annotations.Nullable;

public interface QueuingConnection {
    static void enqueueSend(ServerPlayNetworkHandler network, Packet<?> packet) {
        ((QueuingConnection) network).enqueueSend(packet);
    }

    static void enqueueSend(ServerPlayNetworkHandler network, Packet<?> packet, @Nullable GenericFutureListener<? extends Future<? super Void>> callback) {
        ((QueuingConnection) network).enqueueSend(packet, callback);
    }

    default void enqueueSend(Packet<?> packet) {
        this.enqueueSend(packet, null);
    }

    void enqueueSend(Packet<?> packet, @Nullable GenericFutureListener<? extends Future<? super Void>> callback);
}
