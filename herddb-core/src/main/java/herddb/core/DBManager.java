/*
 Licensed to Diennea S.r.l. under one
 or more contributor license agreements. See the NOTICE file
 distributed with this work for additional information
 regarding copyright ownership. Diennea S.r.l. licenses this file
 to you under the Apache License, Version 2.0 (the
 "License"); you may not use this file except in compliance
 with the License.  You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing,
 software distributed under the License is distributed on an
 "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 KIND, either express or implied.  See the License for the
 specific language governing permissions and limitations
 under the License.

 */
package herddb.core;

import herddb.log.CommitLog;
import herddb.log.CommitLogManager;
import herddb.log.LogNotAvailableException;
import herddb.metadata.MetadataStorageManager;
import herddb.metadata.MetadataStorageManagerException;
import herddb.model.DDLException;
import herddb.model.DDLStatementExecutionResult;
import herddb.model.DMLStatement;
import herddb.model.DMLStatementExecutionResult;
import herddb.model.GetResult;
import herddb.model.TableSpace;
import herddb.model.Statement;
import herddb.model.StatementExecutionException;
import herddb.model.StatementExecutionResult;
import herddb.model.commands.CreateTableSpaceStatement;
import herddb.model.commands.GetStatement;
import herddb.sql.SQLTranslator;
import herddb.storage.DataStorageManager;
import herddb.storage.DataStorageManagerException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * General Manager of the local instance of HerdDB
 *
 * @author enrico.olivelli
 */
public class DBManager implements AutoCloseable {

