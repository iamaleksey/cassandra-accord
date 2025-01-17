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

package accord.coordinate;

import accord.impl.mock.EpochSync;
import accord.impl.mock.MockCluster;
import accord.impl.mock.MockConfigurationService;
import accord.impl.mock.RecordingMessageSink;
import accord.local.Command;
import accord.local.Node;
import accord.local.Status;
import accord.messages.Accept;
import accord.primitives.Range;
import accord.topology.Topology;
import accord.primitives.Keys;
import accord.primitives.Txn;
import accord.primitives.TxnId;
import accord.utils.EpochFunction;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Set;
import java.util.stream.Collectors;

import static accord.Utils.*;
import static accord.impl.IntKey.keys;
import static accord.impl.IntKey.range;
import static accord.local.PreLoadContext.empty;

public class TopologyChangeTest
{
    private static TxnId coordinate(Node node, Keys keys) throws Throwable
    {
        TxnId txnId = node.nextTxnId();
        Txn txn = writeTxn(keys);
        node.coordinate(txnId, txn).get();
        return txnId;
    }

    @Test
    void disjointElectorate() throws Throwable
    {
        Keys keys = keys(150);
        Range range = range(100, 200);
        Topology topology1 = topology(1, shard(range, idList(1, 2, 3), idSet(1, 2)));
        Topology topology2 = topology(2, shard(range, idList(4, 5, 6), idSet(4, 5)));
        EpochFunction<MockConfigurationService> fetchTopology = (epoch, service) -> {
            Assertions.assertEquals(2, epoch);
            service.reportTopology(topology2);
        };
        try (MockCluster cluster = MockCluster.builder()
                                              .nodes(6)
                                              .topology(topology1)
                                              .setOnFetchTopology(fetchTopology)
                                              .build())
        {
            Node node1 = cluster.get(1);
            TxnId txnId1 = node1.nextTxnId();
            Txn txn1 = writeTxn(keys);
            node1.coordinate(txnId1, txn1).get();
            node1.commandStores().forEach(empty(), keys, 1, 1, commands -> {
                Command command = commands.command(txnId1);
                Assertions.assertTrue(command.partialDeps().isEmpty());
            }).awaitUninterruptibly();

            cluster.configServices(4, 5, 6).forEach(config -> config.reportTopology(topology2));

            Node node4 = cluster.get(4);
            TxnId txnId2 = node4.nextTxnId();
            Txn txn2 = writeTxn(keys);
            node4.coordinate(txnId2, txn2).get();

            // new nodes should have the previous epochs operation as a dependency
            cluster.nodes(4, 5, 6).forEach(node -> {
                node.commandStores().forEach(empty(), keys, 2, 2, commands -> {
                    Command command = commands.command(txnId2);
                    Assertions.assertTrue(command.partialDeps().contains(txnId1));
                }).awaitUninterruptibly();
            });

            // ...and participated in consensus
            cluster.nodes(1, 2, 3).forEach(node -> {
                node.commandStores().forEach(empty(), keys, 1, 1, commands -> {
                    Command command = commands.command(txnId2);
                    Assertions.assertTrue(command.hasBeen(Status.Accepted));
                }).awaitUninterruptibly();
            });
        }
    }
}
