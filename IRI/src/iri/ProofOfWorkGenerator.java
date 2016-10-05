package iri;

public final class ProofOfWorkGenerator {

    public static final int[] ZEROED_STATE = new int[729];

    public static final int[] ROUND_0_BETA_INDICES = new int[729], ROUND_0_GAMMA_INDICES = new int[729];
    public static final int[] ROUND_1_BETA_INDICES = new int[729], ROUND_1_GAMMA_INDICES = new int[729];
    public static final int[] ROUND_2_BETA_INDICES = new int[729], ROUND_2_GAMMA_INDICES = new int[729];
    public static final int[] ROUND_3_BETA_INDICES = new int[729], ROUND_3_GAMMA_INDICES = new int[729];
    public static final int[] ROUND_4_BETA_INDICES = new int[729], ROUND_4_GAMMA_INDICES = new int[729];
    public static final int[] ROUND_5_BETA_INDICES = new int[729], ROUND_5_GAMMA_INDICES = new int[729];

    public static final int[] F = {1, -1, 0, 0, 0, 1, 0, 0, -1, 0, 1, 0, 0, 0, 0, 0, 0, 1, 0, 0, -1, 0, 1, 0, 1, -1, -1, 0, 0, 0, 0, 0, -1, 0, 1, 0, 1, -1, -1, 0, -1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};

    static {

        for (int i = 0; i < 729; i++) {

            ROUND_0_BETA_INDICES[i] = (i + 243) % 729;
            ROUND_0_GAMMA_INDICES[i] = (i + 243 + 243) % 729;

            ROUND_1_BETA_INDICES[i] = (i + 81) % 729;
            ROUND_1_GAMMA_INDICES[i] = (i + 81 + 81) % 729;

            ROUND_2_BETA_INDICES[i] = (i + 27) % 729;
            ROUND_2_GAMMA_INDICES[i] = (i + 27 + 27) % 729;

            ROUND_3_BETA_INDICES[i] = (i + 9) % 729;
            ROUND_3_GAMMA_INDICES[i] = (i + 9 + 9) % 729;

            ROUND_4_BETA_INDICES[i] = (i + 3) % 729;
            ROUND_4_GAMMA_INDICES[i] = (i + 3 + 3) % 729;

            ROUND_5_BETA_INDICES[i] = (i + 1) % 729;
            ROUND_5_GAMMA_INDICES[i] = (i + 1 + 1) % 729;
        }
    }

    public static final int[] state = new int[729], stateCopy = new int[729];
    public static final int[] midState = new int[729];

    public static void measureHashingPower(final int numberOfTransforms) {

        System.out.println("Measuring hashing power...");
        final long beginningTime = System.currentTimeMillis();
        int counter = 0;
        while (++counter < numberOfTransforms) {

            transform();
        }
        System.out.println("Hashing power = " + ((double)((counter * Runtime.getRuntime().availableProcessors() * 10) / (System.currentTimeMillis() - beginningTime))) / 10 + " kH/s");
    }

