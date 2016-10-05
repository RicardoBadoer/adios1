package iri;

import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.Arrays;
import java.util.concurrent.*;

class Storage {

    public static final int CELL_SIZE = 2048;
    public static final int CELLS_PER_CHUNK = 65536;
    public static final int CHUNK_SIZE = CELL_SIZE * CELLS_PER_CHUNK;
    public static final int MAX_NUMBER_OF_CHUNKS = 16384; // Limits the storage capacity to ~1 billion transactions
    public static final int SCRATCHPAD_SIZE = CHUNK_SIZE;

    public static final int CELLS_STATES_OFFSET = 0, CELLS_STATES_SIZE = MAX_NUMBER_OF_CHUNKS * CELLS_PER_CHUNK / Byte.SIZE;
    public static final int TIPS_FLAGS_OFFSET = CELLS_STATES_OFFSET + CELLS_STATES_SIZE, TIPS_FLAGS_SIZE = MAX_NUMBER_OF_CHUNKS * CELLS_PER_CHUNK / Byte.SIZE;
    public static final int SUPER_GROUPS_OFFSET = TIPS_FLAGS_OFFSET + TIPS_FLAGS_SIZE, SUPER_GROUPS_SIZE = (Short.MAX_VALUE - Short.MIN_VALUE + 1) * CELL_SIZE;
    public static final int CELLS_OFFSET = SUPER_GROUPS_OFFSET + SUPER_GROUPS_SIZE;

    public static final int INPUT_TRANSACTION = -1;
    public static final int GROUP = 0;
    public static final int OUTPUT_TRANSACTION = 1;

    public static final int CELLS_STATES_BUFFER_SIZE = Integer.BYTES;
    public static final int CELLS_BUFFER_SIZE = CELLS_STATES_BUFFER_SIZE * Byte.SIZE * CELL_SIZE;

    public static final byte[] ZEROED_BUFFER = new byte[CELL_SIZE];

    public static final String TRANSACTIONS_FILE_NAME = "transactions.iri";
    public static final String ADDRESSES_FILE_NAME = "addresses.iri";
    public static final String DIGESTS_FILE_NAME = "digests.iri";
    public static final String SCRATCHPAD_FILE_NAME = "scratchpad.iri";

    public static final int ZEROTH_POINTER_OFFSET = 64;

    static FileChannel transactionsChannel;
    static ByteBuffer transactionsCellsStates;
    static ByteBuffer transactionsTipsFlags;
    static final ByteBuffer[] transactionsChunks = new ByteBuffer[MAX_NUMBER_OF_CHUNKS];
    volatile static long transactionsNextPointer = CELLS_OFFSET - SUPER_GROUPS_OFFSET;
    static final byte[] mainBuffer = new byte[CELL_SIZE], mainBufferCopy = new byte[CELL_SIZE], mainBufferCopy2 = new byte[CELL_SIZE];
    static final byte[] auxBuffer = new byte[CELL_SIZE];

    static FileChannel addressesChannel;
    static final ByteBuffer[] addressesChunks = new ByteBuffer[MAX_NUMBER_OF_CHUNKS];
    volatile static long addressesNextPointer = SUPER_GROUPS_SIZE;

    static FileChannel digestsChannel;
    static final ByteBuffer[] digestsChunks = new ByteBuffer[MAX_NUMBER_OF_CHUNKS];
    volatile static long digestsNextPointer = SUPER_GROUPS_SIZE;

    static ByteBuffer scratchpad;
    volatile static int numberOfTransactionsToRequest = 1; // The 0th is NULL_HASH

    private volatile static boolean shuttingDown;
    private volatile static boolean canBeShutDown;

