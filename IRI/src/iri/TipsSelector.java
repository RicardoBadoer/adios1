package iri;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

class TipsSelector {

    public static final int TIPS_CHANGING_PERIOD = 5000;

    public static final int TIP_SIZE_IN_TRITS = Bastard.HASH_SIZE + 1;
    public static final int TIP_SIZE_IN_BYTES = Converter.sizeInBytes(TIP_SIZE_IN_TRITS);

    private static boolean shuttingDown;
    private static boolean triggered;

    static final List<Hash> tips = new LinkedList<>(Collections.singleton(Hash.NULL_HASH));

    static void launch() {

        if (Configuration.coordinator() == null) { // Do Random walk Monte Carlo tip selection

            trigger();

            (new Thread(() -> {

                while (!shuttingDown) {

                    try {

                        if (triggered) {

                            triggered = false;

                            throw new UnsupportedOperationException("Random walk Monte Carlo tip selection has not been implemented yet, please, add 'iri.coordinator' into the configuration file");

                        } else {

                            Thread.sleep(1000);
                        }

                    } catch (final Exception e) {

                        e.printStackTrace();
                    }
                }

            }, "Tips Selector")).start();

        } else {

            (new Thread(() -> {

                while (!shuttingDown) {

                    try {

                        synchronized (TipsSelector.class) {

                            tips.clear();
                        }

                        final Socket socket = new Socket(Configuration.coordinator(), Node.PORT + 1);
                        final InputStream inputStream = socket.getInputStream();

                        System.out.println("Connected to the coordinator");

                        final byte[] numberOfTipsBuffer = new byte[1];
                        while (true) {

                            int numberOfBytes = inputStream.read(numberOfTipsBuffer);
                            if (numberOfBytes < 0) {

                                break;
                            }

                            final int numberOfTips = numberOfTipsBuffer[0] & 0xFF;
                            if (numberOfTips == 0) {

                                break;
                            }

                            final byte[] tipsBuffer = new byte[TIP_SIZE_IN_BYTES * numberOfTips];
                            int pointer = 0;
                            while (pointer < tipsBuffer.length) {

                                numberOfBytes = inputStream.read(tipsBuffer, pointer, tipsBuffer.length - pointer);
                                if (numberOfBytes < 0) {

                                    break;
                                }

                                pointer += numberOfBytes;
                            }
                            if (numberOfBytes < 0) {

                                break;

                            } else {

                                for (int i = 0; i < numberOfTips; i++) {

                                    final int[] trits = Converter.trits(Arrays.copyOfRange(tipsBuffer, i * TIP_SIZE_IN_BYTES, (i + 1) * TIP_SIZE_IN_BYTES), TIP_SIZE_IN_TRITS);
                                    final Hash hash = new Hash(trits);
                                    synchronized (TipsSelector.class) {

                                        if (trits[Bastard.HASH_SIZE] > 0) {

                                            System.out.println("+" + hash);

                                            if (!tips.contains(hash)) {

                                                tips.add(hash);
                                            }

                                            Storage.storeTransaction(hash.bytes, null, true);

                                        } else if (trits[Bastard.HASH_SIZE] < 0) {

                                            System.out.println("-" + hash);

                                            tips.remove(hash);
                                        }
                                    }
                                }
                            }
                        }

                        System.out.println("Disconnected from the coordinator");

                        inputStream.close();
                        socket.close();

                    } catch (final Exception e) {

                        e.printStackTrace();
                    }
                }

            }, "Tips Receiver")).start();
        }
    }

    static void shutDown() {

        shuttingDown = true;
    }

    static void trigger() {

        triggered = true;
    }

    static synchronized Set<Hash> tips() {

        return new HashSet<>(tips);
    }

    static synchronized Hash randomTip() {

        return tips.get(ThreadLocalRandom.current().nextInt(tips.size()));
    }
}
