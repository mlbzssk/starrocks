package com.starrocks.load;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.starrocks.analysis.AccessTestUtil;
import com.starrocks.analysis.Analyzer;
import com.starrocks.analysis.BinaryPredicate;
import com.starrocks.analysis.DeleteStmt;
import com.starrocks.analysis.IntLiteral;
import com.starrocks.analysis.PartitionNames;
import com.starrocks.analysis.SlotRef;
import com.starrocks.analysis.TableName;
import com.starrocks.backup.CatalogMocker;
import com.starrocks.catalog.Database;
import com.starrocks.catalog.Replica;
import com.starrocks.catalog.TabletInvertedIndex;
import com.starrocks.catalog.TabletMeta;
import com.starrocks.common.AnalysisException;
import com.starrocks.common.DdlException;
import com.starrocks.common.FeConstants;
import com.starrocks.common.MarkedCountDownLatch;
import com.starrocks.common.UserException;
import com.starrocks.common.jmockit.Deencapsulation;
import com.starrocks.load.DeleteJob.DeleteState;
import com.starrocks.mysql.privilege.Auth;
import com.starrocks.persist.EditLog;
import com.starrocks.qe.ConnectContext;
import com.starrocks.qe.QueryStateException;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.system.SystemInfoService;
import com.starrocks.task.AgentBatchTask;
import com.starrocks.task.AgentTask;
import com.starrocks.task.AgentTaskExecutor;
import com.starrocks.task.AgentTaskQueue;
import com.starrocks.transaction.GlobalTransactionMgr;
import com.starrocks.transaction.TabletCommitInfo;
import com.starrocks.transaction.TransactionState;
import com.starrocks.transaction.TransactionStatus;
import com.starrocks.transaction.TxnCommitAttachment;
import mockit.Expectations;
import mockit.Mock;
import mockit.MockUp;
import mockit.Mocked;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class DeleteHandlerTest {

    private DeleteHandler deleteHandler;

    private static final long BACKEND_ID_1 = 10000L;
    private static final long BACKEND_ID_2 = 10001L;
    private static final long BACKEND_ID_3 = 10002L;
    private static final long REPLICA_ID_1 = 70000L;
    private static final long REPLICA_ID_2 = 70001L;
    private static final long REPLICA_ID_3 = 70002L;
    private static final long TABLET_ID = 60000L;
    private static final long PARTITION_ID = 40000L;
    private static final long TBL_ID = 30000L;
    private static final long DB_ID = 20000L;

    @Mocked
    private GlobalStateMgr globalStateMgr;
    @Mocked
    private EditLog editLog;
    @Mocked
    private AgentTaskQueue agentTaskQueue;
    @Mocked
    private AgentTaskExecutor executor;

    private Database db;
    private Auth auth;

    Analyzer analyzer;

    private GlobalTransactionMgr globalTransactionMgr;
    private TabletInvertedIndex invertedIndex = new TabletInvertedIndex();
    private ConnectContext connectContext = new ConnectContext();

    @Before
    public void setUp() {
        FeConstants.runningUnitTest = true;

        globalTransactionMgr = new GlobalTransactionMgr(globalStateMgr);
        globalTransactionMgr.setEditLog(editLog);
        deleteHandler = new DeleteHandler();
        auth = AccessTestUtil.fetchAdminAccess();
        analyzer = AccessTestUtil.fetchAdminAnalyzer(false);
        try {
            db = CatalogMocker.mockDb();
        } catch (AnalysisException e) {
            e.printStackTrace();
            Assert.fail();
        }
        TabletMeta tabletMeta = new TabletMeta(DB_ID, TBL_ID, PARTITION_ID, TBL_ID, 0, null);
        invertedIndex.addTablet(TABLET_ID, tabletMeta);
        invertedIndex.addReplica(TABLET_ID, new Replica(REPLICA_ID_1, BACKEND_ID_1, 0, Replica.ReplicaState.NORMAL));
        invertedIndex.addReplica(TABLET_ID, new Replica(REPLICA_ID_2, BACKEND_ID_2, 0, Replica.ReplicaState.NORMAL));
        invertedIndex.addReplica(TABLET_ID, new Replica(REPLICA_ID_3, BACKEND_ID_3, 0, Replica.ReplicaState.NORMAL));

        new MockUp<EditLog>() {
            @Mock
            public void logSaveTransactionId(long transactionId) {
            }

            @Mock
            public void logInsertTransactionState(TransactionState transactionState) {
            }
        };

        new Expectations() {
            {
                globalStateMgr.getDb(anyString);
                minTimes = 0;
                result = db;

                globalStateMgr.getDb(anyLong);
                minTimes = 0;
                result = db;

                globalStateMgr.getEditLog();
                minTimes = 0;
                result = editLog;

                globalStateMgr.getAuth();
                minTimes = 0;
                result = auth;

                globalStateMgr.getNextId();
                minTimes = 0;
                result = 10L;

                globalStateMgr.getTabletInvertedIndex();
                minTimes = 0;
                result = invertedIndex;

                globalStateMgr.getEditLog();
                minTimes = 0;
                result = editLog;
            }
        };
        globalTransactionMgr.addDatabaseTransactionMgr(db.getId());

        SystemInfoService systemInfoService = new SystemInfoService();
        new Expectations() {
            {
                GlobalStateMgr.getCurrentState();
                minTimes = 0;
                result = globalStateMgr;

                GlobalStateMgr.getCurrentInvertedIndex();
                minTimes = 0;
                result = invertedIndex;

                GlobalStateMgr.getCurrentGlobalTransactionMgr();
                minTimes = 0;
                result = globalTransactionMgr;

                AgentTaskExecutor.submit((AgentBatchTask) any);
                minTimes = 0;

                AgentTaskQueue.addTask((AgentTask) any);
                minTimes = 0;
                result = true;

                GlobalStateMgr.getCurrentSystemInfo();
                minTimes = 0;
                result = systemInfoService;

                systemInfoService.getBackendIds(true);
                minTimes = 0;
                result = Lists.newArrayList();
            }
        };
    }

    @Test(expected = DdlException.class)
    public void testUnQuorumTimeout() throws DdlException, QueryStateException {
        BinaryPredicate binaryPredicate = new BinaryPredicate(BinaryPredicate.Operator.GT, new SlotRef(null, "k1"),
                new IntLiteral(3));

        DeleteStmt deleteStmt = new DeleteStmt(new TableName("test_db", "test_tbl"),
                new PartitionNames(false, Lists.newArrayList("test_tbl")), binaryPredicate);

        new Expectations(globalTransactionMgr) {
            {
                try {
                    globalTransactionMgr.abortTransaction(db.getId(), anyLong, anyString);
                } catch (UserException e) {
                }
                minTimes = 0;
            }
        };
        try {
            deleteStmt.analyze(analyzer);
        } catch (UserException e) {
            Assert.fail();
        }
        deleteHandler.process(deleteStmt);
        Assert.fail();
    }

    @Test
    public void testQuorumTimeout() throws DdlException, QueryStateException {
        BinaryPredicate binaryPredicate = new BinaryPredicate(BinaryPredicate.Operator.GT, new SlotRef(null, "k1"),
                new IntLiteral(3));

        DeleteStmt deleteStmt = new DeleteStmt(new TableName("test_db", "test_tbl"),
                new PartitionNames(false, Lists.newArrayList("test_tbl")), binaryPredicate);

        Set<Replica> finishedReplica = Sets.newHashSet();
        finishedReplica.add(new Replica(REPLICA_ID_1, BACKEND_ID_1, 0, Replica.ReplicaState.NORMAL));
        finishedReplica.add(new Replica(REPLICA_ID_2, BACKEND_ID_2, 0, Replica.ReplicaState.NORMAL));
        TabletDeleteInfo tabletDeleteInfo = new TabletDeleteInfo(PARTITION_ID, TABLET_ID);
        tabletDeleteInfo.getFinishedReplicas().addAll(finishedReplica);

        new MockUp<DeleteJob>() {
            @Mock
            public Collection<TabletDeleteInfo> getTabletDeleteInfo() {
                return Lists.newArrayList(tabletDeleteInfo);
            }
        };

        new MockUp<GlobalTransactionMgr>() {
            @Mock
            public TransactionState getTransactionState(long transactionId) {
                TransactionState transactionState = new TransactionState();
                transactionState.setTransactionStatus(TransactionStatus.VISIBLE);
                return transactionState;
            }
        };

        try {
            deleteStmt.analyze(analyzer);
        } catch (UserException e) {
            Assert.fail();
        }
        try {
            deleteHandler.process(deleteStmt);
        } catch (QueryStateException e) {
        }

        Map<Long, DeleteJob> idToDeleteJob = Deencapsulation.getField(deleteHandler, "idToDeleteJob");
        Collection<DeleteJob> jobs = idToDeleteJob.values();
        Assert.assertEquals(1, jobs.size());
        for (DeleteJob job : jobs) {
            Assert.assertEquals(job.getState(), DeleteState.QUORUM_FINISHED);
        }
    }

    @Test
    public void testNormalTimeout() throws DdlException, QueryStateException {
        BinaryPredicate binaryPredicate = new BinaryPredicate(BinaryPredicate.Operator.GT, new SlotRef(null, "k1"),
                new IntLiteral(3));

        DeleteStmt deleteStmt = new DeleteStmt(new TableName("test_db", "test_tbl"),
                new PartitionNames(false, Lists.newArrayList("test_tbl")), binaryPredicate);

        Set<Replica> finishedReplica = Sets.newHashSet();
        finishedReplica.add(new Replica(REPLICA_ID_1, BACKEND_ID_1, 0, Replica.ReplicaState.NORMAL));
        finishedReplica.add(new Replica(REPLICA_ID_2, BACKEND_ID_2, 0, Replica.ReplicaState.NORMAL));
        finishedReplica.add(new Replica(REPLICA_ID_3, BACKEND_ID_3, 0, Replica.ReplicaState.NORMAL));
        TabletDeleteInfo tabletDeleteInfo = new TabletDeleteInfo(PARTITION_ID, TABLET_ID);
        tabletDeleteInfo.getFinishedReplicas().addAll(finishedReplica);

        new MockUp<DeleteJob>() {
            @Mock
            public Collection<TabletDeleteInfo> getTabletDeleteInfo() {
                return Lists.newArrayList(tabletDeleteInfo);
            }
        };

        new MockUp<GlobalTransactionMgr>() {
            @Mock
            public TransactionState getTransactionState(long transactionId) {
                TransactionState transactionState = new TransactionState();
                transactionState.setTransactionStatus(TransactionStatus.VISIBLE);
                return transactionState;
            }
        };

        try {
            deleteStmt.analyze(analyzer);
        } catch (UserException e) {
            Assert.fail();
        }

        try {
            deleteHandler.process(deleteStmt);
        } catch (QueryStateException e) {
        }

        Map<Long, DeleteJob> idToDeleteJob = Deencapsulation.getField(deleteHandler, "idToDeleteJob");
        Collection<DeleteJob> jobs = idToDeleteJob.values();
        Assert.assertEquals(1, jobs.size());
        for (DeleteJob job : jobs) {
            Assert.assertEquals(job.getState(), DeleteState.FINISHED);
        }
    }

    @Test(expected = DdlException.class)
    public void testCommitFail(@Mocked MarkedCountDownLatch countDownLatch) throws DdlException, QueryStateException {
        BinaryPredicate binaryPredicate = new BinaryPredicate(BinaryPredicate.Operator.GT, new SlotRef(null, "k1"),
                new IntLiteral(3));

        DeleteStmt deleteStmt = new DeleteStmt(new TableName("test_db", "test_tbl"),
                new PartitionNames(false, Lists.newArrayList("test_tbl")), binaryPredicate);

        Set<Replica> finishedReplica = Sets.newHashSet();
        finishedReplica.add(new Replica(REPLICA_ID_1, BACKEND_ID_1, 0, Replica.ReplicaState.NORMAL));
        finishedReplica.add(new Replica(REPLICA_ID_2, BACKEND_ID_2, 0, Replica.ReplicaState.NORMAL));
        finishedReplica.add(new Replica(REPLICA_ID_3, BACKEND_ID_3, 0, Replica.ReplicaState.NORMAL));
        TabletDeleteInfo tabletDeleteInfo = new TabletDeleteInfo(PARTITION_ID, TABLET_ID);
        tabletDeleteInfo.getFinishedReplicas().addAll(finishedReplica);

        new MockUp<DeleteJob>() {
            @Mock
            public Collection<TabletDeleteInfo> getTabletDeleteInfo() {
                return Lists.newArrayList(tabletDeleteInfo);
            }
        };

        new Expectations() {
            {
                try {
                    countDownLatch.await(anyLong, (TimeUnit) any);
                } catch (InterruptedException e) {
                }
                result = false;
            }
        };

        new Expectations(globalTransactionMgr) {
            {
                try {
                    globalTransactionMgr.commitTransaction(anyLong, anyLong, (List<TabletCommitInfo>) any,
                            (TxnCommitAttachment) any);
                } catch (UserException e) {
                }
                result = new UserException("commit fail");
            }
        };

        try {
            deleteStmt.analyze(analyzer);
        } catch (UserException e) {
            Assert.fail();
        }
        try {
            deleteHandler.process(deleteStmt);
        } catch (DdlException e) {
            Map<Long, DeleteJob> idToDeleteJob = Deencapsulation.getField(deleteHandler, "idToDeleteJob");
            Collection<DeleteJob> jobs = idToDeleteJob.values();
            Assert.assertEquals(1, jobs.size());
            for (DeleteJob job : jobs) {
                Assert.assertEquals(job.getState(), DeleteState.FINISHED);
            }
            throw e;
        } catch (QueryStateException e) {
        }
        Assert.fail();
    }

    @Test
    public void testPublishFail(@Mocked MarkedCountDownLatch countDownLatch, @Mocked AgentTaskExecutor taskExecutor)
            throws DdlException, QueryStateException {
        BinaryPredicate binaryPredicate = new BinaryPredicate(BinaryPredicate.Operator.GT, new SlotRef(null, "k1"),
                new IntLiteral(3));

        DeleteStmt deleteStmt = new DeleteStmt(new TableName("test_db", "test_tbl"),
                new PartitionNames(false, Lists.newArrayList("test_tbl")), binaryPredicate);

        Set<Replica> finishedReplica = Sets.newHashSet();
        finishedReplica.add(new Replica(REPLICA_ID_1, BACKEND_ID_1, 0, Replica.ReplicaState.NORMAL));
        finishedReplica.add(new Replica(REPLICA_ID_2, BACKEND_ID_2, 0, Replica.ReplicaState.NORMAL));
        finishedReplica.add(new Replica(REPLICA_ID_3, BACKEND_ID_3, 0, Replica.ReplicaState.NORMAL));
        TabletDeleteInfo tabletDeleteInfo = new TabletDeleteInfo(PARTITION_ID, TABLET_ID);
        tabletDeleteInfo.getFinishedReplicas().addAll(finishedReplica);

        new MockUp<DeleteJob>() {
            @Mock
            public Collection<TabletDeleteInfo> getTabletDeleteInfo() {
                return Lists.newArrayList(tabletDeleteInfo);
            }
        };

        new Expectations() {
            {
                try {
                    countDownLatch.await(anyLong, (TimeUnit) any);
                } catch (InterruptedException e) {
                }
                result = false;
            }
        };

        new Expectations() {
            {
                AgentTaskExecutor.submit((AgentBatchTask) any);
                minTimes = 0;
            }
        };

        try {
            deleteStmt.analyze(analyzer);
        } catch (UserException e) {
            Assert.fail();
        }
        try {
            deleteHandler.process(deleteStmt);
        } catch (QueryStateException e) {
        }

        Map<Long, DeleteJob> idToDeleteJob = Deencapsulation.getField(deleteHandler, "idToDeleteJob");
        Collection<DeleteJob> jobs = idToDeleteJob.values();
        Assert.assertEquals(1, jobs.size());
        for (DeleteJob job : jobs) {
            Assert.assertEquals(job.getState(), DeleteState.FINISHED);
        }
    }

    @Test
    public void testNormal(@Mocked MarkedCountDownLatch countDownLatch) throws DdlException, QueryStateException {
        BinaryPredicate binaryPredicate = new BinaryPredicate(BinaryPredicate.Operator.GT, new SlotRef(null, "k1"),
                new IntLiteral(3));

        DeleteStmt deleteStmt = new DeleteStmt(new TableName("test_db", "test_tbl"),
                new PartitionNames(false, Lists.newArrayList("test_tbl")), binaryPredicate);

        Set<Replica> finishedReplica = Sets.newHashSet();
        finishedReplica.add(new Replica(REPLICA_ID_1, BACKEND_ID_1, 0, Replica.ReplicaState.NORMAL));
        finishedReplica.add(new Replica(REPLICA_ID_2, BACKEND_ID_2, 0, Replica.ReplicaState.NORMAL));
        finishedReplica.add(new Replica(REPLICA_ID_3, BACKEND_ID_3, 0, Replica.ReplicaState.NORMAL));
        TabletDeleteInfo tabletDeleteInfo = new TabletDeleteInfo(PARTITION_ID, TABLET_ID);
        tabletDeleteInfo.getFinishedReplicas().addAll(finishedReplica);

        new MockUp<DeleteJob>() {
            @Mock
            public Collection<TabletDeleteInfo> getTabletDeleteInfo() {
                return Lists.newArrayList(tabletDeleteInfo);
            }
        };

        new Expectations() {
            {
                try {
                    countDownLatch.await(anyLong, (TimeUnit) any);
                } catch (InterruptedException e) {
                }
                result = false;
            }
        };

        try {
            deleteStmt.analyze(analyzer);
        } catch (UserException e) {
            Assert.fail();
        }
        try {
            deleteHandler.process(deleteStmt);
        } catch (QueryStateException e) {
        }

        Map<Long, DeleteJob> idToDeleteJob = Deencapsulation.getField(deleteHandler, "idToDeleteJob");
        Collection<DeleteJob> jobs = idToDeleteJob.values();
        Assert.assertEquals(1, jobs.size());
        for (DeleteJob job : jobs) {
            Assert.assertEquals(job.getState(), DeleteState.FINISHED);
        }
    }
}
