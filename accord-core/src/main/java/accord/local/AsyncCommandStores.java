package accord.local;

import accord.api.Agent;
import accord.api.DataStore;
import accord.api.ProgressLog;
import accord.primitives.Routables;
import accord.utils.MapReduce;
import accord.utils.MapReduceConsume;
import accord.utils.ReducingFuture;
import org.apache.cassandra.utils.concurrent.Future;
import org.apache.cassandra.utils.concurrent.ImmediateFuture;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

public class AsyncCommandStores extends CommandStores<CommandStore>
{
    static class AsyncMapReduceAdapter<O> implements MapReduceAdapter<CommandStore, Future<O>, List<Future<O>>, O>
    {
        private static final AsyncMapReduceAdapter INSTANCE = new AsyncMapReduceAdapter<>();
        public static <O> AsyncMapReduceAdapter<O> instance() { return INSTANCE; }

        @Override
        public List<Future<O>> allocate()
        {
            return new ArrayList<>();
        }

        @Override
        public Future<O> apply(MapReduce<? super SafeCommandStore, O> map, CommandStore commandStore, PreLoadContext context)
        {
            return commandStore.submit(context, map);
        }

        @Override
        public List<Future<O>> reduce(MapReduce<? super SafeCommandStore, O> reduce, List<Future<O>> futures, Future<O> next)
        {
            futures.add(next);
            return futures;
        }

        @Override
        public void consume(MapReduceConsume<?, O> reduceAndConsume, Future<O> future)
        {
            future.addCallback(reduceAndConsume);
        }

        @Override
        public Future<O> reduce(MapReduce<?, O> reduce, List<Future<O>> futures)
        {
            if (futures.isEmpty())
                return ImmediateFuture.success(null);
            return ReducingFuture.reduce(futures, reduce::reduce);
        }
    }

    public AsyncCommandStores(int num, NodeTimeService time, Agent agent, DataStore store, ProgressLog.Factory progressLogFactory, CommandStore.Factory shardFactory)
    {
        super(num, time, agent, store, progressLogFactory, shardFactory);
    }

    @Override
    public <O> void mapReduceConsume(PreLoadContext context, Routables<?, ?> keys, long minEpoch, long maxEpoch, MapReduceConsume<? super SafeCommandStore, O> mapReduceConsume)
    {
        mapReduceConsume(context, keys, minEpoch, maxEpoch, mapReduceConsume, AsyncMapReduceAdapter.INSTANCE);
    }

    @Override
    public <O> void mapReduceConsume(PreLoadContext context, IntStream commandStoreIds, MapReduceConsume<? super SafeCommandStore, O> mapReduceConsume)
    {
        mapReduceConsume(context, commandStoreIds, mapReduceConsume, AsyncMapReduceAdapter.INSTANCE);
    }
}
