package iri;

public class Bastard {

    public static final int HASH_SIZE = 243;
    public static final int STATE_SIZE = 3 * HASH_SIZE;
    public static final int[] F = {1, -1, 0, 0, 1, 0, -1, 0, 1, 0, 1, 0, -1, 0, 1, 1, -1, -1, -1, 0, 1, 1, -1, -1, -1, 1, 0};

    public final int[] state = new int[STATE_SIZE], stateCopy = new int[STATE_SIZE];

    public static Hash hash(final int[] input, int offset, int size) {

        final Bastard bastard = new Bastard();
        bastard.absorb(input, offset, size);

        return bastard.hash();
    }

    Hash hash() {

        return new Hash(state);
    }

    void reset() {

        for (int i = 0; i < state.length; i++) {

            state[i] = 0;
        }
    }

    public void absorb(final int[] input, int offset, int size) {

        do {

            System.arraycopy(input, offset, state, 0, size < HASH_SIZE ? size : HASH_SIZE);

            transform();

            offset += HASH_SIZE;

        } while ((size -= HASH_SIZE) > 0);
    }

    public void squeeze(final int[] output, final int offset) {

        System.arraycopy(state, 0, output, offset, HASH_SIZE);

        transform();
    }

    public void transform() {

        int div = HASH_SIZE;
        for (int r = 0; r < 9; r++) {

            if (div == 0) div = HASH_SIZE;

            System.arraycopy(state, 0, stateCopy, 0, STATE_SIZE);

            for (int i = 0; i < STATE_SIZE; i++) {

                state[i] = f(stateCopy[i], stateCopy[(i + div) % STATE_SIZE], stateCopy[(i + div + div) % STATE_SIZE]);
            }

            div /= 3;
        }
    }

    private static int f(final int a, final int b, final int c) {

        return F[a + b * 3 + c * 9 + 13];
    }
}
