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

package com.alibaba.polardbx.server;

import com.alibaba.polardbx.CobarServer;
import com.alibaba.polardbx.common.eventlogger.EventLogger;
import com.alibaba.polardbx.common.eventlogger.EventType;
import com.alibaba.polardbx.common.utils.AddressUtils;
import com.alibaba.polardbx.common.utils.Pair;
import com.alibaba.polardbx.config.SystemConfig;
import com.alibaba.polardbx.config.loader.ServerLoader;
import com.alibaba.polardbx.gms.metadb.MetaDbDataSource;
import com.alibaba.polardbx.gms.metadb.schema.SchemaChangeManager;
import com.alibaba.polardbx.gms.privilege.AccountType;
import com.alibaba.polardbx.gms.privilege.PolarAccount;
import com.alibaba.polardbx.gms.privilege.PolarAccountInfo;
import com.alibaba.polardbx.gms.privilege.PolarPrivManager;
import com.alibaba.polardbx.gms.privilege.PolarPrivUtil;
import com.alibaba.polardbx.gms.topology.StorageInfoAccessor;
import com.alibaba.polardbx.gms.topology.StorageInfoRecord;
import com.alibaba.polardbx.gms.util.GmsJdbcUtil;
import com.alibaba.polardbx.gms.util.InstIdUtil;
import com.alibaba.polardbx.gms.util.PasswdUtil;
import com.alibaba.polardbx.common.exception.TddlRuntimeException;
import com.google.common.base.Splitter;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileWriter;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * 启动server
 *
 * @author jianghang 2014-4-30 下午5:00:50
 * @since 5.1.0
 */
public final class TddlLauncher {

    private static final Logger logger = LoggerFactory.getLogger(TddlLauncher.class);

    public static void main(String[] args) throws Throwable {
        ServerLoader loader = new ServerLoader();
        loader.loadConfig();
        SystemConfig config = loader.getSystem();
        if (config.isInitializeGms()) {
            try {
                initUserAccount(config);
                initGms(config);
                initPolarxRootUser(config);
                System.err.println("Initialize polardbx success");
                System.exit(0);
            } catch (TddlRuntimeException e) {  
                // Error at Mysql root connection step, for example remote access, password
                logger.error("createConnection failed: " + e);
                e.printStackTrace();
                Thread.sleep(1500);
                System.exit(1);
            } catch (SQLException e) {
                // Sql operation errors. One special case is drop user (my_polarx) in Mysql 8.0
                // Access denied; you need (at least one of) the SYSTEM_USER privilege(s) for this operation
                logger.error("initialize gms failed due to: " + e);
                e.printStackTrace();
                Thread.sleep(1500);            // Wait 1.5s for AsyncROOT logger to flush log.
                System.exit(1);
            }
        }

        try {

            logger.info("## start the tddl server.");
            final CobarServer server = CobarServer.getInstance();
            server.init();
            EventLogger.log(EventType.ONLINE, "CN is online");
            logger.info("## the tddl server is running now ......");
            Runtime.getRuntime().addShutdownHook(new Thread() {

                @Override
                public void run() {
                    try {
                        logger.info("## stop the tddl server");
                        server.destroy();
                    } catch (Throwable e) {
                        logger.warn("##something goes wrong when stopping tddl server:\n{}",
                            ExceptionUtils.getFullStackTrace(e));
                    } finally {
                        logger.info("## tddl server is down.  ");
                    }
                }

            });
        } catch (Throwable e) {
            logger.error("## Something goes wrong when starting up the tddl server:\n{}",
                ExceptionUtils.getFullStackTrace(e));
            System.exit(0);
        }
    }

