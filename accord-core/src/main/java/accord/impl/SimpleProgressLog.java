/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package accord.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

import javax.annotation.Nullable;

import accord.coordinate.*;
import accord.local.*;
import accord.local.Status.Known;
import accord.primitives.*;
import accord.utils.Invariants;

import accord.api.ProgressLog;
import accord.api.RoutingKey;
import accord.local.Node.Id;
import accord.impl.SimpleProgressLog.CoordinateState.CoordinateStatus;
import accord.messages.Callback;
import accord.messages.InformDurable;
import accord.messages.SimpleReply;
import accord.topology.Topologies;
import org.apache.cassandra.utils.concurrent.Future;

import static accord.api.ProgressLog.ProgressShard.Home;
import static accord.api.ProgressLog.ProgressShard.Unsure;
import static accord.coordinate.InformHomeOfTxn.inform;
import static accord.impl.SimpleProgressLog.DisseminateState.DisseminateStatus.NotExecuted;
import static accord.impl.SimpleProgressLog.CoordinateState.CoordinateStatus.NotWitnessed;
import static accord.impl.SimpleProgressLog.CoordinateState.CoordinateStatus.ReadyToExecute;
import static accord.impl.SimpleProgressLog.CoordinateState.CoordinateStatus.Uncommitted;
import static accord.impl.SimpleProgressLog.NonHomeState.Safe;
import static accord.impl.SimpleProgressLog.NonHomeState.StillUnsafe;
import static accord.impl.SimpleProgressLog.NonHomeState.Unsafe;
import static accord.impl.SimpleProgressLog.Progress.Done;
import static accord.impl.SimpleProgressLog.Progress.Expected;
import static accord.impl.SimpleProgressLog.Progress.Investigating;
import static accord.impl.SimpleProgressLog.Progress.NoProgress;
import static accord.impl.SimpleProgressLog.Progress.NoneExpected;
import static accord.impl.SimpleProgressLog.Progress.advance;
import static accord.local.PreLoadContext.contextFor;
import static accord.local.Status.Durability.Durable;
import static accord.local.Status.Known.Nothing;
import static accord.local.Status.PreApplied;
import static accord.local.Status.PreCommitted;
import static accord.primitives.Route.isFullRoute;

// TODO: consider propagating invalidations in the same way as we do applied
public class SimpleProgressLog implements Runnable, ProgressLog.Factory
{
    enum Progress
    {
        NoneExpected, Expected, NoProgress, Investigating, Done;

        static Progress advance(Progress current)
        {
            switch (current)
            {
                default: throw new IllegalStateException();
                case NoneExpected:
                case Investigating:
                case Done:
                    return current;
                case Expected:
                case NoProgress:
                    return NoProgress;
            }
        }
    }

    // exists only on home shard
    static class CoordinateState
    {
        enum CoordinateStatus
        {
            NotWitnessed, Uncommitted, Committed, ReadyToExecute, Done;
            boolean isAtMost(CoordinateStatus equalOrLessThan)
            {
                return compareTo(equalOrLessThan) <= 0;
            }
            boolean isAtLeast(CoordinateStatus equalOrGreaterThan)
            {
                return compareTo(equalOrGreaterThan) >= 0;
            }
        }

        CoordinateStatus status = NotWitnessed;
        Progress progress = NoneExpected;
        ProgressToken token = ProgressToken.NONE;

        Object debugInvestigating;

        void ensureAtLeast(Command command, CoordinateStatus newStatus, Progress newProgress)
        {
            ensureAtLeast(newStatus, newProgress);
            updateMax(command);
        }

        void ensureAtLeast(CoordinateStatus newStatus, Progress newProgress)
        {
            if (newStatus.compareTo(status) > 0)
            {
                status = newStatus;
                progress = newProgress;
            }
        }

        void updateMax(Command command)
        {
            token = token.merge(new ProgressToken(command.durability(), command.status(), command.promised(), command.accepted()));
        }

        void updateMax(ProgressToken ok)
        {
            // TODO: perhaps set localProgress back to Waiting if Investigating and we update anything?
            token = token.merge(ok);
        }

        void durableGlobal()
        {
            switch (status)
            {
                default: throw new IllegalStateException();
                case NotWitnessed:
                case Uncommitted:
                case Committed:
                case ReadyToExecute:
                    status = CoordinateStatus.Done;
                    progress = NoneExpected;
                case Done:
            }
        }

