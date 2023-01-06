package accord.primitives;

import accord.utils.Invariants;

import javax.annotation.Nullable;

public class PartialDeps extends Deps
{
    public static final PartialDeps NONE = new PartialDeps(Ranges.EMPTY, KeyDeps.NONE, RangeDeps.NONE);

    public static OrderedBuilder orderedBuilder(Ranges covering, boolean hasOrderedTxnId)
    {
        return new OrderedBuilder(covering, hasOrderedTxnId);
    }
    public static class OrderedBuilder extends AbstractOrderedBuilder<PartialDeps>
    {
        final Ranges covering;
        public OrderedBuilder(Ranges covering, boolean hasOrderedTxnId)
        {
            super(hasOrderedTxnId);
            this.covering = covering;
        }

        public PartialDeps build()
        {
            return new PartialDeps(covering, keyBuilder.build(), rangeBuilder == null ? RangeDeps.NONE : rangeBuilder.build());
        }
    }

    public final Ranges covering;

    public PartialDeps(Ranges covering, KeyDeps keyDeps, RangeDeps rangeDeps)
    {
        super(keyDeps, rangeDeps);
        this.covering = covering;
        Invariants.checkState(covering.containsAll(keyDeps.keys));
        Invariants.checkState(rangeDeps.isCoveredBy(covering));
    }

    public boolean covers(Unseekables<?, ?> keysOrRanges)
    {
        return covering.containsAll(keysOrRanges);
    }

    public PartialDeps with(PartialDeps that)
    {
        Invariants.checkArgument((this.rangeDeps == null) == (that.rangeDeps == null));
        return new PartialDeps(that.covering.union(this.covering),
                this.keyDeps.with(that.keyDeps),
                this.rangeDeps == null ? null : this.rangeDeps.with(that.rangeDeps));
    }

    public Deps reconstitute(FullRoute<?> route)
    {
        if (!covers(route))
            throw new IllegalArgumentException();
        return new Deps(keyDeps, rangeDeps);
    }

    // PartialRoute<?>might cover a wider set of ranges, some of which may have no involved keys
    public PartialDeps reconstitutePartial(PartialRoute<?> route)
    {
        if (!covers(route))
            throw new IllegalArgumentException();

        if (covers(route.covering()))
            return this;

        return new PartialDeps(route.covering(), keyDeps, rangeDeps);
    }

    @Override
    public boolean equals(Object that)
    {
        return this == that || (that instanceof PartialDeps && equals((PartialDeps) that));
    }

    @Override
    public boolean equals(Deps that)
    {
        return that instanceof PartialDeps && equals((PartialDeps) that);
    }

    public boolean equals(PartialDeps that)
    {
        return this.covering.equals(that.covering) && super.equals(that);
    }

    @Override
    public String toString()
    {
        return covering + ":" + super.toString();
    }
}
