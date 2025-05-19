/*
 * Copyright 2004-2025 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.command.ddl;

import java.util.ArrayList;

import org.h2.engine.SessionLocal;
import org.h2.schema.Schema;
import org.h2.table.Column;

/**
 * 创建表所需的数据
 */
public class CreateTableData {

    /**
     * 表所在的 schema
     */
    public Schema schema;

    /**
     * 创建的表名
     */
    public String tableName;

    /**
     * 对象ID
     */
    public int id;

    /**
     * 列列表
     */
    public ArrayList<Column> columns = new ArrayList<>();

    /**
     * 是否为临时表
     */
    public boolean temporary;

    /**
     * 是否为全局临时表
     */
    public boolean globalTemporary;

    /**
     * 索引是否应该被持久化，默认为true
     */
    public boolean persistIndexes;

    /**
     * 数据是否应该被持久化，默认为true
     */
    public boolean persistData;

    /**
     * 创建表的session
     */
    public SessionLocal session;

    /**
     * 用于创建表的表引擎
     */
    public String tableEngine;

    /**
     * 用于创建表的表引擎参数
     */
    public ArrayList<String> tableEngineParams;

}
