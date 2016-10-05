package iri;

import java.math.*;
import java.util.*;

public class Transaction {

    public static final long TOTAL_NUMBER_OF_IOTAS = 2779530283277761L;

    public static final int SIGNATURE_MESSAGE_CHUNK_OFFSET = 0, SIGNATURE_MESSAGE_CHUNK_SIZE = Signature.PRIVATE_KEY_CHUNK_SIZE;
    public static final int DIGEST_OFFSET = SIGNATURE_MESSAGE_CHUNK_OFFSET + SIGNATURE_MESSAGE_CHUNK_SIZE, DIGEST_SIZE = Bastard.HASH_SIZE;
    public static final int ADDRESS_OFFSET = DIGEST_OFFSET + DIGEST_SIZE, ADDRESS_SIZE = Bastard.HASH_SIZE;
    public static final int VALUE_OFFSET = ADDRESS_OFFSET + ADDRESS_SIZE, VALUE_SIZE = Bastard.HASH_SIZE / 9;
    public static final int TIMESTAMP_OFFSET = VALUE_OFFSET + VALUE_SIZE, TIMESTAMP_SIZE = Bastard.HASH_SIZE / 9;
    public static final int INDEX_OFFSET = TIMESTAMP_OFFSET + TIMESTAMP_SIZE, INDEX_SIZE = Bastard.HASH_SIZE / 9;
    public static final int SIGNATURE_NONCE_OFFSET = INDEX_OFFSET + INDEX_SIZE, SIGNATURE_NONCE_SIZE = Bastard.HASH_SIZE / 3;
    public static final int APPROVAL_NONCE_OFFSET = SIGNATURE_NONCE_OFFSET + SIGNATURE_NONCE_SIZE, APPROVAL_NONCE_SIZE = Bastard.HASH_SIZE / 3;
    public static final int APPROVED_TRUNK_TRANSACTION_OFFSET = APPROVAL_NONCE_OFFSET + APPROVAL_NONCE_SIZE, APPROVED_TRUNK_TRANSACTION_SIZE = Bastard.HASH_SIZE;
    public static final int APPROVED_BRANCH_TRANSACTION_OFFSET = APPROVED_TRUNK_TRANSACTION_OFFSET + APPROVED_TRUNK_TRANSACTION_SIZE, APPROVED_BRANCH_TRANSACTION_SIZE = Bastard.HASH_SIZE;

    public static final int SIZE = APPROVED_BRANCH_TRANSACTION_OFFSET + APPROVED_BRANCH_TRANSACTION_SIZE;
    public static final int SIZE_IN_BYTES = 1556;

    public static final int ESSENCE_OFFSET = DIGEST_OFFSET, ESSENCE_SIZE = DIGEST_SIZE + ADDRESS_SIZE + VALUE_SIZE + TIMESTAMP_SIZE + INDEX_SIZE;

    public static final int INPUT = -1, OUTPUT = 1;

    public static final int MIN_WEIGHT_MAGNITUDE = 13;
    public static final long MAX_TRANSFERABLE_VALUE = BigInteger.valueOf(Converter.RADIX).pow(VALUE_SIZE).subtract(BigInteger.ONE).divide(BigInteger.valueOf(2)).longValueExact();

    public static final byte[] NULL_TRANSACTION_BYTES = new byte[SIZE_IN_BYTES];

    public final int[] trits;

    public final Hash digest;
    public final Hash address;
    public final long value;
    public final long timestamp;
    public final long index;
    public final Hash approvedTrunkTransaction;
    public final Hash approvedBranchTransaction;

    private Hash hash;

    private final int hashCode;

