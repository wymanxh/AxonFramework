/*
 * Copyright (c) 2010-2016. Axon Framework
 *
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

package org.axonframework.eventsourcing;

import org.axonframework.commandhandling.model.ConcurrencyException;
import org.axonframework.common.DirectExecutor;
import org.axonframework.eventsourcing.eventstore.DomainEventStream;
import org.axonframework.eventsourcing.eventstore.EventStorageEngine;
import org.axonframework.messaging.interceptors.NoTransactionManager;
import org.axonframework.messaging.interceptors.TransactionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executor;

/**
 * Abstract implementation of the {@link org.axonframework.eventsourcing.Snapshotter} that uses a task executor to
 * creates snapshots. Actual snapshot creation logic should be provided by a subclass.
 *
 * @author Allard Buijze
 * @since 0.6
 */
public abstract class AbstractSnapshotter implements Snapshotter {

    private static final Logger logger = LoggerFactory.getLogger(AbstractSnapshotter.class);

    private final EventStorageEngine eventStorageEngine;
    private final Executor executor;
    private final TransactionManager transactionManager;

    /**
     * Initializes the Snapshotter to append snapshots in the given {@code eventStore}. This snapshotter will
     * create the snapshots in the process that triggers them, and save them into the Event Store without any
     * transaction.
     *
     * @param eventStorageEngine the EventStore instance to store snapshots in
     */
    protected AbstractSnapshotter(EventStorageEngine eventStorageEngine) {
        this(eventStorageEngine, new NoTransactionManager());
    }

    /**
     * Initializes the Snapshotter to append snapshots in the given {@code eventStore}. This snapshotter will create
     * the snapshots in the process that triggers them, and save them into the Event Store in a transaction managed by
     * the given {@code transactionManager}.
     *
     * @param eventStorageEngine         the EventStore instance to store snapshots in
     * @param transactionManager The transaction manager to create the surrounding transaction with
     */
    protected AbstractSnapshotter(EventStorageEngine eventStorageEngine, TransactionManager transactionManager) {
        this(eventStorageEngine, DirectExecutor.INSTANCE, transactionManager);
    }

    /**
     * Initializes the Snapshotter to append snapshots in the given {@code eventStore}. This snapshotter will create
     * the snapshots in the process provided by the given {@code executor}, and save them into the Event Store in a
     * transaction managed by the given {@code transactionManager}.
     *
     * @param eventStorageEngine         The EventStore instance to store snapshots in
     * @param executor           The executor that handles the actual snapshot creation process
     * @param transactionManager The transaction manager to create the surrounding transaction with
     */
    protected AbstractSnapshotter(EventStorageEngine eventStorageEngine, Executor executor, TransactionManager transactionManager) {
        this.eventStorageEngine = eventStorageEngine;
        this.executor = executor;
        this.transactionManager = transactionManager;
    }

    @Override
    public void scheduleSnapshot(Class<?> aggregateType, String aggregateIdentifier) {
        executor.execute(() -> transactionManager
                .executeInTransaction(new SilentTask(createSnapshotterTask(aggregateType, aggregateIdentifier))));
    }

    /**
     * Creates an instance of a task that contains the actual snapshot creation logic.
     *
     * @param aggregateType       The type of the aggregate to create a snapshot for
     * @param aggregateIdentifier The identifier of the aggregate to create a snapshot for
     * @return the task containing snapshot creation logic
     */
    protected Runnable createSnapshotterTask(Class<?> aggregateType, String aggregateIdentifier) {
        return new CreateSnapshotTask(aggregateType, aggregateIdentifier);
    }

    /**
     * Creates a snapshot event for an aggregate of which passed events are available in the given
     * {@code eventStream}. May return {@code null} to indicate a snapshot event is not necessary or
     * appropriate for the given event stream.
     *
     * @param aggregateType       The aggregate's type identifier
     * @param aggregateIdentifier The identifier of the aggregate to create a snapshot for
     * @param eventStream         The event stream containing the aggregate's past events
     * @return the snapshot event for the given events, or {@code null} if none should be stored.
     */
    protected abstract DomainEventMessage createSnapshot(Class<?> aggregateType, String aggregateIdentifier,
                                                         DomainEventStream eventStream);

    /**
     * Returns the event store this snapshotter uses to load domain events and store snapshot events.
     *
     * @return the event store this snapshotter uses to load domain events and store snapshot events.
     */
    protected EventStorageEngine getEventStorageEngine() {
        return eventStorageEngine;
    }

    /**
     * Returns the executor that executes snapshot taking tasks.
     *
     * @return the executor that executes snapshot taking tasks.
     */
    protected Executor getExecutor() {
        return executor;
    }

    /**
     * Sets the executor that should process actual snapshot taking. Defaults to an instance that runs all actions in
     * the calling thread (i.e. synchronous execution).
     *
     * @param executor the executor to execute snapshotting tasks
     */
    public void setExecutor(Executor executor) {
        this.executor = executor;
    }

    private static class TransactionalRunnableWrapper implements Runnable {

        private final Runnable command;
        private final TransactionManager transactionManager;

        public TransactionalRunnableWrapper(TransactionManager transactionManager, Runnable command) {
            this.command = command;
            this.transactionManager = transactionManager;
        }

        @SuppressWarnings("unchecked")
        @Override
        public void run() {
            Transaction transaction = transactionManager.startTransaction();
            try {
                command.run();
                transaction.commit();
            } catch (Throwable e) {
                transaction.rollback();
                throw e;
            }
        }
    }

    private static class SilentTask implements Runnable {

        private final Runnable snapshotterTask;

        private SilentTask(Runnable snapshotterTask) {
            this.snapshotterTask = snapshotterTask;
        }

        @Override
        public void run() {
            try {
                snapshotterTask.run();
            } catch (ConcurrencyException e) {
                logger.info("An up-to-date snapshot entry already exists, ignoring this attempt.");
            } catch (Exception e) {
                if (logger.isDebugEnabled()) {
                    logger.warn("An attempt to create and store a snapshot resulted in an exception:", e);
                } else {
                    logger.warn("An attempt to create and store a snapshot resulted in an exception. " +
                                        "Exception summary: {}", e.getMessage());
                }
            }
        }
    }

    private final class CreateSnapshotTask implements Runnable {

        private final Class<?> aggregateType;
        private final String identifier;

        private CreateSnapshotTask(Class<?> aggregateType, String identifier) {
            this.aggregateType = aggregateType;
            this.identifier = identifier;
        }

        @Override
        public void run() {
            DomainEventStream eventStream = eventStream = eventStorageEngine.readEvents(identifier);
            // a snapshot should only be stored if the snapshot replaces at least more than one event
            long firstEventSequenceNumber = eventStream.peek().getSequenceNumber();
            DomainEventMessage snapshotEvent = createSnapshot(aggregateType, identifier, eventStream);
            if (snapshotEvent != null && snapshotEvent.getSequenceNumber() > firstEventSequenceNumber) {
                eventStorageEngine.storeSnapshot(snapshotEvent);
            }
        }
    }
}
