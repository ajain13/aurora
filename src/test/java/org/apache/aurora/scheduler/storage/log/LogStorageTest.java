/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.aurora.scheduler.storage.log;

import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterators;
import com.google.common.collect.Sets;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import com.google.common.testing.TearDown;
import com.twitter.common.quantity.Amount;
import com.twitter.common.quantity.Data;
import com.twitter.common.quantity.Time;
import com.twitter.common.testing.easymock.EasyMockTest;

import org.apache.aurora.codec.ThriftBinaryCodec;
import org.apache.aurora.codec.ThriftBinaryCodec.CodingException;
import org.apache.aurora.gen.AssignedTask;
import org.apache.aurora.gen.Attribute;
import org.apache.aurora.gen.HostAttributes;
import org.apache.aurora.gen.InstanceTaskConfig;
import org.apache.aurora.gen.JobConfiguration;
import org.apache.aurora.gen.JobInstanceUpdateEvent;
import org.apache.aurora.gen.JobUpdate;
import org.apache.aurora.gen.JobUpdateAction;
import org.apache.aurora.gen.JobUpdateEvent;
import org.apache.aurora.gen.JobUpdateInstructions;
import org.apache.aurora.gen.JobUpdateKey;
import org.apache.aurora.gen.JobUpdateSettings;
import org.apache.aurora.gen.JobUpdateStatus;
import org.apache.aurora.gen.JobUpdateSummary;
import org.apache.aurora.gen.Lock;
import org.apache.aurora.gen.LockKey;
import org.apache.aurora.gen.MaintenanceMode;
import org.apache.aurora.gen.Range;
import org.apache.aurora.gen.ResourceAggregate;
import org.apache.aurora.gen.ScheduleStatus;
import org.apache.aurora.gen.ScheduledTask;
import org.apache.aurora.gen.TaskConfig;
import org.apache.aurora.gen.storage.LogEntry;
import org.apache.aurora.gen.storage.Op;
import org.apache.aurora.gen.storage.PruneJobUpdateHistory;
import org.apache.aurora.gen.storage.RemoveJob;
import org.apache.aurora.gen.storage.RemoveLock;
import org.apache.aurora.gen.storage.RemoveQuota;
import org.apache.aurora.gen.storage.RemoveTasks;
import org.apache.aurora.gen.storage.RewriteTask;
import org.apache.aurora.gen.storage.SaveCronJob;
import org.apache.aurora.gen.storage.SaveFrameworkId;
import org.apache.aurora.gen.storage.SaveHostAttributes;
import org.apache.aurora.gen.storage.SaveJobInstanceUpdateEvent;
import org.apache.aurora.gen.storage.SaveJobUpdate;
import org.apache.aurora.gen.storage.SaveJobUpdateEvent;
import org.apache.aurora.gen.storage.SaveLock;
import org.apache.aurora.gen.storage.SaveQuota;
import org.apache.aurora.gen.storage.SaveTasks;
import org.apache.aurora.gen.storage.Snapshot;
import org.apache.aurora.gen.storage.Transaction;
import org.apache.aurora.gen.storage.storageConstants;
import org.apache.aurora.scheduler.base.JobKeys;
import org.apache.aurora.scheduler.base.Query;
import org.apache.aurora.scheduler.base.Tasks;
import org.apache.aurora.scheduler.events.EventSink;
import org.apache.aurora.scheduler.events.PubsubEvent;
import org.apache.aurora.scheduler.log.Log;
import org.apache.aurora.scheduler.log.Log.Entry;
import org.apache.aurora.scheduler.log.Log.Position;
import org.apache.aurora.scheduler.log.Log.Stream;
import org.apache.aurora.scheduler.storage.AttributeStore;
import org.apache.aurora.scheduler.storage.SnapshotStore;
import org.apache.aurora.scheduler.storage.Storage;
import org.apache.aurora.scheduler.storage.Storage.MutableStoreProvider;
import org.apache.aurora.scheduler.storage.Storage.MutateWork;
import org.apache.aurora.scheduler.storage.Storage.MutateWork.NoResult;
import org.apache.aurora.scheduler.storage.entities.IHostAttributes;
import org.apache.aurora.scheduler.storage.entities.IJobConfiguration;
import org.apache.aurora.scheduler.storage.entities.IJobInstanceUpdateEvent;
import org.apache.aurora.scheduler.storage.entities.IJobKey;
import org.apache.aurora.scheduler.storage.entities.IJobUpdate;
import org.apache.aurora.scheduler.storage.entities.IJobUpdateEvent;
import org.apache.aurora.scheduler.storage.entities.IJobUpdateKey;
import org.apache.aurora.scheduler.storage.entities.ILock;
import org.apache.aurora.scheduler.storage.entities.ILockKey;
import org.apache.aurora.scheduler.storage.entities.IResourceAggregate;
import org.apache.aurora.scheduler.storage.entities.IScheduledTask;
import org.apache.aurora.scheduler.storage.entities.ITaskConfig;
import org.apache.aurora.scheduler.storage.log.LogStorage.SchedulingService;
import org.apache.aurora.scheduler.storage.log.testing.LogOpMatcher;
import org.apache.aurora.scheduler.storage.log.testing.LogOpMatcher.StreamMatcher;
import org.apache.aurora.scheduler.storage.testing.StorageTestUtil;
import org.easymock.Capture;
import org.easymock.IAnswer;
import org.junit.Before;
import org.junit.Test;