    public static void doWork(final int[] transactionTrits, final int minWeightMagnitude) {

        System.arraycopy(transactionTrits, Transaction.VALUE_OFFSET, midState, 0, Transaction.VALUE_SIZE + Transaction.TIMESTAMP_SIZE + Transaction.INDEX_SIZE + Transaction.SIGNATURE_NONCE_SIZE);
        for (int i = Transaction.APPROVAL_NONCE_OFFSET - Transaction.VALUE_OFFSET; i < Transaction.APPROVAL_NONCE_OFFSET - Transaction.VALUE_OFFSET + Transaction.APPROVAL_NONCE_SIZE; i++) {

            midState[i] = Utils.randomTrit();
        }

        reset();
        absorb(transactionTrits, 0, Transaction.SIGNATURE_MESSAGE_CHUNK_SIZE + Transaction.DIGEST_SIZE + Transaction.ADDRESS_SIZE);
        System.arraycopy(state, 243, midState, 243, 729 - 243);

        while (true) {

            for (int i = Transaction.APPROVAL_NONCE_OFFSET - Transaction.VALUE_OFFSET; i < Transaction.APPROVAL_NONCE_OFFSET - Transaction.VALUE_OFFSET + Transaction.APPROVAL_NONCE_SIZE; i++) {

                if (++midState[i] > Converter.MAX_TRIT_VALUE) {

                    midState[i] = Converter.MIN_TRIT_VALUE;

                } else {

                    break;
                }
            }
            System.arraycopy(midState, 0, state, 0, 729);
            transform();

            System.arraycopy(transactionTrits, Transaction.APPROVED_TRUNK_TRANSACTION_OFFSET, state, 0, 243);
            transform();

            System.arraycopy(transactionTrits, Transaction.APPROVED_BRANCH_TRANSACTION_OFFSET, state, 0, 243);
            transform();

            boolean completed = true;
            for (int i = 243 - minWeightMagnitude; i < 243; i++) {

                if (state[i] != 0) {

                    completed = false;

                    break;
                }
            }
            if (completed) {

                break;
            }
        }

        System.arraycopy(midState, Transaction.APPROVAL_NONCE_OFFSET - Transaction.VALUE_OFFSET, transactionTrits, Transaction.APPROVAL_NONCE_OFFSET, Transaction.APPROVAL_NONCE_SIZE);
    }

    private static void reset() {

        System.arraycopy(ZEROED_STATE, 0, state, 0, 729);
    }

    private static void absorb(final int[] input, int offset, int size) {

        do {

            System.arraycopy(input, offset, state, 0, size < 243 ? size : 243);

            transform();

            offset += 243;

        } while ((size -= 243) > 0);
    }

    public static void transform() {

        System.arraycopy(state, 0, stateCopy, 0, 729);

        for (int i = 0; i < 729; i++) {

            state[i] = f(stateCopy[i], stateCopy[ROUND_0_BETA_INDICES[i]], stateCopy[ROUND_0_GAMMA_INDICES[i]]);
        }
        for (int i = 0; i < 729; i++) {

            stateCopy[i] = f(state[i], state[ROUND_1_BETA_INDICES[i]], state[ROUND_1_GAMMA_INDICES[i]]);
        }
        for (int i = 0; i < 729; i++) {

            state[i] = f(stateCopy[i], stateCopy[ROUND_2_BETA_INDICES[i]], stateCopy[ROUND_2_GAMMA_INDICES[i]]);
        }
        for (int i = 0; i < 729; i++) {

            stateCopy[i] = f(state[i], state[ROUND_3_BETA_INDICES[i]], state[ROUND_3_GAMMA_INDICES[i]]);
        }
        for (int i = 0; i < 729; i++) {

            state[i] = f(stateCopy[i], stateCopy[ROUND_4_BETA_INDICES[i]], stateCopy[ROUND_4_GAMMA_INDICES[i]]);
        }
        for (int i = 0; i < 729; i++) {

            stateCopy[i] = f(state[i], state[ROUND_5_BETA_INDICES[i]], state[ROUND_5_GAMMA_INDICES[i]]);
        }
        for (int i = 0; i < 729; i++) {

            state[i] = f(stateCopy[i], stateCopy[ROUND_0_BETA_INDICES[i]], stateCopy[ROUND_0_GAMMA_INDICES[i]]);
        }
        for (int i = 0; i < 729; i++) {

            stateCopy[i] = f(state[i], state[ROUND_1_BETA_INDICES[i]], state[ROUND_1_GAMMA_INDICES[i]]);
        }
        for (int i = 0; i < 729; i++) {

            state[i] = f(stateCopy[i], stateCopy[ROUND_2_BETA_INDICES[i]], stateCopy[ROUND_2_GAMMA_INDICES[i]]);
        }
    }

    private static int f(final int a, final int b, final int c) {

        return F[a + (b << 2) + (c << 4) + 21];
    }
}
