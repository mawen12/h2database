/*
 * Copyright 2004-2025 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.value;

/**
 * 版本化的值（可能为null）。
 * 其包含当前值与最新提交的值（如何当期值未提交）。
 * 对于未提交的值，其包含 operationId(由 transactionId 和 logId 组成)。
 */
public class VersionedValue<T> {

    protected VersionedValue() {}

    public boolean isCommitted() {
        return true;
    }

    public long getOperationId() {
        return 0L;
    }

    @SuppressWarnings("unchecked")
    public T getCurrentValue() {
        return (T)this;
    }

    @SuppressWarnings("unchecked")
    public T getCommittedValue() {
        return (T)this;
    }

}
