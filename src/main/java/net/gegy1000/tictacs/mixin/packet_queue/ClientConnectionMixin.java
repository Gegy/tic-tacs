package net.gegy1000.tictacs.mixin.packet_queue;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import net.gegy1000.tictacs.QueuingConnection;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.NetworkState;
import net.minecraft.network.Packet;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Unique;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;

@Mixin(ClientConnection.class)
public abstract class ClientConnectionMixin implements QueuingConnection {
    @Shadow
    @Final
    private Queue<ClientConnection.QueuedPacket> packetQueue;

    @Shadow
    private Channel channel;

    @Shadow
    private int packetsSentCounter;

    @Shadow
    @Final
    private static Logger LOGGER;

    @Shadow
    public abstract void setState(NetworkState state);

    @Override
    public void enqueueSend(Packet<?> packet, @Nullable GenericFutureListener<? extends Future<? super Void>> callback) {
        this.packetQueue.add(new ClientConnection.QueuedPacket(packet, callback));
    }

    /**
     * @reason send multiple queued packets by only scheduling to the event loop once
     * @author gegy1000
     */
    @Overwrite
    public void sendQueuedPackets() {
        if (this.channel == null || !this.channel.isOpen()) {
            return;
        }

        List<ClientConnection.QueuedPacket> queue = this.drainQueue();
        if (queue.isEmpty()) {
            return;
        }

        NetworkState currentState = this.channel.attr(ClientConnection.ATTR_KEY_PROTOCOL).get();

        ClientConnection.QueuedPacket lastPacket = queue.get(queue.size() - 1);
        NetworkState lastState = NetworkState.getPacketHandlerState(lastPacket.packet);

        this.packetsSentCounter += queue.size();

        if (lastState != currentState) {
            LOGGER.debug("Disabled auto read");
            this.channel.config().setAutoRead(false);
        }

        if (this.channel.eventLoop().inEventLoop()) {
            this.sendQueue(queue, currentState, lastState);
        } else {
            this.channel.eventLoop().execute(() -> {
                this.sendQueue(queue, currentState, lastState);
            });
        }
    }

    @Unique
    private List<ClientConnection.QueuedPacket> drainQueue() {
        if (this.packetQueue.isEmpty()) {
            return Collections.emptyList();
        }

        List<ClientConnection.QueuedPacket> buffer = new ArrayList<>(this.packetQueue.size());

        ClientConnection.QueuedPacket queued;
        while ((queued = this.packetQueue.poll()) != null) {
            buffer.add(queued);
        }

        return buffer;
    }

    @Unique
    private void sendQueue(List<ClientConnection.QueuedPacket> queue, NetworkState currentState, NetworkState lastState) {
        if (lastState != currentState) {
            this.setState(lastState);
        }

        for (ClientConnection.QueuedPacket packet : queue) {
            ChannelFuture future = this.channel.write(packet.packet);
            if (packet.callback != null) {
                future.addListener(packet.callback);
            }
            future.addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
        }

        this.channel.flush();
    }
}
