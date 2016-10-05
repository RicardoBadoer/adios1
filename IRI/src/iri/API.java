package iri;

import javax.script.*;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.channels.*;
import java.util.*;
import java.util.concurrent.*;

import iri.*;


class API {

    public static final int PORT = 14265;

    public static final String MAM_COMMANDS_PREFIX = "mam.";

    static ScriptEngine scriptEngine;
    static AsynchronousServerSocketChannel serverChannel;

    static void launch() throws IOException {

        scriptEngine = (new ScriptEngineManager()).getEngineByName("JavaScript");

        serverChannel = AsynchronousServerSocketChannel.open(AsynchronousChannelGroup.withThreadPool(Executors.newCachedThreadPool()));
        serverChannel.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), PORT));
        serverChannel.accept(null, new CompletionHandler<AsynchronousSocketChannel, Void>() {

            @Override
            public void completed(final AsynchronousSocketChannel clientChannel, final Void attachment) {

                serverChannel.accept(null, this);

                new Request(clientChannel);
            }

            @Override
            public void failed(final Throwable e, final Void attachment) {
            }
        });
    }

    static void shutDown() {

        try {

            serverChannel.close();

        } catch (final Exception e) {
        }
    }

    static String array(final List<String> elements) {

        String array = "";
        for (int i = 0; i < elements.size(); i++) {

            if (i > 0) {

                array += ", ";
            }

            array += elements.get(i);
        }

        return "[" + array + "]";
    }

    static class Request {

        static final int READING_BUFFER_SIZE = 4096;

        final AsynchronousSocketChannel channel;
        ByteBuffer buffer;
        final ByteArrayOutputStream accumulatedData;

        Request(final AsynchronousSocketChannel channel) {

            this.channel = channel;
            buffer = ByteBuffer.allocateDirect(READING_BUFFER_SIZE);
            accumulatedData = new ByteArrayOutputStream();

            channel.read(buffer, this, new CompletionHandler<Integer, Request>() {

                @Override
                public void completed(final Integer numberOfBytes, final Request request) {

                    try {

                        if (numberOfBytes >= 0) {

                            buffer.flip();
                            final byte[] bufferBytes = new byte[buffer.remaining()];
                            buffer.get(bufferBytes);
                            accumulatedData.write(bufferBytes, 0, bufferBytes.length);
                            final String requestString = accumulatedData.toString();

                            final int crlfcrlfOffset = requestString.indexOf("\r\n\r\n");
                            if (crlfcrlfOffset >= 0) {

                                final int contentLengthOffset = requestString.indexOf("Content-Length:");
                                if (contentLengthOffset >= 0) {

                                    final int contentLengthCRLFOffset = requestString.indexOf("\r\n", contentLengthOffset);
                                    if (contentLengthCRLFOffset >= 0) {

                                        final String body = requestString.substring(crlfcrlfOffset + 4);
                                        final int contentLengthValue = Integer.parseInt(requestString.substring(contentLengthOffset + 15, contentLengthCRLFOffset).trim());
                                        if (body.length() == contentLengthValue) {

                                            process(body);

                                            return;
                                        }
                                    }
                                }
                            }

                            buffer.clear();
                            channel.read(buffer, request, this);

                        } else {

                            channel.close();
                        }

                    } catch (final Exception e) {

                        e.printStackTrace();

                        try {

                            channel.close();

                        } catch (final Exception e2) {

                            e2.printStackTrace();
                        }
                    }
                }

                @Override
                public void failed(final Throwable e, final Request request) {

                    e.printStackTrace();

                    try {

                        channel.close();

                    } catch (final Exception e2) {

                        e2.printStackTrace();
                    }
                }
            });
        }

        void process(final String requestString) throws UnsupportedEncodingException {

            String response;

            try {

                final Map<String, Object> request = (Map<String, Object>)scriptEngine.eval("Java.asJSONCompatible(" + requestString + ");");

                final String command = (String)request.get("command");
                if (command == null) {

                    response = "\"error\": \"'command' parameter has not been specified\"";

                } else {

                    switch (command) {

                        case "analyzeTransactions": {

                            final List<String> elements = new LinkedList<>();
                            for (final String trytes : (List<String>)request.get("trytes")) {

                                elements.add("{" + new Transaction(Converter.bytes(Converter.trits(trytes))) + "}");
                            }

                            response = "\"transactions\": " + array(elements);

                        } break;

                        case "attachToTangle": {

                            final Hash trunkTransactionToApprove = new Hash((String)request.get("trunkTransactionToApprove"));
                            final Hash branchTransactionToApprove = new Hash((String)request.get("branchTransactionToApprove"));
                            final int minWeightMagnitude = (Integer)request.get("minWeightMagnitude");

                            final List<Transaction> transactions = new LinkedList<>();

                            Hash prevTransaction = null;
                            final List<String> trytes = (List<String>)request.get("trytes");
                            for (int i = 0; i < trytes.size(); i++) {

                                final int[] transactionTrits = Converter.trits(trytes.get(i));
                                System.arraycopy((prevTransaction == null ? trunkTransactionToApprove : prevTransaction).trits(), 0, transactionTrits, Transaction.APPROVED_TRUNK_TRANSACTION_TRINARY_OFFSET, Transaction.APPROVED_TRUNK_TRANSACTION_TRINARY_SIZE);
                                System.arraycopy((prevTransaction == null ? branchTransactionToApprove : trunkTransactionToApprove).trits(), 0, transactionTrits, Transaction.APPROVED_BRANCH_TRANSACTION_TRINARY_OFFSET, Transaction.APPROVED_BRANCH_TRANSACTION_TRINARY_SIZE);

                                Utils.doWork(transactionTrits, minWeightMagnitude);

                                final Transaction transaction = new Transaction(Converter.bytes(transactionTrits));
                                transactions.add(transaction);
                                prevTransaction = new Hash(transaction.hash, 0, Transaction.HASH_SIZE);
                            }

                            final List<String> elements = new LinkedList<>();
                            for (int i = transactions.size(); i-- > 0; ) {

                                elements.add("\"" + Converter.trytes(transactions.get(i).trits()) + "\"");
                            }

                            response = "\"trytes\": " + array(elements);

                        } break;

                        case "broadcastTransactions": {

                            for (final String trytes : (List<String>)request.get("trytes")) {

                                final Transaction transaction = new Transaction(Converter.bytes(Converter.trits(trytes)));
                                Broadcaster.broadcast(transaction.bytes);
                            }

                            response = "";

                        } break;

                        case "findTransactions": {

                            final Set<Long> bundlesTransactions;
                            if (request.containsKey("bundles")) {

                                bundlesTransactions = new HashSet<>();
                                for (final String bundle : (List<String>)request.get("bundles")) {

                                    bundlesTransactions.addAll(Storage.bundleTransactions(Storage.bundlePointer((new Hash(bundle)).bytes)));
                                }

                            } else {

                                bundlesTransactions = null;
                            }

                            final Set<Long> addressesTransactions;
                            if (request.containsKey("addresses")) {

                                addressesTransactions = new HashSet<>();
                                for (final String address : (List<String>)request.get("addresses")) {

                                    addressesTransactions.addAll(Storage.addressTransactions(Storage.addressPointer((new Hash(address)).bytes)));
                                }

                            } else {

                                addressesTransactions = null;
                            }

                            final Set<Long> digestsTransactions;
                            if (request.containsKey("digests")) {

                                digestsTransactions = new HashSet<>();
                                for (final String digest : (List<String>)request.get("digests")) {

                                    digestsTransactions.addAll(Storage.digestTransactions(Storage.digestPointer((new Hash(digest)).bytes)));
                                }

                            } else {

                                digestsTransactions = null;
                            }

                            final Set<Long> approveeTransactions;
                            if (request.containsKey("approvees")) {

                                approveeTransactions = new HashSet<>();
                                for (final String approvee : (List<String>)request.get("approvees")) {

                                    approveeTransactions.addAll(Storage.approveeTransactions(Storage.approveePointer((new Hash(approvee)).bytes)));
                                }

                            } else {

                                approveeTransactions = null;
                            }

                            final Set<Long> foundTransactions = bundlesTransactions == null ? (addressesTransactions == null ? (digestsTransactions == null ? (approveeTransactions == null ? new HashSet<>() : approveeTransactions) : digestsTransactions) : addressesTransactions) : bundlesTransactions;
                            if (addressesTransactions != null) {

                                foundTransactions.retainAll(addressesTransactions);
                            }
                            if (digestsTransactions != null) {

                                foundTransactions.retainAll(digestsTransactions);
                            }
                            if (approveeTransactions != null) {

                                foundTransactions.retainAll(approveeTransactions);
                            }

                            final List<String> elements = new LinkedList<>();
                            for (final long pointer : foundTransactions) {

                                elements.add("\"" + new Hash(Storage.loadTransaction(pointer).hash, 0, Transaction.HASH_SIZE) + "\"");
                            }

                            response = "\"hashes\": " + array(elements);

                        } break;

                        case "getAddress": {

                            final List<String> publicKeysTrytes = (List<String>)request.get("publicKeys");
                            final int[][] publicKeys = new int[publicKeysTrytes.size()][];
                            for (int i = 0; i < publicKeys.length; i++) {

                                publicKeys[i] = Converter.trits(publicKeysTrytes.get(i));
                            }

                            response = "\"address\": \"" + Signature.address(publicKeys) + "\"";

                        } break;

                        case "getBundle": {

                            final Hash transactionHash = new Hash((String)request.get("transaction"));

                            final Transaction transaction = Storage.loadTransaction(transactionHash.bytes);
                            if (transaction == null) {

                                response = "\"transactions\": []";

                            } else {

                                final List<String> elements = new LinkedList<>();

                                for (final List<Transaction> bundleTransactions : (new Bundle(transaction.bundle)).transactions) {

                                    //TODO ??????

                                    if (bundleTransactions.get(0).pointer == transaction.pointer) {

                                        for (final Transaction bundleTransaction : bundleTransactions) {

                                            elements.add("{" + bundleTransaction + "}");
                                        }

                                        break;
                                    }
                                }

                                response = "\"transactions\": " + array(elements);
                            }

                        } break;

                        case "getConfig": {

                            response = "\"lines\": [" + Configuration.lines() + "]";

                        } break;

                        case "getNeighborsActivity": {

                            final Set<String> nodes = new HashSet<>(Node.nodesLatestSentPacketsTimes.keySet());
                            nodes.addAll(Node.nodesLatestSentPacketsTimes.keySet());

                            final List<String> elements = new LinkedList<>();

                            final long curTime = System.currentTimeMillis();
                            for (final String node : nodes) {

                                elements.add("{\"node\": \"" + node
                                        + "\", \"latestPacketSent\": " + (Node.nodesLatestSentPacketsTimes.containsKey(node) ? (curTime - Node.nodesLatestSentPacketsTimes.get(node)) : null)
                                        + ", \"latestPacketReceived\": " + (Node.nodesLatestReceivedPacketsTimes.containsKey(node) ? (curTime - Node.nodesLatestReceivedPacketsTimes.get(node)) : null)
                                        + ", \"nonSeenTransactions\": " + (Node.nodesNonSeenTransactions.containsKey(node) ? Node.nodesNonSeenTransactions.get(node) : 0)
                                        + ", \"seenTransactions\": " + (Node.nodesSeenTransactions.containsKey(node) ? Node.nodesSeenTransactions.get(node) : 0) + "}");
                            }

                            response = "\"neighbors\": " + array(elements);

                        } break;

                        case "getNewAddress": {

                            final Hash seed = new Hash((String)request.get("seed"));
                            final int securityLevel = (Integer)request.get("securityLevel");

                            final int index = Utils.newAddressIndex(seed, securityLevel);
                            final int[] subseed = Signature.subseed(seed.trits(), index);
                            final int[][] privateKey = Signature.privateKey(subseed, securityLevel);
                            final int[] publicKey = Signature.publicKey(privateKey);
                            final Hash address = Signature.address(publicKey);

                            response = "\"address\": \"" + address + "\"";

                        } break;

                        case "getNodeInfo": {

                            response = "\"appName\": \"" + IRI.NAME + "\""
                                    + ", \"appVersion\": \"" + IRI.VERSION + "\""
                                    + ", \"incomingPacketsBacklog\": " + Node.envelopes.size()
                                    + ", \"jreAvailableProcessors\": " + Runtime.getRuntime().availableProcessors()
                                    + ", \"jreFreeMemory\": " + Runtime.getRuntime().freeMemory()
                                    + ", \"jreMaxMemory\": " + Runtime.getRuntime().maxMemory()
                                    + ", \"jreTotalMemory\": " + Runtime.getRuntime().totalMemory()
                                    + ", \"milestone\": \"" + TipsManager.curTip + "\""
                                    + ", \"neighbors\": " + Node.nodes.size()
                                    + ", \"time\": " + System.currentTimeMillis()
                                    + ", \"tips\": " + Storage.tips().size()
                                    + ", \"transactionsToRequest\": " + Storage.numberOfTransactionsToRequest;

                        }
                        break;

                        case "getPublicKey": {

                            final int[][] privateKey = privateKey((String)request.get("privateKey"));
                            if (privateKey == null) {

                                response = "\"error\": \"Illegal private key size\"";

                            } else {

                                response = "\"publicKey\": \"" + Converter.trytes(Signature.publicKey(privateKey)) + "\"";
                            }

                        } break;

                        case "getTips": {

                            final List<String> elements = new LinkedList<>();
                            for (final Hash tip : Storage.tips()) {

                                elements.add("\"" + tip + "\"");
                            }

                            response = "\"hashes\": " + array(elements);

                        } break;

                        case "getTransactionsToApprove": {

                            final Hash milestone = new Hash((String)request.get("milestone"));

                            final Hash trunkTransactionToApprove = TipsManager.transactionToApprove(null, milestone);
                            if (trunkTransactionToApprove == null) {

                                response = "\"error\": \"The confirmed subtangle is not solid\"";

                            } else {

                                final Hash branchTransactionToApprove = TipsManager.transactionToApprove(trunkTransactionToApprove, milestone);
                                if (branchTransactionToApprove == null) {

                                    response = "\"error\": \"The confirmed subtangle is not solid\"";

                                } else {

                                    response = "\"trunkTransactionToApprove\": \"" + trunkTransactionToApprove + "\", \"branchTransactionToApprove\": \"" + branchTransactionToApprove + "\"";
                                }
                            }

                        } break;

                        case "getTransfers": {

                            final Hash seed = new Hash((String)request.get("seed"));
                            final int securityLevel = (Integer)request.get("securityLevel");

                            final Set<Hash> addresses = Utils.addresses(seed, securityLevel).keySet();
                            final Set<Hash> bundles = new HashSet<>();

                            for (final Hash address : addresses) {

                                for (final long transactionPointer : Storage.addressTransactions(Storage.addressPointer(address.bytes))) {

                                    bundles.add(new Hash(Storage.loadTransaction(transactionPointer).bundle, 0, Transaction.BUNDLE_SIZE));
                                }
                            }

                            final Set<Bundle> sortedBundles = new TreeSet<>((bundle1, bundle2) -> {

                                final Transaction transaction1 = bundle1.transactions.get(0).get(0);
                                final Transaction transaction2 = bundle2.transactions.get(0).get(0);

                                if (transaction1.timestamp < transaction2.timestamp) {

                                    return -1;

                                } else if (transaction1.timestamp > transaction2.timestamp) {

                                    return 1;
                                }

                                for (int i = 0; i < transaction1.hash.length; i++) {

                                    if (transaction1.hash[i] != transaction2.hash[i]) {

                                        return transaction1.hash[i] - transaction2.hash[i];
                                    }
                                }

                                return 0;
                            });

                            for (final Hash bundle : bundles) {

                                final Bundle bundleObject = new Bundle(bundle.bytes);
                                if (bundleObject.transactions.size() > 0) {

                                    sortedBundles.add(bundleObject);
                                }
                            }

                            final List<String> elements = new LinkedList<>();

                            long balance = 0;
                            for (final Map.Entry<Hash, Long> entry : Tangle.initialState.entrySet()) {

                                if (addresses.contains(entry.getKey())) {

                                    balance += entry.getValue();
                                }
                            }
                            if (balance != 0) {

                                elements.add("{\"hash\": null, \"timestamp\": \"0\", \"address\": null, \"value\": \"" + balance + "\", \"persistence\": 100}");
                            }

                            synchronized (Storage.analyzedTransactionsFlags) {

                                Utils.prepareIncludedTransactions();

                                for (final Bundle bundle : sortedBundles) {

                                    int bestInstance;
                                    for (bestInstance = 0; bestInstance < bundle.transactions.size(); bestInstance++) {

                                        if (Storage.analyzedTransactionFlag(bundle.transactions.get(bestInstance).get(0).pointer)) {

                                            break;
                                        }
                                    }
                                    final int persistence;
                                    if (bestInstance == bundle.transactions.size()) {

                                        bestInstance = 0;
                                        persistence = 0;

                                    } else {

                                        persistence = 100;
                                    }

                                    long value = 0;
                                    for (final Transaction transaction : bundle.transactions.get(bestInstance)) {

                                        if (addresses.contains(new Hash(transaction.address, 0, Transaction.ADDRESS_SIZE))) {

                                            value += transaction.value;
                                        }
                                    }

                                    Hash address = null;
                                    boolean severalAddresses = false;
                                    for (final Transaction transaction : bundle.transactions.get(bestInstance)) {

                                        final Hash transactionAddress = new Hash(transaction.address, 0, Transaction.ADDRESS_SIZE);
                                        if (value > 0) {

                                            if (transaction.value < 0 && !addresses.contains(transactionAddress)) {

                                                if (address == null) {

                                                    address = transactionAddress;

                                                } else if (!address.equals(transactionAddress)) {

                                                    severalAddresses = true;
                                                }
                                            }

                                        } else if (value < 0) {

                                            if (transaction.value > 0 && !addresses.contains(transactionAddress)) {

                                                if (address == null) {

                                                    address = transactionAddress;

                                                } else if (!address.equals(transactionAddress)) {

                                                    severalAddresses = true;
                                                }
                                            }

                                        } else {

                                            if (addresses.contains(transactionAddress)) {

                                                if (address == null) {

                                                    address = transactionAddress;

                                                } else if (!address.equals(transactionAddress)) {

                                                    severalAddresses = true;
                                                }
                                            }
                                        }
                                    }

                                    elements.add("{\"hash\": \"" + new Hash(bundle.transactions.get(bestInstance).get(0).hash, 0, Transaction.HASH_SIZE) + "\", \"timestamp\": \"" + bundle.transactions.get(bestInstance).get(0).timestamp + "\", \"address\": " + (address == null ? null : ("\"" + (severalAddresses ? "" : address) + "\"")) + ", \"value\": \"" + value + "\", \"persistence\": " + persistence + "}");
                                }
                            }

                            response = "\"transfers\": " + array(elements);

                        } break;

                        case "getTrytes": {

                            final List<String> elements = new LinkedList<>();

                            for (final String hash : (List<String>)request.get("hashes")) {

                                final Transaction transaction = Storage.loadTransaction((new Hash(hash)).bytes);
                                elements.add(transaction == null ? "null" : ("\"" + Converter.trytes(transaction.trits()) + "\""));
                            }

                            response = "\"trytes\": " + array(elements);

                        } break;

                        case "prepareTransfers": {

                            final Hash seed = new Hash((String)request.get("seed"));
                            final int securityLevel = (Integer)request.get("securityLevel");

                            long requiredTotalValue = 0;
                            for (final Map<String, Object> transfer : (List<Map<String, Object>>)request.get("transfers")) {

                                final long value = Long.parseLong((String)transfer.get("value"));
                                if (value < 0 || value > Transaction.MAX_TRANSFERABLE_VALUE) {

                                    requiredTotalValue = -1;

                                    break;
                                }
                                requiredTotalValue += value;
                            }

                            if (requiredTotalValue < 0) {

                                response = "\"error\": \"Illegal 'value'\"";

                            } else {

                                long availableTotalValue = 0;
                                final Map<Integer, Long> usedSources = new HashMap<>();

                                if (requiredTotalValue > 0) {

                                    for (final Map.Entry<Integer, Long> entry : Utils.unspentAddresses(seed, securityLevel).entrySet()) {

                                        final long clampedValue = entry.getValue() > Transaction.MAX_TRANSFERABLE_VALUE ? Transaction.MAX_TRANSFERABLE_VALUE : entry.getValue();
                                        usedSources.put(entry.getKey(), clampedValue);
                                        availableTotalValue += clampedValue;

                                        if (availableTotalValue >= requiredTotalValue) {

                                            break;
                                        }
                                    }

                                    if (availableTotalValue < requiredTotalValue) {

                                        response = "\"error\": \"Not enough iotas\"";

                                        break;
                                    }
                                }

                                final List<Utils.BundleEntry> bundleEntries = new LinkedList<>();

                                final long timestamp = System.currentTimeMillis() / 1000;
                                for (final Map<String, Object> transfer : (List<Map<String, Object>>)request.get("transfers")) {

                                    final Hash address = new Hash((String)transfer.get("address"));
                                    final long value = Long.parseLong((String)transfer.get("value"));
                                    final int[] message = Converter.trits((String)transfer.get("message"));

                                    bundleEntries.add(new Utils.BundleEntry(Arrays.copyOf(message, Transaction.SIGNATURE_MESSAGE_CHUNK_TRINARY_SIZE), address, value, timestamp));
                                    for (int offset = Transaction.SIGNATURE_MESSAGE_CHUNK_TRINARY_SIZE; offset < message.length; ) {

                                        bundleEntries.add(new Utils.BundleEntry(Arrays.copyOfRange(message, offset, offset += Transaction.SIGNATURE_MESSAGE_CHUNK_TRINARY_SIZE), address, 0, timestamp));
                                    }
                                }
                                if (requiredTotalValue < availableTotalValue) {

                                    bundleEntries.add(new Utils.BundleEntry(new int[Transaction.SIGNATURE_MESSAGE_CHUNK_TRINARY_SIZE], Signature.address(Signature.publicKey(Signature.privateKey(Signature.subseed(seed.trits(), Utils.newAddressIndex(seed, securityLevel)), securityLevel))), availableTotalValue - requiredTotalValue, timestamp));
                                }
                                for (final Map.Entry<Integer, Long> usedSource : usedSources.entrySet()) {

                                    bundleEntries.add(new Utils.BundleEntry(Signature.privateKey(Signature.subseed(seed.trits(), usedSource.getKey()), securityLevel), Hash.NULL_HASH, -usedSource.getValue(), timestamp));
                                }

                                final List<String> elements = new LinkedList<>();
                                for (final Transaction transaction : Utils.generateBundle(bundleEntries, Hash.NULL_HASH, Hash.NULL_HASH, 0)) {

                                    elements.add("\"" + Converter.trytes(transaction.trits()) + "\"");
                                }

                                response = "\"trytes\": " + array(elements);
                            }

                        } break;

                        case "replayTransfer": {

                            final Hash keyTransactionHash = new Hash((String)request.get("transaction"));

                            final Transaction keyTransaction = Storage.loadTransaction(keyTransactionHash.bytes);
                            if (keyTransaction == null) {

                                response = "\"error\": \"The transaction is not found\"";

                            } else {

                                final Bundle bundle = new Bundle(keyTransaction.bundle);
                                if (bundle.transactions.size() == 0) {

                                    response = "\"error\": \"The transfer can't be replayed because some of its transactions haven't been received yet\"";

                                } else {

                                    final Map<Hash, Long> addressesToSpend = new HashMap<>();

                                    final Transaction[] oldTransactions = new Transaction[bundle.transactions.get(0).size()];
                                    for (int i = 0; i < oldTransactions.length; i++) {

                                        final Transaction transaction = bundle.transactions.get(0).get(i);

                                        final Hash address = new Hash(transaction.address, 0, Transaction.ADDRESS_SIZE);
                                        final Long value = addressesToSpend.get(address);
                                        addressesToSpend.put(address, value == null ? -transaction.value : (value - transaction.value));

                                        oldTransactions[i] = transaction;
                                    }

                                    final Map<Hash, Long> unspentAddresses = Utils.unspentAddresses(addressesToSpend.keySet());
                                    boolean replayable = true;
                                    for (final Map.Entry<Hash, Long> addressToSpendEntry : addressesToSpend.entrySet()) {

                                        if (addressToSpendEntry.getValue() > 0) {

                                            final Long value = unspentAddresses.get(addressToSpendEntry.getKey());
                                            if (value == null || value < addressToSpendEntry.getValue()) {

                                                replayable = false;

                                                break;
                                            }
                                        }
                                    }
                                    if (replayable) {

                                        final Transaction[] newTransactions = Utils.replay(oldTransactions, null, null, Transaction.MIN_WEIGHT_MAGNITUDE);
                                        if (newTransactions == null) {

                                            response = "\"error\": \"The confirmed subtangle is not solid\"";

                                        } else {

                                            for (int i = newTransactions.length; i-- > 0; ) { // Broadcast from the head to the tail

                                                final Transaction transaction = newTransactions[i];
                                                Storage.storeTransaction(transaction.hash, transaction, false);
                                                Broadcaster.broadcast(transaction.bytes);
                                            }

                                            response = "\"neighbors\": " + Node.nodes.size() + ", \"warning\": \"This API command will be removed soon\"";
                                        }

                                    } else {

                                        response = "\"error\": \"The transfer is not replayable now\"";
                                    }
                                }
                            }

                        } break;

                        case "resetNeighborsActivityCounters": {

                            Node.nodesLatestSentPacketsTimes.clear();
                            Node.nodesLatestReceivedPacketsTimes.clear();
                            Node.nodesNonSeenTransactions.clear();
                            Node.nodesSeenTransactions.clear();

                            response = "";

                        } break;

                        case "setConfig": {

                            Configuration.initialize(((List<String>)request.get("lines")).toArray(new String[0]));

                            response = "";

                        } break;

                        case "storeTransactions": {

                            for (final String trytes : (List<String>)request.get("trytes")) {

                                final Transaction transaction = new Transaction(Converter.bytes(Converter.trits(trytes)));
                                Storage.storeTransaction(transaction.hash, transaction, false);
                            }

                            response = "";

                        } break;

                        case "transfer": {

                            final Hash seed = new Hash((String)request.get("seed"));
                            final int securityLevel = (Integer)request.get("securityLevel");
                            final Hash address = new Hash((String)request.get("address"));
                            final long value = Long.parseLong((String)request.get("value"));
                            final int[] message = Converter.trits((String)request.get("message"));
                            final int minWeightMagnitude = (Integer)request.get("minWeightMagnitude");

                            if (value < 0 || value > Transaction.MAX_TRANSFERABLE_VALUE) {

                                response = "\"error\": \"Illegal 'value'\"";

                            } else {

                                final Hash tail = Utils.transfer(seed, securityLevel, address, value, message, minWeightMagnitude);
                                if (tail != null && tail.equals(Hash.NULL_HASH)) {

                                    response = "\"error\": \"The confirmed subtangle is not solid\"";

                                } else {

                                    response = "\"tail\": " + (tail == null ? null : ("\"" + tail + "\""))
                                            + ", \"neighbors\": " + Node.nodes.size() + ", \"warning\": \"This API command will be removed soon\"";
                                }
                            }

                        } break;

                        default: {

                            if (command.startsWith(MAM_COMMANDS_PREFIX)) {

                                switch (command.substring(MAM_COMMANDS_PREFIX.length())) {

                                    /*case "fetchMessage": {

                                        final Hash source = new Hash((String)request.get("source"));
                                        final Hash address = new Hash((String)request.get("address"));

                                        response="";

                                    } break;

                                    case "prepareMessage": {

                                        final int[][] privateKey = privateKey((String)request.get("privateKey"));
                                        if (privateKey == null) {

                                            response = "\"error\": \"Illegal private key size\"";

                                        } else {

                                            final Hash source = new Hash((String)request.get("source"));
                                            final int[] message = Converter.trits((String)request.get("message"));
                                            final Hash followingAddress = new Hash((String)request.get("followingAddress"));

                                            final List<Utils.BundleEntry> bundleEntries = new LinkedList<>();

                                            final long timestamp = System.currentTimeMillis() / 1000;
                                            bundleEntries.add(new Utils.BundleEntry(Arrays.copyOf(message, Transaction.SIGNATURE_MESSAGE_CHUNK_TRINARY_SIZE), address, 0, timestamp));
                                            for (int offset = Transaction.SIGNATURE_MESSAGE_CHUNK_TRINARY_SIZE; offset < message.length; ) {

                                                bundleEntries.add(new Utils.BundleEntry(Arrays.copyOfRange(message, offset, offset += Transaction.SIGNATURE_MESSAGE_CHUNK_TRINARY_SIZE), address, 0, timestamp));
                                            }
                                            bundleEntries.add(new Utils.BundleEntry(privateKey, Hash.NULL_HASH, 0, timestamp));

                                            final List<String> elements = new LinkedList<>();
                                            for (final Transaction transaction : Utils.generateBundle(bundleEntries, Hash.NULL_HASH, Hash.NULL_HASH, 0)) {

                                                elements.add("\"" + Converter.trytes(transaction.trits()) + "\"");
                                            }

                                            response = "\"trytes\": " + array(elements);

                                        }

                                    } break;*/

                                    default: {

                                        response = "\"error\": \"Command '" + command + "' is unknown\"";
                                    }
                                }

                            } else {

                                response = "\"error\": \"Command '" + command + "' is unknown\"";
                            }
                        }
                    }
                }

            } catch (final Exception e) {

                e.printStackTrace();

                response = "\"exception\": \"" + e.toString().replaceAll("\"", "'").replace("\r", "\\r").replace("\n", "\\n") + "\"";
            }

            response = "{" + response + "}";
            response = "HTTP/1.1 200 OK\r\nAccess-Control-Allow-Origin: *\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: " + response.getBytes("UTF-8").length + "\r\nConnection: close\r\n\r\n" + response;
            final byte[] responseBytes = response.getBytes("UTF-8");
            buffer = ByteBuffer.allocateDirect(responseBytes.length);
            buffer.put(responseBytes);
            buffer.flip();
            channel.write(buffer, this, new CompletionHandler<Integer, Request>() {

                @Override
                public void completed(final Integer numberOfBytes, final Request request) {

                    if (buffer.hasRemaining()) {

                        channel.write(buffer, request, this);

                    } else {

                        try {

                            channel.close();

                        } catch (final Exception e) {
                        }
                    }
                }

                @Override
                public void failed(final Throwable e, final Request request) {

                    try {

                        channel.close();

                    } catch (final Exception e2) {
                    }
                }
            });
        }

        private static int[][] privateKey(final String privateKeyTrytes) {

            if (privateKeyTrytes.length() == 0 || privateKeyTrytes.length() > 81 || privateKeyTrytes.length() % 27 != 0) {

                return null;
            }

            final int[][] privateKey = new int[privateKeyTrytes.length() / 27][];
            for (int i = 0; i < privateKey.length; i++) {

                privateKey[i] = Converter.trits(privateKeyTrytes.substring(i * 27, (i + 1) * 27));
            }

            return privateKey;
        }
    }
}