        void update(Node node, CommandStore commandStore, TxnId txnId, Command command)
        {
            if (progress != NoProgress)
            {
                progress = advance(progress);
                return;
            }

            progress = Investigating;
            switch (status)
            {
                default: throw new AssertionError();
                case NotWitnessed: // can't make progress if we haven't witnessed it yet
                case Committed: // can't make progress if we aren't yet ReadyToExecute
                case Done: // shouldn't be trying to make progress, as we're done
                    throw new IllegalStateException();

                case Uncommitted:
                case ReadyToExecute:
                {
                    if (status.isAtLeast(CoordinateStatus.Committed) && command.durability().isDurable())
                    {
                        // must also be committed, as at the time of writing we do not guarantee dissemination of Commit
                        // records to the home shard, so we only know the executeAt shards will have witnessed this
                        // if the home shard is at an earlier phase, it must run recovery
                        long epoch = command.executeAt().epoch;
                        node.withEpoch(epoch, () -> debugInvestigating = FetchData.fetch(PreApplied.minKnown, node, txnId, command.route(), epoch, (success, fail) -> {
                            // should have found enough information to apply the result, but in case we did not reset progress
                            if (progress == Investigating)
                                progress = Expected;
                        }));
                    }
                    else
                    {
                        RoutingKey homeKey = command.homeKey();
                        node.withEpoch(txnId.epoch, () -> {

                            Future<? extends Outcome> recover = node.maybeRecover(txnId, homeKey, command.route(), token);
                            recover.addCallback((success, fail) -> {
                                if (status.isAtMost(ReadyToExecute) && progress == Investigating)
                                {
                                    progress = Expected;
                                    if (fail != null)
                                        return;

                                    ProgressToken token = success.asProgressToken();
                                    // TODO: avoid returning null (need to change semantics here in this case, though, as Recover doesn't return CheckStatusOk)
                                    if (token.durability.isDurable())
                                    {
                                        commandStore.execute(contextFor(txnId), safeStore -> {
                                            Command cmd = safeStore.command(txnId);
                                            cmd.setDurability(safeStore, token.durability, homeKey, null);
                                            safeStore.progressLog().durable(txnId, cmd.maxUnseekables(), null);
                                        }).addCallback(commandStore.agent());
                                    }

                                    updateMax(token);
                                }
                            });

                            debugInvestigating = recover;
                        });
                    }
                }
            }
        }

        @Override
        public String toString()
        {
            return "{" + status + ',' + progress + '}';
        }
    }

    // exists only on home shard
    static class DisseminateState
    {
        enum DisseminateStatus { NotExecuted, Durable, Done }

        // TODO: thread safety (schedule on progress log executor)
        class CoordinateAwareness implements Callback<SimpleReply>
        {
            @Override
            public void onSuccess(Id from, SimpleReply reply)
            {
                notAwareOfDurability.remove(from);
                maybeDone();
            }

            @Override
            public void onFailure(Id from, Throwable failure)
            {
            }

            @Override
            public void onCallbackFailure(Id from, Throwable failure)
            {
            }
        }

        DisseminateStatus status = NotExecuted;
        Progress progress = NoneExpected;
        Set<Id> notAwareOfDurability;
        Set<Id> notPersisted;

        List<Runnable> whenReady;

        CoordinateAwareness investigating;

        private void whenReady(Node node, Command command, Runnable runnable)
        {
            if (notAwareOfDurability != null || maybeReady(node, command))
            {
                runnable.run();
            }
            else
            {
                if (whenReady == null)
                    whenReady = new ArrayList<>();
                whenReady.add(runnable);
            }
        }

        private void whenReady(Runnable runnable)
        {
            if (notAwareOfDurability != null)
            {
                runnable.run();
            }
            else
            {
                if (whenReady == null)
                    whenReady = new ArrayList<>();
                whenReady.add(runnable);
            }
        }

        // must know the epoch information, and have a valid Route
        private boolean maybeReady(Node node, Command command)
        {
            if (!command.status().hasBeen(Status.PreCommitted))
                return false;

            if (!isFullRoute(command.route()))
                return false;

            if (!node.topology().hasEpoch(command.executeAt().epoch))
                return false;

            Topologies topology = node.topology().preciseEpochs(command.route(), command.txnId().epoch, command.executeAt().epoch);
            notAwareOfDurability = topology.copyOfNodes();
            notPersisted = topology.copyOfNodes();
            if (whenReady != null)
            {
                whenReady.forEach(Runnable::run);
                whenReady = null;
            }

            return true;
        }

        private void maybeDone()
        {
            if (notAwareOfDurability.isEmpty())
            {
                status = DisseminateStatus.Done;
                progress = Done;
            }
        }

