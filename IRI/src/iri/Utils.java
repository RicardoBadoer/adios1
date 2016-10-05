package iri;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public class Utils {

    static class RawTransaction {

        final int[] signatureMessageChunk;
        final Hash digest;
        final Hash address;
        final long value;
        final long timestamp;
        final long index;
        final int[] signatureNonce;

        final int type;

        RawTransaction(final int type, final int[] privateKeyMessageChunk, final Hash digest, final Hash address, final long value, final long timestamp, final long index) {

            this.type = type;

            signatureMessageChunk = privateKeyMessageChunk;
            this.digest = digest;
            this.address = address;
            this.value = value;
            this.timestamp = timestamp;
            this.index = index;
            signatureNonce = new int[Transaction.SIGNATURE_NONCE_SIZE];

            if (this.type == Transaction.INPUT) {

                for (int i = 0; i < Transaction.SIGNATURE_NONCE_SIZE; i++) {

                    signatureNonce[i] = randomTrit();
                }
            }
        }

        RawTransaction(final Map<String, Object> object) {

            this((Integer)object.get("type"),
                    Converter.trits((String)object.get("privateKeyMessageChunk")),
                    object.containsKey("digest") ? null : new Hash((String)object.get("digest")),
                    new Hash((String)object.get("address")),
                    (Long)object.get("value"), (Long)object.get("timestamp"), (Long)object.get("index"));
        }

        int[] essence() {

            final int[] essence = new int[Transaction.ESSENCE_SIZE];
            System.arraycopy(digest.trits(), 0, essence, Transaction.DIGEST_OFFSET - Transaction.ESSENCE_OFFSET, Transaction.DIGEST_SIZE);
            System.arraycopy(address.trits(), 0, essence, Transaction.ADDRESS_OFFSET - Transaction.ESSENCE_OFFSET, Transaction.ADDRESS_SIZE);
            System.arraycopy(Converter.trits(value, Transaction.VALUE_SIZE), 0, essence, Transaction.VALUE_OFFSET - Transaction.ESSENCE_OFFSET, Transaction.VALUE_SIZE);
            System.arraycopy(Converter.trits(timestamp, Transaction.TIMESTAMP_SIZE), 0, essence, Transaction.TIMESTAMP_OFFSET - Transaction.ESSENCE_OFFSET, Transaction.TIMESTAMP_SIZE);
            System.arraycopy(Converter.trits(index, Transaction.INDEX_SIZE), 0, essence, Transaction.INDEX_OFFSET - Transaction.ESSENCE_OFFSET, Transaction.INDEX_SIZE);

            return essence;
        }

        void sign(final int[] hashChunk) {

            if (type == Transaction.INPUT) {

                Signature.sign(hashChunk, signatureMessageChunk, signatureMessageChunk);
            }
        }
    }

    static class BundleEntry {

        final int[][] privateKey;
        final int[] message;

        final Hash digest;
        final Hash address;
        final long value;
        final long timestamp;

        BundleEntry(final int[][] privateKey, final Hash digest, final long value, final long timestamp) {

            this.privateKey = privateKey;
            message = null;

            this.digest = digest;
            address = Signature.address(Signature.publicKey(privateKey));
            this.value = value;
            this.timestamp = timestamp;
        }

        BundleEntry(final int[] message, final Hash address, final long value, final long timestamp) {

            privateKey = null;
            this.message = message;

            digest = Bastard.hash(this.message, 0, this.message.length);
            this.address = address;
            this.value = value;
            this.timestamp = timestamp;
        }

        List<RawTransaction> rawTransactions(final int firstIndex) {

            final List<RawTransaction> rawTransactions = new LinkedList<>();

            if (privateKey != null) {

                for (int i = 0; i < privateKey.length; i++) {

                    final RawTransaction rawTransaction = new RawTransaction(Transaction.INPUT,
                            privateKey[i],
                            digest, address, (i == 0 ? value : 0), timestamp, firstIndex + rawTransactions.size());
                    rawTransactions.add(rawTransaction);

                    if (i != 0) {

                        System.arraycopy(rawTransactions.get(0).signatureNonce, 0, rawTransaction.signatureNonce, 0, Transaction.SIGNATURE_NONCE_SIZE);
                    }
                }

            } else {

                for (int offset = 0; offset < message.length; offset += Transaction.SIGNATURE_MESSAGE_CHUNK_SIZE) {

                    final RawTransaction rawTransaction = new RawTransaction(Transaction.OUTPUT,
                            Arrays.copyOfRange(message, offset, offset + Transaction.SIGNATURE_MESSAGE_CHUNK_SIZE),
                            digest, address, (offset == 0 ? value : 0), timestamp, firstIndex + rawTransactions.size());
                    rawTransactions.add(rawTransaction);

                    if (offset != 0) {

                        System.arraycopy(rawTransactions.get(0).signatureNonce, 0, rawTransaction.signatureNonce, 0, Transaction.SIGNATURE_NONCE_SIZE);
                    }
                }
            }

            return rawTransactions;
        }
    }

    public static Transaction[] generateBundle(final List<BundleEntry> bundleEntries,
                                               Hash approvedTrunkTransaction, Hash approvedBranchTransaction,
                                               final int minWeightMagnitude) {

        if (approvedTrunkTransaction == null) {

            approvedTrunkTransaction = TipsSelector.randomTip();
        }
        if (approvedBranchTransaction == null) {

            approvedBranchTransaction = TipsSelector.randomTip();
        }

        final List<RawTransaction> rawTransactions = new LinkedList<>();
        for (final BundleEntry bundleEntry : bundleEntries) {

            rawTransactions.addAll(bundleEntry.rawTransactions(rawTransactions.size()));
        }

        final Bastard bastard = new Bastard();
        for (final RawTransaction rawTransaction : rawTransactions) {

            bastard.absorb(rawTransaction.essence(), 0, Transaction.ESSENCE_SIZE);
        }
        final Hash bundle = bastard.hash();

        for (int i = 0; i < rawTransactions.size(); i++) {

            final RawTransaction rawTransaction = rawTransactions.get(i);
            if (rawTransaction.type == Transaction.INPUT) {

                int signatureSecurityLevel = Signature.LOW_SECURITY_LEVEL;

                if (i + 1 < rawTransactions.size()
                        && rawTransactions.get(i + 1).type == rawTransaction.type
                        && rawTransactions.get(i + 1).digest.equals(rawTransaction.digest) && rawTransactions.get(i + 1).address.equals(rawTransaction.address)
                        && rawTransactions.get(i + 1).value == 0 && rawTransactions.get(i + 1).timestamp == rawTransaction.timestamp
                        && Arrays.equals(rawTransactions.get(i + 1).signatureNonce, rawTransaction.signatureNonce)) {

                    signatureSecurityLevel = Signature.MEDIUM_SECURITY_LEVEL;

                    if (i + 2 < rawTransactions.size()
                            && rawTransactions.get(i + 2).type == rawTransaction.type
                            && rawTransactions.get(i + 2).digest.equals(rawTransaction.digest) && rawTransactions.get(i + 2).address.equals(rawTransaction.address)
                            && rawTransactions.get(i + 2).value == 0 && rawTransactions.get(i + 2).timestamp == rawTransaction.timestamp
                            && Arrays.equals(rawTransactions.get(i + 2).signatureNonce, rawTransaction.signatureNonce)) {

                        signatureSecurityLevel = Signature.HIGH_SECURITY_LEVEL;
                    }
                }

                Hash signatureHash;
                while (true) {

                    increment(rawTransaction.signatureNonce, 0, Transaction.SIGNATURE_NONCE_SIZE);

                    bastard.reset();
                    bastard.absorb(bundle.trits(), 0, Bastard.HASH_SIZE);
                    bastard.absorb(rawTransaction.signatureNonce, 0, Transaction.SIGNATURE_NONCE_SIZE);
                    signatureHash = bastard.hash();

                    if (signatureSecurityLevel == Signature.LOW_SECURITY_LEVEL) {

                        int sum = 0;
                        for (int j = 0; j < Signature.PRIVATE_KEY_CHUNK_LENGTH; j++) {

                            sum += Converter.tryteValue(signatureHash.trits(), j * Converter.NUMBER_OF_TRITS_IN_A_TRYTE);
                        }
                        if (sum == 0) {

                            break;
                        }

                    } else if (signatureSecurityLevel == Signature.MEDIUM_SECURITY_LEVEL) {

                        int sum = 0;
                        for (int j = 0; j < Signature.PRIVATE_KEY_CHUNK_LENGTH; j++) {

                            sum += Converter.tryteValue(signatureHash.trits(), j * Converter.NUMBER_OF_TRITS_IN_A_TRYTE);
                        }
                        if (sum != 0) {

                            for (int j = Signature.PRIVATE_KEY_CHUNK_LENGTH; j < Signature.PRIVATE_KEY_CHUNK_LENGTH + Signature.PRIVATE_KEY_CHUNK_LENGTH; j++) {

                                sum += Converter.tryteValue(signatureHash.trits(), j * Converter.NUMBER_OF_TRITS_IN_A_TRYTE);
                            }
                            if (sum == 0) {

                                break;
                            }
                        }

                    } else {

                        int sum = 0;
                        for (int j = 0; j < Signature.PRIVATE_KEY_CHUNK_LENGTH; j++) {

                            sum += Converter.tryteValue(signatureHash.trits(), j * Converter.NUMBER_OF_TRITS_IN_A_TRYTE);
                        }
                        if (sum != 0) {

                            for (int j = Signature.PRIVATE_KEY_CHUNK_LENGTH; j < Signature.PRIVATE_KEY_CHUNK_LENGTH + Signature.PRIVATE_KEY_CHUNK_LENGTH; j++) {

                                sum += Converter.tryteValue(signatureHash.trits(), j * Converter.NUMBER_OF_TRITS_IN_A_TRYTE);
                            }
                            if (sum != 0) {

                                for (int j = Signature.PRIVATE_KEY_CHUNK_LENGTH + Signature.PRIVATE_KEY_CHUNK_LENGTH; j < Signature.PRIVATE_KEY_CHUNK_LENGTH + Signature.PRIVATE_KEY_CHUNK_LENGTH + Signature.PRIVATE_KEY_CHUNK_LENGTH; j++) {

                                    sum += Converter.tryteValue(signatureHash.trits(), j * Converter.NUMBER_OF_TRITS_IN_A_TRYTE);
                                }
                                if (sum == 0) {

                                    break;
                                }
                            }
                        }
                    }
                }

                rawTransaction.sign(Arrays.copyOf(signatureHash.trits(), Signature.PRIVATE_KEY_CHUNK_LENGTH * Converter.NUMBER_OF_TRITS_IN_A_TRYTE));
                if (signatureSecurityLevel > Signature.LOW_SECURITY_LEVEL) {

                    System.arraycopy(rawTransaction.signatureNonce, 0, rawTransactions.get(i + 1).signatureNonce, 0, Transaction.SIGNATURE_NONCE_SIZE);
                    rawTransactions.get(i + 1).sign(Arrays.copyOfRange(signatureHash.trits(),
                            Signature.PRIVATE_KEY_CHUNK_LENGTH * Converter.NUMBER_OF_TRITS_IN_A_TRYTE,
                            Signature.PRIVATE_KEY_CHUNK_LENGTH * Converter.NUMBER_OF_TRITS_IN_A_TRYTE + Signature.PRIVATE_KEY_CHUNK_LENGTH * Converter.NUMBER_OF_TRITS_IN_A_TRYTE));

                    if (signatureSecurityLevel > Signature.MEDIUM_SECURITY_LEVEL) {

                        System.arraycopy(rawTransaction.signatureNonce, 0, rawTransactions.get(i + 2).signatureNonce, 0, Transaction.SIGNATURE_NONCE_SIZE);
                        rawTransactions.get(i + 2).sign(Arrays.copyOfRange(signatureHash.trits(),
                                Signature.PRIVATE_KEY_CHUNK_LENGTH * Converter.NUMBER_OF_TRITS_IN_A_TRYTE + Signature.PRIVATE_KEY_CHUNK_LENGTH * Converter.NUMBER_OF_TRITS_IN_A_TRYTE,
                                Signature.PRIVATE_KEY_CHUNK_LENGTH * Converter.NUMBER_OF_TRITS_IN_A_TRYTE + Signature.PRIVATE_KEY_CHUNK_LENGTH * Converter.NUMBER_OF_TRITS_IN_A_TRYTE + Signature.PRIVATE_KEY_CHUNK_LENGTH * Converter.NUMBER_OF_TRITS_IN_A_TRYTE));
                    }
                }

                i += signatureSecurityLevel;
            }
        }

        final List<Transaction> transactions = new LinkedList<>();

        Hash prevTransaction = null;
        for (int i = rawTransactions.size(); i-- > 0; ) {

            final RawTransaction rawTransaction = rawTransactions.get(i);
            final int[] transactionTrits = (new Transaction(rawTransaction.signatureMessageChunk,
                    rawTransaction.digest, rawTransaction.address,
                    rawTransaction.value, rawTransaction.timestamp, rawTransaction.index,
                    rawTransaction.signatureNonce, new int[Transaction.APPROVAL_NONCE_SIZE],
                    prevTransaction == null ? approvedTrunkTransaction : prevTransaction,
                    prevTransaction == null ? approvedBranchTransaction : approvedTrunkTransaction)).trits;

            ProofOfWorkGenerator.doWork(transactionTrits, minWeightMagnitude);

            final Transaction transaction = new Transaction(transactionTrits);
            transactions.add(transaction);

            prevTransaction = transaction.hash();
        }

        return transactions.toArray(new Transaction[transactions.size()]);
    }

    public static boolean transferIotas(final Hash seed, final int securityLevel, final Hash destination, final long valueToTransfer,
                                        final int minWeightMagnitude) {

        final long timestamp = System.currentTimeMillis() / 1000;

        final Map<Hash, Long> sourcesValues = new HashMap<>();
        final Map<Hash, Integer> sourcesIndices = new HashMap<>();

        for (final Map.Entry<Hash, Integer> addressEntry : addresses(seed, securityLevel).entrySet()) {

            sourcesValues.put(addressEntry.getKey(), 0L);
            sourcesIndices.put(addressEntry.getKey(), addressEntry.getValue());
        }

        final Set<Hash> includedTransactions = new HashSet<>();
        Tangle.getIncludedTransactions(includedTransactions);
        for (final Hash hash : includedTransactions) {

            final Storage.Transaction transaction = Storage.loadTransaction(hash.bytes);
            if (transaction != null) {

                final Hash address = new Hash(transaction.address);
                final Long prevValue = sourcesValues.get(address);
                if (prevValue != null) {

                    sourcesValues.put(address, prevValue + transaction.value);
                }
            }
        }

        long totalValue = 0;
        final Map<Hash, Long> usedSources = new HashMap<>();

        for (final Map.Entry<Hash, Long> sourceValueEntry : sourcesValues.entrySet()) {

            if (sourceValueEntry.getValue() > 0) {

                totalValue += sourceValueEntry.getValue();
                usedSources.put(sourceValueEntry.getKey(), sourceValueEntry.getValue());

                if (totalValue >= valueToTransfer) {

                    break;
                }
            }
        }

        if (totalValue < valueToTransfer) {

            return false;
        }

        final List<BundleEntry> bundleEntries = new LinkedList<>();

        bundleEntries.add(new BundleEntry(new int[Transaction.SIGNATURE_MESSAGE_CHUNK_SIZE], destination, valueToTransfer, timestamp));
        if (valueToTransfer < totalValue) {

            bundleEntries.add(new BundleEntry(new int[Transaction.SIGNATURE_MESSAGE_CHUNK_SIZE], Signature.address(Signature.publicKey(Signature.privateKey(Signature.subseed(seed.trits(), newAddressIndex(seed, securityLevel)), securityLevel))), totalValue - valueToTransfer, timestamp));
        }

        for (final Map.Entry<Hash, Long> usedSource : usedSources.entrySet()) {

            bundleEntries.add(new BundleEntry(Signature.privateKey(Signature.subseed(seed.trits(), sourcesIndices.get(usedSource.getKey())), securityLevel), Hash.NULL_HASH, -usedSource.getValue(), timestamp));
        }

        final Transaction[] transactions = generateBundle(bundleEntries, null, null, minWeightMagnitude);
        for (int i = transactions.length; i-- > 0; ) { // Broadcast from the head to the tail

            Broadcaster.push(Converter.bytes(transactions[i].trits));
        }

        return true;
    }

    public static Map<Hash, Integer> addresses(final Hash seed, final int securityLevel) {

        final Map<Hash, Integer> addresses = new HashMap<>();

        for (int i = 0; i < Integer.MAX_VALUE; i++) {

            final int[] subseed = Signature.subseed(seed.trits(), i);
            final int[][] privateKey = Signature.privateKey(subseed, securityLevel);
            final int[] publicKey = Signature.publicKey(privateKey);
            final Hash address = Signature.address(publicKey);

            if (Storage.addressPointer(address.bytes) != 0) {

                addresses.put(address, i);

            } else {

                break;
            }
        }

        return addresses;
    }

    public static int newAddressIndex(final Hash seed, final int securityLevel) {

        for (int i = 0; i < Integer.MAX_VALUE; i++) {

            final int[] subseed = Signature.subseed(seed.trits(), i);
            final int[][] privateKey = Signature.privateKey(subseed, securityLevel);
            final int[] publicKey = Signature.publicKey(privateKey);
            final Hash address = Signature.address(publicKey);

            if (Storage.addressPointer(address.bytes) == 0) {

                return i;
            }
        }

        return -1;
    }

    public static int randomTrit() {

        return ThreadLocalRandom.current().nextInt(Converter.RADIX) - 1;
    }

    public static void increment(final int[] trits, final int offset, final int size) {

        for (int i = offset; i < offset + size; i++) {

            if (++trits[i] > Converter.MAX_TRIT_VALUE) {

                trits[i] = Converter.MIN_TRIT_VALUE;

            } else {

                break;
            }
        }
    }
}
