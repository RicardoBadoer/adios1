package iri;

import java.util.*;

public class Tangle {

    static synchronized boolean add(final Transaction transaction) {

        final long pointer = Storage.storeTransaction(transaction.hash().bytes, transaction, false);
        if (pointer != 0) {

            TipsSelector.trigger();
            if (Storage.transactionPointer(transaction.approvedTrunkTransaction.bytes) != 0
                    && Storage.transactionPointer(transaction.approvedBranchTransaction.bytes) != 0) { // TODO: Relax the requirements for rebroadcasting

                Rebroadcaster.push(Converter.bytes(transaction.trits));
            }

            return true;

        } else {

            return false;
        }
    }

    static synchronized boolean getIncludedTransactions(final Set<Hash> includedTransactions) {

        boolean solidSubtangle = true;

        final Queue<Hash> nonAnalyzedTransactions = new LinkedList<>(TipsSelector.tips());
        Hash hash;
        while ((hash = nonAnalyzedTransactions.poll()) != null) {

            if (includedTransactions.add(hash)) {

                final Storage.Transaction transaction = Storage.loadTransaction(hash.bytes);
                if (transaction == null) {

                    solidSubtangle = false;

                } else {

                    final Hash approvedTrunkTransaction = new Hash(transaction.approvedTrunkTransaction, 0, Storage.Transaction.HASH_SIZE);
                    if (!includedTransactions.contains(approvedTrunkTransaction)) {

                        nonAnalyzedTransactions.offer(approvedTrunkTransaction);
                    }

                    final Hash approvedBranchTransaction = new Hash(transaction.approvedBranchTransaction, 0, Storage.Transaction.HASH_SIZE);
                    if (!includedTransactions.contains(approvedBranchTransaction)) {

                        nonAnalyzedTransactions.offer(approvedBranchTransaction);
                    }
                }
            }
        }

        return solidSubtangle;
    }
}