        void durableGlobal(Node node, Command command, @Nullable Set<Id> persistedOn)
        {
            if (status == DisseminateStatus.Done)
                return;

            status = DisseminateStatus.Durable;
            progress = Expected;
            if (persistedOn == null)
                return;

            whenReady(node, command, () -> {
                notPersisted.removeAll(persistedOn);
                notAwareOfDurability.removeAll(persistedOn);
                maybeDone();
            });
        }

        void durableLocal(Node node)
        {
            if (status == DisseminateStatus.Done)
                return;

            status = DisseminateStatus.Durable;
            progress = Expected;

            whenReady(() -> {
                notPersisted.remove(node.id());
                notAwareOfDurability.remove(node.id());
                maybeDone();
            });
        }

        void update(Node node, TxnId txnId, Command command)
        {
            switch (status)
            {
                default: throw new IllegalStateException();
                case NotExecuted:
                case Done:
                    return;
                case Durable:
            }

            if (notAwareOfDurability == null && !maybeReady(node, command))
                return;

            if (progress != NoProgress)
            {
                progress = advance(progress);
                return;
            }

            progress = Investigating;
            if (notAwareOfDurability.isEmpty())
            {
                // TODO: also track actual durability
                status = DisseminateStatus.Done;
                progress = Done;
                return;
            }

            FullRoute<?> route = Route.castToFullRoute(command.route());
            Timestamp executeAt = command.executeAt();
            investigating = new CoordinateAwareness();
            Topologies topologies = node.topology().preciseEpochs(route, txnId.epoch, executeAt.epoch);
            node.send(notAwareOfDurability, to -> new InformDurable(to, topologies, route, txnId, executeAt, Durable), investigating);
        }

        @Override
        public String toString()
        {
            return "{" + status + ',' + progress + '}';
        }
    }

    static class BlockingState
    {
        Known blockedUntil = Nothing;
        Progress progress = NoneExpected;

        Unseekables<?, ?> blockedOn;

        Object debugInvestigating;

        void recordBlocking(Known blockedUntil, Unseekables<?, ?> blockedOn)
        {
            Invariants.checkState(!blockedOn.isEmpty());
            if (this.blockedOn == null) this.blockedOn = blockedOn;
            else this.blockedOn = Unseekables.merge(this.blockedOn, (Unseekables)blockedOn);
            if (!blockedUntil.isSatisfiedBy(this.blockedUntil))
            {
                this.blockedUntil = this.blockedUntil.merge(blockedUntil);
                progress = Expected;
            }
        }

        void record(Known known)
        {
            if (blockedUntil.isSatisfiedBy(known))
                progress = NoneExpected;
        }

        void update(Node node, TxnId txnId, Command command)
        {
            if (progress != NoProgress)
            {
                progress = advance(progress);
                return;
            }

            if (command.has(blockedUntil))
            {
                progress = NoneExpected;
                return;
            }

            progress = Investigating;
            // first make sure we have enough information to obtain the command locally
            Timestamp executeAt = command.hasBeen(PreCommitted) ? command.executeAt() : null;
            long srcEpoch = (executeAt != null ? executeAt : txnId).epoch;
            // TODO: compute fromEpoch, the epoch we already have this txn replicated until
            long toEpoch = Math.max(srcEpoch, node.topology().epoch());
            Unseekables<?, ?> someKeys = unseekables(command);

            BiConsumer<Known, Throwable> callback = (success, fail) -> {
                if (progress != Investigating)
                    return;

                progress = Expected;
                if (fail == null)
                {
                    if (!success.isDefinitionKnown()) invalidate(node, txnId, someKeys);
                    else record(success);
                }
            };

            node.withEpoch(toEpoch, () -> {
                debugInvestigating = FetchData.fetch(blockedUntil, node, txnId, someKeys, executeAt, toEpoch, callback);
            });
        }

        private Unseekables<?, ?> unseekables(Command command)
        {
            return Unseekables.merge((Route)command.route(), blockedOn);
        }

        private void invalidate(Node node, TxnId txnId, Unseekables<?, ?> someKeys)
        {
            progress = Investigating;
            // TODO (RangeTxns): This should be a Routable, or we should guarantee it is safe to operate on any key in the range
            RoutingKey someKey = Route.isRoute(someKeys) ? (Route.castToRoute(someKeys)).homeKey() : someKeys.get(0).someIntersectingRoutingKey();
            someKeys = someKeys.with(someKey);
            debugInvestigating = Invalidate.invalidate(node, txnId, someKeys, (success, fail) -> {
                if (progress != Investigating)
                    return;

                progress = Expected;
                if (fail == null && success.asProgressToken().durability.isDurable())
                    progress = Done;
            });
        }