import static org.easymock.EasyMock.capture;
import static org.easymock.EasyMock.eq;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.expectLastCall;
import static org.easymock.EasyMock.notNull;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LogStorageTest extends EasyMockTest {

  private static final Amount<Long, Time> SNAPSHOT_INTERVAL = Amount.of(1L, Time.MINUTES);
  private static final IJobKey JOB_KEY = JobKeys.from("role", "env", "name");
  private static final IJobUpdateKey UPDATE_ID =
      IJobUpdateKey.build(new JobUpdateKey(JOB_KEY.newBuilder(), "testUpdateId"));
  private static final long NOW = 42L;

  private LogStorage logStorage;
  private Log log;
  private Stream stream;
  private Position position;
  private StreamMatcher streamMatcher;
  private SchedulingService schedulingService;
  private SnapshotStore<Snapshot> snapshotStore;
  private StorageTestUtil storageUtil;
  private SnapshotDeduplicator snapshotDeduplicator;
  private EventSink eventSink;

  @Before
  public void setUp() {
    log = createMock(Log.class);
    snapshotDeduplicator = createMock(SnapshotDeduplicator.class);

    StreamManagerFactory streamManagerFactory = new StreamManagerFactory() {
      @Override
      public StreamManager create(Stream logStream) {
        HashFunction md5 = Hashing.md5();
        return new StreamManagerImpl(
            logStream,
            new EntrySerializer.EntrySerializerImpl(
                Amount.of(1, Data.GB),
                md5),
            false,
            md5,
            snapshotDeduplicator,
            false);
      }
    };
    LogManager logManager = new LogManager(log, streamManagerFactory);

    schedulingService = createMock(SchedulingService.class);
    snapshotStore = createMock(new Clazz<SnapshotStore<Snapshot>>() { });
    storageUtil = new StorageTestUtil(this);
    eventSink = createMock(EventSink.class);

    logStorage = new LogStorage(
        logManager,
        schedulingService,
        snapshotStore,
        SNAPSHOT_INTERVAL,
        storageUtil.storage,
        storageUtil.schedulerStore,
        storageUtil.jobStore,
        storageUtil.taskStore,
        storageUtil.lockStore,
        storageUtil.quotaStore,
        storageUtil.attributeStore,
        storageUtil.jobUpdateStore,
        eventSink,
        new ReentrantLock());

    stream = createMock(Stream.class);
    streamMatcher = LogOpMatcher.matcherFor(stream);
    position = createMock(Position.class);

    storageUtil.storage.prepare();
  }

  @Test
  public void testStart() throws Exception {
    // We should open the log and arrange for its clean shutdown.
    expect(log.open()).andReturn(stream);

    // Our start should recover the log and then forward to the underlying storage start of the
    // supplied initialization logic.
    final AtomicBoolean initialized = new AtomicBoolean(false);
    MutateWork.NoResult.Quiet initializationLogic = new MutateWork.NoResult.Quiet() {
      @Override
      protected void execute(MutableStoreProvider provider) {
        // Creating a mock and expecting apply(storeProvider) does not work here for whatever
        // reason.
        initialized.set(true);
      }
    };

    final Capture<MutateWork.NoResult.Quiet> recoverAndInitializeWork = createCapture();
    storageUtil.storage.write(capture(recoverAndInitializeWork));
    expectLastCall().andAnswer(new IAnswer<Void>() {
      @Override
      public Void answer() throws Throwable {
        recoverAndInitializeWork.getValue().apply(storageUtil.mutableStoreProvider);
        return null;
      }
    });

    final Capture<MutateWork<Void, RuntimeException>> recoveryWork = createCapture();
    expect(storageUtil.storage.write(capture(recoveryWork))).andAnswer(
        new IAnswer<Void>() {
          @Override
          public Void answer() {
            recoveryWork.getValue().apply(storageUtil.mutableStoreProvider);
            return null;
          }
        });

    final Capture<MutateWork<Void, RuntimeException>> initializationWork = createCapture();
    expect(storageUtil.storage.write(capture(initializationWork))).andAnswer(
        new IAnswer<Void>() {
          @Override
          public Void answer() {
            initializationWork.getValue().apply(storageUtil.mutableStoreProvider);
            return null;
          }
        });

    // We should perform a snapshot when the snapshot thread runs.
    Capture<Runnable> snapshotAction = createCapture();
    schedulingService.doEvery(eq(SNAPSHOT_INTERVAL), capture(snapshotAction));
    Snapshot snapshotContents = new Snapshot()
        .setTimestamp(NOW)
        .setTasks(ImmutableSet.of(
            new ScheduledTask()
                .setStatus(ScheduleStatus.RUNNING)
                .setAssignedTask(new AssignedTask().setTaskId("task_id"))));
    expect(snapshotStore.createSnapshot()).andReturn(snapshotContents);
    streamMatcher.expectSnapshot(snapshotContents).andReturn(position);
    stream.truncateBefore(position);
    final Capture<MutateWork<Void, RuntimeException>> snapshotWork = createCapture();
    expect(storageUtil.storage.write(capture(snapshotWork))).andAnswer(
        new IAnswer<Void>() {
          @Override
          public Void answer() {
            snapshotWork.getValue().apply(storageUtil.mutableStoreProvider);
            return null;
          }
        }).anyTimes();

    // Populate all LogEntry types.
    buildReplayLogEntries();

    control.replay();

    logStorage.prepare();
    logStorage.start(initializationLogic);
    assertTrue(initialized.get());

    // Run the snapshot thread.
    snapshotAction.getValue().run();

    // Assert all LogEntry types have handlers defined.
    // Our current StreamManagerImpl.readFromBeginning() does not let some entries escape
    // the decoding routine making handling them in replay unnecessary.
    assertEquals(
        Sets.complementOf(EnumSet.of(
            LogEntry._Fields.FRAME,
            LogEntry._Fields.DEDUPLICATED_SNAPSHOT,
            LogEntry._Fields.DEFLATED_ENTRY)),
        EnumSet.copyOf(logStorage.buildLogEntryReplayActions().keySet()));

    // Assert all Transaction types have handlers defined.
    assertEquals(
        EnumSet.allOf(Op._Fields.class),
        EnumSet.copyOf(logStorage.buildTransactionReplayActions().keySet()));
  }

  private void buildReplayLogEntries() throws Exception {
    ImmutableSet.Builder<LogEntry> builder = ImmutableSet.builder();

    builder.add(createTransaction(Op.saveFrameworkId(new SaveFrameworkId("bob"))));
    storageUtil.schedulerStore.saveFrameworkId("bob");

    SaveCronJob cronJob = new SaveCronJob().setJobConfig(new JobConfiguration());
    builder.add(createTransaction(Op.saveCronJob(cronJob)));
    storageUtil.jobStore.saveAcceptedJob(IJobConfiguration.build(cronJob.getJobConfig()));

    RemoveJob removeJob = new RemoveJob(JOB_KEY.newBuilder());
    builder.add(createTransaction(Op.removeJob(removeJob)));
    storageUtil.jobStore.removeJob(JOB_KEY);

    SaveTasks saveTasks = new SaveTasks(ImmutableSet.of(new ScheduledTask()));
    builder.add(createTransaction(Op.saveTasks(saveTasks)));
    storageUtil.taskStore.saveTasks(IScheduledTask.setFromBuilders(saveTasks.getTasks()));

    RewriteTask rewriteTask = new RewriteTask("id1", new TaskConfig());
    builder.add(createTransaction(Op.rewriteTask(rewriteTask)));
    expect(storageUtil.taskStore.unsafeModifyInPlace(
        rewriteTask.getTaskId(),
        ITaskConfig.build(rewriteTask.getTask()))).andReturn(true);

    RemoveTasks removeTasks = new RemoveTasks(ImmutableSet.of("taskId1"));
    builder.add(createTransaction(Op.removeTasks(removeTasks)));
    storageUtil.taskStore.deleteTasks(removeTasks.getTaskIds());

    SaveQuota saveQuota = new SaveQuota(JOB_KEY.getRole(), new ResourceAggregate());
    builder.add(createTransaction(Op.saveQuota(saveQuota)));
    storageUtil.quotaStore.saveQuota(
        saveQuota.getRole(),
        IResourceAggregate.build(saveQuota.getQuota()));

    builder.add(createTransaction(Op.removeQuota(new RemoveQuota(JOB_KEY.getRole()))));
    storageUtil.quotaStore.removeQuota(JOB_KEY.getRole());

    // This entry lacks a slave ID, and should therefore be discarded.
    SaveHostAttributes hostAttributes1 = new SaveHostAttributes(new HostAttributes()
        .setHost("host1")
        .setMode(MaintenanceMode.DRAINED));
    builder.add(createTransaction(Op.saveHostAttributes(hostAttributes1)));

    SaveHostAttributes hostAttributes2 = new SaveHostAttributes(new HostAttributes()
        .setHost("host2")
        .setSlaveId("slave2")
        .setMode(MaintenanceMode.DRAINED));
    builder.add(createTransaction(Op.saveHostAttributes(hostAttributes2)));
    expect(storageUtil.attributeStore.saveHostAttributes(
        IHostAttributes.build(hostAttributes2.getHostAttributes()))).andReturn(true);

    SaveLock saveLock = new SaveLock(new Lock().setKey(LockKey.job(JOB_KEY.newBuilder())));
    builder.add(createTransaction(Op.saveLock(saveLock)));
    storageUtil.lockStore.saveLock(ILock.build(saveLock.getLock()));

    RemoveLock removeLock = new RemoveLock(LockKey.job(JOB_KEY.newBuilder()));
    builder.add(createTransaction(Op.removeLock(removeLock)));
    storageUtil.lockStore.removeLock(ILockKey.build(removeLock.getLockKey()));

    JobUpdate update = new JobUpdate()
        .setSummary(new JobUpdateSummary()
            .setKey(UPDATE_ID.newBuilder())
            .setJobKey(UPDATE_ID.getJob().newBuilder())
            .setUpdateId(UPDATE_ID.getId()));
    SaveJobUpdate saveUpdate = new SaveJobUpdate(update, "token");
    builder.add(createTransaction(Op.saveJobUpdate(saveUpdate)));
    storageUtil.jobUpdateStore.saveJobUpdate(
        IJobUpdate.build(saveUpdate.getJobUpdate()),
        Optional.of(saveUpdate.getLockToken()));

    // Ensure that JobUpdateSummary.key is backfilled.
    IJobUpdateKey updateId2 =
        IJobUpdateKey.build(new JobUpdateKey(JOB_KEY.newBuilder(), "backfilled"));
    JobUpdate legacyUpdate = new JobUpdate()
        .setSummary(new JobUpdateSummary()
            .setJobKey(updateId2.getJob().newBuilder())
            .setUpdateId(updateId2.getId()));
    SaveJobUpdate saveUpdateBackfilled = new SaveJobUpdate(legacyUpdate, "token2");
    builder.add(createTransaction(Op.saveJobUpdate(saveUpdateBackfilled)));
    JobUpdate legacyUpdateBackfilled = legacyUpdate.deepCopy();
    legacyUpdateBackfilled.getSummary().setKey(updateId2.newBuilder());
    storageUtil.jobUpdateStore.saveJobUpdate(
        IJobUpdate.build(legacyUpdateBackfilled),
        Optional.of(saveUpdateBackfilled.getLockToken()));

    SaveJobUpdateEvent saveUpdateEvent =
        new SaveJobUpdateEvent(new JobUpdateEvent(), "update", UPDATE_ID.newBuilder());
    builder.add(createTransaction(Op.saveJobUpdateEvent(saveUpdateEvent)));
    storageUtil.jobUpdateStore.saveJobUpdateEvent(
        UPDATE_ID,
        IJobUpdateEvent.build(saveUpdateEvent.getEvent()));

    SaveJobInstanceUpdateEvent saveInstanceEvent = new SaveJobInstanceUpdateEvent(
        new JobInstanceUpdateEvent(),
        "update",
        UPDATE_ID.newBuilder());
    builder.add(createTransaction(Op.saveJobInstanceUpdateEvent(saveInstanceEvent)));
    storageUtil.jobUpdateStore.saveJobInstanceUpdateEvent(
        UPDATE_ID,
        IJobInstanceUpdateEvent.build(saveInstanceEvent.getEvent()));

    // Backwards compatibility as part of AURORA-1093.  Exercises behavior to drop job
    // update-related records that cannot be found based on their update ID string alone.
    SaveJobInstanceUpdateEvent unknownSaveInstanceEvent =
        new SaveJobInstanceUpdateEvent(new JobInstanceUpdateEvent(), "update5", null);
    builder.add(createTransaction(Op.saveJobInstanceUpdateEvent(unknownSaveInstanceEvent)));
    expect(storageUtil.jobUpdateStore.fetchUpdateKey("update5"))
        .andReturn(Optional.<IJobUpdateKey>absent());

    SaveJobUpdateEvent unknownSaveUpdateEvent =
        new SaveJobUpdateEvent(new JobUpdateEvent(), "update6", null);
    builder.add(createTransaction(Op.saveJobUpdateEvent(unknownSaveUpdateEvent)));
    expect(storageUtil.jobUpdateStore.fetchUpdateKey("update6"))
        .andReturn(Optional.<IJobUpdateKey>absent());

    builder.add(createTransaction(Op.pruneJobUpdateHistory(new PruneJobUpdateHistory(5, 10L))));
    expect(storageUtil.jobUpdateStore.pruneHistory(5, 10L))
        .andReturn(ImmutableSet.of(IJobUpdateKey.build(UPDATE_ID.newBuilder())));

    // NOOP LogEntry
    builder.add(LogEntry.noop(true));

    // Snapshot LogEntry
    Snapshot snapshot = new Snapshot();
    builder.add(LogEntry.snapshot(snapshot));
    snapshotStore.applySnapshot(snapshot);

    ImmutableSet.Builder<Entry> entryBuilder = ImmutableSet.builder();
    for (LogEntry logEntry : builder.build()) {
      Entry entry = createMock(Entry.class);
      entryBuilder.add(entry);
      expect(entry.contents()).andReturn(ThriftBinaryCodec.encodeNonNull(logEntry));
    }

    expect(stream.readAll()).andReturn(entryBuilder.build().iterator());
  }

  abstract class StorageTestFixture {
    private final AtomicBoolean runCalled = new AtomicBoolean(false);

    StorageTestFixture() {
      // Prevent otherwise silent noop tests that forget to call run().
      addTearDown(new TearDown() {
        @Override
        public void tearDown() {
          assertTrue(runCalled.get());
        }
      });
    }

    void run() throws Exception {
      runCalled.set(true);

      // Expect basic start operations.

      // Open the log stream.
      expect(log.open()).andReturn(stream);

      // Replay the log and perform and supplied initializationWork.
      // Simulate NOOP initialization work
      // Creating a mock and expecting apply(storeProvider) does not work here for whatever
      // reason.
      MutateWork.NoResult.Quiet initializationLogic = new NoResult.Quiet() {
        @Override
        protected void execute(Storage.MutableStoreProvider storeProvider) {
          // No-op.
        }
      };

      final Capture<MutateWork.NoResult.Quiet> recoverAndInitializeWork = createCapture();
      storageUtil.storage.write(capture(recoverAndInitializeWork));
      expectLastCall().andAnswer(new IAnswer<Void>() {
        @Override
        public Void answer() throws Throwable {
          recoverAndInitializeWork.getValue().apply(storageUtil.mutableStoreProvider);
          return null;
        }
      });

      expect(stream.readAll()).andReturn(Iterators.<Entry>emptyIterator());
      final Capture<MutateWork<Void, RuntimeException>> recoveryWork = createCapture();
      expect(storageUtil.storage.write(capture(recoveryWork))).andAnswer(
          new IAnswer<Void>() {
            @Override
            public Void answer() {
              recoveryWork.getValue().apply(storageUtil.mutableStoreProvider);
              return null;
            }
          });

      // Schedule snapshots.
      schedulingService.doEvery(eq(SNAPSHOT_INTERVAL), notNull(Runnable.class));

      // Setup custom test expectations.
      setupExpectations();

      control.replay();

      // Start the system.
      logStorage.prepare();
      logStorage.start(initializationLogic);

      // Exercise the system.
      runTest();
    }

    protected void setupExpectations() throws Exception {
      // Default to no expectations.
    }

    protected abstract void runTest();
  }

  abstract class MutationFixture extends StorageTestFixture {
    @Override
    protected void runTest() {
      logStorage.write(new MutateWork.NoResult.Quiet() {
        @Override
        protected void execute(MutableStoreProvider storeProvider) {
          performMutations(storeProvider);
        }
      });
    }

    protected abstract void performMutations(MutableStoreProvider storeProvider);
  }

  @Test
  public void testSaveFrameworkId() throws Exception {
    final String frameworkId = "bob";
    new MutationFixture() {
      @Override
      protected void setupExpectations() throws CodingException {
        storageUtil.expectWrite();
        storageUtil.schedulerStore.saveFrameworkId(frameworkId);
        streamMatcher.expectTransaction(Op.saveFrameworkId(new SaveFrameworkId(frameworkId)))
            .andReturn(position);
      }

      @Override
      protected void performMutations(MutableStoreProvider storeProvider) {
        storeProvider.getSchedulerStore().saveFrameworkId(frameworkId);
      }
    }.run();
  }

  @Test
  public void testSaveAcceptedJob() throws Exception {
    final IJobConfiguration jobConfig =
        IJobConfiguration.build(new JobConfiguration().setKey(JOB_KEY.newBuilder()));
    new MutationFixture() {
      @Override
      protected void setupExpectations() throws Exception {
        storageUtil.expectWrite();
        storageUtil.jobStore.saveAcceptedJob(jobConfig);
        streamMatcher.expectTransaction(
            Op.saveCronJob(new SaveCronJob(jobConfig.newBuilder())))
            .andReturn(position);
      }

      @Override
      protected void performMutations(MutableStoreProvider storeProvider) {
        storeProvider.getCronJobStore().saveAcceptedJob(jobConfig);
      }
    }.run();
  }

  @Test
  public void testRemoveJob() throws Exception {
    new MutationFixture() {
      @Override
      protected void setupExpectations() throws Exception {
        storageUtil.expectWrite();
        storageUtil.jobStore.removeJob(JOB_KEY);
        streamMatcher.expectTransaction(
            Op.removeJob(new RemoveJob().setJobKey(JOB_KEY.newBuilder())))
            .andReturn(position);
      }

      @Override
      protected void performMutations(MutableStoreProvider storeProvider) {
        storeProvider.getCronJobStore().removeJob(JOB_KEY);
      }
    }.run();
  }

  @Test
  public void testSaveTasks() throws Exception {
    final Set<IScheduledTask> tasks = ImmutableSet.of(task("a", ScheduleStatus.INIT));
    new MutationFixture() {
      @Override
      protected void setupExpectations() throws Exception {
        storageUtil.expectWrite();
        storageUtil.taskStore.saveTasks(tasks);
        streamMatcher.expectTransaction(
            Op.saveTasks(new SaveTasks(IScheduledTask.toBuildersSet(tasks))))
            .andReturn(position);
      }

      @Override
      protected void performMutations(MutableStoreProvider storeProvider) {
        storeProvider.getUnsafeTaskStore().saveTasks(tasks);
      }
    }.run();
  }

  @Test
  public void testMutateTasks() throws Exception {
    final Query.Builder query = Query.taskScoped("fred");
    final Function<IScheduledTask, IScheduledTask> mutation = Functions.identity();
    final ImmutableSet<IScheduledTask> mutated =
        ImmutableSet.of(task("a", ScheduleStatus.STARTING));
    new MutationFixture() {
      @Override
      protected void setupExpectations() throws Exception {
        storageUtil.expectWrite();
        expect(storageUtil.taskStore.mutateTasks(query, mutation)).andReturn(mutated);
        streamMatcher.expectTransaction(
            Op.saveTasks(new SaveTasks(IScheduledTask.toBuildersSet(mutated))))
            .andReturn(null);
      }

      @Override
      protected void performMutations(MutableStoreProvider storeProvider) {
        assertEquals(mutated, storeProvider.getUnsafeTaskStore().mutateTasks(query, mutation));
      }
    }.run();
  }

  @Test
  public void testUnsafeModifyInPlace() throws Exception {
    final String taskId = "wilma";
    final String taskId2 = "barney";
    final ITaskConfig updatedConfig =
        task(taskId, ScheduleStatus.RUNNING).getAssignedTask().getTask();
    new MutationFixture() {
      @Override
      protected void setupExpectations() throws Exception {
        storageUtil.expectWrite();
        expect(storageUtil.taskStore.unsafeModifyInPlace(taskId2, updatedConfig)).andReturn(false);
        expect(storageUtil.taskStore.unsafeModifyInPlace(taskId, updatedConfig)).andReturn(true);
        streamMatcher.expectTransaction(
            Op.rewriteTask(new RewriteTask(taskId, updatedConfig.newBuilder())))
            .andReturn(position);
      }

      @Override
      protected void performMutations(MutableStoreProvider storeProvider) {
        storeProvider.getUnsafeTaskStore().unsafeModifyInPlace(taskId2, updatedConfig);
        storeProvider.getUnsafeTaskStore().unsafeModifyInPlace(taskId, updatedConfig);
      }
    }.run();
  }

  @Test
  public void testNestedTransactions() throws Exception {
    final Query.Builder query = Query.taskScoped("fred");
    final Function<IScheduledTask, IScheduledTask> mutation = Functions.identity();
    final ImmutableSet<IScheduledTask> mutated =
        ImmutableSet.of(task("a", ScheduleStatus.STARTING));
    final ImmutableSet<String> tasksToRemove = ImmutableSet.of("b");

    new MutationFixture() {
      @Override
      protected void setupExpectations() throws Exception {
        storageUtil.expectWrite();
        expect(storageUtil.taskStore.mutateTasks(query, mutation)).andReturn(mutated);

        storageUtil.taskStore.deleteTasks(tasksToRemove);

        streamMatcher.expectTransaction(
            Op.saveTasks(new SaveTasks(IScheduledTask.toBuildersSet(mutated))),
            Op.removeTasks(new RemoveTasks(tasksToRemove)))
            .andReturn(position);
      }

      @Override
      protected void performMutations(MutableStoreProvider storeProvider) {
        assertEquals(mutated, storeProvider.getUnsafeTaskStore().mutateTasks(query, mutation));

        logStorage.write(new MutateWork.NoResult.Quiet() {
          @Override
          protected void execute(MutableStoreProvider innerProvider) {
            innerProvider.getUnsafeTaskStore().deleteTasks(tasksToRemove);
          }
        });
      }
    }.run();
  }

  @Test
  public void testSaveAndMutateTasks() throws Exception {
    final Query.Builder query = Query.taskScoped("fred");
    final Function<IScheduledTask, IScheduledTask> mutation = Functions.identity();
    final Set<IScheduledTask> saved = ImmutableSet.of(task("a", ScheduleStatus.INIT));
    final ImmutableSet<IScheduledTask> mutated = ImmutableSet.of(task("a", ScheduleStatus.PENDING));

    new MutationFixture() {
      @Override
      protected void setupExpectations() throws Exception {
        storageUtil.expectWrite();
        storageUtil.taskStore.saveTasks(saved);

        // Nested transaction with result.
        expect(storageUtil.taskStore.mutateTasks(query, mutation)).andReturn(mutated);

        // Resulting stream operation.
        streamMatcher.expectTransaction(Op.saveTasks(
            new SaveTasks(IScheduledTask.toBuildersSet(mutated))))
            .andReturn(null);
      }

      @Override
      protected void performMutations(MutableStoreProvider storeProvider) {
        storeProvider.getUnsafeTaskStore().saveTasks(saved);
        assertEquals(mutated, storeProvider.getUnsafeTaskStore().mutateTasks(query, mutation));
      }
    }.run();
  }

  @Test
  public void testSaveAndMutateTasksNoCoalesceUniqueIds() throws Exception {
    final Query.Builder query = Query.taskScoped("fred");
    final Function<IScheduledTask, IScheduledTask> mutation = Functions.identity();
    final Set<IScheduledTask> saved = ImmutableSet.of(task("b", ScheduleStatus.INIT));
    final ImmutableSet<IScheduledTask> mutated = ImmutableSet.of(task("a", ScheduleStatus.PENDING));

    new MutationFixture() {
      @Override
      protected void setupExpectations() throws Exception {
        storageUtil.expectWrite();
        storageUtil.taskStore.saveTasks(saved);

        // Nested transaction with result.
        expect(storageUtil.taskStore.mutateTasks(query, mutation)).andReturn(mutated);

        // Resulting stream operation.
        streamMatcher.expectTransaction(
            Op.saveTasks(new SaveTasks(
                ImmutableSet.<ScheduledTask>builder()
                    .addAll(IScheduledTask.toBuildersList(saved))
                    .addAll(IScheduledTask.toBuildersList(mutated))
                    .build())))
            .andReturn(position);
      }

      @Override
      protected void performMutations(MutableStoreProvider storeProvider) {
        storeProvider.getUnsafeTaskStore().saveTasks(saved);
        assertEquals(mutated, storeProvider.getUnsafeTaskStore().mutateTasks(query, mutation));
      }
    }.run();
  }

  @Test
  public void testRemoveTasksQuery() throws Exception {
    final IScheduledTask task = task("a", ScheduleStatus.FINISHED);
    final Set<String> taskIds = Tasks.ids(task);
    new MutationFixture() {
      @Override
      protected void setupExpectations() throws Exception {
        storageUtil.expectWrite();
        storageUtil.taskStore.deleteTasks(taskIds);
        streamMatcher.expectTransaction(Op.removeTasks(new RemoveTasks(taskIds)))
            .andReturn(position);
      }

      @Override
      protected void performMutations(MutableStoreProvider storeProvider) {
        storeProvider.getUnsafeTaskStore().deleteTasks(taskIds);
      }
    }.run();
  }

  @Test
  public void testRemoveTasksIds() throws Exception {
    final Set<String> taskIds = ImmutableSet.of("42");
    new MutationFixture() {
      @Override
      protected void setupExpectations() throws Exception {
        storageUtil.expectWrite();
        storageUtil.taskStore.deleteTasks(taskIds);
        streamMatcher.expectTransaction(Op.removeTasks(new RemoveTasks(taskIds)))
            .andReturn(position);
      }

      @Override
      protected void performMutations(MutableStoreProvider storeProvider) {
        storeProvider.getUnsafeTaskStore().deleteTasks(taskIds);
      }
    }.run();
  }

  @Test
  public void testSaveQuota() throws Exception {
    final String role = "role";
    final IResourceAggregate quota =
        IResourceAggregate.build(new ResourceAggregate(1.0, 128L, 1024L));

    new MutationFixture() {
      @Override
      protected void setupExpectations() throws Exception {
        storageUtil.expectWrite();
        storageUtil.quotaStore.saveQuota(role, quota);
        streamMatcher.expectTransaction(Op.saveQuota(new SaveQuota(role, quota.newBuilder())))
            .andReturn(position);
      }

      @Override
      protected void performMutations(MutableStoreProvider storeProvider) {
        storeProvider.getQuotaStore().saveQuota(role, quota);
      }
    }.run();
  }

  @Test
  public void testRemoveQuota() throws Exception {
    final String role = "role";
    new MutationFixture() {
      @Override
      protected void setupExpectations() throws Exception {
        storageUtil.expectWrite();
        storageUtil.quotaStore.removeQuota(role);
        streamMatcher.expectTransaction(Op.removeQuota(new RemoveQuota(role))).andReturn(position);
      }

      @Override
      protected void performMutations(MutableStoreProvider storeProvider) {
        storeProvider.getQuotaStore().removeQuota(role);
      }
    }.run();
  }

  @Test
  public void testSaveLock() throws Exception {
    final ILock lock = ILock.build(new Lock()
        .setKey(LockKey.job(JOB_KEY.newBuilder()))
        .setToken("testLockId")
        .setUser("testUser")
        .setTimestampMs(12345L));
    new MutationFixture() {
      @Override
      protected void setupExpectations() throws Exception {
        storageUtil.expectWrite();
        storageUtil.lockStore.saveLock(lock);
        streamMatcher.expectTransaction(Op.saveLock(new SaveLock(lock.newBuilder())))
            .andReturn(position);
      }

      @Override
      protected void performMutations(MutableStoreProvider storeProvider) {
        storeProvider.getLockStore().saveLock(lock);
      }
    }.run();
  }

  @Test
  public void testRemoveLock() throws Exception {
    final ILockKey lockKey =
        ILockKey.build(LockKey.job(JOB_KEY.newBuilder()));
    new MutationFixture() {
      @Override
      protected void setupExpectations() throws Exception {
        storageUtil.expectWrite();
        storageUtil.lockStore.removeLock(lockKey);
        streamMatcher.expectTransaction(Op.removeLock(new RemoveLock(lockKey.newBuilder())))
            .andReturn(position);
      }

      @Override
      protected void performMutations(MutableStoreProvider storeProvider) {
        storeProvider.getLockStore().removeLock(lockKey);
      }
    }.run();
  }

  @Test
  public void testSaveHostAttributes() throws Exception {
    final String host = "hostname";
    final Set<Attribute> attributes =
        ImmutableSet.of(new Attribute().setName("attr").setValues(ImmutableSet.of("value")));
    final Optional<IHostAttributes> hostAttributes = Optional.of(
        IHostAttributes.build(new HostAttributes()
            .setHost(host)
            .setAttributes(attributes)));

    new MutationFixture() {
      @Override
      protected void setupExpectations() throws Exception {
        storageUtil.expectWrite();
        expect(storageUtil.attributeStore.getHostAttributes(host))
            .andReturn(Optional.<IHostAttributes>absent());

        expect(storageUtil.attributeStore.getHostAttributes(host)).andReturn(hostAttributes);

        expect(storageUtil.attributeStore.saveHostAttributes(hostAttributes.get())).andReturn(true);
        eventSink.post(new PubsubEvent.HostAttributesChanged(hostAttributes.get()));
        streamMatcher.expectTransaction(
            Op.saveHostAttributes(new SaveHostAttributes(hostAttributes.get().newBuilder())))
            .andReturn(position);

        expect(storageUtil.attributeStore.saveHostAttributes(hostAttributes.get()))
            .andReturn(false);

        expect(storageUtil.attributeStore.getHostAttributes(host)).andReturn(hostAttributes);
      }

      @Override
      protected void performMutations(MutableStoreProvider storeProvider) {
        AttributeStore.Mutable store = storeProvider.getAttributeStore();
        assertEquals(Optional.<IHostAttributes>absent(), store.getHostAttributes(host));

        assertTrue(store.saveHostAttributes(hostAttributes.get()));

        assertEquals(hostAttributes, store.getHostAttributes(host));

        assertFalse(store.saveHostAttributes(hostAttributes.get()));

        assertEquals(hostAttributes, store.getHostAttributes(host));
      }
    }.run();
  }

  @Test
  public void testSaveUpdateWithLockToken() throws Exception {
    saveAndAssertJobUpdate("id1", Optional.of("token"));
  }

  @Test
  public void testSaveUpdateWithNullLockToken() throws Exception {
    saveAndAssertJobUpdate("id2", Optional.<String>absent());
  }

  private void saveAndAssertJobUpdate(final String updateId, final Optional<String> lockToken)
      throws Exception {

    final IJobUpdate update = IJobUpdate.build(new JobUpdate()
        .setSummary(new JobUpdateSummary()
            .setUpdateId(updateId)
            .setJobKey(JOB_KEY.newBuilder())
            .setUser("user"))
        .setInstructions(new JobUpdateInstructions()
            .setDesiredState(new InstanceTaskConfig()
                .setTask(new TaskConfig())
                .setInstances(ImmutableSet.of(new Range(0, 3))))
            .setInitialState(ImmutableSet.of(new InstanceTaskConfig()
                .setTask(new TaskConfig())
                .setInstances(ImmutableSet.of(new Range(0, 3)))))
            .setSettings(new JobUpdateSettings())));

    new MutationFixture() {
      @Override
      protected void setupExpectations() throws Exception {
        storageUtil.expectWrite();
        storageUtil.jobUpdateStore.saveJobUpdate(update, lockToken);
        streamMatcher.expectTransaction(
            Op.saveJobUpdate(new SaveJobUpdate(update.newBuilder(), lockToken.orNull())))
            .andReturn(position);
      }

      @Override
      protected void performMutations(MutableStoreProvider storeProvider) {
        storeProvider.getJobUpdateStore().saveJobUpdate(update, lockToken);
      }
    }.run();
  }

  @Test
  public void testSaveJobUpdateEvent() throws Exception {
    final IJobUpdateEvent event = IJobUpdateEvent.build(new JobUpdateEvent()
        .setStatus(JobUpdateStatus.ROLLING_BACK)
        .setTimestampMs(12345L));

    new MutationFixture() {
      @Override
      protected void setupExpectations() throws Exception {
        storageUtil.expectWrite();
        storageUtil.jobUpdateStore.saveJobUpdateEvent(UPDATE_ID, event);
        streamMatcher.expectTransaction(Op.saveJobUpdateEvent(new SaveJobUpdateEvent(
            event.newBuilder(),
            UPDATE_ID.getId(),
            UPDATE_ID.newBuilder()))).andReturn(position);
      }

      @Override
      protected void performMutations(MutableStoreProvider storeProvider) {
        storeProvider.getJobUpdateStore().saveJobUpdateEvent(UPDATE_ID, event);
      }
    }.run();
  }

  @Test
  public void testSaveJobInstanceUpdateEvent() throws Exception {
    final IJobInstanceUpdateEvent event = IJobInstanceUpdateEvent.build(new JobInstanceUpdateEvent()
        .setAction(JobUpdateAction.INSTANCE_ROLLING_BACK)
        .setTimestampMs(12345L)
        .setInstanceId(0));

    new MutationFixture() {
      @Override
      protected void setupExpectations() throws Exception {
        storageUtil.expectWrite();
        storageUtil.jobUpdateStore.saveJobInstanceUpdateEvent(UPDATE_ID, event);
        streamMatcher.expectTransaction(Op.saveJobInstanceUpdateEvent(
            new SaveJobInstanceUpdateEvent(
                event.newBuilder(),
                UPDATE_ID.getId(),
                UPDATE_ID.newBuilder())))
            .andReturn(position);
      }

      @Override
      protected void performMutations(MutableStoreProvider storeProvider) {
        storeProvider.getJobUpdateStore().saveJobInstanceUpdateEvent(UPDATE_ID, event);
      }
    }.run();
  }

  @Test
  public void testPruneHistory() throws Exception {
    final PruneJobUpdateHistory pruneHistory = new PruneJobUpdateHistory()
        .setHistoryPruneThresholdMs(1L)
        .setPerJobRetainCount(1);

    new MutationFixture() {
      @Override
      protected void setupExpectations() throws Exception {
        storageUtil.expectWrite();
        expect(storageUtil.jobUpdateStore.pruneHistory(
            pruneHistory.getPerJobRetainCount(),
            pruneHistory.getHistoryPruneThresholdMs()))
            .andReturn(ImmutableSet.of(UPDATE_ID));

        streamMatcher.expectTransaction(Op.pruneJobUpdateHistory(pruneHistory)).andReturn(position);
      }

      @Override
      protected void performMutations(MutableStoreProvider storeProvider) {
        storeProvider.getJobUpdateStore().pruneHistory(
            pruneHistory.getPerJobRetainCount(),
            pruneHistory.getHistoryPruneThresholdMs());
      }
    }.run();
  }

  private LogEntry createTransaction(Op... ops) {
    return LogEntry.transaction(
        new Transaction(ImmutableList.copyOf(ops), storageConstants.CURRENT_SCHEMA_VERSION));
  }

  private static IScheduledTask task(String id, ScheduleStatus status) {
    return IScheduledTask.build(new ScheduledTask()
        .setStatus(status)
        .setAssignedTask(new AssignedTask()
            .setTaskId(id)
            .setTask(new TaskConfig())));
  }
}
