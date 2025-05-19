/*
 * Copyright 2004-2025 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.mvstore.tx;

import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.h2.engine.IsolationLevel;
import org.h2.mvstore.DataUtils;
import org.h2.mvstore.MVMap;
import org.h2.mvstore.MVStore;
import org.h2.mvstore.RootReference;
import org.h2.mvstore.type.DataType;
import org.h2.value.VersionedValue;

/**
 * 代表一个事务
 */
public final class Transaction {

    /**
     * 已关闭的事务状态（已提交/回滚）
     */
    public static final int STATUS_CLOSED = 0;

    /**
     * 已打开事务的状态
     */
    public static final int STATUS_OPEN = 1;

    /**
     * 已准备事务的状态
     */
    public static final int STATUS_PREPARED = 2;

    /**
     * 该事务的状态为逻辑上已提交，或者更确切地说，已标记为已提交。因为如果它已准备好提交，
     * 它可能仍列在“准备就绪”状态中。它的undo log条目仍然可能存在，并且在其映射中的更改
     * 尚未全部重写为已提交。尽管如此，其他事务应该已经将这些更改视为已提交。
     * 此事务的ID必须在上述所有操作完成且事务关闭之前无法重复使用。如果存储已关闭但事务
     * 尚未关闭，则可以观察到处于此状态的事务。打开存储时，此类事务将自动处理并关闭为已提交。
     */
    public static final int STATUS_COMMITTED = 3;

    /**
     * 当前正在回滚到savepoint的事务的状态
     */
    private static final int STATUS_ROLLING_BACK = 4;

    /**
     * 事务已完全回滚，但撤销操作尚未完成的状态
     */
    private static final int STATUS_ROLLED_BACK  = 5;

    private static final String[] STATUS_NAMES = {
            "CLOSED", "OPEN", "PREPARED", "COMMITTED", "ROLLING_BACK", "ROLLED_BACK"
    };
    /**
     * How many bits of the "operation id" we store in the transaction belong to the
     * log id (the rest belong to the transaction id).
     */
    static final int LOG_ID_BITS = 40;
    private static final int LOG_ID_BITS1 = LOG_ID_BITS + 1;
    private static final long LOG_ID_LIMIT = 1L << LOG_ID_BITS;
    private static final long LOG_ID_MASK = (1L << LOG_ID_BITS1) - 1;
    private static final int STATUS_BITS = 4;
    private static final int STATUS_MASK = (1 << STATUS_BITS) - 1;


    /**
     * 事务存储
     */
    final TransactionStore store;

    /**
     * 该事务回滚变更的监听器
     */
    final TransactionStore.RollbackListener listener;

    /**
     * 事务ID。该字段更合适的名称是 “slotId”
     */
    final int transactionId;

    /**
     * 这是事务的身份，因为它不会被重复使用
     */
    final long sequenceNum;

    /**
     * 事务状态是一个原子复合状态：
     * bit 45: 事务是否已回滚的标识
     * bits 44-41: 状态
     * bits 40: 溢出控制位，1表示溢出
     * bits 39-0: undo log 映射中最后一个条目的日志id
     */
    private final AtomicLong statusAndLogId;

    /**
     * 引用此事务使用的最早存储版本的计数器。至少在此事务结束之前，引用的版本和所有较新的版本都不能被丢弃。
     */
    private MVStore.TxCounter txCounter;

    /**
     * 事务名称
     */
    private String name;

    /**
     * 表示该事务是否被存储在 preparedTransactions map
     */
    boolean wasStored;

    /**
     * 等待阻塞事务提交或回滚的时间
     */
    int timeoutMillis;

    /**
     * 标识此事务的拥有者，通常所有者是数据库session
     */
    private final int ownerId;

    /**
     * Blocking transaction, if any
     */
    private volatile Transaction blockingTransaction;

    /**
     * Map on which this transaction is blocked.
     */
    private String blockingMapName;

    /**
     * Key in blockingMap on which this transaction is blocked.
     */
    private Object blockingKey;

