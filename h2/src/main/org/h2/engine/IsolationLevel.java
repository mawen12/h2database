/*
 * Copyright 2004-2025 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.engine;

import java.sql.Connection;

import org.h2.message.DbException;

/**
 * 隔离级别
 */
public enum IsolationLevel {

    /**
     * 允许脏读、不可重复读、幻读（读未提交）
     */
    READ_UNCOMMITTED(Connection.TRANSACTION_READ_UNCOMMITTED, Constants.LOCK_MODE_OFF),

    /**
     * 不允许脏读，允许不可重复读、幻读（读已提交）
     */
    READ_COMMITTED(Connection.TRANSACTION_READ_COMMITTED, Constants.LOCK_MODE_READ_COMMITTED),

    /**
     * 不允许脏读、不可重复读；允许幻读（可重复读）
     */
    REPEATABLE_READ(Connection.TRANSACTION_REPEATABLE_READ, Constants.LOCK_MODE_TABLE),

    /**
     * 不允许脏读、不可重读读、幻读（快照）
     */
    SNAPSHOT(Constants.TRANSACTION_SNAPSHOT, Constants.LOCK_MODE_TABLE),

    /**
     * 不允许脏读、不可重复读、缓存。（串行）
     * 具有此隔离级别的并发和串行执行应该具有相同的效果。
     */
    SERIALIZABLE(Connection.TRANSACTION_SERIALIZABLE, Constants.LOCK_MODE_TABLE);

    /**
     * Returns the isolation level from LOCK_MODE equivalent for PageStore and
     * old versions of H2.
     *
     * @param level
     *            the LOCK_MODE value
     * @return the isolation level
     */
    public static IsolationLevel fromJdbc(int level) {
        switch (level) {
        case Connection.TRANSACTION_READ_UNCOMMITTED:
            return IsolationLevel.READ_UNCOMMITTED;
        case Connection.TRANSACTION_READ_COMMITTED:
            return IsolationLevel.READ_COMMITTED;
        case Connection.TRANSACTION_REPEATABLE_READ:
            return IsolationLevel.REPEATABLE_READ;
        case Constants.TRANSACTION_SNAPSHOT:
            return IsolationLevel.SNAPSHOT;
        case Connection.TRANSACTION_SERIALIZABLE:
            return IsolationLevel.SERIALIZABLE;
        default:
            throw DbException.getInvalidValueException("isolation level", level);
        }
    }

    /**
     * Returns the isolation level from LOCK_MODE equivalent for PageStore and
     * old versions of H2.
     *
     * @param lockMode
     *            the LOCK_MODE value
     * @return the isolation level
     */
    public static IsolationLevel fromLockMode(int lockMode) {
        switch (lockMode) {
        case Constants.LOCK_MODE_OFF:
            return IsolationLevel.READ_UNCOMMITTED;
        case Constants.LOCK_MODE_READ_COMMITTED:
        default:
            return IsolationLevel.READ_COMMITTED;
        case Constants.LOCK_MODE_TABLE:
        case Constants.LOCK_MODE_TABLE_GC:
            return IsolationLevel.SERIALIZABLE;
        }
    }

    /**
     * Returns the isolation level from its SQL name.
     *
     * @param sql
     *            the SQL name
     * @return the isolation level from its SQL name
     */
    public static IsolationLevel fromSql(String sql) {
        switch (sql) {
        case "READ UNCOMMITTED":
            return READ_UNCOMMITTED;
        case "READ COMMITTED":
            return READ_COMMITTED;
        case "REPEATABLE READ":
            return REPEATABLE_READ;
        case "SNAPSHOT":
            return SNAPSHOT;
        case "SERIALIZABLE":
            return SERIALIZABLE;
        default:
            throw DbException.getInvalidValueException("isolation level", sql);
        }
    }

    private final String sql;

    private final int jdbc, lockMode;

    private IsolationLevel(int jdbc, int lockMode) {
        sql = name().replace('_', ' ').intern();
        this.jdbc = jdbc;
        this.lockMode = lockMode;
    }

    /**
     * Returns the SQL representation of this isolation level.
     *
     * @return SQL representation of this isolation level
     */
    public String getSQL() {
        return sql;
    }

    /**
     * Returns the JDBC constant for this isolation level.
     *
     * @return the JDBC constant for this isolation level
     */
    public int getJdbc() {
        return jdbc;
    }

    /**
     * Returns the LOCK_MODE equivalent for PageStore and old versions of H2.
     *
     * @return the LOCK_MODE equivalent
     */
    public int getLockMode() {
        return lockMode;
    }

    /**
     * Returns whether a non-repeatable read phenomena is allowed.
     *
     * @return whether a non-repeatable read phenomena is allowed
     */
    public boolean allowNonRepeatableRead() {
        return ordinal() < REPEATABLE_READ.ordinal();
    }

}
