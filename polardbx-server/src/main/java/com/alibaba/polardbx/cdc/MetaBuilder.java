/*
 * Copyright [2013-2021], Alibaba Group Holding Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.alibaba.polardbx.cdc;

import com.alibaba.polardbx.cdc.entity.LogicMeta;
import com.alibaba.polardbx.server.conn.InnerConnection;
import com.google.common.collect.Lists;
import com.alibaba.polardbx.common.exception.TddlNestableRuntimeException;
import com.alibaba.polardbx.common.utils.Pair;
import com.alibaba.polardbx.gms.metadb.table.TablesAccessor;
import com.alibaba.polardbx.gms.metadb.table.TablesExtAccessor;
import com.alibaba.polardbx.gms.metadb.table.TablesExtRecord;
import com.alibaba.polardbx.gms.metadb.table.TablesRecord;
import com.alibaba.polardbx.gms.partition.TablePartitionAccessor;
import com.alibaba.polardbx.gms.partition.TablePartitionRecord;
import com.alibaba.polardbx.gms.topology.DbGroupInfoAccessor;
import com.alibaba.polardbx.gms.topology.DbGroupInfoRecord;
import com.alibaba.polardbx.gms.topology.DbInfoAccessor;
import com.alibaba.polardbx.gms.topology.DbInfoManager;
import com.alibaba.polardbx.gms.topology.DbInfoRecord;
import com.alibaba.polardbx.gms.topology.GroupDetailInfoAccessor;
import com.alibaba.polardbx.gms.util.InstIdUtil;
import com.alibaba.polardbx.gms.util.MetaDbUtil;
import com.alibaba.polardbx.rule.model.TargetDB;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Created by ziyang.lb
 **/
public class MetaBuilder {
    private static volatile String lowerCaseFlag;

    static LogicMeta.LogicDbMeta buildLogicDbMeta(String schemaName, Map<String, List<TargetDB>> tablesParams)
        throws SQLException {
        Map<String, String> group2PhyDbMapping = buildGroup2PhyDbMapping(schemaName);
        Map<String, String> group2StorageInstMapping = buildGroup2StorageInstMapping(schemaName);

        LogicMeta.LogicDbMeta logicDbMeta = new LogicMeta.LogicDbMeta();
        logicDbMeta.setSchema(schemaName);
        logicDbMeta.setCharset(buildDbInfo(schemaName).charset);

        logicDbMeta.setPhySchemas(Lists.newArrayList());
        for (Map.Entry<String, String> entry : group2PhyDbMapping.entrySet()) {
            LogicMeta.PhySchema phySchema = new LogicMeta.PhySchema();
            phySchema.setGroup(entry.getKey());
            phySchema.setSchema(entry.getValue());
            phySchema.setStorageInstId(group2StorageInstMapping.get(entry.getKey()));
            logicDbMeta.getPhySchemas().add(phySchema);
        }

        logicDbMeta.setLogicTableMetas(new ArrayList<>());
        if (tablesParams != null && !tablesParams.isEmpty()) {
            Map<String, Pair<TablesRecord, TablesExtInfo>> tablesInfo = buildTablesInfo(schemaName);
            for (Map.Entry<String, List<TargetDB>> m : tablesParams.entrySet()) {
                TableMode tableMode = tablesInfo.get(m.getKey()).getValue().tableMode;
                logicDbMeta.getLogicTableMetas().add(
                    buildLogicTableMetaInternal(tableMode, m.getKey(), m.getValue(), tablesInfo.get(m.getKey()),
                        group2PhyDbMapping, group2StorageInstMapping));
            }
        }
        return logicDbMeta;
    }

    static LogicMeta.LogicTableMeta buildLogicTableMeta(TableMode tableMode, String schemaName, String tableName,
                                                        List<TargetDB> targetDbList)
        throws SQLException {
        Map<String, String> group2PhyDbMapping = buildGroup2PhyDbMapping(schemaName);
        Map<String, String> group2StorageInstMapping = buildGroup2StorageInstMapping(schemaName);
        return buildLogicTableMetaInternal(tableMode, tableName, targetDbList,
            buildOneTablesInfo(tableMode, schemaName, tableName),
            group2PhyDbMapping,
            group2StorageInstMapping);
    }

