package net.gegy1000.tictacs.chunk.ticket;

public interface TicketTracker {
    void enqueueTicket(long pos, int distance);

    void removeTicket(long pos);

    void moveTicket(long pos, int fromDistance, int toDistance);
}
