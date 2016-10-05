package iri;

import java.math.*;
import java.util.*;

public class Converter {

    static final int RADIX = 3;
    static final int MAX_TRIT_VALUE = (RADIX - 1) / 2, MIN_TRIT_VALUE = -MAX_TRIT_VALUE;

    static final int NUMBER_OF_TRITS_IN_A_BYTE = 5;
    static final int[][] BYTE_TO_TRITS_MAPPING;

    static final int NUMBER_OF_TRITS_IN_A_TRYTE = 3;
    static final int[][] TRYTE_TO_TRITS_MAPPING;
    static final String TRYTE_ALPHABET = "9ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    static final int MIN_TRYTE_VALUE = -13, MAX_TRYTE_VALUE = 13;

    static {

        int numberOfByteToTritsMappingEntries = 1;
        for (int i = 0; i < NUMBER_OF_TRITS_IN_A_BYTE; i++) {

            numberOfByteToTritsMappingEntries *= RADIX;
        }
        BYTE_TO_TRITS_MAPPING = new int[numberOfByteToTritsMappingEntries][];

        final int[] trits = new int[NUMBER_OF_TRITS_IN_A_BYTE];
        for (int i = 0; i < BYTE_TO_TRITS_MAPPING.length; i++) {

            int index = (int)longValue(trits, 0, trits.length);
            if (index < 0) {

                index += BYTE_TO_TRITS_MAPPING.length;
            }
            BYTE_TO_TRITS_MAPPING[index] = Arrays.copyOf(trits, trits.length);

            Utils.increment(trits, 0, trits.length);
        }

        TRYTE_TO_TRITS_MAPPING = new int[TRYTE_ALPHABET.length()][];
        final int[] trits2 = new int[NUMBER_OF_TRITS_IN_A_TRYTE];
        for (int i = 0; i < TRYTE_TO_TRITS_MAPPING.length; i++) {

            int index = (int)longValue(trits2, 0, trits2.length);
            if (index < 0) {

                index += TRYTE_TO_TRITS_MAPPING.length;
            }
            TRYTE_TO_TRITS_MAPPING[index] = Arrays.copyOf(trits2, trits2.length);

            Utils.increment(trits2, 0, trits2.length);
        }
    }

    public static int sizeInBytes(final int sizeInTrits) {

        return (sizeInTrits + NUMBER_OF_TRITS_IN_A_BYTE - 1) / NUMBER_OF_TRITS_IN_A_BYTE;
    }

    public static long longValue(final int[] trits, final int offset, final int size) {

        long value = 0;
        for (int i = size; i-- > 0; ) {

            value = value * RADIX + trits[offset + i];
        }

        return value;
    }

    public static BigInteger bigIntegerValue(final int[] trits) {

        BigInteger value = BigInteger.ZERO;
        for (int i = Bastard.HASH_SIZE; i-- > 0; ) {

            value = value.multiply(BigInteger.valueOf(RADIX)).add(BigInteger.valueOf(trits[i]));
        }

        return value;
    }

    public static byte[] bytes(final int[] trits, final int offset, final int size) {

        final byte[] bytes = new byte[sizeInBytes(size)];
        for (int i = 0; i < bytes.length; i++) {

            int value = 0;
            for (int j = (size - i * NUMBER_OF_TRITS_IN_A_BYTE) < 5 ? (size - i * NUMBER_OF_TRITS_IN_A_BYTE) : NUMBER_OF_TRITS_IN_A_BYTE; j-- > 0; ) {

                value = value * RADIX + trits[offset + i * NUMBER_OF_TRITS_IN_A_BYTE + j];
            }
            bytes[i] = (byte)value;
        }

        return bytes;
    }

    public static byte[] bytes(final int[] trits) {

        return bytes(trits, 0, trits.length);
    }

    public static int[] trits(final byte[] bytes, final int destinationSize) {

        final int[] trits = new int[destinationSize];
        for (int i = 0, offset = 0; i < bytes.length && offset < trits.length; i++) {

            System.arraycopy(BYTE_TO_TRITS_MAPPING[bytes[i] < 0 ? (bytes[i] + BYTE_TO_TRITS_MAPPING.length) : bytes[i]], 0, trits, offset, trits.length - offset < NUMBER_OF_TRITS_IN_A_BYTE ? (trits.length - offset) : NUMBER_OF_TRITS_IN_A_BYTE);
            offset += NUMBER_OF_TRITS_IN_A_BYTE;
        }

        return trits;
    }

    public static int[] trits(final String trytes) {

        final int[] trits = new int[trytes.length() * NUMBER_OF_TRITS_IN_A_TRYTE];
        for (int i = 0; i < trytes.length(); i++) {

            System.arraycopy(TRYTE_TO_TRITS_MAPPING[TRYTE_ALPHABET.indexOf(trytes.charAt(i))], 0, trits, i * NUMBER_OF_TRITS_IN_A_TRYTE, NUMBER_OF_TRITS_IN_A_TRYTE);
        }

        return trits;
    }

    public static int[] trits(final BigInteger bigInteger, final int destinationSize) {

        final List<Integer> trits = new LinkedList<>();

        BigInteger absoluteValue = bigInteger.signum() >= 0 ? bigInteger : bigInteger.negate();

        while (absoluteValue.signum() != 0) {

            int remainder = absoluteValue.mod(BigInteger.valueOf(RADIX)).intValue();
            absoluteValue = absoluteValue.divide(BigInteger.valueOf(RADIX));
            if (remainder > MAX_TRIT_VALUE) {

                remainder = MIN_TRIT_VALUE;
                absoluteValue = absoluteValue.add(BigInteger.ONE);
            }
            trits.add(remainder);
        }

        if (bigInteger.signum() < 0) {

            for (int i = 0; i < trits.size(); i++) {

                trits.set(i, -trits.get(i));
            }
        }

        return Arrays.copyOf(trits.stream().mapToInt(i -> i).toArray(), destinationSize);
    }

    public static int[] trits(final long value, final int destinationSize) {

        return trits(BigInteger.valueOf(value), destinationSize);
    }

    public static String trytes(final int[] trits, final int offset, final int size) {

        StringBuilder trytes = new StringBuilder();
        for (int i = 0; i < (size + NUMBER_OF_TRITS_IN_A_TRYTE - 1) / NUMBER_OF_TRITS_IN_A_TRYTE; i++) {

            int j = trits[offset + i * 3] + trits[offset + i * 3 + 1] * 3 + trits[offset + i * 3 + 2] * 9;
            if (j < 0) {

                j += TRYTE_ALPHABET.length();
            }
            trytes.append(TRYTE_ALPHABET.charAt(j));
        }

        return trytes.toString();
    }

    public static String trytes(final int[] trits) {

        return trytes(trits, 0, trits.length);
    }

    public static int tryteValue(final int[] trits, final int offset) {

        return trits[offset] + trits[offset + 1] * 3 + trits[offset + 2] * 9;
    }
}