    /**
     * Initialize inner user and account
     */
    private static void initUserAccount(SystemConfig config) throws SQLException, IOException {
        String sqlDropDatabase = "DROP DATABASE IF EXISTS %s";
        String sqlCreateDatabase = "CREATE DATABASE %s";

        String rootDb = "mysql";
        String rootUser = config.getMetaDbRootUser();
        String rootPasswd = config.getMetaDbRootPasswd();
        Pair<String, Integer> metaDbAddr =
            AddressUtils.getIpPortPairByAddrStr(Objects.requireNonNull(config.getMetaDbFirstAddr()));

        String metaDatabase = config.getMetaDbName();
        String innerUser = config.getMetaDbUser();
        String innerPasswd = PasswdUtil.genRandomPasswd(12);
        String innerPasswdEnc = PasswdUtil.encrypt(innerPasswd);
        config.setMetaDbPasswd(PasswdUtil.encrypt(innerPasswd));
        System.err.printf("Generate password for user: %s && %s%n", innerUser, innerPasswd);
        System.err.printf("Encrypted password: %s\n", innerPasswdEnc);

        if (!saveMetaDbPasswd(config, innerPasswdEnc)) {
            System.err.println(" ======== Paste following configurations to conf/server.properties ! ======= ");
            System.err.println("metaDbPasswd=" + innerPasswdEnc);
            System.err.println(" ======== Paste above configurations to conf/server.properties ! ======= ");
        }

        // create database
        try (Connection conn = GmsJdbcUtil.createConnection(
            metaDbAddr.getKey(),
            metaDbAddr.getValue(),
            rootDb,
            "",
            rootUser,
            rootPasswd)) {

            Statement stmt = conn.createStatement();

            if (config.isForceCleanup()) {
                stmt.executeUpdate(String.format(sqlDropDatabase, metaDatabase));
            }

            stmt.executeUpdate(String.format(sqlCreateDatabase, metaDatabase));
            System.err.println("create metadb database: " + metaDatabase);
        }

        // create user on all storage nodes
        createInnerUser(config, config.getMetaDbFirstAddr(), innerPasswd);
        List<String> dataNodeList = Splitter.on(",").splitToList(config.getDataNodeList());
        for (String dataNode : dataNodeList) {
            createInnerUser(config, dataNode, innerPasswd);
        }
    }

    /**
     * Save generated password into property file
     *
     * @return true if save success
     */
    private static boolean saveMetaDbPasswd(SystemConfig config, String passWdEnc) throws IOException {
        String propertyFile = config.getPropertyFile();

        if (propertyFile.startsWith("classpath")) {
            System.err.println("The property file is resident at resource file, skip saving password into it");
            return false;
        }

        try (FileWriter writer = new FileWriter(propertyFile, true)) {
            writer.append("metaDbPasswd=").append(passWdEnc).append("\n");
            writer.flush();
            System.err.printf("Save generated password into file %s success", propertyFile);
            return true;
        }
    }

    private static void createInnerUser(SystemConfig config, String addr, String passwd) throws SQLException {
        String sqlDropUser = "DROP USER IF EXISTS %s";
        String sqlCreateUser = "CREATE USER %s IDENTIFIED WITH mysql_native_password BY '%s'";
        String sqlGrantPrivileges = "GRANT ALL PRIVILEGES ON *.* TO %s";
        String innerUser = config.getMetaDbUser();

        Pair<String, Integer> ipPort = AddressUtils.getIpPortPairByAddrStr(addr);

        String rootDb = "mysql";
        String rootUser = config.getMetaDbRootUser();
        String rootPasswd = config.getMetaDbRootPasswd();

        try (Connection conn = GmsJdbcUtil.createConnection(
            ipPort.getKey(),
            ipPort.getValue(),
            rootDb,
            "",
            rootUser,
            rootPasswd)) {

            Statement stmt = conn.createStatement();
            for (String scope : Arrays.asList("%", "localhost", "127.0.0.1")) {
                String userName = String.format("'%s'@'%s'", innerUser, scope);
                stmt.executeUpdate(String.format(sqlDropUser, userName));
                stmt.executeUpdate(String.format(sqlCreateUser, userName, passwd));
                stmt.executeUpdate(String.format(sqlGrantPrivileges, userName));
            }
            System.err.printf("create user (%s) on node (%s)\n", innerUser, addr);
        }
    }