    Transaction(final int[] signatureMessageChunk,
                final Hash digest, final Hash address, final long value, final long timestamp, final long index, final int[] signatureNonce,
                final int[] approvalNonce, final Hash approvedTrunkTransaction, final Hash approvedBranchTransaction) {

        trits = new int[SIZE];

        System.arraycopy(signatureMessageChunk, 0, trits, SIGNATURE_MESSAGE_CHUNK_OFFSET, SIGNATURE_MESSAGE_CHUNK_SIZE);
        System.arraycopy(digest.trits(), 0, trits, DIGEST_OFFSET, DIGEST_SIZE);
        System.arraycopy(address.trits(), 0, trits, ADDRESS_OFFSET, ADDRESS_SIZE);
        System.arraycopy(Converter.trits(value, VALUE_SIZE), 0, trits, VALUE_OFFSET, VALUE_SIZE);
        System.arraycopy(Converter.trits(timestamp, TIMESTAMP_SIZE), 0, trits, TIMESTAMP_OFFSET, TIMESTAMP_SIZE);
        System.arraycopy(Converter.trits(index, INDEX_SIZE), 0, trits, INDEX_OFFSET, INDEX_SIZE);
        System.arraycopy(signatureNonce, 0, trits, SIGNATURE_NONCE_OFFSET, SIGNATURE_NONCE_SIZE);
        System.arraycopy(approvalNonce, 0, trits, APPROVAL_NONCE_OFFSET, APPROVAL_NONCE_SIZE);
        System.arraycopy(approvedTrunkTransaction.trits(), 0, trits, APPROVED_TRUNK_TRANSACTION_OFFSET, APPROVED_TRUNK_TRANSACTION_SIZE);
        System.arraycopy(approvedBranchTransaction.trits(), 0, trits, APPROVED_BRANCH_TRANSACTION_OFFSET, APPROVED_BRANCH_TRANSACTION_SIZE);

        this.digest = digest;
        this.address = address;
        this.value = value;
        this.timestamp = timestamp;
        this.index = index;
        this.approvedTrunkTransaction = approvedTrunkTransaction;
        this.approvedBranchTransaction = approvedBranchTransaction;

        hashCode = Arrays.hashCode(trits);
    }

    Transaction(final Map<String, Object> object) {

        digest = new Hash((String)object.get("digest"));
        address = new Hash((String)object.get("address"));
        value = (new BigInteger((String)object.get("value"))).longValueExact();
        timestamp = (new BigInteger((String)object.get("timestamp"))).longValueExact();
        index = (new BigInteger((String)object.get("index"))).longValueExact();
        approvedTrunkTransaction = new Hash((String)object.get("approvedTrunkTransaction"));
        approvedBranchTransaction = new Hash((String)object.get("approvedBranchTransaction"));

        trits = new int[SIZE];

        System.arraycopy(Converter.trits((String)object.get("signatureMessageChunk")), 0, trits, SIGNATURE_MESSAGE_CHUNK_OFFSET, SIGNATURE_MESSAGE_CHUNK_SIZE);
        System.arraycopy(digest.trits(), 0, trits, DIGEST_OFFSET, DIGEST_SIZE);
        System.arraycopy(address.trits(), 0, trits, ADDRESS_OFFSET, ADDRESS_SIZE);
        System.arraycopy(Converter.trits(value, VALUE_SIZE), 0, trits, VALUE_OFFSET, VALUE_SIZE);
        System.arraycopy(Converter.trits(timestamp, TIMESTAMP_SIZE), 0, trits, TIMESTAMP_OFFSET, TIMESTAMP_SIZE);
        System.arraycopy(Converter.trits(index, INDEX_SIZE), 0, trits, INDEX_OFFSET, INDEX_SIZE);
        System.arraycopy(Converter.trits((String)object.get("signatureNonce")), 0, trits, SIGNATURE_NONCE_OFFSET, SIGNATURE_NONCE_SIZE);
        System.arraycopy(Converter.trits((String)object.get("approvalNonce")), 0, trits, APPROVAL_NONCE_OFFSET, APPROVAL_NONCE_SIZE);
        System.arraycopy(approvedTrunkTransaction.trits(), 0, trits, APPROVED_TRUNK_TRANSACTION_OFFSET, APPROVED_TRUNK_TRANSACTION_SIZE);
        System.arraycopy(approvedBranchTransaction.trits(), 0, trits, APPROVED_BRANCH_TRANSACTION_OFFSET, APPROVED_BRANCH_TRANSACTION_SIZE);

        hashCode = Arrays.hashCode(trits);
    }