        public String toString()
        {
            return progress.toString();
        }
    }

    enum NonHomeState
    {
        Unsafe, StillUnsafe, Investigating, Safe
    }

    static class State
    {
        final TxnId txnId;
        final CommandStore commandStore;

        CoordinateState coordinateState;
        DisseminateState disseminateState;
        NonHomeState nonHomeState;
        BlockingState blockingState;

        State(TxnId txnId, CommandStore commandStore)
        {
            this.txnId = txnId;
            this.commandStore = commandStore;
        }

        void recordBlocking(TxnId txnId, Known waitingFor, Unseekables<?, ?> unseekables)
        {
            Invariants.checkArgument(txnId.equals(this.txnId));
            if (blockingState == null)
                blockingState = new BlockingState();
            blockingState.recordBlocking(waitingFor, unseekables);
        }

        void ensureAtLeast(NonHomeState ensureAtLeast)
        {
            if (nonHomeState == null || nonHomeState.compareTo(ensureAtLeast) < 0)
                nonHomeState = ensureAtLeast;
        }

        CoordinateState local()
        {
            if (coordinateState == null)
                coordinateState = new CoordinateState();
            return coordinateState;
        }

        DisseminateState global()
        {
            if (disseminateState == null)
                disseminateState = new DisseminateState();
            return disseminateState;
        }

        void ensureAtLeast(Command command, CoordinateStatus newStatus, Progress newProgress)
        {
            local().ensureAtLeast(command, newStatus, newProgress);
        }

        void ensureAtLeast(TxnId txnId, RoutingKey homeKey, CoordinateStatus newStatus, Progress newProgress)
        {
            local().ensureAtLeast(newStatus, newProgress);
        }

        void updateNonHome(Node node, Command command)
        {
            switch (nonHomeState)
            {
                default: throw new IllegalStateException();
                case Safe:
                case Investigating:
                    break;
                case Unsafe:
                    nonHomeState = StillUnsafe;
                    break;
                case StillUnsafe:
                    // make sure a quorum of the home shard is aware of the transaction, so we can rely on it to ensure progress
                    Future<Void> inform = inform(node, txnId, command.homeKey());
                    inform.addCallback((success, fail) -> {
                        if (nonHomeState == Safe)
                            return;

                        if (fail != null) nonHomeState = Unsafe;
                        else nonHomeState = Safe;
                    });
                    break;
            }
        }

        void update(Node node)
        {
            PreLoadContext context = contextFor(txnId);
            commandStore.execute(context, safeStore -> {
                Command command = safeStore.command(txnId);
                if (blockingState != null)
                    blockingState.update(node, txnId, command);

                if (coordinateState != null)
                    coordinateState.update(node, safeStore.commandStore(), txnId, command);

                if (disseminateState != null)
                    disseminateState.update(node, txnId, command);

                if (nonHomeState != null)
                    updateNonHome(node, command);
            }).addCallback(commandStore.agent());
        }

        @Override
        public String toString()
        {
            return coordinateState != null ? coordinateState.toString()
                                           : nonHomeState != null
                                       ? nonHomeState.toString()
                                       : blockingState.toString();
        }
    }

    final Node node;
    final List<Instance> instances = new CopyOnWriteArrayList<>();

    public SimpleProgressLog(Node node)
    {
        this.node = node;
        node.scheduler().recurring(this, 200L, TimeUnit.MILLISECONDS);
    }

    class Instance implements ProgressLog
    {
        final CommandStore commandStore;
        final Map<TxnId, State> stateMap = new HashMap<>();

        Instance(CommandStore commandStore)
        {
            this.commandStore = commandStore;
            instances.add(this);
        }

        State ensure(TxnId txnId)
        {
            return stateMap.computeIfAbsent(txnId, id -> new State(id, commandStore));
        }

        State ensure(TxnId txnId, State state)
        {
            return state != null ? state : ensure(txnId);
        }

        @Override
        public void unwitnessed(TxnId txnId, RoutingKey homeKey, ProgressShard shard)
        {
            if (shard.isHome())
                ensure(txnId).ensureAtLeast(txnId, homeKey, Uncommitted, Expected);
        }

