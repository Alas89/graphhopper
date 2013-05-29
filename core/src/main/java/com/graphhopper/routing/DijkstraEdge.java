/*
 *  Licensed to Peter Karich under one or more contributor license 
 *  agreements. See the NOTICE file distributed with this work for 
 *  additional information regarding copyright ownership.
 * 
 *  Peter Karich licenses this file to you under the Apache License, 
 *  Version 2.0 (the "License"); you may not use this file except 
 *  in compliance with the License. You may obtain a copy of the 
 *  License at
 * 
 *       http://www.apache.org/licenses/LICENSE-2.0
 * 
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.graphhopper.routing;

import gnu.trove.map.TIntObjectMap;
import gnu.trove.map.hash.TIntObjectHashMap;

import java.util.PriorityQueue;

import com.graphhopper.routing.util.EdgePropertyEncoder;
import com.graphhopper.storage.EdgeEntry;
import com.graphhopper.storage.Graph;
import com.graphhopper.util.EdgeIterator;

/**
 * An edge-based version of Dijkstras Algorithms. End link costs will be 
 * stored for each edge instead of for each node. This is necessary 
 * when considering turn costs, but will be around three times slower than classic Dijkstra.
 *
 * @see http://www.easts.info/on-line/journal_06/1426.pdf
 *
 * @author Karl Hübner
 */
public class DijkstraEdge extends AbstractRoutingAlgorithm {

    protected TIntObjectMap<EdgeEntry> map = new TIntObjectHashMap<EdgeEntry>();
    protected PriorityQueue<EdgeEntry> heap = new PriorityQueue<EdgeEntry>();
    protected boolean alreadyRun;
    protected int visitedNodes;

    public DijkstraEdge(Graph graph, EdgePropertyEncoder encoder) {
        super(graph, encoder);
    }

    @Override
    public Path calcPath(int from, int to) {
        if (alreadyRun)
            throw new IllegalStateException("Create a new instance per call");
        alreadyRun = true;
        EdgeEntry fromEdge = new EdgeEntry(EdgeIterator.NO_EDGE, from, 0d);
        EdgeEntry currEdge = calcEdgeEntry(fromEdge, to);
        if (currEdge == null || currEdge.endNode != to)
            return new Path(graph, flagEncoder);

        return extractPath(currEdge);
    }
   
    public EdgeEntry calcEdgeEntry(EdgeEntry currEdge, int to) {
        while (true) {
            visitedNodes++;
            if (finished(currEdge, to))
                break;

            int neighborNode = currEdge.endNode;
            EdgeIterator iter = neighbors(neighborNode);
            while (iter.next()) {
                if (!accept(iter) || // we don't want to traverse the same edge we just found before
                        (currEdge.edge != EdgeIterator.NO_EDGE && iter.edge() == currEdge.edge))
                    continue;

                if((iter.edge() & 0x80000000) == 0x80000000){
                    //since we need to distinguish between backward and forward direction we only can accept 2147483647 edges 
                    throw new IllegalStateException("graph has too many edges :(");
                }

                //we need to distinguish between backward and forward direction when storing end weights
                int key = iter.edge() | directionFlag(iter);

                int tmpNode = iter.adjNode();
                double tmpWeight = weightCalc.getWeight(iter.distance(), iter.flags()) + currEdge.weight + turnCostCalc.getTurnCosts(neighborNode, currEdge.edge, iter.edge());
                EdgeEntry nEdge = map.get(key);
                if (nEdge == null) {
                    nEdge = new EdgeEntry(iter.edge(), tmpNode, tmpWeight);
                    nEdge.parent = currEdge;
                    map.put(key, nEdge);
                    heap.add(nEdge);
                } else if (nEdge.weight > tmpWeight) {
                    heap.remove(nEdge);
                    nEdge.edge = iter.edge();
                    nEdge.weight = tmpWeight;
                    nEdge.parent = currEdge;
                    heap.add(nEdge);
                }

                updateShortest(nEdge, neighborNode);
            }

            if (heap.isEmpty())
                return null;
            currEdge = heap.poll();
            if (currEdge == null)
                throw new AssertionError("cannot happen?");
        }
        return currEdge;
    }

    private int directionFlag(EdgeIterator iter) {
        if(iter.baseNode() > iter.adjNode()){
            return 0x80000000;    
        }
        return 0;
    }

    protected boolean finished(EdgeEntry currEdge, int to) {
        return currEdge.endNode == to;
    }

    public Path extractPath(EdgeEntry goalEdge) {
        return new Path(graph, flagEncoder).edgeEntry(goalEdge).extract();
    }

    @Override public String name() {
        return "edge-based dijkstra";
    }

    @Override
    public int visitedNodes() {
        return visitedNodes;
    }
}