    /**
     * Whether other transaction(s) are waiting for this to close.
     */
    private volatile boolean notificationRequested;

    /**
     * RootReferences for undo log snapshots
     */
    private RootReference<Long,Record<?,?>>[] undoLogRootReferences;

    /**
     * Map of transactional maps for this transaction
     */
    private final Map<Integer, TransactionMap<?,?>> transactionMaps = new HashMap<>();

    /**
     * The current isolation level.
     */
    final IsolationLevel isolationLevel;


    Transaction(TransactionStore store, int transactionId, long sequenceNum, int status,
                String name, long logId, int timeoutMillis, int ownerId,
                IsolationLevel isolationLevel, TransactionStore.RollbackListener listener) {
        this.store = store;
        this.transactionId = transactionId;
        this.sequenceNum = sequenceNum;
        this.statusAndLogId = new AtomicLong(composeState(status, logId, false));
        this.name = name;
        setTimeoutMillis(timeoutMillis);
        this.ownerId = ownerId;
        this.isolationLevel = isolationLevel;
        this.listener = listener;
    }

    public int getId() {
        return transactionId;
    }

    public long getSequenceNum() {
        return sequenceNum;
    }

    public int getStatus() {
        return getStatus(statusAndLogId.get());
    }

    RootReference<Long,Record<?,?>>[] getUndoLogRootReferences() {
        return undoLogRootReferences;
    }

    /**
     * Changes transaction status to a specified value
     * @param status to be set
     * @return transaction state as it was before status change
     */
    private long setStatus(int status) {
        while (true) {
            long currentState = statusAndLogId.get();
            long logId = getLogId(currentState);
            int currentStatus = getStatus(currentState);
            boolean valid;
            switch (status) {
                case STATUS_ROLLING_BACK:
                    valid = currentStatus == STATUS_OPEN;
                    break;
                case STATUS_PREPARED:
                    valid = currentStatus == STATUS_OPEN;
                    break;
                case STATUS_COMMITTED:
                    valid = currentStatus == STATUS_OPEN ||
                            currentStatus == STATUS_PREPARED ||
                            // this case is only possible if called
                            // from endLeftoverTransactions()
                            currentStatus == STATUS_COMMITTED;
                    break;
                case STATUS_ROLLED_BACK:
                    valid = currentStatus == STATUS_OPEN ||
                            currentStatus == STATUS_PREPARED ||
                            currentStatus == STATUS_ROLLING_BACK;
                    break;
                case STATUS_CLOSED:
                    valid = currentStatus == STATUS_COMMITTED ||
                            currentStatus == STATUS_ROLLED_BACK;
                    break;
                case STATUS_OPEN:
                default:
                    valid = false;
                    break;
            }
            if (!valid) {
                throw DataUtils.newMVStoreException(
                        DataUtils.ERROR_TRANSACTION_ILLEGAL_STATE,
                        "Transaction was illegally transitioned from {0} to {1}",
                        getStatusName(currentStatus), getStatusName(status));
            }
            long newState = composeState(status, logId, hasRollback(currentState));
            if (statusAndLogId.compareAndSet(currentState, newState)) {
                return currentState;
            }
        }
    }

    /**
     * Determine if any database changes were made as part of this transaction.
     *
     * @return true if there are changes to commit, false otherwise
     */
    public boolean hasChanges() {
        return hasChanges(statusAndLogId.get());
    }

    public void setName(String name) {
        checkNotClosed();
        this.name = name;
        store.storeTransaction(this);
    }

    public String getName() {
        return name;
    }

    public int getBlockerId() {
        Transaction blocker = this.blockingTransaction;
        return blocker == null ? 0 : blocker.ownerId;
    }

    /**
     * Create a new savepoint.
     *
     * @return the savepoint id
     */
    public long setSavepoint() {
        return getLogId();
    }

    /**
     * Returns whether statement dependencies are currently set.
     *
     * @return whether statement dependencies are currently set
     */
    public boolean hasStatementDependencies() {
        return !transactionMaps.isEmpty();
    }