    private final Logger LOGGER = Logger.getLogger(DBManager.class.getName());
    private final Map<String, TableSpaceManager> tablesSpaces = new ConcurrentHashMap<>();
    private final MetadataStorageManager metadataStorageManager;
    private final DataStorageManager dataStorageManager;
    private final CommitLogManager commitLogManager;
    private final String nodeId;
    private final ReentrantReadWriteLock generalLock = new ReentrantReadWriteLock();
    private final Thread activator;
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final BlockingQueue<Object> activatorQueue = new LinkedBlockingDeque<>();
    private final SQLTranslator translator;
    private final ExecutorService threadPool = Executors.newCachedThreadPool(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, r + "");
            t.setDaemon(true);
            return t;
        }
    });

    public DBManager(String nodeId, MetadataStorageManager metadataStorageManager, DataStorageManager dataStorageManager, CommitLogManager commitLogManager) {
        this.metadataStorageManager = metadataStorageManager;
        this.dataStorageManager = dataStorageManager;
        this.commitLogManager = commitLogManager;
        this.nodeId = nodeId;
        this.translator = new SQLTranslator(this);
        this.activator = new Thread(new Activator(), "hdb-" + nodeId + "-activator");
        this.activator.setDaemon(true);
    }

    public SQLTranslator getTranslator() {
        return translator;
    }

    /**
     * Initial boot of the system
     */
    public void start() throws DataStorageManagerException, LogNotAvailableException, MetadataStorageManagerException {

        metadataStorageManager.start();

        metadataStorageManager.ensureDefaultTableSpace(nodeId);

        generalLock.writeLock().lock();
        try {
            dataStorageManager.start();
        } finally {
            generalLock.writeLock().unlock();
        }
        activator.start();

        triggerActivator();
    }

    public boolean waitForTablespace(String tableSpace, int millis) throws InterruptedException {
        return waitForTablespace(tableSpace, millis, true);
    }

    public boolean waitForTablespace(String tableSpace, int millis, boolean checkLeader) throws InterruptedException {
        long now = System.currentTimeMillis();
        while (System.currentTimeMillis() - now <= millis) {
            TableSpaceManager manager = tablesSpaces.get(tableSpace);
            if (manager != null) {
                if (checkLeader && manager.isLeader()) {
                    return true;
                }
                if (!checkLeader) {
                    return true;
                }
            }
            Thread.sleep(100);
        }
        return false;
    }

    public boolean waitForTable(String tableSpace, String table, int millis, boolean checkLeader) throws InterruptedException {
        long now = System.currentTimeMillis();
        while (System.currentTimeMillis() - now <= millis) {
            TableSpaceManager manager = tablesSpaces.get(tableSpace);
            if (manager != null) {
                if (checkLeader && manager.isLeader()) {
                    if (manager.getTableManager(table) != null) {
                        return true;
                    }
                }
                if (!checkLeader) {
                    if (manager.getTableManager(table) != null) {
                        return true;
                    }
                }
            }
            Thread.sleep(100);
        }
        return false;
    }

    private void bootTableSpace(String tableSpaceName) throws DataStorageManagerException, LogNotAvailableException, MetadataStorageManagerException, DDLException {
        TableSpace tableSpace = metadataStorageManager.describeTableSpace(tableSpaceName);
        if (!tableSpace.replicas.contains(nodeId)) {
            return;
        }
        LOGGER.log(Level.SEVERE, "Booting tablespace {0} on {1}", new Object[]{tableSpaceName, nodeId});
        CommitLog commitLog = commitLogManager.createCommitLog(tableSpaceName);
        generalLock.writeLock().lock();
        try {
            TableSpaceManager manager = new TableSpaceManager(nodeId, tableSpaceName, metadataStorageManager, dataStorageManager, commitLog, this);
            tablesSpaces.put(tableSpaceName, manager);
            manager.start();
        } finally {
            generalLock.writeLock().unlock();
        }
    }

    public StatementExecutionResult executeStatement(Statement statement) throws StatementExecutionException {
        LOGGER.log(Level.FINEST, "executeStatement {0}", new Object[]{statement});
        String tableSpace = statement.getTableSpace();
        if (tableSpace == null) {
            throw new StatementExecutionException("invalid tableSpace " + tableSpace);
        }

        if (statement instanceof CreateTableSpaceStatement) {
            if (statement.getTransactionId() > 0) {
                throw new StatementExecutionException("CREATE TABLESPACE cannot be issued inside a transaction");
            }
            return createTableSpace((CreateTableSpaceStatement) statement);
        }

        TableSpaceManager manager;
        generalLock.readLock().lock();
        try {
            manager = tablesSpaces.get(tableSpace);
        } finally {
            generalLock.readLock().unlock();
        }
        if (manager == null) {
            throw new StatementExecutionException("not such tableSpace " + tableSpace + " here");
        }
        return manager.executeStatement(statement);
    }

    /**
     * Executes a single lookup
     *
     * @param statement
     * @return
     * @throws StatementExecutionException
     */
    public GetResult get(GetStatement statement) throws StatementExecutionException {
        return (GetResult) executeStatement(statement);
    }

    /**
     * Utility method for DML/DDL statements
     *
     * @param statement
     * @param transaction
     * @return
     * @throws herddb.model.StatementExecutionException
     */
    public DMLStatementExecutionResult executeUpdate(DMLStatement statement) throws StatementExecutionException {
        return (DMLStatementExecutionResult) executeStatement(statement);
    }

    private StatementExecutionResult createTableSpace(CreateTableSpaceStatement createTableSpaceStatement) throws StatementExecutionException {
        TableSpace tableSpace;
        try {
            tableSpace = TableSpace.builder().leader(createTableSpaceStatement.getLeaderId()).name(createTableSpaceStatement.getTableSpace()).replicas(createTableSpaceStatement.getReplicas()).build();
        } catch (IllegalArgumentException invalid) {
            throw new StatementExecutionException("invalid CREATE TABLESPACE statement: " + invalid.getMessage(), invalid);
        }

        try {
            metadataStorageManager.registerTableSpace(tableSpace);
            triggerActivator();
            return new DDLStatementExecutionResult();
        } catch (Exception err) {
            throw new StatementExecutionException(err);
        }
    }

    @Override
    public void close() throws DataStorageManagerException {
        stopped.set(true);
        triggerActivator();
        try {
            activator.join();
        } catch (InterruptedException ignore) {
            ignore.printStackTrace();
        }
        threadPool.shutdown();
    }

    public void flush() throws DataStorageManagerException {

        List<TableSpaceManager> managers;
        generalLock.readLock().lock();
        try {
            managers = new ArrayList<>(tablesSpaces.values());
        } finally {
            generalLock.readLock().unlock();
        }
        for (TableSpaceManager man : managers) {
            man.flush();
        }
    }

    private void triggerActivator() {
        activatorQueue.offer("");
    }

    public String getNodeId() {
        return nodeId;
    }

    void submit(Runnable runnable) {
        try {
            threadPool.submit(runnable);
        } catch (RejectedExecutionException err) {
            LOGGER.log(Level.SEVERE, "rejected " + runnable, err);
        }
    }

    private class Activator implements Runnable {

        @Override
        public void run() {
            try {
                while (!stopped.get()) {
                    activatorQueue.take();
                    if (!stopped.get()) {
                        generalLock.writeLock().lock();
                        try {
                            Collection<String> actualTablesSpaces = metadataStorageManager.listTableSpaces();
                            LOGGER.log(Level.SEVERE, "actualTablesSpaces {0} node {1}, already booted {2}", new Object[]{actualTablesSpaces, nodeId, tablesSpaces});
                            for (String tableSpace : actualTablesSpaces) {
                                if (!tablesSpaces.containsKey(tableSpace)) {
                                    try {
                                        bootTableSpace(tableSpace);
                                    } catch (Exception err) {
                                        err.printStackTrace();
                                    }
                                }
                            }
                        } catch (MetadataStorageManagerException error) {
                            LOGGER.log(Level.SEVERE, "cannot access tablespace metadata", error);
                        } finally {
                            generalLock.writeLock().unlock();
                        }
                        Set<String> failedTableSpaces = new HashSet<>();
                        for (Map.Entry<String, TableSpaceManager> entry : tablesSpaces.entrySet()) {
                            if (entry.getValue().isFailed()) {
                                failedTableSpaces.add(entry.getKey());
                            }
                        }
                        if (!failedTableSpaces.isEmpty()) {
                            generalLock.writeLock().lock();
                            try {
                                for (String tableSpace : failedTableSpaces) {
                                    try {
                                        tablesSpaces.get(tableSpace).close();
                                    } catch (LogNotAvailableException err) {
                                        LOGGER.log(Level.SEVERE, "cannot reboot tablespace " + tableSpace, err);
                                    }
                                    tablesSpaces.remove(tableSpace);
                                }
                            } finally {
                                generalLock.writeLock().unlock();
                            }
                        }
                    }
                }

            } catch (InterruptedException ee) {
            }

            generalLock.writeLock().lock();
            try {
                for (Map.Entry<String, TableSpaceManager> manager : tablesSpaces.entrySet()) {
                    try {
                        manager.getValue().close();
                    } catch (Exception err) {
                        LOGGER.log(Level.SEVERE, "error during shutdown of manager of tablespace " + manager.getKey(), err);
                    }
                }
            } finally {
                generalLock.writeLock().unlock();
            }
            try {
                dataStorageManager.close();
            } catch (Exception err) {
                LOGGER.log(Level.SEVERE, "error during shutdown", err);
            }
            try {
                metadataStorageManager.close();
            } catch (Exception err) {
                LOGGER.log(Level.SEVERE, "error during shutdown", err);
            }
            LOGGER.log(Level.SEVERE, "{0} activator stopped", nodeId);

        }
    }

    public TableSpaceManager getTableSpaceManager(String tableSpace) {
        return tablesSpaces.get(tableSpace);
    }
}