    private static LogicMeta.LogicTableMeta buildLogicTableMetaInternal(TableMode tableMode,
                                                                        String tableName,
                                                                        List<TargetDB> targetDbList,
                                                                        Pair<TablesRecord, TablesExtInfo> tableInfo,
                                                                        Map<String, String> group2PhyDbMapping,
                                                                        Map<String, String> group2StorageInstMapping) {

        LogicMeta.LogicTableMeta logicTableMeta = new LogicMeta.LogicTableMeta();
        logicTableMeta.setTableName(tableName);
        logicTableMeta.setTableMode(tableMode.getValue());
        logicTableMeta.setTableType(tableInfo.getValue().tableType);
        logicTableMeta.setTableCollation(tableInfo.getKey().tableCollation);
        logicTableMeta.setPhySchemas(Lists.newArrayList());
        targetDbList.forEach(t -> {
            LogicMeta.PhySchema phySchema = new LogicMeta.PhySchema();
            phySchema.setGroup(t.getDbIndex());
            phySchema.setSchema(group2PhyDbMapping.get(t.getDbIndex()));
            phySchema.setStorageInstId(group2StorageInstMapping.get(t.getDbIndex()));
            phySchema.setPhyTables(Lists.newArrayList(t.getTableNames()).stream().map(MetaBuilder::translate).collect(
                Collectors.toList()));
            logicTableMeta.getPhySchemas().add(phySchema);
        });

        return logicTableMeta;
    }

    private static Map<String, String> buildGroup2PhyDbMapping(String schemaName)
        throws SQLException {
        try (Connection metaDbConn = MetaDbUtil.getConnection()) {
            DbGroupInfoAccessor dbGroupInfoAccessor = new DbGroupInfoAccessor();
            dbGroupInfoAccessor.setConnection(metaDbConn);

            // 在scale out场景下，会查询到group_type为GROUP_TYPE_SCALEOUT_FINISHED的记录，需要过滤掉
            return dbGroupInfoAccessor.queryDbGroupByDbName(schemaName).stream()
                .filter(g -> g.groupType == DbGroupInfoRecord.GROUP_TYPE_NORMAL).collect(
                    Collectors.toMap(g -> g.groupName, g -> translate(g.phyDbName)));
        }
    }

    private static Map<String, String> buildGroup2StorageInstMapping(String schemaName)
        throws SQLException {
        try (Connection metaDbConn = MetaDbUtil.getConnection()) {
            GroupDetailInfoAccessor groupDetailInfoAccessor = new GroupDetailInfoAccessor();
            groupDetailInfoAccessor.setConnection(metaDbConn);

            return groupDetailInfoAccessor.getGroupDetailInfoByInstIdAndDbName(InstIdUtil.getInstId(), schemaName)
                .stream()
                .collect(Collectors.toMap(g -> g.groupName, g -> g.storageInstId));
        }
    }

    private static Map<String, Pair<TablesRecord, TablesExtInfo>> buildTablesInfo(String schema) throws SQLException {
        try (Connection metaDbConn = MetaDbUtil.getConnection()) {
            TablesAccessor tablesAccessor = new TablesAccessor();
            tablesAccessor.setConnection(metaDbConn);
            Map<String, TablesRecord> tablesMap =
                tablesAccessor.query(schema).stream().collect(Collectors.toMap(i -> i.tableName, j -> j));

            if (DbInfoManager.getInstance().isNewPartitionDb(schema)) {
                TablePartitionAccessor partitionAccessor = new TablePartitionAccessor();
                partitionAccessor.setConnection(metaDbConn);
                Map<String, List<TablePartitionRecord>> partitionRecords =
                    partitionAccessor.getTablePartitionsByDbNameTbName(schema, null).stream()
                        .collect(Collectors.groupingBy(TablePartitionRecord::getTableName));

                final Map<String, Pair<TablesRecord, TablesExtInfo>> result = new HashMap<>();
                tablesMap.keySet().forEach(k -> {
                    result.put(k,
                        new Pair<>(tablesMap.get(k),
                            new TablesExtInfo(TableMode.PARTITION, partitionRecords.get(k).get(0).tblType)));
                });
                return result;
            } else {
                TablesExtAccessor tablesExtAccessor = new TablesExtAccessor();
                tablesExtAccessor.setConnection(metaDbConn);
                Map<String, TablesExtRecord> tablesExtMap =
                    tablesExtAccessor.query(schema).stream().collect(Collectors.toMap(i -> i.tableName, j -> j));

                final Map<String, Pair<TablesRecord, TablesExtInfo>> result = new HashMap<>();
                tablesMap.keySet().forEach(k -> {
                    result.put(k,
                        new Pair<>(tablesMap.get(k),
                            new TablesExtInfo(TableMode.SHARDING, tablesExtMap.get(k).tableType)));
                });
                return result;
            }
        }
    }

