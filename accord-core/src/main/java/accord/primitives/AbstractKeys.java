package accord.primitives;

import java.util.Arrays;
import java.util.Iterator;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import accord.api.RoutingKey;
import accord.utils.*;
import net.nicoulaj.compilecommand.annotations.Inline;

import static accord.primitives.Routable.Kind.Key;

@SuppressWarnings("rawtypes")
// TODO: check that foldl call-sites are inlined and optimised by HotSpot
public abstract class AbstractKeys<K extends RoutableKey, KS extends Routables<K, ?>> implements Iterable<K>, Routables<K, KS>
{
    final K[] keys;

    protected AbstractKeys(K[] keys)
    {
        this.keys = keys;
    }

    @Override
    public boolean equals(Object o)
    {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AbstractKeys that = (AbstractKeys) o;
        return Arrays.equals(keys, that.keys);
    }

    public final int indexOf(K key)
    {
        return Arrays.binarySearch(keys, key);
    }

    public final K get(int indexOf)
    {
        return keys[indexOf];
    }

    @Override
    public final Unseekable.Kind kindOfContents()
    {
        return Key;
    }

    public final boolean isEmpty()
    {
        return keys.length == 0;
    }

    public final int size()
    {
        return keys.length;
    }

    public final boolean contains(RoutableKey key)
    {
        return Arrays.binarySearch(keys, key) >= 0;
    }

    public final boolean containsAll(Routables<?, ?> keysOrRanges)
    {
        return keysOrRanges.size() == Routables.foldl(keysOrRanges, this, (i, k, p, v) -> v + 1, 0, 0, 0);
    }

    @Override
    public boolean intersects(AbstractRanges<?> ranges)
    {
        // TODO (now): make this final
        return ranges.intersects(this);
    }

    public final int findNext(K key, int startIndex)
    {
        return SortedArrays.exponentialSearch(keys, startIndex, keys.length, key);
    }

    @Override
    public final int findNext(int thisIndex, Range find, SortedArrays.Search search)
    {
        return SortedArrays.exponentialSearch(keys, thisIndex, size(), find, Range::compareTo, search);
    }

    @Override
    public final int findNext(int thisIndex, K find, SortedArrays.Search search)
    {
        return SortedArrays.exponentialSearch(keys, thisIndex, size(), find, RoutableKey::compareTo, search);
    }

    @Override
    public final long findNextIntersection(int thisIdx, AbstractRanges<?> that, int thatIdx)
    {
        return SortedArrays.findNextIntersectionWithMultipleMatches(this.keys, thisIdx, that.ranges, thatIdx, (RoutableKey k, Range r) -> -r.compareTo(k), Range::compareTo);
    }

    @Override
    public final long findNextIntersection(int thisIdx, AbstractKeys<?, ?> that, int thatIdx)
    {
        return SortedArrays.findNextIntersection(this.keys, thisIdx, that.keys, thatIdx, RoutableKey::compareTo);
    }

    @Override
    public final long findNextIntersection(int thisIndex, Routables<K, ?> with, int withIndex)
    {
        return findNextIntersection(thisIndex, (AbstractKeys<?, ?>) with, withIndex);
    }

    public Stream<K> stream()
    {
        return Stream.of(keys);
    }

    @Override
    public Iterator<K> iterator()
    {
        return new Iterator<K>()
        {
            int i = 0;
            @Override
            public boolean hasNext()
            {
                return i < keys.length;
            }

            @Override
            public K next()
            {
                return keys[i++];
            }
        };
    }

    @Override
    public String toString()
    {
        return stream().map(Object::toString).collect(Collectors.joining(",", "[", "]"));
    }

    // TODO (now): accept cached buffers
    protected K[] slice(Ranges ranges, IntFunction<K[]> factory)
    {
        return SortedArrays.sliceWithMultipleMatches(keys, ranges.ranges, factory, (k, r) -> -r.compareTo(k), Range::compareTo);
    }

    public boolean any(Ranges ranges, Predicate<? super K> predicate)
    {
        return 1 == foldl(ranges, (i1, key, i2, i3) -> predicate.test(key) ? 1 : 0, 0, 0, 1);
    }

    public boolean any(Predicate<? super K> predicate)
    {
        return 1 == foldl((i, key, p, v) -> predicate.test(key) ? 1 : 0, 0, 0, 1);
    }

