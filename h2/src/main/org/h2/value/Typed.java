/*
 * Copyright 2004-2025 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.value;

/**
 * 带有数据类型的对象
 */
public interface Typed {

    /**
     * 返回数据类型
     *
     * @return the data type
     */
    TypeInfo getType();

}
