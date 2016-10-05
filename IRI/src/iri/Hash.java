package iri;

import java.util.*;

public class Hash {

    public static final int SIZE_IN_BYTES = Converter.sizeInBytes(Bastard.HASH_SIZE);

    public static final Hash NULL_HASH = new Hash(new int[Bastard.HASH_SIZE]);

    public final byte[] bytes;

    private final int hashCode;

    public Hash(final byte[] bytes, final int offset, final int size) {

        this.bytes = new byte[SIZE_IN_BYTES];
        System.arraycopy(bytes, offset, this.bytes, 0, size);

        hashCode = Arrays.hashCode(this.bytes);
    }

    public Hash(final byte[] bytes) {

        this(bytes, 0, SIZE_IN_BYTES);
    }

    public Hash(final int[] trits, final int offset) {

        this(Converter.bytes(trits, offset, Bastard.HASH_SIZE));
    }

    public Hash(final int[] trits) {

        this(trits, 0);
    }

    public Hash(final String trytes) {

        this(Converter.trits(trytes));
    }

    public int[] trits() {

        return Converter.trits(bytes, Bastard.HASH_SIZE);
    }

    @Override
    public boolean equals(final Object obj) {

        return Arrays.equals(bytes, ((Hash)obj).bytes);
    }

    @Override
    public int hashCode() {

        return hashCode;
    }

    @Override
    public String toString() {

        return Converter.trytes(trits());
    }
}
