/*******************************************************************************
 *     ___                  _   ____  ____
 *    / _ \ _   _  ___  ___| |_|  _ \| __ )
 *   | | | | | | |/ _ \/ __| __| | | |  _ \
 *   | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *    \__\_\\__,_|\___||___/\__|____/|____/
 *
 *  Copyright (c) 2014-2019 Appsicle
 *  Copyright (c) 2019-2022 QuestDB
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

package io.questdb.cairo;

import io.questdb.MessageBus;
import io.questdb.MessageBusImpl;
import io.questdb.Metrics;
import io.questdb.cairo.mig.EngineMigration;
import io.questdb.cairo.pool.*;
import io.questdb.cairo.sql.AsyncWriterCommand;
import io.questdb.cairo.sql.TableRecordMetadata;
import io.questdb.cairo.sql.TableReferenceOutOfDateException;
import io.questdb.cairo.vm.api.MemoryMARW;
import io.questdb.cairo.wal.WalReader;
import io.questdb.cairo.wal.WalWriter;
import io.questdb.cairo.wal.seq.TableSequencerAPI;
import io.questdb.cutlass.text.TextImportExecutionContext;
import io.questdb.griffin.DatabaseSnapshotAgent;
import io.questdb.log.Log;
import io.questdb.log.LogFactory;
import io.questdb.mp.*;
import io.questdb.std.*;
import io.questdb.std.datetime.microtime.MicrosecondClock;
import io.questdb.std.str.Path;
import io.questdb.std.str.StringSink;
import io.questdb.tasks.TelemetryTask;
import io.questdb.tasks.WalTxnNotificationTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;

import java.io.Closeable;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public class CairoEngine implements Closeable, WriterSource {
    public static final String BUSY_READER = "busyReader";
    private static final Log LOG = LogFactory.getLog(CairoEngine.class);
    private final AtomicLong asyncCommandCorrelationId = new AtomicLong();
    private final CairoConfiguration configuration;
    private final EngineMaintenanceJob engineMaintenanceJob;
    private final MessageBusImpl messageBus;
    private final MetadataPool metadataPool;
    private final Metrics metrics;
    private final ReaderPool readerPool;
    private final IDGenerator tableIdGenerator;
    private final TableNameRegistry tableNameRegistry;
    private final TableSequencerAPI tableSequencerAPI;
    private final MPSequence telemetryPubSeq;
    private final RingQueue<TelemetryTask> telemetryQueue;
    private final SCSequence telemetrySubSeq;
    private final TextImportExecutionContext textImportExecutionContext;
    // initial value of unpublishedWalTxnCount is 1 because we want to scan for unapplied WAL transactions on startup
    private final AtomicLong unpublishedWalTxnCount = new AtomicLong(1);
    private final WalWriterPool walWriterPool;
    private final WriterPool writerPool;


    // Kept for embedded API purposes. The second constructor (the one with metrics)
    // should be preferred for internal use.
    public CairoEngine(CairoConfiguration configuration) {
        this(configuration, Metrics.disabled());
    }

    public CairoEngine(CairoConfiguration configuration, Metrics metrics) {
        this.configuration = configuration;
        this.textImportExecutionContext = new TextImportExecutionContext(configuration);
        this.metrics = metrics;
        this.tableSequencerAPI = new TableSequencerAPI(this, configuration);
        this.messageBus = new MessageBusImpl(configuration);
        this.writerPool = new WriterPool(this.getConfiguration(), this.getMessageBus(), metrics);
        this.readerPool = new ReaderPool(configuration, messageBus);
        this.metadataPool = new MetadataPool(configuration, this);
        this.walWriterPool = new WalWriterPool(configuration, this);
        this.engineMaintenanceJob = new EngineMaintenanceJob(configuration);
        if (configuration.getTelemetryConfiguration().getEnabled()) {
            this.telemetryQueue = new RingQueue<>(TelemetryTask::new, configuration.getTelemetryConfiguration().getQueueCapacity());
            this.telemetryPubSeq = new MPSequence(telemetryQueue.getCycle());
            this.telemetrySubSeq = new SCSequence();
            telemetryPubSeq.then(telemetrySubSeq).then(telemetryPubSeq);
        } else {
            this.telemetryQueue = null;
            this.telemetryPubSeq = null;
            this.telemetrySubSeq = null;
        }
        tableIdGenerator = new IDGenerator(configuration, TableUtils.TAB_INDEX_FILE_NAME);
        try {
            tableIdGenerator.open();
        } catch (Throwable e) {
            close();
            throw e;
        }
        // Recover snapshot, if necessary.
        try {
            DatabaseSnapshotAgent.recoverSnapshot(this);
        } catch (Throwable e) {
            close();
            throw e;
        }
        // Migrate database files.
        try {
            EngineMigration.migrateEngineTo(this, ColumnType.VERSION, false);
        } catch (Throwable e) {
            close();
            throw e;
        }

        try {
            this.tableNameRegistry = configuration.isReadOnlyInstance() ?
                    new TableNameRegistryRO(configuration) : new TableNameRegistryRW(configuration);
            this.tableNameRegistry.reloadTableNameCache();
        } catch (Throwable e) {
            close();
            throw e;
        }
    }

    @TestOnly
    public boolean clear() {
        boolean b1 = readerPool.releaseAll();
        boolean b2 = writerPool.releaseAll();
        boolean b3 = tableSequencerAPI.releaseAll();
        boolean b4 = metadataPool.releaseAll();
        boolean b5 = walWriterPool.releaseAll();
        messageBus.reset();
        return b1 & b2 & b3 & b4 & b5;
    }

    @Override
    public void close() {
        Misc.free(writerPool);
        Misc.free(readerPool);
        Misc.free(metadataPool);
        Misc.free(walWriterPool);
        Misc.free(tableIdGenerator);
        Misc.free(messageBus);
        Misc.free(tableSequencerAPI);
        Misc.free(telemetryQueue);
        Misc.free(tableNameRegistry);
    }

    @TestOnly
    public void closeNameRegistry() {
        tableNameRegistry.close();
    }

    public TableToken createTable(
            CairoSecurityContext securityContext,
            MemoryMARW mem,
            Path path,
            boolean ifNotExists,
            TableStructure struct,
            boolean keepLock
    ) {
        securityContext.checkWritePermission();
        CharSequence tableName = struct.getTableName();
        validNameOrThrow(tableName);

        int tableId = (int) tableIdGenerator.getNextId();
        TableToken tableToken = lockTableName(tableName, tableId, struct.isWalEnabled());
        if (tableToken == null) {
            if (ifNotExists) {
                return null;
            }
            throw EntryUnavailableException.instance("table exists");
        }

        try {
            String lockedReason = lock(securityContext, tableToken, "createTable");
            if (null == lockedReason) {
                boolean tableCreated = false;
                try {
                    int status = TableUtils.exists(configuration.getFilesFacade(), path, configuration.getRoot(), tableToken.getDirName());
                    if (status != TableUtils.TABLE_DOES_NOT_EXIST) {
                        throw CairoException.nonCritical().put("name is reserved [table=").put(tableName).put(']');
                    }
                    createTableUnsafe(
                            securityContext,
                            mem,
                            path,
                            struct,
                            tableToken,
                            tableId
                    );
                    tableCreated = true;
                } finally {
                    if (!keepLock) {
                        unlockTableUnsafe(tableToken, null, tableCreated);
                        LOG.info().$("unlocked [table=`").$(tableToken).$("`]").$();
                    }
                }
                tableNameRegistry.registerName(tableToken);
            } else {
                if (!ifNotExists) {
                    throw EntryUnavailableException.instance(lockedReason);
                }
            }
        } catch (Throwable th) {
            if (struct.isWalEnabled()) {
                // tableToken.getLoggingName() === tableName, table cannot be renamed while creation hasn't finished
                tableSequencerAPI.dropTable(tableToken, true);
            }
            throw th;
        } finally {
            tableNameRegistry.unlockTableName(tableToken);
        }
        return tableToken;
    }

    // caller has to acquire the lock before this method is called and release the lock after the call
    public void createTableUnsafe(
            CairoSecurityContext securityContext,
            MemoryMARW mem,
            Path path,
            TableStructure struct,
            TableToken tableToken,
            int tableId
    ) {
        securityContext.checkWritePermission();

        // only create the table after it has been registered
        final FilesFacade ff = configuration.getFilesFacade();
        final CharSequence root = configuration.getRoot();
        final int mkDirMode = configuration.getMkDirMode();
        TableUtils.createTable(
                ff,
                root,
                mkDirMode,
                mem,
                path,
                tableToken.getDirName(),
                struct,
                ColumnType.VERSION,
                tableId
        );
        if (struct.isWalEnabled()) {
            tableSequencerAPI.registerTable(tableId, struct, tableToken);
        }
    }

    public void drop(
            CairoSecurityContext securityContext,
            Path path,
            TableToken tableToken
    ) {
        securityContext.checkWritePermission();
        verifyTableToken(tableToken);
        if (tableToken.isWal()) {
            if (tableNameRegistry.dropTable(tableToken)) {
                tableSequencerAPI.dropTable(tableToken, false);
            } else {
                LOG.info().$("table is already dropped [table=").$(tableToken)
                        .$(", dirName=").$(tableToken.getDirName()).I$();
            }
        } else {
            CharSequence lockedReason = lock(securityContext, tableToken, "removeTable");
            if (null == lockedReason) {
                try {
                    path.of(configuration.getRoot()).concat(tableToken).$();
                    int errno;
                    if ((errno = configuration.getFilesFacade().rmdir(path)) != 0) {
                        LOG.error().$("drop failed [tableName='").$(tableToken).$("', error=").$(errno).$(']').$();
                        throw CairoException.critical(errno).put("could not remove table [name=").put(tableToken)
                                .put(", dirName=").put(tableToken.getDirName()).put(']');
                    }
                } finally {
                    unlockTableUnsafe(tableToken, null, false);
                }

                tableNameRegistry.dropTable(tableToken);
                return;
            }
            throw CairoException.nonCritical().put("Could not lock '").put(tableToken).put("' [reason='").put(lockedReason).put("']");
        }
    }

    public TableWriter getBackupWriter(
            CairoSecurityContext securityContext,
            TableToken tableToken,
            CharSequence backupDirName
    ) {
        securityContext.checkWritePermission();
        verifyTableToken(tableToken);

        // There is no point in pooling/caching these writers since they are only used once, backups are not incremental
        return new TableWriter(
                configuration,
                tableToken,
                messageBus,
                null,
                true,
                DefaultLifecycleManager.INSTANCE,
                backupDirName,
                Metrics.disabled()
        );
    }

    @TestOnly
    public int getBusyReaderCount() {
        return readerPool.getBusyCount();
    }

    @TestOnly
    public int getBusyWriterCount() {
        return writerPool.getBusyCount();
    }

    public long getCommandCorrelationId() {
        return asyncCommandCorrelationId.incrementAndGet();
    }

    public CairoConfiguration getConfiguration() {
        return configuration;
    }

    public Job getEngineMaintenanceJob() {
        return engineMaintenanceJob;
    }

    public MessageBus getMessageBus() {
        return messageBus;
    }

    public TableRecordMetadata getMetadata(CairoSecurityContext securityContext, TableToken tableToken) {
        verifyTableToken(tableToken);
        try {
            return metadataPool.get(tableToken);
        } catch (CairoException e) {
            tryRepairTable(securityContext, tableToken, e);
        }
        return metadataPool.get(tableToken);
    }

    public TableRecordMetadata getMetadata(CairoSecurityContext securityContext, TableToken tableToken, long structureVersion) {
        verifyTableToken(tableToken);
        try {
            final TableRecordMetadata metadata = metadataPool.get(tableToken);
            if (structureVersion != TableUtils.ANY_TABLE_VERSION && metadata.getStructureVersion() != structureVersion) {
                final TableReferenceOutOfDateException ex = TableReferenceOutOfDateException.of(tableToken, metadata.getTableId(), metadata.getTableId(), structureVersion, metadata.getStructureVersion());
                metadata.close();
                throw ex;
            }
            return metadata;
        } catch (CairoException e) {
            tryRepairTable(securityContext, tableToken, e);
        }
        return metadataPool.get(tableToken);
    }

    public Metrics getMetrics() {
        return metrics;
    }

    @TestOnly
    public PoolListener getPoolListener() {
        return this.writerPool.getPoolListener();
    }

    public TableReader getReader(CairoSecurityContext securityContext, TableToken tableToken) {
        verifyTableToken(tableToken);
        return readerPool.get(tableToken);
    }

    public TableReader getReader(
            CairoSecurityContext securityContext,
            TableToken tableToken,
            long version
    ) {
        TableToken newTableToken = tableNameRegistry.getTableToken(tableToken.getTableName());
        if (newTableToken == null) {
            throw CairoException.tableDoesNotExist(tableToken.getTableName());
        }
        final int tableId = tableToken.getTableId();
        if (tableId > -1 && newTableToken.getTableId() != tableId) {
            throw TableReferenceOutOfDateException.of(tableToken, tableId, newTableToken.getTableId(), version, -1);
        }

        TableReader reader = readerPool.get(tableToken);
        if ((version > -1 && reader.getVersion() != version)
                || tableId > -1 && reader.getMetadata().getTableId() != tableId) {
            TableReferenceOutOfDateException ex = TableReferenceOutOfDateException.of(tableToken, tableId, reader.getMetadata().getTableId(), version, reader.getVersion());
            reader.close();
            throw ex;
        }
        return reader;
    }

    public Map<CharSequence, AbstractMultiTenantPool.Entry<ReaderPool.R>> getReaderPoolEntries() {
        return readerPool.entries();
    }

    public TableReader getReaderWithRepair(CairoSecurityContext securityContext, TableToken tableToken) {
        try {
            return getReader(securityContext, tableToken);
        } catch (CairoException e) {
            // Cannot open reader on existing table is pretty bad.
            // In some messed states, for example after _meta file swap failure Reader cannot be opened
            // but writer can be. Opening writer fixes the table mess.
            tryRepairTable(securityContext, tableToken, e);
        }
        try {
            return getReader(securityContext, tableToken);
        } catch (CairoException e) {
            LOG.critical()
                    .$("could not open reader [table=").$(tableToken)
                    .$(", errno=").$(e.getErrno())
                    .$(", error=").$(e.getMessage()).I$();
            throw e;
        }
    }

    public int getStatus(
            CairoSecurityContext securityContext,
            Path path,
            TableToken tableToken
    ) {
        if (tableToken == TableNameRegistry.LOCKED_TOKEN) {
            return TableUtils.TABLE_RESERVED;
        }
        if (tableToken == null || !tableToken.equals(tableNameRegistry.getTableToken(tableToken.getTableName()))) {
            return TableUtils.TABLE_DOES_NOT_EXIST;
        }
        return TableUtils.exists(configuration.getFilesFacade(), path, configuration.getRoot(), tableToken.getDirName());
    }

    public IDGenerator getTableIdGenerator() {
        return tableIdGenerator;
    }

    public TableSequencerAPI getTableSequencerAPI() {
        return tableSequencerAPI;
    }

    public TableToken getTableToken(final CharSequence tableName) {
        TableToken tableToken = tableNameRegistry.getTableToken(tableName);
        if (tableToken == null) {
            throw CairoException.tableDoesNotExist(tableName);
        }
        if (tableToken == TableNameRegistry.LOCKED_TOKEN) {
            throw CairoException.nonCritical().put("table name is reserved [table=").put(tableName).put("]");
        }
        return tableToken;
    }

    public TableToken getTableToken(final CharSequence tableName, int lo, int hi) {
        StringSink sink = Misc.getThreadLocalBuilder();
        sink.put(tableName, lo, hi);
        return getTableToken(sink);
    }

    public TableToken getTableTokenByDirName(String dirName, int tableId) {
        return tableNameRegistry.getTableToken(dirName, tableId);
    }

    public TableToken getTableTokenByDirName(CharSequence dirName) {
        return tableNameRegistry.getTokenByDirName(dirName);
    }

    public TableToken getTableTokenIfExists(CharSequence tableName) {
        return tableNameRegistry.getTableToken(tableName);
    }

    public TableToken getTableTokenIfExists(CharSequence tableName, int lo, int hi) {
        StringSink sink = Misc.getThreadLocalBuilder();
        sink.put(tableName, lo, hi);
        return tableNameRegistry.getTableToken(sink);
    }

    public void getTableTokens(ObjList<TableToken> bucket, boolean includeDropped) {
        tableNameRegistry.getTableTokens(bucket, includeDropped);
    }

    @Override
    public TableWriterAPI getTableWriterAPI(
            CairoSecurityContext securityContext,
            TableToken tableToken,
            @Nullable String lockReason
    ) {
        securityContext.checkWritePermission();
        verifyTableToken(tableToken);

        if (!tableToken.isWal()) {
            return writerPool.get(tableToken, lockReason);

        }
        return walWriterPool.get(tableToken);
    }

    public Sequence getTelemetryPubSequence() {
        return telemetryPubSeq;
    }

    public RingQueue<TelemetryTask> getTelemetryQueue() {
        return telemetryQueue;
    }

    public SCSequence getTelemetrySubSequence() {
        return telemetrySubSeq;
    }

    public TextImportExecutionContext getTextImportExecutionContext() {
        return textImportExecutionContext;
    }

    public long getUnpublishedWalTxnCount() {
        return unpublishedWalTxnCount.get();
    }

    public TableToken getUpdatedTableToken(TableToken tableToken) {
        return tableNameRegistry.getTokenByDirName(tableToken.getDirName());
    }

    // For testing only
    @TestOnly
    public WalReader getWalReader(
            @SuppressWarnings("unused") CairoSecurityContext securityContext,
            TableToken tableToken,
            CharSequence walName,
            int segmentId,
            long walRowCount
    ) {
        if (tableToken.isWal()) {
            return new WalReader(configuration, tableToken, walName, segmentId, walRowCount);
        }

        throw CairoException.nonCritical().put("WAL reader is not supported for table ").put(tableToken);
    }

    @TestOnly
    public @NotNull WalWriter getWalWriter(CairoSecurityContext securityContext, TableToken tableToken) {
        securityContext.checkWritePermission();
        verifyTableToken(tableToken);
        return walWriterPool.get(tableToken);
    }

    public TableWriter getWriter(
            CairoSecurityContext securityContext,
            TableToken tableToken,
            String lockReason
    ) {
        securityContext.checkWritePermission();
        verifyTableToken(tableToken);
        return writerPool.get(tableToken, lockReason);
    }

    public TableWriter getWriterOrPublishCommand(
            CairoSecurityContext securityContext,
            TableToken tableToken,
            @NotNull AsyncWriterCommand asyncWriterCommand
    ) {
        securityContext.checkWritePermission();
        verifyTableToken(tableToken);
        return writerPool.getWriterOrPublishCommand(tableToken, asyncWriterCommand.getCommandName(), asyncWriterCommand);
    }

    public TableWriter getWriterUnsafe(TableToken tableToken, String lockReason) {
        return writerPool.get(tableToken, lockReason);
    }

    public boolean isTableDropped(TableToken tableToken) {
        return tableNameRegistry.isTableDropped(tableToken);
    }

    public boolean isWalTable(TableToken tableToken) {
        return tableToken.isWal();
    }

    public String lock(
            CairoSecurityContext securityContext,
            TableToken tableToken,
            String lockReason
    ) {
        assert null != lockReason;
        securityContext.checkWritePermission();
        // busy metadata is same as busy reader from user perspective
        String lockedReason = BUSY_READER;
        if (metadataPool.lock(tableToken)) {
            lockedReason = writerPool.lock(tableToken, lockReason);
            if (lockedReason == null) {
                // not locked
                if (readerPool.lock(tableToken)) {
                    LOG.info().$("locked [table=`").utf8(tableToken.getDirName()).$("`, thread=").$(Thread.currentThread().getId()).I$();
                    return null;
                }
                writerPool.unlock(tableToken);
                lockedReason = BUSY_READER;
            }
            metadataPool.unlock(tableToken);
        }
        return lockedReason;
    }

    public boolean lockReaders(TableToken tableToken) {
        verifyTableToken(tableToken);
        return readerPool.lock(tableToken);
    }

    public boolean lockReadersByTableToken(TableToken tableToken) {
        return readerPool.lock(tableToken);
    }

    public TableToken lockTableName(CharSequence tableName, boolean isWal) {
        validNameOrThrow(tableName);
        int tableId = (int) getTableIdGenerator().getNextId();
        return lockTableName(tableName, tableId, isWal);
    }

    @Nullable
    public TableToken lockTableName(CharSequence tableName, int tableId, boolean isWal) {
        String tableNameStr = Chars.toString(tableName);
        final String dirName = TableUtils.getTableDir(configuration.mangleTableDirNames(), tableNameStr, tableId, isWal);
        return tableNameRegistry.lockTableName(tableNameStr, dirName, tableId, isWal);
    }

    public CharSequence lockWriter(CairoSecurityContext securityContext, TableToken tableToken, String lockReason) {
        securityContext.checkWritePermission();
        verifyTableToken(tableToken);
        return writerPool.lock(tableToken, lockReason);
    }

    public void notifyWalTxnCommitted(TableToken tableToken, long txn) {
        final Sequence pubSeq = messageBus.getWalTxnNotificationPubSequence();
        while (true) {
            long cursor = pubSeq.next();
            if (cursor > -1L) {
                WalTxnNotificationTask task = messageBus.getWalTxnNotificationQueue().get(cursor);
                task.of(tableToken, txn);
                pubSeq.done(cursor);
                return;
            } else if (cursor == -1L) {
                LOG.info().$("cannot publish WAL notifications, queue is full [current=")
                        .$(pubSeq.current()).$(", table=").utf8(tableToken.getDirName())
                        .I$();
                // queue overflow, throw away notification and notify a job to rescan all tables
                notifyWalTxnRepublisher();
                return;
            }
        }
    }

    public void notifyWalTxnRepublisher() {
        unpublishedWalTxnCount.incrementAndGet();
    }

    public void registerTableToken(TableToken tableToken) {
        tableNameRegistry.registerName(tableToken);
    }

    @TestOnly
    public boolean releaseAllReaders() {
        boolean b1 = metadataPool.releaseAll();
        return readerPool.releaseAll() & b1;
    }

    @TestOnly
    public void releaseAllWriters() {
        writerPool.releaseAll();
    }

    public boolean releaseInactive() {
        boolean useful = writerPool.releaseInactive();
        useful |= readerPool.releaseInactive();
        useful |= tableSequencerAPI.releaseInactive();
        useful |= metadataPool.releaseInactive();
        useful |= walWriterPool.releaseInactive();
        return useful;
    }

    @TestOnly
    public void releaseInactiveTableSequencers() {
        walWriterPool.releaseInactive();
        tableSequencerAPI.releaseInactive();
    }

    public void releaseReadersByTableToken(TableToken tableToken) {
        readerPool.unlock(tableToken);
    }

    @TestOnly
    public void reloadTableNames() {
        tableNameRegistry.reloadTableNameCache();
    }

    public int removeDirectory(@Transient Path path, CharSequence dir) {
        path.of(configuration.getRoot()).concat(dir);
        final FilesFacade ff = configuration.getFilesFacade();
        return ff.rmdir(path.slash$());
    }

    public void removeTableToken(TableToken tableName) {
        tableNameRegistry.purgeToken(tableName);
    }

    public TableToken rename(
            CairoSecurityContext securityContext,
            Path path,
            MemoryMARW memory,
            CharSequence tableName,
            Path otherPath,
            CharSequence newName
    ) {
        securityContext.checkWritePermission();

        validNameOrThrow(tableName);
        validNameOrThrow(newName);

        TableToken tableToken = getTableToken(tableName);
        final TableToken newTableToken;
        if (tableToken != null) {
            if (tableToken.isWal()) {
                newTableToken = tableNameRegistry.rename(tableName, newName, tableToken);
                TableUtils.overwriteTableNameFile(path.of(configuration.getRoot()).concat(newTableToken), memory, configuration.getFilesFacade(), newTableToken);
                tableSequencerAPI.renameWalTable(tableToken, newTableToken);
                return newTableToken;
            } else {
                String lockedReason = lock(securityContext, tableToken, "renameTable");
                if (null == lockedReason) {
                    try {
                        newTableToken = rename0(path, tableToken, tableName, otherPath, newName);
                        TableUtils.overwriteTableNameFile(path.of(configuration.getRoot()).concat(newTableToken), memory, configuration.getFilesFacade(), newTableToken);
                    } finally {
                        unlock(securityContext, tableToken, null, false);
                    }
                    tableNameRegistry.dropTable(tableToken);
                } else {
                    LOG.error().$("cannot lock and rename [from='").$(tableName).$("', to='").$(newName).$("', reason='").$(lockedReason).$("']").$();
                    throw EntryUnavailableException.instance(lockedReason);
                }
                return newTableToken;
            }
        } else {
            LOG.error().$('\'').utf8(tableName).$("' does not exist. Rename failed.").$();
            throw CairoException.nonCritical().put("Rename failed. Table '").put(tableName).put("' does not exist");
        }
    }

    @TestOnly
    public void resetNameRegistryMemory() {
        tableNameRegistry.resetMemory();
    }

    @TestOnly
    public void setPoolListener(PoolListener poolListener) {
        this.metadataPool.setPoolListener(poolListener);
        this.writerPool.setPoolListener(poolListener);
        this.readerPool.setPoolListener(poolListener);
        this.walWriterPool.setPoolListener(poolListener);
    }

    public void unlock(
            @SuppressWarnings("unused") CairoSecurityContext securityContext,
            TableToken tableToken,
            @Nullable TableWriter writer,
            boolean newTable
    ) {
        verifyTableToken(tableToken);
        unlockTableUnsafe(tableToken, writer, newTable);
        LOG.info().$("unlocked [table=`").$(tableToken).$("`]").$();
    }

    public void unlockReaders(TableToken tableToken) {
        verifyTableToken(tableToken);
        readerPool.unlock(tableToken);
    }

    public void unlockTableName(TableToken tableToken) {
        tableNameRegistry.unlockTableName(tableToken);
    }

    public void unlockWriter(CairoSecurityContext securityContext, TableToken tableToken) {
        securityContext.checkWritePermission();
        verifyTableToken(tableToken);
        writerPool.unlock(tableToken);
    }

    private TableToken rename0(Path path, TableToken srcTableToken, CharSequence tableName, Path otherPath, CharSequence to) {
        final FilesFacade ff = configuration.getFilesFacade();
        final CharSequence root = configuration.getRoot();

        path.of(root).concat(srcTableToken).$();
        TableToken dstTableToken = lockTableName(to, srcTableToken.getTableId(), false);

        if (dstTableToken == null || ff.exists(otherPath.of(root).concat(dstTableToken).$())) {
            if (dstTableToken != null) {
                tableNameRegistry.unlockTableName(dstTableToken);
            }
            LOG.error().$("rename target exists [from='").utf8(tableName).$("', to='").utf8(otherPath.chop$()).I$();
            throw CairoException.nonCritical().put("Rename target exists");
        }

        try {
            if (ff.rename(path, otherPath) != Files.FILES_RENAME_OK) {
                int error = ff.errno();
                LOG.error().$("could not rename [from='").$(path).$("', to='").utf8(otherPath).$("', error=").$(error).I$();
                throw CairoException.critical(error)
                        .put("could not rename [from='").put(path)
                        .put("', to='").put(otherPath)
                        .put("', error=").put(error);
            }
            tableNameRegistry.registerName(dstTableToken);
            return dstTableToken;
        } finally {
            tableNameRegistry.unlockTableName(dstTableToken);
        }
    }

    private void tryRepairTable(
            CairoSecurityContext securityContext,
            TableToken tableToken,
            RuntimeException rethrow
    ) {
        try {
            securityContext.checkWritePermission();
            writerPool.get(tableToken, "repair").close();
        } catch (EntryUnavailableException e) {
            // This is fine, writer is busy. Throw back origin error.
            throw rethrow;
        } catch (Throwable th) {
            LOG.critical()
                    .$("could not repair before reading [dirName=").utf8(tableToken.getDirName())
                    .$(" ,error=").$(th.getMessage()).I$();
            throw rethrow;
        }
    }

    private void unlockTableUnsafe(TableToken tableToken, TableWriter writer, boolean newTable) {
        readerPool.unlock(tableToken);
        writerPool.unlock(tableToken, writer, newTable);
        metadataPool.unlock(tableToken);
    }

    private void validNameOrThrow(CharSequence tableName) {
        if (!TableUtils.isValidTableName(tableName, configuration.getMaxFileNameLength())) {
            throw CairoException.nonCritical()
                    .put("invalid table name [table=").putAsPrintable(tableName)
                    .put(']');
        }
    }

    private void verifyTableToken(TableToken tableToken) {
        TableToken newTableToken = tableNameRegistry.getTableToken(tableToken.getTableName());
        if (newTableToken == null) {
            throw CairoException.tableDoesNotExist(tableToken.getTableName());
        }
        if (!newTableToken.equals(tableToken)) {
            throw TableReferenceOutOfDateException.of(tableToken, tableToken.getTableId(), newTableToken.getTableId(), newTableToken.getTableId(), -1);
        }
    }

    private class EngineMaintenanceJob extends SynchronizedJob {

        private final long checkInterval;
        private final MicrosecondClock clock;
        private long last = 0;

        public EngineMaintenanceJob(CairoConfiguration configuration) {
            this.clock = configuration.getMicrosecondClock();
            this.checkInterval = configuration.getIdleCheckInterval() * 1000;
        }

        @Override
        protected boolean runSerially() {
            long t = clock.getTicks();
            if (last + checkInterval < t) {
                last = t;
                return releaseInactive();
            }
            return false;
        }
    }
}