    Transaction(final int[] trits) {

        this.trits = Arrays.copyOf(trits, SIZE);

        digest = new Hash(this.trits, DIGEST_OFFSET);
        address = new Hash(this.trits, ADDRESS_OFFSET);
        value = Converter.longValue(this.trits, VALUE_OFFSET, VALUE_SIZE);
        timestamp = Converter.longValue(this.trits, TIMESTAMP_OFFSET, TIMESTAMP_SIZE);
        index = Converter.longValue(this.trits, INDEX_OFFSET, INDEX_SIZE);
        approvedTrunkTransaction = new Hash(this.trits, APPROVED_TRUNK_TRANSACTION_OFFSET);
        approvedBranchTransaction = new Hash(this.trits, APPROVED_BRANCH_TRANSACTION_OFFSET);

        hashCode = Arrays.hashCode(this.trits);
    }

    Transaction(final byte[] bytes) {

        this(Converter.trits(bytes, SIZE));
    }

    Hash hash() {

        if (hash == null) {

            synchronized (this) {

                if (hash == null) {

                    hash = Bastard.hash(trits, 0, SIZE);
                }
            }
        }

        return hash;
    }

    boolean valid() {

        final byte[] hashBytes = hash().bytes;

        return type() * value >= 0
                && hashBytes[Hash.SIZE_IN_BYTES - 3] == 0 && hashBytes[Hash.SIZE_IN_BYTES - 2] == 0 && hashBytes[Hash.SIZE_IN_BYTES - 1] == 0;
    }

    int weightMagnitude() {

        int weightMagnitude = 0;

        final int[] hashTrits = hash().trits();
        while (weightMagnitude < Bastard.HASH_SIZE && hashTrits[Bastard.HASH_SIZE - weightMagnitude - 1] == 0) {

            weightMagnitude++;
        }

        return weightMagnitude;
    }

    int type() {

        for (int i = SIGNATURE_NONCE_OFFSET; i < SIGNATURE_NONCE_OFFSET + SIGNATURE_NONCE_SIZE; i++) {

            if (trits[i] != 0) {

                return INPUT;
            }
        }

        return OUTPUT;
    }

    @Override
    public boolean equals(final Object obj) {

        return Arrays.equals(trits, ((Transaction)obj).trits);
    }

    @Override
    public int hashCode() {

        return hashCode;
    }

    @Override
    public String toString() {

        return "\"hash\": \"" + hash() + "\""
                + ", \"valid\": " + valid()
                + ", \"type\": " + type()
                + ", \"signatureMessageChunk\": \"" + Converter.trytes(trits, SIGNATURE_MESSAGE_CHUNK_OFFSET, SIGNATURE_MESSAGE_CHUNK_SIZE) + "\""
                + ", \"digest\": \"" + digest + "\""
                + ", \"address\": \"" + address + "\""
                + ", \"value\": \"" + value + "\""
                + ", \"timestamp\": \"" + timestamp + "\""
                + ", \"index\": \"" + index + "\""
                + ", \"signatureNonce\": \"" + Converter.trytes(trits, SIGNATURE_NONCE_OFFSET, SIGNATURE_NONCE_SIZE) + "\""
                + ", \"approvalNonce\": \"" + Converter.trytes(trits, APPROVAL_NONCE_OFFSET, APPROVAL_NONCE_SIZE) + "\""
                + ", \"approvedTrunkTransaction\": \"" + approvedTrunkTransaction + "\""
                + ", \"approvedBranchTransaction\": \"" + approvedBranchTransaction + "\"";
    }
}
