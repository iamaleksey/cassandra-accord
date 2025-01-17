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

package accord.utils;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

import static accord.utils.Property.qt;

public class GenTest {
    @Test
    public void randomNextInt()
    {
        qt().forAll(Gens.random()).check(r -> {
            int value = r.nextInt(1, 10);
            if (value < 1)
                throw new AssertionError(value + " is less than 1");
            if (value >= 10)
                throw new AssertionError(value + " is >= " + 10);
        });
    }

    @Test
    public void listUnique()
    {
        qt().withExamples(1000).forAll(Gens.lists(Gens.longs().all()).unique().ofSize(1000)).check(list -> {
            SortedSet<Long> unique = new TreeSet<>(list);
            Assertions.assertEquals(unique.size(), list.size(), () -> {
                Collections.sort(list);
                return "Expected " + list + " to be equal to " + unique + " but had different sizes";
            });
        });
    }

    @Test
    public void arrayUnique()
    {
        qt().withExamples(1000).forAll(Gens.arrays(Long.class, Gens.longs().all()).unique().ofSize(1000)).check(array -> {
            List<Long> list = Arrays.asList(array);
            SortedSet<Long> unique = new TreeSet<>(list);
            Assertions.assertEquals(unique.size(), list.size(), () -> {
                Collections.sort(list);
                return "Expected " + list + " to be equal to " + unique + " but had different sizes";
            });
        });
    }

    @Test
    public void intArrayUnique()
    {
        qt().withExamples(1000).forAll(Gens.arrays(Gens.ints().all()).unique().ofSize(1000)).check(array -> {
            List<Integer> list = new ArrayList<>(array.length);
            IntStream.of(array).forEach(list::add);
            SortedSet<Integer> unique = new TreeSet<>(list);
            Assertions.assertEquals(unique.size(), list.size(), () -> {
                Collections.sort(list);
                return "Expected " + list + " to be equal to " + unique + " but had different sizes";
            });
        });
    }

    @Test
    public void longArrayUnique()
    {
        qt().withExamples(1000).forAll(Gens.arrays(Gens.longs().all()).unique().ofSize(1000)).check(array -> {
            List<Long> list = new ArrayList<>(array.length);
            LongStream.of(array).forEach(list::add);
            SortedSet<Long> unique = new TreeSet<>(list);
            Assertions.assertEquals(unique.size(), list.size(), () -> {
                Collections.sort(list);
                return "Expected " + list + " to be equal to " + unique + " but had different sizes";
            });
        });
    }
}