        @Override
        public void preaccepted(Command command, ProgressShard shard)
        {
            Invariants.checkState(shard != Unsure);

            if (shard.isProgress())
            {
                State state = ensure(command.txnId());
                if (shard.isHome()) state.ensureAtLeast(command, Uncommitted, Expected);
                else state.ensureAtLeast(NonHomeState.Unsafe);
            }
        }

        State recordCommit(TxnId txnId)
        {
            State state = stateMap.get(txnId);
            if (state != null && state.blockingState != null)
                state.blockingState.record(SaveStatus.Committed.known);
            return state;
        }

        State recordApply(TxnId txnId)
        {
            State state = stateMap.get(txnId);
            if (state != null && state.blockingState != null)
                state.blockingState.record(SaveStatus.PreApplied.known);
            return state;
        }

        private void ensureSafeOrAtLeast(Command command, ProgressShard shard, CoordinateStatus newStatus, Progress newProgress)
        {
            Invariants.checkState(shard != Unsure);

            State state = null;
            assert newStatus.isAtMost(ReadyToExecute);
            if (newStatus.isAtLeast(CoordinateStatus.Committed))
                state = recordCommit(command.txnId());

            if (shard.isProgress())
            {
                state = ensure(command.txnId(), state);

                if (shard.isHome()) state.ensureAtLeast(command, newStatus, newProgress);
                else ensure(command.txnId()).ensureAtLeast(Safe);
            }
        }

        @Override
        public void accepted(Command command, ProgressShard shard)
        {
            ensureSafeOrAtLeast(command, shard, Uncommitted, Expected);
        }

        @Override
        public void committed(Command command, ProgressShard shard)
        {
            ensureSafeOrAtLeast(command, shard, CoordinateStatus.Committed, NoneExpected);
        }

        @Override
        public void readyToExecute(Command command, ProgressShard shard)
        {
            ensureSafeOrAtLeast(command, shard, CoordinateStatus.ReadyToExecute, Expected);
        }

        @Override
        public void executed(Command command, ProgressShard shard)
        {
            recordApply(command.txnId());
            // this is the home shard's state ONLY, so we don't know it is fully durable locally
            ensureSafeOrAtLeast(command, shard, CoordinateStatus.ReadyToExecute, Expected);
        }

        @Override
        public void invalidated(Command command, ProgressShard shard)
        {
            State state = recordApply(command.txnId());

            Invariants.checkState(shard == Home || state == null || state.coordinateState == null);

            // note: we permit Unsure here, so we check if we have any local home state
            if (shard.isProgress())
            {
                state = ensure(command.txnId(), state);

                if (shard.isHome()) state.ensureAtLeast(command, CoordinateStatus.Done, Done);
                else ensure(command.txnId()).ensureAtLeast(Safe);
            }
        }

        @Override
        public void durableLocal(TxnId txnId)
        {
            State state = ensure(txnId);
            state.global().durableLocal(node);
        }

        @Override
        public void durable(Command command, @Nullable Set<Id> persistedOn)
        {
            State state = ensure(command.txnId());
            if (!command.status().hasBeen(PreApplied))
                state.recordBlocking(command.txnId(), PreApplied.minKnown, command.maxUnseekables());
            state.local().durableGlobal();
            state.global().durableGlobal(node, command, persistedOn);
        }

        @Override
        public void durable(TxnId txnId, Unseekables<?, ?> unseekables, ProgressShard shard)
        {
            State state = ensure(txnId);
            // TODO: we can probably simplify things by requiring (empty) Apply messages to be sent also to the coordinating topology
            state.recordBlocking(txnId, PreApplied.minKnown, unseekables);
        }

        public void waiting(TxnId blockedBy, Known blockedUntil, Unseekables<?, ?> blockedOn)
        {
            // TODO (soon): forward to progress shard for processing (if known)
            // TODO (soon): if we are co-located with the home shard, don't need to do anything unless we're in a
            //              later topology that wasn't covered by its coordination
            ensure(blockedBy).recordBlocking(blockedBy, blockedUntil, blockedOn);
        }
    }

    @Override
    public void run()
    {
        for (Instance instance : instances)
        {
            // TODO: we want to be able to poll others about pending dependencies to check forward progress,
            //       as we don't know all dependencies locally (or perhaps any, at execution time) so we may
            //       begin expecting forward progress too early
            new ArrayList<>(instance.stateMap.values()).forEach(state -> {
                try
                {
                    state.update(node);
                }
                catch (Throwable t)
                {
                    node.agent().onUncaughtException(t);
                }
            });
        }
    }

    @Override
    public ProgressLog create(CommandStore commandStore)
    {
        return new Instance(commandStore);
    }
}
