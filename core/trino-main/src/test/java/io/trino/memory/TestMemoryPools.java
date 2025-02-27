/*
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
package io.trino.memory;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.ListenableFuture;
import io.airlift.stats.TestingGcMonitor;
import io.airlift.units.DataSize;
import io.trino.Session;
import io.trino.execution.StageId;
import io.trino.execution.TaskId;
import io.trino.execution.buffer.TestingPagesSerdeFactory;
import io.trino.memory.context.LocalMemoryContext;
import io.trino.operator.Driver;
import io.trino.operator.DriverContext;
import io.trino.operator.Operator;
import io.trino.operator.OperatorContext;
import io.trino.operator.OutputFactory;
import io.trino.operator.TableScanOperator;
import io.trino.operator.TaskContext;
import io.trino.plugin.tpch.TpchConnectorFactory;
import io.trino.spi.Page;
import io.trino.spi.QueryId;
import io.trino.spiller.SpillSpaceTracker;
import io.trino.sql.planner.plan.PlanNodeId;
import io.trino.testing.LocalQueryRunner;
import io.trino.testing.PageConsumerOperator.PageConsumerOutputFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.util.concurrent.Futures.immediateVoidFuture;
import static io.airlift.units.DataSize.Unit.GIGABYTE;
import static io.airlift.units.DataSize.Unit.MEGABYTE;
import static io.trino.testing.TestingSession.testSessionBuilder;
import static io.trino.testing.TestingTaskContext.createTaskContext;
import static java.lang.String.format;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.TestInstance.Lifecycle.PER_METHOD;

@TestInstance(PER_METHOD)
public class TestMemoryPools
{
    private static final DataSize TEN_MEGABYTES = DataSize.of(10, MEGABYTE);
    private static final DataSize TEN_MEGABYTES_WITHOUT_TWO_BYTES = DataSize.ofBytes(TEN_MEGABYTES.toBytes() - 2);
    private static final DataSize ONE_BYTE = DataSize.ofBytes(1);

    private TaskId fakeTaskId;
    private LocalQueryRunner localQueryRunner;
    private MemoryPool userPool;
    private List<Driver> drivers;
    private TaskContext taskContext;

    private void setUp(Supplier<List<Driver>> driversSupplier)
    {
        checkState(localQueryRunner == null, "Already set up");

        Session session = testSessionBuilder()
                .setCatalog("tpch")
                .setSchema("tiny")
                .build();

        localQueryRunner = LocalQueryRunner.create(session);

        // add tpch
        localQueryRunner.createCatalog("tpch", new TpchConnectorFactory(1), ImmutableMap.of());

        userPool = new MemoryPool(TEN_MEGABYTES);
        QueryId queryId = new QueryId("fake");
        fakeTaskId = new TaskId(new StageId(queryId, 0), 0, 0);
        SpillSpaceTracker spillSpaceTracker = new SpillSpaceTracker(DataSize.of(1, GIGABYTE));
        QueryContext queryContext = new QueryContext(new QueryId("query"),
                TEN_MEGABYTES,
                userPool,
                new TestingGcMonitor(),
                localQueryRunner.getExecutor(),
                localQueryRunner.getScheduler(),
                localQueryRunner.getScheduler(),
                TEN_MEGABYTES,
                spillSpaceTracker);
        taskContext = createTaskContext(queryContext, localQueryRunner.getExecutor(), localQueryRunner.getDefaultSession());
        drivers = driversSupplier.get();
    }

    private void setUpCountStarFromOrdersWithJoin()
    {
        // query will reserve all memory in the user pool and discard the output
        setUp(() -> {
            OutputFactory outputFactory = new PageConsumerOutputFactory(types -> (page -> {}));
            return localQueryRunner.createDrivers("SELECT COUNT(*) FROM orders JOIN lineitem ON CAST(orders.orderkey AS VARCHAR) = CAST(lineitem.orderkey AS VARCHAR)", outputFactory, taskContext);
        });
    }

    private RevocableMemoryOperator setupConsumeRevocableMemory(DataSize reservedPerPage, long numberOfPages)
    {
        AtomicReference<RevocableMemoryOperator> createOperator = new AtomicReference<>();
        setUp(() -> {
            DriverContext driverContext = taskContext.addPipelineContext(0, false, false, false).addDriverContext();
            OperatorContext revokableOperatorContext = driverContext.addOperatorContext(
                    Integer.MAX_VALUE,
                    new PlanNodeId("revokable_operator"),
                    TableScanOperator.class.getSimpleName());

            OutputFactory outputFactory = new PageConsumerOutputFactory(types -> (page -> {}));
            Operator outputOperator = outputFactory.createOutputOperator(2, new PlanNodeId("output"), ImmutableList.of(), Function.identity(), new TestingPagesSerdeFactory()).createOperator(driverContext);
            RevocableMemoryOperator revocableMemoryOperator = new RevocableMemoryOperator(revokableOperatorContext, reservedPerPage, numberOfPages);
            createOperator.set(revocableMemoryOperator);

            Driver driver = Driver.createDriver(driverContext, revocableMemoryOperator, outputOperator);
            return ImmutableList.of(driver);
        });
        return createOperator.get();
    }

    @AfterEach
    public void tearDown()
    {
        if (localQueryRunner != null) {
            localQueryRunner.close();
            localQueryRunner = null;
        }
    }

    @Test
    public void testNotifyListenerOnMemoryReserved()
    {
        setupConsumeRevocableMemory(ONE_BYTE, 10);
        AtomicReference<MemoryPool> notifiedPool = new AtomicReference<>();
        AtomicLong notifiedBytes = new AtomicLong();
        userPool.addListener(MemoryPoolListener.onMemoryReserved(pool -> {
            notifiedPool.set(pool);
            notifiedBytes.set(pool.getReservedBytes());
        }));

        userPool.reserve(fakeTaskId, "test", 3);
        assertThat(notifiedPool.get()).isEqualTo(userPool);
        assertThat(notifiedBytes.get()).isEqualTo(3L);
    }

    @Test
    public void testMemoryFutureCancellation()
    {
        setUpCountStarFromOrdersWithJoin();
        ListenableFuture<Void> future = userPool.reserve(fakeTaskId, "test", TEN_MEGABYTES.toBytes());
        assertThat(!future.isDone()).isTrue();
        assertThatThrownBy(() -> future.cancel(true))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("cancellation is not supported");
        userPool.free(fakeTaskId, "test", TEN_MEGABYTES.toBytes());
        assertThat(future.isDone()).isTrue();
    }

    @Test
    public void testBlockingOnRevocableMemoryFreeUser()
    {
        setupConsumeRevocableMemory(ONE_BYTE, 10);
        assertThat(userPool.tryReserve(fakeTaskId, "test", TEN_MEGABYTES_WITHOUT_TWO_BYTES.toBytes())).isTrue();

        // we expect 2 iterations as we have 2 bytes remaining in memory pool and we allocate 1 byte per page
        assertThat(runDriversUntilBlocked(waitingForRevocableMemory())).isEqualTo(2);
        assertThat(userPool.getFreeBytes() <= 0)
                .describedAs(format("Expected empty pool but got [%d]", userPool.getFreeBytes()))
                .isTrue();

        // lets free 5 bytes
        userPool.free(fakeTaskId, "test", 5);
        assertThat(runDriversUntilBlocked(waitingForRevocableMemory())).isEqualTo(5);
        assertThat(userPool.getFreeBytes() <= 0)
                .describedAs(format("Expected empty pool but got [%d]", userPool.getFreeBytes()))
                .isTrue();

        // 3 more bytes is enough for driver to finish
        userPool.free(fakeTaskId, "test", 3);
        assertDriversProgress(waitingForRevocableMemory());
        assertThat(userPool.getFreeBytes()).isEqualTo(10);
    }

    @Test
    public void testBlockingOnRevocableMemoryFreeViaRevoke()
    {
        RevocableMemoryOperator revocableMemoryOperator = setupConsumeRevocableMemory(ONE_BYTE, 5);
        assertThat(userPool.tryReserve(fakeTaskId, "test", TEN_MEGABYTES_WITHOUT_TWO_BYTES.toBytes())).isTrue();

        // we expect 2 iterations as we have 2 bytes remaining in memory pool and we allocate 1 byte per page
        assertThat(runDriversUntilBlocked(waitingForRevocableMemory())).isEqualTo(2);
        revocableMemoryOperator.getOperatorContext().requestMemoryRevoking();

        // 2 more iterations
        assertThat(runDriversUntilBlocked(waitingForRevocableMemory())).isEqualTo(2);
        revocableMemoryOperator.getOperatorContext().requestMemoryRevoking();

        // 3 more bytes is enough for driver to finish
        assertDriversProgress(waitingForRevocableMemory());
        assertThat(userPool.getFreeBytes()).isEqualTo(2);
    }

    @Test
    public void testTaggedAllocations()
    {
        TaskId testTask = new TaskId(new StageId(new QueryId("test_query"), 0), 0, 0);
        MemoryPool testPool = new MemoryPool(DataSize.ofBytes(1000));

        testPool.reserve(testTask, "test_tag", 10);

        Map<String, Long> allocations = testPool.getTaggedMemoryAllocations().get(new QueryId("test_query"));
        assertThat(allocations).isEqualTo(ImmutableMap.of("test_tag", 10L));

        // free 5 bytes for test_tag
        testPool.free(testTask, "test_tag", 5);
        assertThat(allocations).isEqualTo(ImmutableMap.of("test_tag", 5L));

        testPool.reserve(testTask, "test_tag2", 20);
        assertThat(allocations).isEqualTo(ImmutableMap.of("test_tag", 5L, "test_tag2", 20L));

        // free the remaining 5 bytes for test_tag
        testPool.free(testTask, "test_tag", 5);
        assertThat(allocations).isEqualTo(ImmutableMap.of("test_tag2", 20L));

        // free all for test_tag2
        testPool.free(testTask, "test_tag2", 20);
        assertThat(testPool.getTaggedMemoryAllocations().size()).isEqualTo(0);
    }

    @Test
    public void testPerTaskAllocations()
    {
        QueryId query1 = new QueryId("test_query1");
        TaskId q1task1 = new TaskId(new StageId(query1, 0), 0, 0);
        TaskId q1task2 = new TaskId(new StageId(query1, 0), 1, 0);

        QueryId query2 = new QueryId("test_query2");
        TaskId q2task1 = new TaskId(new StageId(query2, 0), 0, 0);

        MemoryPool testPool = new MemoryPool(DataSize.ofBytes(1000));

        // allocate for some task for q1
        testPool.reserve(q1task1, "tag", 10);
        assertThat(testPool.getQueryMemoryReservations().keySet()).hasSize(1);
        assertThat(testPool.getQueryMemoryReservation(query1)).isEqualTo(10L);
        assertThat(testPool.getTaskMemoryReservations().keySet()).hasSize(1);
        assertThat(testPool.getTaskMemoryReservation(q1task1)).isEqualTo(10L);

        // different task same for q1
        testPool.reserve(q1task2, "tag", 7);
        assertThat(testPool.getQueryMemoryReservations().keySet()).hasSize(1);
        assertThat(testPool.getQueryMemoryReservation(query1)).isEqualTo(17L);
        assertThat(testPool.getTaskMemoryReservations().keySet()).hasSize(2);
        assertThat(testPool.getTaskMemoryReservation(q1task1)).isEqualTo(10L);
        assertThat(testPool.getTaskMemoryReservation(q1task2)).isEqualTo(7L);

        // task for a different query
        testPool.reserve(q2task1, "tag", 9);
        assertThat(testPool.getQueryMemoryReservations().keySet()).hasSize(2);
        assertThat(testPool.getQueryMemoryReservation(query1)).isEqualTo(17L);
        assertThat(testPool.getQueryMemoryReservation(query2)).isEqualTo(9L);
        assertThat(testPool.getTaskMemoryReservation(q1task1)).isEqualTo(10L);
        assertThat(testPool.getTaskMemoryReservations().keySet()).hasSize(3);
        assertThat(testPool.getTaskMemoryReservation(q1task2)).isEqualTo(7L);
        assertThat(testPool.getTaskMemoryReservation(q2task1)).isEqualTo(9L);

        // increase memory for one of the tasks
        testPool.reserve(q1task1, "tag", 3);
        assertThat(testPool.getQueryMemoryReservation(query1)).isEqualTo(20L);
        assertThat(testPool.getQueryMemoryReservation(query2)).isEqualTo(9L);
        assertThat(testPool.getTaskMemoryReservations().keySet()).hasSize(3);
        assertThat(testPool.getTaskMemoryReservation(q1task1)).isEqualTo(13L);
        assertThat(testPool.getTaskMemoryReservation(q1task2)).isEqualTo(7L);
        assertThat(testPool.getTaskMemoryReservation(q2task1)).isEqualTo(9L);

        // decrease memory for one of the tasks
        testPool.free(q1task1, "tag", 5);
        assertThat(testPool.getQueryMemoryReservations().keySet()).hasSize(2);
        assertThat(testPool.getQueryMemoryReservation(query1)).isEqualTo(15L);
        assertThat(testPool.getQueryMemoryReservation(query2)).isEqualTo(9L);
        assertThat(testPool.getTaskMemoryReservations().keySet()).hasSize(3);
        assertThat(testPool.getTaskMemoryReservation(q1task1)).isEqualTo(8L);
        assertThat(testPool.getTaskMemoryReservation(q1task2)).isEqualTo(7L);
        assertThat(testPool.getTaskMemoryReservation(q2task1)).isEqualTo(9L);

        // try to reserve more than allocated by task
        assertThatThrownBy(() -> testPool.free(q1task1, "tag", 9))
                .hasMessage("tried to free more memory than is reserved by task");
        assertThat(testPool.getQueryMemoryReservations().keySet()).hasSize(2);
        assertThat(testPool.getQueryMemoryReservation(query1)).isEqualTo(15L);
        assertThat(testPool.getQueryMemoryReservation(query2)).isEqualTo(9L);
        assertThat(testPool.getTaskMemoryReservations().keySet()).hasSize(3);
        assertThat(testPool.getTaskMemoryReservation(q1task1)).isEqualTo(8L);
        assertThat(testPool.getTaskMemoryReservation(q1task2)).isEqualTo(7L);
        assertThat(testPool.getTaskMemoryReservation(q2task1)).isEqualTo(9L);

        // zero memory for one of the tasks
        testPool.free(q1task1, "tag", 8);
        assertThat(testPool.getQueryMemoryReservations().keySet()).hasSize(2);
        assertThat(testPool.getQueryMemoryReservation(query1)).isEqualTo(7L);
        assertThat(testPool.getQueryMemoryReservation(query2)).isEqualTo(9L);
        assertThat(testPool.getTaskMemoryReservations().keySet()).hasSize(2);
        assertThat(testPool.getTaskMemoryReservation(q1task1)).isEqualTo(0L);
        assertThat(testPool.getTaskMemoryReservation(q1task2)).isEqualTo(7L);
        assertThat(testPool.getTaskMemoryReservation(q2task1)).isEqualTo(9L);

        // zero memory for all query the tasks
        testPool.free(q1task2, "tag", 7);
        assertThat(testPool.getQueryMemoryReservations().keySet()).hasSize(1);
        assertThat(testPool.getQueryMemoryReservation(query1)).isEqualTo(0L);
        assertThat(testPool.getQueryMemoryReservation(query2)).isEqualTo(9L);
        assertThat(testPool.getTaskMemoryReservations().keySet()).hasSize(1);
        assertThat(testPool.getTaskMemoryReservation(q1task1)).isEqualTo(0L);
        assertThat(testPool.getTaskMemoryReservation(q1task2)).isEqualTo(0L);
        assertThat(testPool.getTaskMemoryReservation(q2task1)).isEqualTo(9L);
    }

    @Test
    public void testGlobalRevocableAllocations()
    {
        MemoryPool testPool = new MemoryPool(DataSize.ofBytes(1000));

        assertThat(testPool.tryReserveRevocable(999)).isTrue();
        assertThat(testPool.tryReserveRevocable(2)).isFalse();
        assertThat(testPool.getReservedBytes()).isEqualTo(0);
        assertThat(testPool.getReservedRevocableBytes()).isEqualTo(999);
        assertThat(testPool.getTaskMemoryReservations()).isEmpty();
        assertThat(testPool.getQueryMemoryReservations()).isEmpty();
        assertThat(testPool.getTaggedMemoryAllocations()).isEmpty();

        // non-revocable allocation should block
        QueryId query = new QueryId("test_query1");
        TaskId task = new TaskId(new StageId(query, 0), 0, 0);
        ListenableFuture<Void> memoryFuture = testPool.reserve(task, "tag", 2);
        assertThat(memoryFuture).isNotDone();

        // non-revocable allocation should unblock after global revocable is freed
        testPool.freeRevocable(999);
        assertThat(memoryFuture).isDone();

        assertThat(testPool.getReservedBytes()).isEqualTo(2L);
        assertThat(testPool.getReservedRevocableBytes()).isEqualTo(0);
    }

    @Test
    public void testPerTaskRevocableAllocations()
    {
        QueryId query1 = new QueryId("test_query1");
        TaskId q1task1 = new TaskId(new StageId(query1, 0), 0, 0);
        TaskId q1task2 = new TaskId(new StageId(query1, 0), 1, 0);

        QueryId query2 = new QueryId("test_query2");
        TaskId q2task1 = new TaskId(new StageId(query2, 0), 0, 0);

        MemoryPool testPool = new MemoryPool(DataSize.ofBytes(1000));

        // allocate for some task for q1
        testPool.reserveRevocable(q1task1, 10);
        assertThat(testPool.getQueryRevocableMemoryReservations().keySet()).hasSize(1);
        assertThat(testPool.getQueryRevocableMemoryReservation(query1)).isEqualTo(10L);
        assertThat(testPool.getTaskRevocableMemoryReservations().keySet()).hasSize(1);
        assertThat(testPool.getTaskRevocableMemoryReservation(q1task1)).isEqualTo(10L);

        // different task same for q1
        testPool.reserveRevocable(q1task2, 7);
        assertThat(testPool.getQueryRevocableMemoryReservations().keySet()).hasSize(1);
        assertThat(testPool.getQueryRevocableMemoryReservation(query1)).isEqualTo(17L);
        assertThat(testPool.getTaskRevocableMemoryReservations().keySet()).hasSize(2);
        assertThat(testPool.getTaskRevocableMemoryReservation(q1task1)).isEqualTo(10L);
        assertThat(testPool.getTaskRevocableMemoryReservation(q1task2)).isEqualTo(7L);

        // task for a different query
        testPool.reserveRevocable(q2task1, 9);
        assertThat(testPool.getQueryRevocableMemoryReservations().keySet()).hasSize(2);
        assertThat(testPool.getQueryRevocableMemoryReservation(query1)).isEqualTo(17L);
        assertThat(testPool.getQueryRevocableMemoryReservation(query2)).isEqualTo(9L);
        assertThat(testPool.getTaskRevocableMemoryReservation(q1task1)).isEqualTo(10L);
        assertThat(testPool.getTaskRevocableMemoryReservations().keySet()).hasSize(3);
        assertThat(testPool.getTaskRevocableMemoryReservation(q1task2)).isEqualTo(7L);
        assertThat(testPool.getTaskRevocableMemoryReservation(q2task1)).isEqualTo(9L);

        // increase memory for one of the tasks
        testPool.reserveRevocable(q1task1, 3);
        assertThat(testPool.getQueryRevocableMemoryReservation(query1)).isEqualTo(20L);
        assertThat(testPool.getQueryRevocableMemoryReservation(query2)).isEqualTo(9L);
        assertThat(testPool.getTaskRevocableMemoryReservations().keySet()).hasSize(3);
        assertThat(testPool.getTaskRevocableMemoryReservation(q1task1)).isEqualTo(13L);
        assertThat(testPool.getTaskRevocableMemoryReservation(q1task2)).isEqualTo(7L);
        assertThat(testPool.getTaskRevocableMemoryReservation(q2task1)).isEqualTo(9L);

        // decrease memory for one of the tasks
        testPool.freeRevocable(q1task1, 5);
        assertThat(testPool.getQueryRevocableMemoryReservations().keySet()).hasSize(2);
        assertThat(testPool.getQueryRevocableMemoryReservation(query1)).isEqualTo(15L);
        assertThat(testPool.getQueryRevocableMemoryReservation(query2)).isEqualTo(9L);
        assertThat(testPool.getTaskRevocableMemoryReservations().keySet()).hasSize(3);
        assertThat(testPool.getTaskRevocableMemoryReservation(q1task1)).isEqualTo(8L);
        assertThat(testPool.getTaskRevocableMemoryReservation(q1task2)).isEqualTo(7L);
        assertThat(testPool.getTaskRevocableMemoryReservation(q2task1)).isEqualTo(9L);

        // try to reserve more than allocated by task
        assertThatThrownBy(() -> testPool.freeRevocable(q1task1, 9))
                .hasMessage("tried to free more revocable memory than is reserved by task");
        assertThat(testPool.getQueryRevocableMemoryReservations().keySet()).hasSize(2);
        assertThat(testPool.getQueryRevocableMemoryReservation(query1)).isEqualTo(15L);
        assertThat(testPool.getQueryRevocableMemoryReservation(query2)).isEqualTo(9L);
        assertThat(testPool.getTaskRevocableMemoryReservations().keySet()).hasSize(3);
        assertThat(testPool.getTaskRevocableMemoryReservation(q1task1)).isEqualTo(8L);
        assertThat(testPool.getTaskRevocableMemoryReservation(q1task2)).isEqualTo(7L);
        assertThat(testPool.getTaskRevocableMemoryReservation(q2task1)).isEqualTo(9L);

        // zero memory for one of the tasks
        testPool.freeRevocable(q1task1, 8);
        assertThat(testPool.getQueryRevocableMemoryReservations().keySet()).hasSize(2);
        assertThat(testPool.getQueryRevocableMemoryReservation(query1)).isEqualTo(7L);
        assertThat(testPool.getQueryRevocableMemoryReservation(query2)).isEqualTo(9L);
        assertThat(testPool.getTaskRevocableMemoryReservations().keySet()).hasSize(2);
        assertThat(testPool.getTaskRevocableMemoryReservation(q1task1)).isEqualTo(0L);
        assertThat(testPool.getTaskRevocableMemoryReservation(q1task2)).isEqualTo(7L);
        assertThat(testPool.getTaskRevocableMemoryReservation(q2task1)).isEqualTo(9L);

        // zero memory for all query the tasks
        testPool.freeRevocable(q1task2, 7);
        assertThat(testPool.getQueryRevocableMemoryReservations().keySet()).hasSize(1);
        assertThat(testPool.getQueryRevocableMemoryReservation(query1)).isEqualTo(0L);
        assertThat(testPool.getQueryRevocableMemoryReservation(query2)).isEqualTo(9L);
        assertThat(testPool.getTaskRevocableMemoryReservations().keySet()).hasSize(1);
        assertThat(testPool.getTaskRevocableMemoryReservation(q1task1)).isEqualTo(0L);
        assertThat(testPool.getTaskRevocableMemoryReservation(q1task2)).isEqualTo(0L);
        assertThat(testPool.getTaskRevocableMemoryReservation(q2task1)).isEqualTo(9L);
    }

    private long runDriversUntilBlocked(Predicate<OperatorContext> reason)
    {
        long iterationsCount = 0;

        // run driver, until it blocks
        while (!isOperatorBlocked(drivers, reason)) {
            for (Driver driver : drivers) {
                driver.processForNumberOfIterations(1);
            }
            iterationsCount++;
        }

        // driver should be blocked waiting for memory
        for (Driver driver : drivers) {
            assertThat(driver.isFinished()).isFalse();
        }
        return iterationsCount;
    }

    private void assertDriversProgress(Predicate<OperatorContext> reason)
    {
        do {
            assertThat(isOperatorBlocked(drivers, reason)).isFalse();
            boolean progress = false;
            for (Driver driver : drivers) {
                ListenableFuture<Void> blocked = driver.processUntilBlocked();
                progress = progress | blocked.isDone();
            }
            // query should not block
            assertThat(progress).isTrue();
        }
        while (!drivers.stream().allMatch(Driver::isFinished));
    }

    private Predicate<OperatorContext> waitingForUserMemory()
    {
        return (OperatorContext operatorContext) -> !operatorContext.isWaitingForMemory().isDone();
    }

    private Predicate<OperatorContext> waitingForRevocableMemory()
    {
        return (OperatorContext operatorContext) ->
                !operatorContext.isWaitingForRevocableMemory().isDone() &&
                        !operatorContext.isMemoryRevokingRequested();
    }

    private static boolean isOperatorBlocked(List<Driver> drivers, Predicate<OperatorContext> reason)
    {
        for (Driver driver : drivers) {
            for (OperatorContext operatorContext : driver.getDriverContext().getOperatorContexts()) {
                if (reason.test(operatorContext)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static class RevocableMemoryOperator
            implements Operator
    {
        private final DataSize reservedPerPage;
        private final long numberOfPages;
        private final OperatorContext operatorContext;
        private long producedPagesCount;
        private final LocalMemoryContext revocableMemoryContext;

        public RevocableMemoryOperator(OperatorContext operatorContext, DataSize reservedPerPage, long numberOfPages)
        {
            this.operatorContext = operatorContext;
            this.reservedPerPage = reservedPerPage;
            this.numberOfPages = numberOfPages;
            this.revocableMemoryContext = operatorContext.localRevocableMemoryContext();
        }

        @Override
        public ListenableFuture<Void> startMemoryRevoke()
        {
            return immediateVoidFuture();
        }

        @Override
        public void finishMemoryRevoke()
        {
            revocableMemoryContext.setBytes(0);
        }

        @Override
        public OperatorContext getOperatorContext()
        {
            return operatorContext;
        }

        @Override
        public void finish()
        {
            revocableMemoryContext.setBytes(0);
        }

        @Override
        public boolean isFinished()
        {
            return producedPagesCount >= numberOfPages;
        }

        @Override
        public boolean needsInput()
        {
            return false;
        }

        @Override
        public void addInput(Page page)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public Page getOutput()
        {
            revocableMemoryContext.setBytes(revocableMemoryContext.getBytes() + reservedPerPage.toBytes());
            producedPagesCount++;
            if (producedPagesCount == numberOfPages) {
                finish();
            }
            return new Page(10);
        }
    }
}