    /**
     * Initialize gms configuration
     * 1. create table for metadb
     * 2. insert meta of datanode into storage_info table
     */
    private static void initGms(SystemConfig config) throws SQLException {
        // initialize storage_info
        // Init metadb datasource
        MetaDbDataSource.initMetaDbDataSource(
            config.getMetaDbAddr(),
            config.getMetaDbName(),
            config.getMetaDbProp(),
            config.getMetaDbRootUser(),
            PasswdUtil.encrypt(config.getMetaDbRootPasswd()));
        MetaDbDataSource metaDb = MetaDbDataSource.getInstance();

        // create all tables
        SchemaChangeManager scm = SchemaChangeManager.getInstance();
        scm.handle();

        try (Connection conn = metaDb.getConnection()) {
            StorageInfoAccessor accessor = new StorageInfoAccessor();
            accessor.setConnection(conn);

            // metadb
            String metadbInstId = "polardbx_meta";
            StorageInfoRecord metaDbRecord = prepareStorageInfoRecord(config, metadbInstId, config.getMetaDbAddr());
            metaDbRecord.instKind = StorageInfoRecord.INST_KIND_META_DB;
            metaDbRecord.xport = config.getMetaDbXprotoPort();
            accessor.addStorageInfo(metaDbRecord);

            // storage instance
            List<String> dataNodeList = Splitter.on(",").splitToList(config.getDataNodeList());
            for (int i = 0; i < dataNodeList.size(); i++) {
                String storageInstId = "polardbx_dn_" + i;
                String addr = dataNodeList.get(i);
                StorageInfoRecord record = prepareStorageInfoRecord(config, storageInstId, addr);
                record.instKind = StorageInfoRecord.INST_KIND_MASTER;
                accessor.addStorageInfo(record);

            }
            logger.info("initialize storage_info for " + config.getDataNodeList());
        }
    }

    private static StorageInfoRecord prepareStorageInfoRecord(SystemConfig config,
                                                              String storageInstId,
                                                              String addr) {
        StorageInfoRecord record = new StorageInfoRecord();

        AddressUtils.XAddress xaddr = AddressUtils.resolveXAddress(addr);
        record.instId = config.getInstanceId();
        record.user = config.getMetaDbUser();
        record.passwdEnc = config.getMetaDbPasswd();
        record.storageInstId = storageInstId;
        record.storageMasterInstId = storageInstId;
        record.ip = xaddr.getIp();
        record.port = xaddr.getPort();
        record.xport = xaddr.getXport();
        record.status = 0;
        record.storageType = StorageInfoRecord.STORAGE_TYPE_GALAXY_SINGLE;
        record.instKind = StorageInfoRecord.INST_KIND_MASTER;

        return record;
    }

    private static void initPolarxRootUser(SystemConfig config) throws SQLException {
        String passwordEnc = PolarPrivManager.getInstance().encryptPassword(config.getPolarxRootPasswd());
        PolarAccount account =
            PolarAccount.newBuilder()
                .setUsername(config.getPolarxRootUser())
                .setPassword(passwordEnc)
                .setAccountType(AccountType.GOD)
                .build();
        PolarAccountInfo accountInfo = new PolarAccountInfo(account);

        MetaDbDataSource metaDb = MetaDbDataSource.getInstance();
        try (Connection conn = metaDb.getConnection()) {
            Statement stmt = conn.createStatement();

            stmt.executeUpdate(PolarPrivUtil.getInsertUserPrivSql(accountInfo, true));

            System.err.printf("Root user for polarx with password: %s && %s\n",
                config.getPolarxRootUser(), config.getPolarxRootPasswd());
            System.err.println("Encrypted password for polarx: " + PasswdUtil.encrypt(config.getPolarxRootPasswd()));

            // add quarantine config
            String insertQuarantine = String.format("INSERT IGNORE INTO quarantine_config " +
                    "VALUES (null, now(), now(), '%s', 'test_grp', null, null, '*.*.*.*')",
                InstIdUtil.getInstId());
            stmt.executeUpdate(insertQuarantine);
        }
    }
}
