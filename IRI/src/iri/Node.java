package iri;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

class Node {

    static final int PORT = 999;

    static final int PACKET_SIZE_IN_TRITS = Transaction.SIZE + Bastard.HASH_SIZE;
    static final int PACKET_SIZE_IN_BYTES = Converter.sizeInBytes(PACKET_SIZE_IN_TRITS);

    static final List<SocketAddress> nodes = new ArrayList<>();
    static DatagramSocket socket;
    private static boolean shuttingDown;

    static final DatagramPacket receivingPacket = new DatagramPacket(new byte[PACKET_SIZE_IN_BYTES], PACKET_SIZE_IN_BYTES);
    static final DatagramPacket sendingPacket = new DatagramPacket(new byte[PACKET_SIZE_IN_BYTES], PACKET_SIZE_IN_BYTES);
    static final Map<String, Long> nodesActivity = new HashMap<>();

    static void launch() throws Exception {

        for (final String node : Files.readAllLines(Paths.get(Configuration.NODES_FILE_NAME))) {

            final URI uri = new URI(node);
            if (uri.getScheme().equals("udp")) {

                nodes.add(new InetSocketAddress(uri.getHost(), uri.getPort()));
            }
        }
        socket = new DatagramSocket(PORT);
        final BlockingQueue<Envelope> envelopes = new LinkedBlockingQueue<>();

        (new Thread(() -> {

            while (!shuttingDown) {

                try {

                    socket.receive(receivingPacket);
                    if (receivingPacket.getLength() == PACKET_SIZE_IN_BYTES) {

                        envelopes.put(new Envelope());

                        nodesActivity.put(receivingPacket.getSocketAddress().toString(), System.currentTimeMillis());

                    } else {

                        receivingPacket.setLength(PACKET_SIZE_IN_BYTES);
                    }

                } catch (final Exception e) {

                    e.printStackTrace();
                }
            }

        }, "Packets Receiver")).start();

        (new Thread(() -> {

            while (!shuttingDown) {

                try {

                    final Envelope envelope = envelopes.take();

                    final Transaction receivedTransaction = new Transaction(envelope.trits);
                    if (receivedTransaction.valid()) {

                        Tangle.add(receivedTransaction);

                        final Hash requestedHash = new Hash(envelope.trits, Transaction.SIZE);
                        final long transactionPointer = requestedHash.equals(receivedTransaction.hash())
                                ?
                                Storage.transactionPointer(TipsSelector.randomTip().bytes)
                                :
                                (requestedHash.equals(Hash.NULL_HASH) ? 0 : Storage.transactionPointer(requestedHash.bytes));
                        if (transactionPointer > Storage.CELLS_OFFSET - Storage.SUPER_GROUPS_OFFSET) {

                            send(envelope.socketAddress, Storage.loadTransaction(transactionPointer).bytes);
                        }
                    }

                } catch (final Exception e) {

                    e.printStackTrace();
                }
            }

        }, "Packets Processor")).start();
    }

    static void send(final SocketAddress node, final byte[] bytes) {

        try {

            System.arraycopy(bytes, 0, sendingPacket.getData(), 0, Transaction.SIZE_IN_BYTES);

            final int[] transactionsToRequest = new int[Transaction.SIZE % Converter.NUMBER_OF_TRITS_IN_A_BYTE + Bastard.HASH_SIZE];
            System.arraycopy(Storage.transactionToRequest().trits(), 0, transactionsToRequest, Transaction.SIZE % Converter.NUMBER_OF_TRITS_IN_A_BYTE, Bastard.HASH_SIZE);
            System.arraycopy(Converter.bytes(transactionsToRequest), 0, sendingPacket.getData(), Transaction.SIZE_IN_BYTES - 1, Converter.sizeInBytes(Transaction.SIZE % Converter.NUMBER_OF_TRITS_IN_A_BYTE + Bastard.HASH_SIZE)); // The last byte of a valid transaction is always zero, we can overwrite it

            sendingPacket.setSocketAddress(node);
            socket.send(sendingPacket);

        } catch (final IOException e) {

            e.printStackTrace();
        }
    }

    static void broadcast(final byte[] bytes) {

        for (final SocketAddress node : nodes) {

            send(node, bytes);
        }
    }

    static void shutDown() {

        shuttingDown = true;
    }

    private static final class Envelope {

        final SocketAddress socketAddress = receivingPacket.getSocketAddress();
        final int[] trits = Converter.trits(receivingPacket.getData(), PACKET_SIZE_IN_TRITS);
    }
}
