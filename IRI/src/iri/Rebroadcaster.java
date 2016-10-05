package iri;

import java.util.concurrent.*;

class Rebroadcaster {

    private static boolean shuttingDown;

    private static final BlockingQueue<byte[]> transactionsBytes = new LinkedBlockingQueue<>();

    public static void launch() {

        (new Thread(() -> {

            while (!shuttingDown) {

                try {

                    final byte[] transactionBytes = transactionsBytes.poll(1, TimeUnit.SECONDS);
                    Node.broadcast(transactionBytes == null ? Transaction.NULL_TRANSACTION_BYTES : transactionBytes);

                } catch (final Exception e) {

                    e.printStackTrace();
                }
            }

        }, "Rebroadcaster")).start();
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