    /**
     * Returns the isolation level of this transaction.
     *
     * @return the isolation level of this transaction
     */
    public IsolationLevel getIsolationLevel() {
        return isolationLevel;
    }

    boolean isReadCommitted() {
        return isolationLevel == IsolationLevel.READ_COMMITTED;
    }

    /**
     * Whether this transaction has isolation level READ_COMMITTED or below.
     * @return true if isolation level is READ_COMMITTED or READ_UNCOMMITTED
     */
    public boolean allowNonRepeatableRead() {
        return isolationLevel.allowNonRepeatableRead();
    }

    /**
     * Mark an entry into a new SQL statement execution within this transaction.
     *
     * @param maps
     *            set of maps used by transaction or statement is about to be executed
     */
    @SuppressWarnings({"unchecked","rawtypes"})
    public void markStatementStart(HashSet<MVMap<Object,VersionedValue<Object>>> maps) {
        markStatementEnd();
        if (txCounter == null && store.store.isVersioningRequired()) {
            txCounter = store.store.registerVersionUsage();
        }

        if (maps != null && !maps.isEmpty()) {
            // The purpose of the following loop is to get a coherent picture
            // In order to get such a "snapshot", we wait for a moment of silence,
            // when no new transaction were committed / closed.
            BitSet committingTransactions;
            do {
                committingTransactions = store.committingTransactions.get();
                for (MVMap<Object,VersionedValue<Object>> map : maps) {
                    TransactionMap<?,?> txMap = openMapX(map);
                    txMap.setStatementSnapshot(new Snapshot(map.flushAndGetRoot(), committingTransactions));
                }
                if (isReadCommitted()) {
                    undoLogRootReferences = store.collectUndoLogRootReferences();
                }
            } while (committingTransactions != store.committingTransactions.get());
            // Now we have a snapshot, where each map RootReference point to state of the map,
            // undoLogRootReferences captures the state of undo logs
            // and committingTransactions mask tells us which of seemingly uncommitted changes
            // should be considered as committed.
            // Subsequent processing uses this snapshot info only.
            for (MVMap<Object,VersionedValue<Object>> map : maps) {
                TransactionMap<?,?> txMap = openMapX(map);
                txMap.promoteSnapshot();
            }
        }
    }

    /**
     * Mark an exit from SQL statement execution within this transaction.
     */
    public void markStatementEnd() {
        if (allowNonRepeatableRead()) {
            releaseSnapshot();
        }
        for (TransactionMap<?, ?> transactionMap : transactionMaps.values()) {
            transactionMap.setStatementSnapshot(null);
        }
    }

    private void markTransactionEnd() {
        if (!allowNonRepeatableRead()) {
            releaseSnapshot();
        }
    }

    private void releaseSnapshot() {
        transactionMaps.clear();
        undoLogRootReferences = null;
        MVStore.TxCounter counter = txCounter;
        if (counter != null) {
            txCounter = null;
            store.store.deregisterVersionUsage(counter);
        }
    }

    /**
     * Add a log entry.
     *
     * @param logRecord to append
     *
     * @return key for the newly added undo log entry
     */
    long log(Record<?,?> logRecord) {
        long currentState = statusAndLogId.getAndIncrement();
        long logId = getLogId(currentState);
        if (logId >= LOG_ID_LIMIT) {
            throw DataUtils.newMVStoreException(
                    DataUtils.ERROR_TRANSACTION_TOO_BIG,
                    "Transaction {0} has too many changes",
                    transactionId);
        }
        int currentStatus = getStatus(currentState);
        checkOpen(currentStatus);
        long undoKey = store.addUndoLogRecord(transactionId, logId, logRecord);
        return undoKey;
    }

