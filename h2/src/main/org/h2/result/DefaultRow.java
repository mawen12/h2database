/*
 * Copyright 2004-2025 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.result;

import org.h2.engine.Constants;
import org.h2.table.Column;
import org.h2.value.Value;
import org.h2.value.ValueBigint;

/**
 * 代表一个表的一条记录的默认实现
 */
public class DefaultRow extends Row {

    /**
     * 该常量代表"内存使用未知，需要被首先计算"
     */
    public static final int MEMORY_CALCULATE = -1;

    /**
     * 一行数据的所有值（每一列一个实体）
     * 其索引和{@link Column#getColumnId()}一一对应
     */
    protected final Value[] data;

    /**
     * 实际占用内存
     */
    private int memory;

    DefaultRow(int columnCount) {
        this.data = new Value[columnCount];
        this.memory = MEMORY_CALCULATE;
    }

    public DefaultRow(Value[] data) {
        this.data = data;
        this.memory = MEMORY_CALCULATE;
    }

    public DefaultRow(Value[] data, int memory) {
        this.data = data;
        this.memory = memory;
    }

    @Override
    public Value getValue(int i) {
        return i == ROWID_INDEX ? ValueBigint.get(key) : data[i];
    }

    @Override
    public void setValue(int i, Value v) {
        if (i == ROWID_INDEX) {
            key = v.getLong();
        } else {
            data[i] = v;
        }
    }

    @Override
    public int getColumnCount() {
        return data.length;
    }

    @Override
    public int getMemory() {
        if (memory != MEMORY_CALCULATE) {
            return memory;
        }
        return memory = calculateMemory();
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder("( /* key:").append(key).append(" */ ");
        for (int i = 0, length = data.length; i < length; i++) {
            if (i > 0) {
                builder.append(", ");
            }
            Value v = data[i];
            builder.append(v == null ? "null" : v.getTraceSQL());
        }
        return builder.append(')').toString();
    }

    /**
     * Calculate the estimated memory used for this row, in bytes.
     *
     * @return the memory
     */
    protected int calculateMemory() {
        int m = Constants.MEMORY_ROW + Constants.MEMORY_ARRAY + data.length * Constants.MEMORY_POINTER;
        for (Value v : data) {
            if (v != null) {
                m += v.getMemory();
            }
        }
        return m;
    }

    @Override
    public Value[] getValueList() {
        return data;
    }

    @Override
    public boolean hasSharedData(Row other) {
        return other instanceof DefaultRow && data == ((DefaultRow) other).data;
    }

    @Override
    public void copyFrom(SearchRow source) {
        setKey(source.getKey());
        for (int i = 0; i < getColumnCount(); i++) {
            setValue(i, source.getValue(i));
        }
    }
}
