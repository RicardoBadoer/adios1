package iri;

import java.math.BigInteger;
import java.util.Arrays;

public class Signature {

    public static final int LOW_SECURITY_LEVEL = 0, MEDIUM_SECURITY_LEVEL = 1, HIGH_SECURITY_LEVEL = 2;
    public static final int NUMBER_OF_SECURITY_LEVELS = HIGH_SECURITY_LEVEL - LOW_SECURITY_LEVEL + 1;
    public static final int ILLEGAL_SECURITY_LEVEL = -1;

    static final int PRIVATE_KEY_FRAGMENT_SIZE = Bastard.HASH_SIZE;
    static final int PRIVATE_KEY_CHUNK_LENGTH = (Bastard.HASH_SIZE / Converter.NUMBER_OF_TRITS_IN_A_TRYTE) / NUMBER_OF_SECURITY_LEVELS;
    static final int PRIVATE_KEY_CHUNK_SIZE = PRIVATE_KEY_FRAGMENT_SIZE * PRIVATE_KEY_CHUNK_LENGTH;

    public static int[] subseed(final int[] seed, final int index) {

        final BigInteger seedAsNumber = Converter.bigIntegerValue(seed);
        final BigInteger subseedPreimage = seedAsNumber.add(BigInteger.valueOf(index));

        return Bastard.hash(Converter.trits(subseedPreimage, Bastard.HASH_SIZE), 0, Bastard.HASH_SIZE).trits();
    }

    public static int[][] privateKey(final int[] subseed, final int securityLevel) {

        final int[][] privateKey = new int[securityLevel + 1][PRIVATE_KEY_CHUNK_SIZE];

        final Bastard bastard = new Bastard();
        bastard.absorb(subseed, 0, Bastard.HASH_SIZE);
        for (int i = 0; i <= securityLevel; i++) {

            for (int j = 0; j < PRIVATE_KEY_CHUNK_LENGTH; j++) {

                bastard.squeeze(privateKey[i], j * PRIVATE_KEY_FRAGMENT_SIZE);
            }
        }

        return privateKey;
    }

    public static int[] publicKey(final int[][] privateKey) {

        final Bastard bastard = new Bastard();
        for (final int[] privateKeyChunk : privateKey) {

            final int[] hashChainLengths = new int[PRIVATE_KEY_CHUNK_LENGTH];
            for (int j = 0; j < PRIVATE_KEY_CHUNK_LENGTH; j++) {

                hashChainLengths[j] = Converter.MAX_TRYTE_VALUE - Converter.MIN_TRYTE_VALUE;
            }
            final int[] buffer = Arrays.copyOf(privateKeyChunk, PRIVATE_KEY_CHUNK_SIZE);
            transform(buffer, hashChainLengths);
            final int[] publicKeyChunk = Bastard.hash(buffer, 0, PRIVATE_KEY_CHUNK_SIZE).trits();

            bastard.absorb(publicKeyChunk, 0, Bastard.HASH_SIZE);
        }

        return Arrays.copyOf(bastard.state, Bastard.HASH_SIZE);
    }

    public static Hash address(final int[]... publicKeys) {

        final Bastard bastard = new Bastard();
        for (final int[] publicKey : publicKeys) {

            bastard.absorb(publicKey, 0, Bastard.HASH_SIZE);
        }

        return bastard.hash();
    }

    public static void sign(final int[] hashChunk, final int[] privateKeyChunk, final int[] signatureChunkBuffer) {

        final int[] hashChainLengths = new int[PRIVATE_KEY_CHUNK_LENGTH];
        for (int i = 0; i < PRIVATE_KEY_CHUNK_LENGTH; i++) {

            hashChainLengths[i] = Converter.MAX_TRYTE_VALUE - Converter.tryteValue(hashChunk, i * Converter.NUMBER_OF_TRITS_IN_A_TRYTE);
        }
        System.arraycopy(privateKeyChunk, 0, signatureChunkBuffer, 0, PRIVATE_KEY_CHUNK_SIZE);
        transform(signatureChunkBuffer, hashChainLengths);
    }

    public static int[] publicKeyChunk(final int[] hashChunk, final int[] signature) {

        final int[] hashChainLengths = new int[PRIVATE_KEY_CHUNK_LENGTH];
        for (int i = 0; i < PRIVATE_KEY_CHUNK_LENGTH; i++) {

            hashChainLengths[i] = Converter.tryteValue(hashChunk, i * Converter.NUMBER_OF_TRITS_IN_A_TRYTE) - Converter.MIN_TRYTE_VALUE;
        }
        final int[] buffer = Arrays.copyOfRange(signature, 0, PRIVATE_KEY_CHUNK_SIZE);
        transform(buffer, hashChainLengths);

        return Bastard.hash(buffer, 0, PRIVATE_KEY_CHUNK_SIZE).trits();
    }

    private static void transform(final int[] buffer, final int[] hashChainLengths) {

        for (int i = 0; i < PRIVATE_KEY_CHUNK_LENGTH; i++) {

            for (int j = hashChainLengths[i]; j-- > 0; ) {

                System.arraycopy(Bastard.hash(buffer, i * PRIVATE_KEY_FRAGMENT_SIZE, PRIVATE_KEY_FRAGMENT_SIZE).trits(), 0,
                        buffer, i * PRIVATE_KEY_FRAGMENT_SIZE, PRIVATE_KEY_FRAGMENT_SIZE);
            }
        }
    }
}