    private static Pair<TablesRecord, TablesExtInfo> buildOneTablesInfo(TableMode tableMode, String schema,
                                                                        String table)
        throws SQLException {
        try (Connection metaDbConn = MetaDbUtil.getConnection()) {
            TablesAccessor tablesAccessor = new TablesAccessor();
            tablesAccessor.setConnection(metaDbConn);
            TablesRecord tablesRecord = tablesAccessor.query(schema, table, false);
            if (tablesRecord == null) {
                throw new TddlNestableRuntimeException(
                    "TablesRecord not found , schema is {" + schema + "}, table is {" + table + "}.");
            }

            TablesExtInfo tablesExtInfo;
            if (tableMode == TableMode.SHARDING) {
                TablesExtAccessor tablesExtAccessor = new TablesExtAccessor();
                tablesExtAccessor.setConnection(metaDbConn);
                TablesExtRecord tablesExtRecord = tablesExtAccessor.query(schema, table, false);
                if (tablesExtRecord == null) {
                    throw new TddlNestableRuntimeException(
                        "TablesExtRecord not found , schema is {" + schema + "}, table is {" + table + "}.");
                }

                tablesExtInfo = new TablesExtInfo(tableMode, tablesExtRecord.tableType);
            } else {
                TablePartitionAccessor tablePartitionAccessor = new TablePartitionAccessor();
                tablePartitionAccessor.setConnection(metaDbConn);
                List<TablePartitionRecord> records =
                    tablePartitionAccessor.getTablePartitionsByDbNameTbName(schema, table);
                if (records == null || records.isEmpty()) {
                    throw new TddlNestableRuntimeException(
                        "TablePartitionRecord not found , schema is {" + schema + "}, table is {" + table + "}.");
                }

                tablesExtInfo = new TablesExtInfo(tableMode, records.get(0).tblType);
            }

            return new Pair<>(tablesRecord, tablesExtInfo);
        }
    }

    private static DbInfoRecord buildDbInfo(String schema) throws SQLException {
        try (Connection metaDbConn = MetaDbUtil.getConnection()) {
            DbInfoAccessor dbInfoAccessor = new DbInfoAccessor();
            dbInfoAccessor.setConnection(metaDbConn);
            return dbInfoAccessor.getDbInfoByDbName(schema);
        }
    }

    private static String translate(String input) {
        tryInitLowerCaseFlag();
        if ("0".equals(lowerCaseFlag) || "2".equals(lowerCaseFlag)) {
            return input;
        } else {
            return input.toLowerCase();
        }
    }

    private static void tryInitLowerCaseFlag() {
        // 获取大小写配置
        // 该参数的介绍参见：https://dev.mysql.com/doc/refman/5.7/en/server-system-variables.html#sysvar_lower_case_table_names

        if (lowerCaseFlag == null) {
            synchronized (MetaBuilder.class) {
                if (lowerCaseFlag == null) {
                    initLowerCaseFlag();
                }
            }
        }
    }

    private static void initLowerCaseFlag() {
        try (Connection connection = new InnerConnection()) {
            try (Statement stmt = connection.createStatement();
                ResultSet resultSet = stmt.executeQuery("select @@lower_case_table_names")) {
                if (resultSet.next()) {
                    lowerCaseFlag = resultSet.getString(1);
                }
            }
        } catch (SQLException e) {
            throw new TddlNestableRuntimeException("get lower_case_table_names parameter failed.", e);
        }
    }
}