    public static synchronized void launch() throws IOException {

        transactionsChannel = FileChannel.open(Paths.get(TRANSACTIONS_FILE_NAME), StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        transactionsCellsStates = transactionsChannel.map(FileChannel.MapMode.READ_WRITE, CELLS_STATES_OFFSET, CELLS_STATES_SIZE);
        transactionsTipsFlags = transactionsChannel.map(FileChannel.MapMode.READ_WRITE, TIPS_FLAGS_OFFSET, TIPS_FLAGS_SIZE);
        transactionsChunks[0] = transactionsChannel.map(FileChannel.MapMode.READ_WRITE, SUPER_GROUPS_OFFSET, SUPER_GROUPS_SIZE);
        while (true) {

            if ((transactionsNextPointer & (CHUNK_SIZE - 1)) == 0) {

                transactionsChunks[(int)(transactionsNextPointer >> 27)] = transactionsChannel.map(FileChannel.MapMode.READ_WRITE, SUPER_GROUPS_OFFSET + transactionsNextPointer, CHUNK_SIZE);
            }

            transactionsChunks[(int)(transactionsNextPointer >> 27)].get(mainBuffer);
            boolean empty = true;
            for (final int value : mainBuffer) {

                if (value != 0) {

                    empty = false;

                    break;
                }
            }
            if (empty) {

                break;
            }

            transactionsNextPointer += CELL_SIZE;
        }
        if (transactionsNextPointer == CELLS_OFFSET - SUPER_GROUPS_OFFSET) {

            // No need to zero "mainBuffer", it already contains only zeros
            setValue(mainBuffer, Transaction.TYPE_OFFSET, OUTPUT_TRANSACTION);
            setValue(mainBuffer, Transaction.HEIGHT_OFFSET, 1);
            append();

            System.arraycopy(ZEROED_BUFFER, 0, mainBuffer, 0, CELL_SIZE);
            setValue(mainBuffer, 128 << 3, CELLS_OFFSET - SUPER_GROUPS_OFFSET);
            write(((long)(128 + (128 << 8))) << 11);

            transactionsCellsStates.put((byte)1);
        }

        addressesChannel = FileChannel.open(Paths.get(ADDRESSES_FILE_NAME), StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        addressesChunks[0] = addressesChannel.map(FileChannel.MapMode.READ_WRITE, 0, SUPER_GROUPS_SIZE);
        while (true) {

            if ((addressesNextPointer & (CHUNK_SIZE - 1)) == 0) {

                addressesChunks[(int)(addressesNextPointer >> 27)] = addressesChannel.map(FileChannel.MapMode.READ_WRITE, addressesNextPointer, CHUNK_SIZE);
            }

            addressesChunks[(int)(addressesNextPointer >> 27)].get(mainBuffer);
            boolean empty = true;
            for (final int value : mainBuffer) {

                if (value != 0) {

                    empty = false;

                    break;
                }
            }
            if (empty) {

                break;
            }

            addressesNextPointer += CELL_SIZE;
        }

        digestsChannel = FileChannel.open(Paths.get(DIGESTS_FILE_NAME), StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        digestsChunks[0] = digestsChannel.map(FileChannel.MapMode.READ_WRITE, 0, SUPER_GROUPS_SIZE);
        while (true) {

            if ((digestsNextPointer & (CHUNK_SIZE - 1)) == 0) {

                digestsChunks[(int)(digestsNextPointer >> 27)] = digestsChannel.map(FileChannel.MapMode.READ_WRITE, digestsNextPointer, CHUNK_SIZE);
            }

            digestsChunks[(int)(digestsNextPointer >> 27)].get(mainBuffer);
            boolean empty = true;
            for (final int value : mainBuffer) {

                if (value != 0) {

                    empty = false;

                    break;
                }
            }
            if (empty) {

                break;
            }

            digestsNextPointer += CELL_SIZE;
        }

        final FileChannel scratchpadChannel = FileChannel.open(Paths.get(SCRATCHPAD_FILE_NAME), StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        scratchpad = scratchpadChannel.map(FileChannel.MapMode.READ_WRITE, 0, SCRATCHPAD_SIZE);
        scratchpadChannel.close();

        (new Thread(() -> {

            final byte[] cellsStatesBuffer = new byte[CELLS_STATES_BUFFER_SIZE];
            final byte[] cellsBuffer = new byte[CELLS_BUFFER_SIZE];
            final byte[] longValueBuffer = new byte[Long.BYTES];
            final byte[] hash = new byte[Transaction.HASH_SIZE];

            while (!shuttingDown) {

                try {

                    Thread.sleep(5000);

                    final long beginningTime = System.currentTimeMillis();

                    numberOfTransactionsToRequest = 1;

                    long numberOfNonFinalizedCells = 0, numberOfJustFinalizedCells = 0, numberOfFinalizedCells = 0;

                    final long maxCellPointer = transactionsNextPointer;
                    int cellsStatesPointer = 0;
                    for (long firstCellPointer = CELLS_OFFSET - SUPER_GROUPS_OFFSET; firstCellPointer < maxCellPointer; cellsStatesPointer += CELLS_STATES_BUFFER_SIZE, firstCellPointer += CELLS_BUFFER_SIZE) {

                        ((ByteBuffer) transactionsCellsStates.position(cellsStatesPointer)).get(cellsStatesBuffer);
                        final int states = (cellsStatesBuffer[0] & 0xFF) + ((cellsStatesBuffer[1] & 0xFF) << 8) + ((cellsStatesBuffer[2] & 0xFF) << 16) + ((cellsStatesBuffer[3] & 0xFF) << 24);
                        if (states == -1) {

                            numberOfFinalizedCells += Integer.SIZE;

                        } else {

                            ((ByteBuffer)transactionsChunks[(int)(firstCellPointer >> 27)].position((int)(firstCellPointer & (CHUNK_SIZE - 1)))).get(cellsBuffer);

                            int bit = 1;
                            for (int i = 0; i < Integer.SIZE && firstCellPointer + (i << 11) < maxCellPointer; i++) {

                                if ((states & bit) == 0) {

                                    if (cellsBuffer[i << 11] == GROUP) {

                                        cellsStatesBuffer[i >> 3] |= 1 << (i & 7);

                                        numberOfJustFinalizedCells++;

                                    } else {

                                        long requestRating = value(cellsBuffer, (i << 11) + Transaction.REQUEST_RATING_OFFSET);
                                        if (requestRating == 0) {

                                            final long approvedTrunkTransactionPointer = value(cellsBuffer, (i << 11) + Transaction.APPROVED_TRUNK_TRANSACTION_POINTER_OFFSET);
                                            ((ByteBuffer)transactionsChunks[(int)(approvedTrunkTransactionPointer >> 27)].position((int)(approvedTrunkTransactionPointer & (CHUNK_SIZE - 1)))).get(auxBuffer);
                                            final long approvedTrunkTransactionHeight = value(auxBuffer, Transaction.HEIGHT_OFFSET);
                                            if (approvedTrunkTransactionHeight == 0) {

                                                numberOfNonFinalizedCells++;

                                            } else {

                                                final long approvedBranchTransactionPointer = value(cellsBuffer, (i << 11) + Transaction.APPROVED_BRANCH_TRANSACTION_POINTER_OFFSET);
                                                ((ByteBuffer)transactionsChunks[(int)(approvedBranchTransactionPointer >> 27)].position((int)(approvedBranchTransactionPointer & (CHUNK_SIZE - 1)))).get(auxBuffer);
                                                final long approvedBranchTransactionHeight = value(auxBuffer, Transaction.HEIGHT_OFFSET);
                                                if (approvedBranchTransactionHeight == 0) {

                                                    numberOfNonFinalizedCells++;

                                                } else {

                                                    setValue(longValueBuffer, 0, approvedTrunkTransactionHeight + 1);
                                                    ((ByteBuffer)transactionsChunks[(int)(firstCellPointer >> 27)].position((int)(firstCellPointer & (CHUNK_SIZE - 1)) + (i << 11) + Transaction.HEIGHT_OFFSET)).put(longValueBuffer);

                                                    cellsStatesBuffer[i >> 3] |= 1 << (i & 7);

                                                    numberOfJustFinalizedCells++;
                                                }
                                            }

                                        } else {

                                            if ((numberOfTransactionsToRequest + requestRating) * Transaction.HASH_SIZE <= SCRATCHPAD_SIZE) {

                                                System.arraycopy(cellsBuffer, (i << 11) + Transaction.HASH_OFFSET, hash, 0, Transaction.HASH_SIZE);
                                                while (requestRating-- > 0) {

                                                    ((ByteBuffer)scratchpad.position(numberOfTransactionsToRequest * Transaction.HASH_SIZE)).put(hash);
                                                    numberOfTransactionsToRequest++;
                                                }
                                            }

                                            numberOfNonFinalizedCells++;
                                        }
                                    }

                                } else {

                                    numberOfFinalizedCells++;
                                }

                                bit <<= 1;
                            }

                            if ((cellsStatesBuffer[0] & 0xFF) + ((cellsStatesBuffer[1] & 0xFF) << 8) + ((cellsStatesBuffer[2] & 0xFF) << 16) + ((cellsStatesBuffer[3] & 0xFF) << 24) != states) {

                                ((ByteBuffer) transactionsCellsStates.position(cellsStatesPointer)).put(cellsStatesBuffer);
                            }
                        }
                    }

                    System.out.println(Thread.currentThread().getName() + ": " + (System.currentTimeMillis() - beginningTime) + " ms (" + numberOfNonFinalizedCells + " -> " + numberOfJustFinalizedCells + " -> " + numberOfFinalizedCells + ") // " + numberOfTransactionsToRequest + " / " + transactionsNextPointer);

                } catch (final Exception e) {

                    e.printStackTrace();
                }
            }

            System.out.println(Thread.currentThread().getName() + " has stopped successfully");

            canBeShutDown = true;

        }, "Storage Updater")).start();
    }

    public static synchronized void shutDown() {

        shuttingDown = true;

        try {

            while (!canBeShutDown) {

                Thread.sleep(1);
            }

            transactionsChannel.close();
            addressesChannel.close();
            digestsChannel.close();

            synchronized (Storage.class) {

                for (int i = 0; i < MAX_NUMBER_OF_CHUNKS && transactionsChunks[i] != null; i++) {

                    System.out.println("Flushing transactions chunk #" + i);
                    ((MappedByteBuffer)transactionsChunks[i]).force();
                }
                ((MappedByteBuffer)transactionsTipsFlags).force();
                ((MappedByteBuffer)transactionsCellsStates).force();

                for (int i = 0; i < MAX_NUMBER_OF_CHUNKS && addressesChunks[i] != null; i++) {

                    System.out.println("Flushing addresses chunk #" + i);
                    ((MappedByteBuffer)addressesChunks[i]).force();
                }

                for (int i = 0; i < MAX_NUMBER_OF_CHUNKS && digestsChunks[i] != null; i++) {

                    System.out.println("Flushing digests chunk #" + i);
                    ((MappedByteBuffer)digestsChunks[i]).force();
                }
            }

        } catch (final Exception e) {

            e.printStackTrace();
        }
    }

    public static synchronized long storeTransaction(final byte[] hash, final iri.Transaction transaction, final boolean tip) { // Returns the pointer or 0 if the transaction was already in the storage and "transaction" value is not null

        long pointer = ((long)((hash[0] + 128) + ((hash[1] + 128) << 8))) << 11, prevPointer = 0;
        for (int depth = 2; depth < Transaction.HASH_SIZE; depth++) {

            read(pointer);

            if (mainBuffer[Transaction.TYPE_OFFSET] == GROUP) {

                prevPointer = pointer;
                if ((pointer = value(mainBuffer, (hash[depth] + 128) << 3)) == 0) {

                    setValue(mainBuffer, (hash[depth] + 128) << 3, pointer = transactionsNextPointer);
                    System.arraycopy(mainBuffer, 0, mainBufferCopy, 0, CELL_SIZE);

                    Transaction.dump(hash, transaction);
                    append();
                    if (transaction != null) {

                        updateAddressAndDigest(transaction, pointer);
                    }

                    System.arraycopy(mainBufferCopy, 0, mainBuffer, 0, CELL_SIZE);
                    write(prevPointer);

                    return pointer;
                }

            } else {

                for (int i = depth; i < Transaction.HASH_SIZE; i++) {

                    if (mainBuffer[Transaction.HASH_OFFSET + i] != hash[i]) {

                        final int differentHashByte = mainBuffer[Transaction.HASH_OFFSET + i];

                        final long detourPointer = transactionsNextPointer;

                        for (int j = depth; j < i; j++) {

                            System.arraycopy(ZEROED_BUFFER, 0, mainBuffer, 0, CELL_SIZE);
                            setValue(mainBuffer, (hash[j] + 128) << 3, transactionsNextPointer + CELL_SIZE);
                            append();
                        }

                        System.arraycopy(ZEROED_BUFFER, 0, mainBuffer, 0, CELL_SIZE);
                        setValue(mainBuffer, (differentHashByte + 128) << 3, pointer);
                        setValue(mainBuffer, (hash[i] + 128) << 3, transactionsNextPointer + CELL_SIZE);
                        append();

                        Transaction.dump(hash, transaction);
                        pointer = transactionsNextPointer;
                        append();
                        if (transaction != null) {

                            updateAddressAndDigest(transaction, pointer);
                        }

                        read(prevPointer);
                        setValue(mainBuffer, (hash[depth - 1] + 128) << 3, detourPointer);
                        write(prevPointer);

                        return pointer;
                    }
                }

                if (transaction == null) {

                    if (!tip) {

                        final long prevRequestRating = value(mainBuffer, Transaction.REQUEST_RATING_OFFSET);
                        if (prevRequestRating > 0) {

                            setValue(mainBuffer, Transaction.REQUEST_RATING_OFFSET, prevRequestRating + 1);
                            write(pointer);
                        }
                    }

                    return pointer;

                } else {

                    if (value(mainBuffer, Transaction.REQUEST_RATING_OFFSET) == 0) {

                        return 0;

                    } else {

                        Transaction.dump(hash, transaction);
                        write(pointer);
                        updateAddressAndDigest(transaction, pointer);

                        return pointer;
                    }
                }
            }
        }

        throw new IllegalStateException("Corrupted storage");
    }

    public static synchronized long transactionPointer(final byte[] hash) { // Returns a negative value if the transaction hasn't been seen yet but was referenced

        long pointer = ((long)((hash[0] + 128) + ((hash[1] + 128) << 8))) << 11;
        for (int depth = 2; depth < Transaction.HASH_SIZE; depth++) {

            read(pointer);

            if (mainBuffer[Transaction.TYPE_OFFSET] == GROUP) {

                if ((pointer = value(mainBuffer, (hash[depth] + 128) << 3)) == 0) {

                    return 0;
                }

            } else {

                for (; depth < Transaction.HASH_SIZE; depth++) {

                    if (mainBuffer[Transaction.HASH_OFFSET + depth] != hash[depth]) {

                        return 0;
                    }
                }

                return value(mainBuffer, Transaction.REQUEST_RATING_OFFSET) == 0 ? pointer : -pointer;
            }
        }

        throw new IllegalStateException("Corrupted storage");
    }

    public static synchronized Transaction loadTransaction(final long pointer) {

        read(pointer);

        return new Transaction();
    }

    public static synchronized Transaction loadTransaction(final byte[] hash) {

        final long pointer = transactionPointer(hash);

        return pointer > 0 ? loadTransaction(pointer) : null;
    }

    public static Hash transactionToRequest() {

        final byte[] hash = new byte[Transaction.HASH_SIZE];
        ((ByteBuffer)scratchpad.position(ThreadLocalRandom.current().nextInt(numberOfTransactionsToRequest) * Transaction.HASH_SIZE)).get(hash);

        return new Hash(hash, 0, Transaction.HASH_SIZE);
    }

    public static synchronized long addressPointer(final byte[] hash) {

        long pointer = ((long)((hash[0] + 128) + ((hash[1] + 128) << 8))) << 11;
        for (int depth = 2; depth < Hash.SIZE_IN_BYTES; depth++) {

            ((ByteBuffer)addressesChunks[(int)(pointer >> 27)].position((int)(pointer & (CHUNK_SIZE - 1)))).get(mainBuffer);

            if (mainBuffer[Transaction.TYPE_OFFSET] == GROUP) {

                if ((pointer = value(mainBuffer, (hash[depth] + 128) << 3)) == 0) {

                    return 0;
                }

            } else {

                for (; depth < Hash.SIZE_IN_BYTES; depth++) {

                    if (mainBuffer[Transaction.HASH_OFFSET + depth] != hash[depth]) {

                        return 0;
                    }
                }

                return pointer;
            }
        }

        throw new IllegalStateException("Corrupted storage");
    }

    private static long value(final byte[] buffer, final int offset) {

        return ((long)(buffer[offset] & 0xFF)) + (((long)(buffer[offset + 1] & 0xFF)) << 8) + (((long)(buffer[offset + 2] & 0xFF)) << 16) + (((long)(buffer[offset + 3] & 0xFF)) << 24) + (((long)(buffer[offset + 4] & 0xFF)) << 32) + (((long)(buffer[offset + 5] & 0xFF)) << 40) + (((long)(buffer[offset + 6] & 0xFF)) << 48) + (((long)(buffer[offset + 7] & 0xFF)) << 56);
    }

    private static void setValue(final byte[] buffer, final int offset, final long value) {

        buffer[offset] = (byte)value;
        buffer[offset + 1] = (byte)(value >> 8);
        buffer[offset + 2] = (byte)(value >> 16);
        buffer[offset + 3] = (byte)(value >> 24);
        buffer[offset + 4] = (byte)(value >> 32);
        buffer[offset + 5] = (byte)(value >> 40);
        buffer[offset + 6] = (byte)(value >> 48);
        buffer[offset + 7] = (byte)(value >> 56);
    }

    private static void append() {

        write(transactionsNextPointer);

        transactionsTipsFlags.put((int)(transactionsNextPointer >> 3), (byte)(transactionsTipsFlags.get((int)(transactionsNextPointer >> 3)) | (1 << (transactionsNextPointer & 7))));

        if (((transactionsNextPointer += CELL_SIZE) & (CHUNK_SIZE - 1)) == 0) {

            try {

                transactionsChunks[(int)(transactionsNextPointer >> 27)] = transactionsChannel.map(FileChannel.MapMode.READ_WRITE, SUPER_GROUPS_OFFSET + transactionsNextPointer, CHUNK_SIZE);

            } catch (final IOException e) {

                e.printStackTrace();
            }
        }
    }

    private static void write(final long pointer) {

        ((ByteBuffer)transactionsChunks[(int)(pointer >> 27)].position((int)(pointer & (CHUNK_SIZE - 1)))).put(mainBuffer);
    }

    private static void read(final long pointer) {

        ((ByteBuffer)transactionsChunks[(int)(pointer >> 27)].position((int)(pointer & (CHUNK_SIZE - 1)))).get(mainBuffer);
    }

    private static void updateAddressAndDigest(final iri.Transaction transaction, final long transactionPointer) {

        boolean nullAddress = true;
        for (final byte value : transaction.address.bytes) {

            if (value != 0) {

                nullAddress = false;

                break;
            }
        }
        if (!nullAddress) {

            long pointer = ((long)((transaction.address.bytes[0] + 128) + ((transaction.address.bytes[1] + 128) << 8))) << 11, prevPointer = 0;
            for (int depth = 2; depth < Hash.SIZE_IN_BYTES; depth++) {

                ((ByteBuffer)addressesChunks[(int)(pointer >> 27)].position((int)(pointer & (CHUNK_SIZE - 1)))).get(mainBuffer);

                if (mainBuffer[Transaction.TYPE_OFFSET] == GROUP) {

                    prevPointer = pointer;
                    if ((pointer = value(mainBuffer, (transaction.address.bytes[depth] + 128) << 3)) == 0) {

                        setValue(mainBuffer, (transaction.address.bytes[depth] + 128) << 3, pointer = addressesNextPointer);

                        System.arraycopy(ZEROED_BUFFER, 0, mainBufferCopy2, 0, CELL_SIZE);
                        mainBufferCopy2[Transaction.TYPE_OFFSET] = 1;
                        System.arraycopy(transaction.address.bytes, 0, mainBufferCopy2, 8, Hash.SIZE_IN_BYTES);
                        setValue(mainBufferCopy2, 64, transactionPointer);

                        ((ByteBuffer)addressesChunks[(int)(pointer >> 27)].position((int)(pointer & (CHUNK_SIZE - 1)))).put(mainBufferCopy2);
                        if (((addressesNextPointer += CELL_SIZE) & (CHUNK_SIZE - 1)) == 0) {

                            try {

                                addressesChunks[(int)(addressesNextPointer >> 27)] = addressesChannel.map(FileChannel.MapMode.READ_WRITE, addressesNextPointer, CHUNK_SIZE);

                            } catch (final IOException e) {

                                e.printStackTrace();
                            }
                        }

                        ((ByteBuffer)addressesChunks[(int)(prevPointer >> 27)].position((int)(prevPointer & (CHUNK_SIZE - 1)))).put(mainBuffer);

                        break;
                    }

                } else {

                    boolean sameAddress = true;

                    for (int i = depth; i < Hash.SIZE_IN_BYTES; i++) {

                        if (mainBuffer[Transaction.HASH_OFFSET + i] != transaction.address.bytes[i]) {

                            final int differentHashByte = mainBuffer[Transaction.HASH_OFFSET + i];

                            final long detourPointer = addressesNextPointer;

                            for (int j = depth; j < i; j++) {

                                System.arraycopy(ZEROED_BUFFER, 0, mainBuffer, 0, CELL_SIZE);
                                setValue(mainBuffer, (transaction.address.bytes[j] + 128) << 3, addressesNextPointer + CELL_SIZE);

                                ((ByteBuffer)addressesChunks[(int)(addressesNextPointer >> 27)].position((int)(addressesNextPointer & (CHUNK_SIZE - 1)))).put(mainBuffer);
                                if (((addressesNextPointer += CELL_SIZE) & (CHUNK_SIZE - 1)) == 0) {

                                    try {

                                        addressesChunks[(int)(addressesNextPointer >> 27)] = addressesChannel.map(FileChannel.MapMode.READ_WRITE, addressesNextPointer, CHUNK_SIZE);

                                    } catch (final IOException e) {

                                        e.printStackTrace();
                                    }
                                }
                            }

                            System.arraycopy(ZEROED_BUFFER, 0, mainBuffer, 0, CELL_SIZE);
                            setValue(mainBuffer, (differentHashByte + 128) << 3, pointer);
                            setValue(mainBuffer, (transaction.address.bytes[i] + 128) << 3, addressesNextPointer + CELL_SIZE);

                            ((ByteBuffer)addressesChunks[(int)(addressesNextPointer >> 27)].position((int)(addressesNextPointer & (CHUNK_SIZE - 1)))).put(mainBuffer);
                            if (((addressesNextPointer += CELL_SIZE) & (CHUNK_SIZE - 1)) == 0) {

                                try {

                                    addressesChunks[(int)(addressesNextPointer >> 27)] = addressesChannel.map(FileChannel.MapMode.READ_WRITE, addressesNextPointer, CHUNK_SIZE);

                                } catch (final IOException e) {

                                    e.printStackTrace();
                                }
                            }

                            System.arraycopy(ZEROED_BUFFER, 0, mainBufferCopy2, 0, CELL_SIZE);
                            mainBufferCopy2[Transaction.TYPE_OFFSET] = 1;
                            System.arraycopy(transaction.address.bytes, 0, mainBufferCopy2, 8, Hash.SIZE_IN_BYTES);
                            setValue(mainBufferCopy2, 64, transactionPointer);

                            ((ByteBuffer)addressesChunks[(int)(pointer >> 27)].position((int)(pointer & (CHUNK_SIZE - 1)))).put(mainBufferCopy2);
                            if (((addressesNextPointer += CELL_SIZE) & (CHUNK_SIZE - 1)) == 0) {

                                try {

                                    addressesChunks[(int)(addressesNextPointer >> 27)] = addressesChannel.map(FileChannel.MapMode.READ_WRITE, addressesNextPointer, CHUNK_SIZE);

                                } catch (final IOException e) {

                                    e.printStackTrace();
                                }
                            }

                            ((ByteBuffer)addressesChunks[(int)(prevPointer >> 27)].position((int)(prevPointer & (CHUNK_SIZE - 1)))).get(mainBuffer);
                            setValue(mainBuffer, (transaction.address.bytes[depth - 1] + 128) << 3, detourPointer);
                            ((ByteBuffer)addressesChunks[(int)(prevPointer >> 27)].position((int)(prevPointer & (CHUNK_SIZE - 1)))).put(mainBuffer);

                            sameAddress = false;

                            break;
                        }
                    }

                    if (sameAddress) {

                        int offset = ZEROTH_POINTER_OFFSET;
                        while (true) {

                            while ((offset += Long.BYTES) < CELL_SIZE - Long.BYTES && value(mainBuffer, offset) != 0) {

                                // Do nothing
                            }
                            if (offset == CELL_SIZE - Long.BYTES) {

                                final long nextCellPointer = value(mainBuffer, offset);
                                if (nextCellPointer == 0) {

                                    setValue(mainBuffer, offset, addressesNextPointer);
                                    ((ByteBuffer)addressesChunks[(int)(pointer >> 27)].position((int)(pointer & (CHUNK_SIZE - 1)))).put(mainBuffer);

                                    System.arraycopy(ZEROED_BUFFER, 0, mainBuffer, 0, CELL_SIZE);
                                    setValue(mainBuffer, 0, transactionPointer);
                                    ((ByteBuffer)addressesChunks[(int)(addressesNextPointer >> 27)].position((int)(addressesNextPointer & (CHUNK_SIZE - 1)))).put(mainBuffer);
                                    if (((addressesNextPointer += CELL_SIZE) & (CHUNK_SIZE - 1)) == 0) {

                                        try {

                                            addressesChunks[(int)(addressesNextPointer >> 27)] = addressesChannel.map(FileChannel.MapMode.READ_WRITE, addressesNextPointer, CHUNK_SIZE);

                                        } catch (final IOException e) {

                                            e.printStackTrace();
                                        }
                                    }

                                } else {

                                    ((ByteBuffer)addressesChunks[(int)(nextCellPointer >> 27)].position((int)(nextCellPointer & (CHUNK_SIZE - 1)))).get(mainBuffer);
                                    offset = -Long.BYTES;
                                }

                            } else {

                                setValue(mainBuffer, offset, transactionPointer);
                                ((ByteBuffer)addressesChunks[(int)(pointer >> 27)].position((int)(pointer & (CHUNK_SIZE - 1)))).put(mainBuffer);

                                break;
                            }
                        }
                    }

                    break;
                }
            }
        }

        boolean nullDigest = true;
        for (final byte value : transaction.digest.bytes) {

            if (value != 0) {

                nullDigest = false;

                break;
            }
        }
        if (!nullDigest) {

            long pointer = ((long)((transaction.digest.bytes[0] + 128) + ((transaction.digest.bytes[1] + 128) << 8))) << 11, prevPointer = 0;
            for (int depth = 2; depth < Hash.SIZE_IN_BYTES; depth++) {

                ((ByteBuffer)digestsChunks[(int)(pointer >> 27)].position((int)(pointer & (CHUNK_SIZE - 1)))).get(mainBuffer);

                if (mainBuffer[Transaction.TYPE_OFFSET] == GROUP) {

                    prevPointer = pointer;
                    if ((pointer = value(mainBuffer, (transaction.digest.bytes[depth] + 128) << 3)) == 0) {

                        setValue(mainBuffer, (transaction.digest.bytes[depth] + 128) << 3, pointer = digestsNextPointer);

                        System.arraycopy(ZEROED_BUFFER, 0, mainBufferCopy2, 0, CELL_SIZE);
                        mainBufferCopy2[Transaction.TYPE_OFFSET] = 1;
                        System.arraycopy(transaction.digest.bytes, 0, mainBufferCopy2, 8, Hash.SIZE_IN_BYTES);
                        setValue(mainBufferCopy2, 64, transactionPointer);

                        ((ByteBuffer)digestsChunks[(int)(pointer >> 27)].position((int)(pointer & (CHUNK_SIZE - 1)))).put(mainBufferCopy2);
                        if (((digestsNextPointer += CELL_SIZE) & (CHUNK_SIZE - 1)) == 0) {

                            try {

                                digestsChunks[(int)(digestsNextPointer >> 27)] = digestsChannel.map(FileChannel.MapMode.READ_WRITE, digestsNextPointer, CHUNK_SIZE);

                            } catch (final IOException e) {

                                e.printStackTrace();
                            }
                        }

                        ((ByteBuffer)digestsChunks[(int)(prevPointer >> 27)].position((int)(prevPointer & (CHUNK_SIZE - 1)))).put(mainBuffer);

                        return;
                    }

                } else {

                    for (int i = depth; i < Hash.SIZE_IN_BYTES; i++) {

                        if (mainBuffer[Transaction.HASH_OFFSET + i] != transaction.digest.bytes[i]) {

                            final int differentHashByte = mainBuffer[Transaction.HASH_OFFSET + i];

                            final long detourPointer = digestsNextPointer;

                            for (int j = depth; j < i; j++) {

                                System.arraycopy(ZEROED_BUFFER, 0, mainBuffer, 0, CELL_SIZE);
                                setValue(mainBuffer, (transaction.digest.bytes[j] + 128) << 3, digestsNextPointer + CELL_SIZE);

                                ((ByteBuffer)digestsChunks[(int)(digestsNextPointer >> 27)].position((int)(digestsNextPointer & (CHUNK_SIZE - 1)))).put(mainBuffer);
                                if (((digestsNextPointer += CELL_SIZE) & (CHUNK_SIZE - 1)) == 0) {

                                    try {

                                        digestsChunks[(int)(digestsNextPointer >> 27)] = digestsChannel.map(FileChannel.MapMode.READ_WRITE, digestsNextPointer, CHUNK_SIZE);

                                    } catch (final IOException e) {

                                        e.printStackTrace();
                                    }
                                }
                            }

                            System.arraycopy(ZEROED_BUFFER, 0, mainBuffer, 0, CELL_SIZE);
                            setValue(mainBuffer, (differentHashByte + 128) << 3, pointer);
                            setValue(mainBuffer, (transaction.digest.bytes[i] + 128) << 3, digestsNextPointer + CELL_SIZE);

                            ((ByteBuffer)digestsChunks[(int)(digestsNextPointer >> 27)].position((int)(digestsNextPointer & (CHUNK_SIZE - 1)))).put(mainBuffer);
                            if (((digestsNextPointer += CELL_SIZE) & (CHUNK_SIZE - 1)) == 0) {

                                try {

                                    digestsChunks[(int)(digestsNextPointer >> 27)] = digestsChannel.map(FileChannel.MapMode.READ_WRITE, digestsNextPointer, CHUNK_SIZE);

                                } catch (final IOException e) {

                                    e.printStackTrace();
                                }
                            }

                            System.arraycopy(ZEROED_BUFFER, 0, mainBufferCopy2, 0, CELL_SIZE);
                            mainBufferCopy2[Transaction.TYPE_OFFSET] = 1;
                            System.arraycopy(transaction.digest.bytes, 0, mainBufferCopy2, 8, Hash.SIZE_IN_BYTES);
                            setValue(mainBufferCopy2, 64, transactionPointer);

                            ((ByteBuffer)digestsChunks[(int)(pointer >> 27)].position((int)(pointer & (CHUNK_SIZE - 1)))).put(mainBufferCopy2);
                            if (((digestsNextPointer += CELL_SIZE) & (CHUNK_SIZE - 1)) == 0) {

                                try {

                                    digestsChunks[(int)(digestsNextPointer >> 27)] = digestsChannel.map(FileChannel.MapMode.READ_WRITE, digestsNextPointer, CHUNK_SIZE);

                                } catch (final IOException e) {

                                    e.printStackTrace();
                                }
                            }

                            ((ByteBuffer)digestsChunks[(int)(prevPointer >> 27)].position((int)(prevPointer & (CHUNK_SIZE - 1)))).get(mainBuffer);
                            setValue(mainBuffer, (transaction.digest.bytes[depth - 1] + 128) << 3, detourPointer);
                            ((ByteBuffer)digestsChunks[(int)(prevPointer >> 27)].position((int)(prevPointer & (CHUNK_SIZE - 1)))).put(mainBuffer);

                            return;
                        }
                    }

                    int offset = ZEROTH_POINTER_OFFSET;
                    while (true) {

                        while ((offset += Long.BYTES) < CELL_SIZE - Long.BYTES && value(mainBuffer, offset) != 0) {

                            // Do nothing
                        }
                        if (offset == CELL_SIZE - Long.BYTES) {

                            final long nextCellPointer = value(mainBuffer, offset);
                            if (nextCellPointer == 0) {

                                setValue(mainBuffer, offset, digestsNextPointer);
                                ((ByteBuffer)digestsChunks[(int)(pointer >> 27)].position((int)(pointer & (CHUNK_SIZE - 1)))).put(mainBuffer);

                                System.arraycopy(ZEROED_BUFFER, 0, mainBuffer, 0, CELL_SIZE);
                                setValue(mainBuffer, 0, transactionPointer);
                                ((ByteBuffer)digestsChunks[(int)(digestsNextPointer >> 27)].position((int)(digestsNextPointer & (CHUNK_SIZE - 1)))).put(mainBuffer);
                                if (((digestsNextPointer += CELL_SIZE) & (CHUNK_SIZE - 1)) == 0) {

                                    try {

                                        digestsChunks[(int)(digestsNextPointer >> 27)] = digestsChannel.map(FileChannel.MapMode.READ_WRITE, digestsNextPointer, CHUNK_SIZE);

                                    } catch (final IOException e) {

                                        e.printStackTrace();
                                    }
                                }

                            } else {

                                ((ByteBuffer)digestsChunks[(int)(nextCellPointer >> 27)].position((int)(nextCellPointer & (CHUNK_SIZE - 1)))).get(mainBuffer);
                                offset = -Long.BYTES;
                            }

                        } else {

                            setValue(mainBuffer, offset, transactionPointer);
                            ((ByteBuffer)digestsChunks[(int)(pointer >> 27)].position((int)(pointer & (CHUNK_SIZE - 1)))).put(mainBuffer);

                            return;
                        }
                    }
                }
            }
        }
    }

    public static final class Transaction {

        public static final int TYPE_OFFSET = 0, TYPE_SIZE = Byte.BYTES;
        public static final int HASH_OFFSET = TYPE_OFFSET + TYPE_SIZE + ((Long.BYTES - (TYPE_SIZE & (Long.BYTES - 1))) & (Long.BYTES - 1)), HASH_SIZE = 46;
        public static final int REQUEST_RATING_OFFSET = HASH_OFFSET + HASH_SIZE + ((Long.BYTES - (HASH_SIZE & (Long.BYTES - 1))) & (Long.BYTES - 1)), REQUEST_RATING_SIZE = Long.BYTES;

        public static final int BYTES_OFFSET = REQUEST_RATING_OFFSET + REQUEST_RATING_SIZE + ((Long.BYTES - (REQUEST_RATING_SIZE & (Long.BYTES - 1))) & (Long.BYTES - 1)), BYTES_SIZE = iri.Transaction.SIZE_IN_BYTES;

        public static final int DIGEST_OFFSET = BYTES_OFFSET + BYTES_SIZE + ((Long.BYTES - (BYTES_SIZE & (Long.BYTES - 1))) & (Long.BYTES - 1)), DIGEST_SIZE = 49;
        public static final int ADDRESS_OFFSET = DIGEST_OFFSET + DIGEST_SIZE + ((Long.BYTES - (DIGEST_SIZE & (Long.BYTES - 1))) & (Long.BYTES - 1)), ADDRESS_SIZE = 49;
        public static final int VALUE_OFFSET = ADDRESS_OFFSET + ADDRESS_SIZE + ((Long.BYTES - (ADDRESS_SIZE & (Long.BYTES - 1))) & (Long.BYTES - 1)), VALUE_SIZE = Long.BYTES;
        public static final int TIMESTAMP_OFFSET = VALUE_OFFSET + VALUE_SIZE + ((Long.BYTES - (VALUE_SIZE & (Long.BYTES - 1))) & (Long.BYTES - 1)), TIMESTAMP_SIZE = Long.BYTES;
        public static final int INDEX_OFFSET = TIMESTAMP_OFFSET + TIMESTAMP_SIZE + ((Long.BYTES - (TIMESTAMP_SIZE & (Long.BYTES - 1))) & (Long.BYTES - 1)), INDEX_SIZE = Long.BYTES;
        public static final int SIGNATURE_NONCE_OFFSET = INDEX_OFFSET + INDEX_SIZE + ((Long.BYTES - (INDEX_SIZE & (Long.BYTES - 1))) & (Long.BYTES - 1)), SIGNATURE_NONCE_SIZE = 17;
        public static final int APPROVAL_NONCE_OFFSET = SIGNATURE_NONCE_OFFSET + SIGNATURE_NONCE_SIZE + ((Long.BYTES - (SIGNATURE_NONCE_SIZE & (Long.BYTES - 1))) & (Long.BYTES - 1)), APPROVAL_NONCE_SIZE = 17;
        public static final int APPROVED_TRUNK_TRANSACTION_OFFSET = APPROVAL_NONCE_OFFSET + APPROVAL_NONCE_SIZE + ((Long.BYTES - (APPROVAL_NONCE_SIZE & (Long.BYTES - 1))) & (Long.BYTES - 1)), APPROVED_TRUNK_TRANSACTION_SIZE = HASH_SIZE;
        public static final int APPROVED_BRANCH_TRANSACTION_OFFSET = APPROVED_TRUNK_TRANSACTION_OFFSET + APPROVED_TRUNK_TRANSACTION_SIZE + ((Long.BYTES - (APPROVED_TRUNK_TRANSACTION_SIZE & (Long.BYTES - 1))) & (Long.BYTES - 1)), APPROVED_BRANCH_TRANSACTION_SIZE = HASH_SIZE;

        public static final int APPROVED_TRUNK_TRANSACTION_POINTER_OFFSET = APPROVED_BRANCH_TRANSACTION_OFFSET + APPROVED_BRANCH_TRANSACTION_SIZE + ((Long.BYTES - (APPROVED_BRANCH_TRANSACTION_SIZE & (Long.BYTES - 1))) & (Long.BYTES - 1)), APPROVED_TRUNK_TRANSACTION_POINTER_SIZE = Long.BYTES;
        public static final int APPROVED_BRANCH_TRANSACTION_POINTER_OFFSET = APPROVED_TRUNK_TRANSACTION_POINTER_OFFSET + APPROVED_TRUNK_TRANSACTION_POINTER_SIZE + ((Long.BYTES - (APPROVED_TRUNK_TRANSACTION_POINTER_SIZE & (Long.BYTES - 1))) & (Long.BYTES - 1)), APPROVED_BRANCH_TRANSACTION_POINTER_SIZE = Long.BYTES;
        public static final int HEIGHT_OFFSET = APPROVED_BRANCH_TRANSACTION_POINTER_OFFSET + APPROVED_BRANCH_TRANSACTION_POINTER_SIZE + ((Long.BYTES - (APPROVED_BRANCH_TRANSACTION_POINTER_SIZE & (Long.BYTES - 1))) & (Long.BYTES - 1)), HEIGHT_SIZE = Long.BYTES;
        public static final int VALID_SUBTANGLE_OFFSET = HEIGHT_OFFSET + HEIGHT_SIZE + ((Long.BYTES - (HEIGHT_SIZE & (Long.BYTES - 1))) & (Long.BYTES - 1)), VALID_SUBTANGLE_SIZE = Long.BYTES;

        final int type;
        final byte[] hash;
        final long requestRating;

        final byte[] bytes;

        final byte[] digest;
        final byte[] address;
        final long value;
        final long timestamp;
        final long index;
        final byte[] signatureNonce;
        final byte[] approvalNonce;
        final byte[] approvedTrunkTransaction;
        final byte[] approvedBranchTransaction;

        final long approvedTrunkTransactionPointer;
        final long approvedBranchTransactionPointer;
        final long height;
        final int validSubtangle;

        Transaction() {

            type = mainBuffer[TYPE_OFFSET];
            System.arraycopy(mainBuffer, HASH_OFFSET, hash = new byte[HASH_SIZE], 0, HASH_SIZE);
            requestRating = value(mainBuffer, REQUEST_RATING_OFFSET);

            System.arraycopy(mainBuffer, BYTES_OFFSET, bytes = new byte[BYTES_SIZE], 0, BYTES_SIZE);

            System.arraycopy(mainBuffer, DIGEST_OFFSET, digest = new byte[DIGEST_SIZE], 0, DIGEST_SIZE);
            System.arraycopy(mainBuffer, ADDRESS_OFFSET, address = new byte[ADDRESS_SIZE], 0, ADDRESS_SIZE);
            value = value(mainBuffer, VALUE_OFFSET);
            timestamp = value(mainBuffer, TIMESTAMP_OFFSET);
            index = value(mainBuffer, INDEX_OFFSET);
            System.arraycopy(mainBuffer, SIGNATURE_NONCE_OFFSET, signatureNonce = new byte[SIGNATURE_NONCE_SIZE], 0, SIGNATURE_NONCE_SIZE);
            System.arraycopy(mainBuffer, APPROVAL_NONCE_OFFSET, approvalNonce = new byte[APPROVAL_NONCE_SIZE], 0, APPROVAL_NONCE_SIZE);
            System.arraycopy(mainBuffer, APPROVED_TRUNK_TRANSACTION_OFFSET, approvedTrunkTransaction = new byte[APPROVED_TRUNK_TRANSACTION_SIZE], 0, APPROVED_TRUNK_TRANSACTION_SIZE);
            System.arraycopy(mainBuffer, APPROVED_BRANCH_TRANSACTION_OFFSET, approvedBranchTransaction = new byte[APPROVED_BRANCH_TRANSACTION_SIZE], 0, APPROVED_BRANCH_TRANSACTION_SIZE);

            approvedTrunkTransactionPointer = value(mainBuffer, APPROVED_TRUNK_TRANSACTION_POINTER_OFFSET);
            approvedBranchTransactionPointer = value(mainBuffer, APPROVED_BRANCH_TRANSACTION_POINTER_OFFSET);
            height = value(mainBuffer, HEIGHT_OFFSET);
            validSubtangle = mainBuffer[VALID_SUBTANGLE_OFFSET];
        }

        public iri.Transaction transaction() {

            return new iri.Transaction(bytes);
        }

        public static void dump(final byte[] hash, final iri.Transaction transaction) {

            if (transaction == null) {

                System.arraycopy(ZEROED_BUFFER, 0, mainBuffer, 0, CELL_SIZE);

                mainBuffer[TYPE_OFFSET] = OUTPUT_TRANSACTION;
                System.arraycopy(hash, 0, mainBuffer, HASH_OFFSET, HASH_SIZE);
                mainBuffer[REQUEST_RATING_OFFSET] = 1;

            } else {

                final long approvedTrunkTransactionPointer = storeTransaction(transaction.approvedTrunkTransaction.bytes, null, false);
                if (approvedTrunkTransactionPointer > 0) {

                    transactionsTipsFlags.put((int)(approvedTrunkTransactionPointer >> 3), (byte) (transactionsTipsFlags.get((int)(approvedTrunkTransactionPointer >> 3)) & (0xFF ^ (1 << (approvedTrunkTransactionPointer & 7)))));
                }

                final long approvedBranchTransactionPointer = storeTransaction(transaction.approvedBranchTransaction.bytes, null, false);
                if (approvedBranchTransactionPointer > 0) {

                    transactionsTipsFlags.put((int)(approvedBranchTransactionPointer >> 3), (byte) (transactionsTipsFlags.get((int)(approvedBranchTransactionPointer >> 3)) & (0xFF ^ (1 << (approvedBranchTransactionPointer & 7)))));
                }

                System.arraycopy(ZEROED_BUFFER, 0, mainBuffer, 0, CELL_SIZE);

                setValue(mainBuffer, APPROVED_TRUNK_TRANSACTION_POINTER_OFFSET, approvedTrunkTransactionPointer);
                setValue(mainBuffer, APPROVED_BRANCH_TRANSACTION_POINTER_OFFSET, approvedBranchTransactionPointer);

                mainBuffer[TYPE_OFFSET] = (byte)transaction.type();
                System.arraycopy(hash, 0, mainBuffer, HASH_OFFSET, HASH_SIZE);

                System.arraycopy(Converter.bytes(transaction.trits), 0, mainBuffer, BYTES_OFFSET, BYTES_SIZE);

                System.arraycopy(transaction.digest.bytes, 0, mainBuffer, DIGEST_OFFSET, DIGEST_SIZE);
                System.arraycopy(transaction.address.bytes, 0, mainBuffer, ADDRESS_OFFSET, ADDRESS_SIZE);
                setValue(mainBuffer, VALUE_OFFSET, transaction.value);
                setValue(mainBuffer, TIMESTAMP_OFFSET, transaction.timestamp);
                setValue(mainBuffer, INDEX_OFFSET, transaction.index);
                System.arraycopy(Converter.bytes(transaction.trits, iri.Transaction.SIGNATURE_NONCE_OFFSET, iri.Transaction.SIGNATURE_NONCE_SIZE), 0, mainBuffer, SIGNATURE_NONCE_OFFSET, SIGNATURE_NONCE_SIZE);
                System.arraycopy(Converter.bytes(transaction.trits, iri.Transaction.APPROVAL_NONCE_OFFSET, iri.Transaction.APPROVAL_NONCE_SIZE), 0, mainBuffer, APPROVAL_NONCE_OFFSET, APPROVAL_NONCE_SIZE);
                System.arraycopy(transaction.approvedTrunkTransaction.bytes, 0, mainBuffer, APPROVED_TRUNK_TRANSACTION_OFFSET, APPROVED_TRUNK_TRANSACTION_SIZE);
                System.arraycopy(transaction.approvedBranchTransaction.bytes, 0, mainBuffer, APPROVED_BRANCH_TRANSACTION_OFFSET, APPROVED_BRANCH_TRANSACTION_SIZE);
            }
        }
    }
}
