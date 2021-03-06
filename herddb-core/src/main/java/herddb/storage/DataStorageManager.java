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
package herddb.storage;

import herddb.log.LogSequenceNumber;
import herddb.model.Record;
import herddb.model.Table;
import herddb.utils.Bytes;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Physical storage of data
 *
 * @author enrico.olivelli
 */
public abstract class DataStorageManager {

    /**
     * Load a data page in memory
     *
     * @param tableName
     * @param pageId
     * @return
     */
    public abstract List<Record> loadPage(String tableName, Long pageId) throws DataStorageManagerException;

    /**
     * Load the ful list of keys on a page
     *
     * @param tableName
     * @param consumer
     */
    public abstract void loadExistingKeys(String tableName, BiConsumer<Bytes, Long> consumer) throws DataStorageManagerException;

    /**
     * Write a page on disk
     *
     * @param tableName
     * @param sequenceNumber
     * @param newPage
     * @return
     * @throws herddb.storage.DataStorageManagerException
     */
    public abstract Long writePage(String tableName, LogSequenceNumber sequenceNumber, List<Record> newPage) throws DataStorageManagerException;

    /**
     * Return the actual number of pages presents on disk
     *
     * @param tableName
     * @return
     * @throws DataStorageManagerException
     */
    public abstract int getActualNumberOfPages(String tableName) throws DataStorageManagerException;

    /**
     * Boots the Storage Manager
     *
     * @throws DataStorageManagerException
     */
    public abstract void start() throws DataStorageManagerException;

    /**
     * Shutsdown cleanly the Storage Manager
     *
     * @throws DataStorageManagerException
     */
    public abstract void close() throws DataStorageManagerException;

    /**
     * Load tables metadata
     * @param sequenceNumber 
     * @param tableSpace 
     * @return
     * @throws DataStorageManagerException
     */
    public abstract List<Table> loadTables(LogSequenceNumber sequenceNumber,String tableSpace) throws DataStorageManagerException;

    /**
     * Writes tables metadata
     * @param sequenceNumber
     * @param tableSpace 
     * @param tables
     * @throws DataStorageManagerException 
     */
    public abstract void writeTables(String tableSpace, LogSequenceNumber sequenceNumber, List<Table> tables) throws DataStorageManagerException;

    public abstract LogSequenceNumber getLastcheckpointSequenceNumber();

}
