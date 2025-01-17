package accord.utils;

import java.util.function.Function;

public interface MapReduce<I, O> extends Function<I, O>
{
    // TODO (soon): ensure mutual exclusivity when calling each of these methods
    O apply(I in);
    O reduce(O o1, O o2);
}