    /**
     * Remove the last log entry.
     */
    void logUndo() {
        long currentState = statusAndLogId.decrementAndGet();
        long logId = getLogId(currentState);
        if (logId >= LOG_ID_LIMIT) {
            throw DataUtils.newMVStoreException(
                    DataUtils.ERROR_TRANSACTION_CORRUPT,
                    "Transaction {0} has internal error",
                    transactionId);
        }
        int currentStatus = getStatus(currentState);
        checkOpen(currentStatus);
        store.removeUndoLogRecord(transactionId);
    }

    /**
     * Open a data map.
     *
     * @param <K> the key type
     * @param <V> the value type
     * @param name the name of the map
     * @return the transaction map
     */
    public <K, V> TransactionMap<K, V> openMap(String name) {
        return openMap(name, null, null);
    }

    /**
     * Open the map to store the data.
     *
     * @param <K> the key type
     * @param <V> the value type
     * @param name the name of the map
     * @param keyType the key data type
     * @param valueType the value data type
     * @return the transaction map
     */
    public <K, V> TransactionMap<K, V> openMap(String name,
                                                DataType<K> keyType,
                                                DataType<V> valueType) {
        MVMap<K, VersionedValue<V>> map = store.openVersionedMap(name, keyType, valueType);
        return openMapX(map);
    }

    /**
     * Open the transactional version of the given map.
     *
     * @param <K> the key type
     * @param <V> the value type
     * @param map the base map
     * @return the transactional map
     */
    @SuppressWarnings("unchecked")
    public <K, V> TransactionMap<K,V> openMapX(MVMap<K,VersionedValue<V>> map) {
        checkNotClosed();
        int id = map.getId();
        TransactionMap<K,V> transactionMap = (TransactionMap<K,V>)transactionMaps.get(id);
        if (transactionMap == null) {
            transactionMap = new TransactionMap<>(this, map);
            transactionMaps.put(id, transactionMap);
        }
        return transactionMap;
    }

    /**
     * Prepare the transaction. Afterwards, the transaction can only be
     * committed or completely rolled back.
     */
    public void prepare() {
        setStatus(STATUS_PREPARED);
        store.storeTransaction(this);
    }

    /**
     * Commit the transaction. Afterwards, this transaction is closed.
     */
    public void commit() {
        assert store.openTransactions.get().get(transactionId);
        markTransactionEnd();
        Throwable ex = null;
        boolean hasChanges = false;
        int previousStatus = STATUS_OPEN;
        try {
            long state = setStatus(STATUS_COMMITTED);
            hasChanges = hasChanges(state);
            previousStatus = getStatus(state);
            if (hasChanges) {
                store.commit(this, previousStatus == STATUS_COMMITTED);
            }
        } catch (Throwable e) {
            ex = e;
            throw e;
        } finally {
            if (isActive(previousStatus)) {
                try {
                    store.endTransaction(this, hasChanges);
                } catch (Throwable e) {
                    if (ex == null) {
                        throw e;
                    } else {
                        ex.addSuppressed(e);
                    }
                }
            }
        }
    }

    /**
     * Roll back to the given savepoint. This is only allowed if the
     * transaction is open.
     *
     * @param savepointId the savepoint id
     */
    public void rollbackToSavepoint(long savepointId) {
        long lastState = setStatus(STATUS_ROLLING_BACK);
        long logId = getLogId(lastState);
        boolean success;
        try {
            store.rollbackTo(this, logId, savepointId);
        } finally {
            notifyAllWaitingTransactions();
            long expectedState = composeState(STATUS_ROLLING_BACK, logId, hasRollback(lastState));
            long newState = composeState(STATUS_OPEN, savepointId, true);
            do {
                success = statusAndLogId.compareAndSet(expectedState, newState);
            } while (!success && statusAndLogId.get() == expectedState);
        }
        // this is moved outside of finally block to avert masking original exception, if any
        if (!success) {
            throw DataUtils.newMVStoreException(
                    DataUtils.ERROR_TRANSACTION_ILLEGAL_STATE,
                    "Transaction {0} concurrently modified while rollback to savepoint was in progress",
                    transactionId);
        }
    }