    public boolean none(Predicate<? super K> predicate)
    {
        return !any(predicate);
    }

    /**
     * Count the number of keys matching the predicate and intersecting with the given ranges.
     * If terminateAfter is greater than 0, the method will return once terminateAfter matches are encountered
     */
    @Inline
    public final <V> V foldl(Ranges rs, IndexedFold<? super K, V> fold, V accumulator)
    {
        return Routables.foldl(this, rs, fold, accumulator);
    }

    /**
     * Count the number of keys matching the predicate and intersecting with the given ranges.
     * If terminateAfter is greater than 0, the method will return once terminateAfter matches are encountered
     */
    @Inline
    public final void forEach(Ranges rs, Consumer<? super K> forEach)
    {
        Routables.foldl(this, rs, (i, k, consumer) -> { consumer.accept(k); return consumer; }, forEach);
    }

    @Inline
    public final long foldl(Ranges rs, IndexedFoldToLong<? super K> fold, long param, long initialValue, long terminalValue)
    {
        return Routables.foldl(this, rs, fold, param, initialValue, terminalValue);
    }

    @Inline
    public final long foldl(IndexedFoldToLong<? super K> fold, long param, long initialValue, long terminalValue)
    {
        for (int i = 0; i < keys.length; i++)
        {
            initialValue = fold.apply(i, keys[i], param, initialValue);
            if (terminalValue == initialValue)
                return initialValue;
        }
        return initialValue;
    }

    public final FullKeyRoute toRoute(RoutingKey homeKey)
    {
        if (isEmpty())
            return new FullKeyRoute(homeKey, new RoutingKey[] { homeKey });

        RoutingKey[] result = toRoutingKeysArray(homeKey);
        int pos = Arrays.binarySearch(result, homeKey);
        return new FullKeyRoute(result[pos], result);
    }

    protected RoutingKey[] toRoutingKeysArray(RoutingKey withKey)
    {
        RoutingKey[] result;
        int resultCount;
        int insertPos = Arrays.binarySearch(keys, withKey);
        if (insertPos < 0)
            insertPos = -1 - insertPos;

        if (insertPos < keys.length && keys[insertPos].toUnseekable().equals(withKey))
        {
            result = new RoutingKey[keys.length];
            resultCount = copyToRoutingKeys(keys, 0, result, 0, keys.length);
        }
        else
        {
            result = new RoutingKey[1 + keys.length];
            resultCount = copyToRoutingKeys(keys, 0, result, 0, insertPos);
            if (resultCount == 0 || !withKey.equals(result[resultCount - 1]))
                result[resultCount++] = withKey;
            resultCount += copyToRoutingKeys(keys, insertPos, result, resultCount, keys.length - insertPos);
        }

        if (resultCount < result.length)
            result = Arrays.copyOf(result, resultCount);

        return result;
    }

    public final RoutingKeys toUnseekables()
    {
        return toUnseekables(array -> array.length == 0 ? RoutingKeys.EMPTY : new RoutingKeys(array));
    }

    private <R> R toUnseekables(Function<RoutingKey[], R> constructor)
    {
        if (isEmpty())
            constructor.apply(RoutingKeys.EMPTY.keys);

        RoutingKey[] result = new RoutingKey[keys.length];
        int resultCount = copyToRoutingKeys(keys, 0, result, 0, keys.length);
        if (resultCount < result.length)
            result = Arrays.copyOf(result, resultCount);
        return constructor.apply(result);
    }

    private static <K extends RoutableKey> int copyToRoutingKeys(K[] src, int srcPos, RoutingKey[] trg, int trgPos, int count)
    {
        if (count == 0)
            return 0;

        int srcEnd = srcPos + count;
        int trgStart = trgPos;
        if (trgPos == 0)
            trg[trgPos++] = src[srcPos++].toUnseekable();

        while (srcPos < srcEnd)
        {
            RoutingKey next = src[srcPos++].toUnseekable();
            if (!next.equals(trg[trgPos - 1]))
                trg[trgPos++] = next;
        }

        return trgPos - trgStart;
    }

    static <T extends RoutableKey> T[] sort(T[] keys)
    {
        Arrays.sort(keys);
        return keys;
    }

    @Override
    public int hashCode()
    {
        return Arrays.hashCode(keys);
    }

}
