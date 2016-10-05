package iri;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class Broadcaster {

    public static final int PAUSE_BETWEEN_TRANSACTIONS = 1000;

    private static boolean shuttingDown;

    private static final BlockingQueue<byte[]> transactionsBytes = new LinkedBlockingQueue<>();

    public static void launch() {

        (new Thread(() -> {

            while (!shuttingDown) {

                try {

                    Node.broadcast(transactionsBytes.take());

                    Thread.sleep(PAUSE_BETWEEN_TRANSACTIONS);

                } catch (final Exception e) {

                    e.printStackTrace();
                }
            }

        }, "Broadcaster")).start();
    }

    public static void shutDown() {

        shuttingDown = true;
    }

    public static void push(final byte[] transactionBytes) {

        try {

            transactionsBytes.put(transactionBytes);

        } catch (final InterruptedException e) {

            e.printStackTrace();
        }
    }
}