    /**
     * Roll the transaction back. Afterwards, this transaction is closed.
     */
    public void rollback() {
        markTransactionEnd();
        Throwable ex = null;
        int status = STATUS_OPEN;
        try {
            long lastState = setStatus(STATUS_ROLLED_BACK);
            status = getStatus(lastState);
            long logId = getLogId(lastState);
            if (logId > 0) {
                store.rollbackTo(this, logId, 0);
            }
        } catch (Throwable e) {
            status = getStatus();
            if (isActive(status)) {
                ex = e;
                throw e;
            }
        } finally {
            try {
                if (isActive(status)) {
                    store.endTransaction(this, true);
                }
            } catch (Throwable e) {
                if (ex == null) {
                    throw e;
                } else {
                    ex.addSuppressed(e);
                }
            }
        }
    }

    private static boolean isActive(int status) {
        return status != STATUS_CLOSED
            && status != STATUS_COMMITTED
            && status != STATUS_ROLLED_BACK;
    }

    /**
     * Get the list of changes, starting with the latest change, up to the
     * given savepoint (in reverse order than they occurred). The value of
     * the change is the value before the change was applied.
     *
     * @param savepointId the savepoint id, 0 meaning the beginning of the
     *            transaction
     * @return the changes
     */
    public Iterator<TransactionStore.Change> getChanges(long savepointId) {
        return store.getChanges(this, getLogId(), savepointId);
    }

    /**
     * Sets the new lock timeout.
     *
     * @param timeoutMillis the new lock timeout in milliseconds
     */
    public void setTimeoutMillis(int timeoutMillis) {
        this.timeoutMillis = timeoutMillis > 0 ? timeoutMillis : store.timeoutMillis;
    }

    private long getLogId() {
        return getLogId(statusAndLogId.get());
    }

    /**
     * Check whether this transaction is open.
     */
    private void checkOpen(int status) {
        if (status != STATUS_OPEN) {
            throw DataUtils.newMVStoreException(
                    DataUtils.ERROR_TRANSACTION_ILLEGAL_STATE,
                    "Transaction {0} has status {1}, not OPEN", transactionId, getStatusName(status));
        }
    }

    /**
     * Check whether this transaction is open or prepared.
     */
    private void checkNotClosed() {
        if (getStatus() == STATUS_CLOSED) {
            throw DataUtils.newMVStoreException(
                    DataUtils.ERROR_CLOSED, "Transaction {0} is closed", transactionId);
        }
    }

    /**
     * Transition this transaction into a closed state.
     */
    void closeIt() {
        transactionMaps.clear();
        long lastState = setStatus(STATUS_CLOSED);
        store.store.deregisterVersionUsage(txCounter);
        if((hasChanges(lastState) || hasRollback(lastState))) {
            notifyAllWaitingTransactions();
        }
    }

    private void notifyAllWaitingTransactions() {
        if (notificationRequested) {
            synchronized (this) {
                notifyAll();
            }
        }
    }

    /**
     * Make this transaction to wait for the specified transaction to be closed,
     * because both of them try to modify the same map entry.
     *
     * @param toWaitFor transaction to wait for
     * @param mapName name of the map containing blocking entry
     * @param key of the blocking entry
     * @param timeoutMillis timeout in milliseconds, {@code -1} for default
     * @return true if other transaction was closed and this one can proceed, false if timed out
     */
    public boolean waitFor(Transaction toWaitFor, String mapName, Object key, int timeoutMillis) {
        blockingTransaction = toWaitFor;
        blockingMapName = mapName;
        blockingKey = key;
        if (isDeadlocked(toWaitFor)) {
            tryThrowDeadLockException(false);
        }
        boolean result = toWaitFor.waitForThisToEnd(timeoutMillis == -1 ? this.timeoutMillis : timeoutMillis, this);
        blockingMapName = null;
        blockingKey = null;
        blockingTransaction = null;
        return result;
    }

    private boolean isDeadlocked(Transaction toWaitFor) {
        // use transaction sequence No as a tie-breaker
        // the youngest transaction should be selected as a victim
        Transaction youngest = toWaitFor;
        int backstop = store.getMaxTransactionId();
        for(Transaction tx = toWaitFor, nextTx;
            (nextTx = tx.blockingTransaction) != null && tx.getStatus() == Transaction.STATUS_OPEN && backstop > 0;
            tx = nextTx, --backstop) {

            if (nextTx.sequenceNum > youngest.sequenceNum) {
                youngest = nextTx;
            }

            if (nextTx == this) {
                if (youngest == this) {
                    return true;
                }
                Transaction btx = youngest.blockingTransaction;
                if (btx != null) {
                    youngest.setStatus(STATUS_ROLLING_BACK);
                    btx.notifyAllWaitingTransactions();
                    return false;
                }
            }
        }
        return false;
    }

    private void tryThrowDeadLockException(boolean throwIt) {
        BitSet visited = new BitSet();
        StringBuilder details = new StringBuilder(
                String.format("Transaction %d has been chosen as a deadlock victim. Details:%n", transactionId));
        for (Transaction tx = this, nextTx;
                !visited.get(tx.transactionId) &&  (nextTx = tx.blockingTransaction) != null; tx = nextTx) {
            visited.set(tx.transactionId);
            details.append(String.format(
                    "Transaction %d attempts to update map <%s> entry with key <%s> modified by transaction %s%n",
                    tx.transactionId, tx.blockingMapName, tx.blockingKey, tx.blockingTransaction));
            if (nextTx == this) {
                throwIt = true;
            }
        }
        if (throwIt) {
            throw DataUtils.newMVStoreException(DataUtils.ERROR_TRANSACTIONS_DEADLOCK, "{0}", details.toString());
        }
    }

    private synchronized boolean waitForThisToEnd(int millis, Transaction waiter) {
        long time = System.nanoTime();
        notificationRequested = true;
        long state;
        int status;
        while ((status = getStatus(state = statusAndLogId.get())) != STATUS_CLOSED
                && status != STATUS_ROLLED_BACK && !hasRollback(state)) {
            if (waiter.getStatus() != STATUS_OPEN) {
                waiter.tryThrowDeadLockException(true);
            }
            int remaining = millis - (int) ((System.nanoTime() - time) / 1_000_000L);
            if (remaining <= 0) {
                return false;
            }
            try {
                wait(remaining);
            } catch (InterruptedException ex) {
                return false;
            }
        }
        return true;
    }

    /**
     * Remove the map.
     *
     * @param <K> the key type
     * @param <V> the value type
     * @param map the map
     */
    public <K, V> void removeMap(TransactionMap<K, V> map) {
        store.removeMap(map);
    }

    @Override
    public String toString() {
        return transactionId + "(" + sequenceNum + ") " + stateToString();
    }

    private String stateToString() {
        return stateToString(statusAndLogId.get());
    }

    private static String stateToString(long state) {
        return getStatusName(getStatus(state)) + (hasRollback(state) ? "<" : "") + " " + getLogId(state);
    }


    private static int getStatus(long state) {
        return (int)(state >>> LOG_ID_BITS1) & STATUS_MASK;
    }

    private static long getLogId(long state) {
        return state & LOG_ID_MASK;
    }

    private static boolean hasRollback(long state) {
        return (state & (1L << (STATUS_BITS + LOG_ID_BITS1))) != 0;
    }

    private static boolean hasChanges(long state) {
        return getLogId(state) != 0;
    }

    private static long composeState(int status, long logId, boolean hasRollback) {
        assert logId < LOG_ID_LIMIT : logId;
        assert (status & ~STATUS_MASK) == 0 : status;

        if (hasRollback) {
            status |= 1 << STATUS_BITS;
        }
        return ((long)status << LOG_ID_BITS1) | logId;
    }

    private static String getStatusName(int status) {
        return status >= 0 && status < STATUS_NAMES.length ? STATUS_NAMES[status] : "UNKNOWN_STATUS_" + status;
    }
}
