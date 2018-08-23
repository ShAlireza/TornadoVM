/*
 * This file is part of Tornado: A heterogeneous programming framework: 
 * https://github.com/beehive-lab/tornado
 *
 * Copyright (c) 2013-2018, APT Group, School of Computer Science,
 * The University of Manchester. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Authors: Juan Fumero
 *
 */
package uk.ac.manchester.tornado.examples.compute;

import java.io.IOException;
import java.util.Arrays;
import java.util.Random;
import java.util.stream.IntStream;

import uk.ac.manchester.tornado.api.TaskSchedule;
import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.common.GenericDevice;
import uk.ac.manchester.tornado.api.runtime.TornadoRuntime;

/**
 * Parallel Implementation of the BFS: this is based on the Marawacc compiler
 * framework.
 * 
 * @author Juan Fumero
 *
 */
public class BFS {

    private static final boolean BIDIRECTIONAL = false;
    private static final boolean PRINT_SOLUTION = false;

    int[] vertices;
    int[] adjacencyMatrix;
    int[] modify;
    int[] currentDepth;

    public static final boolean SAMPLE = false;

    /**
     * Set to one the connection between node from and node to into the
     * adjacency matrix.
     *
     * @param from
     * @param to
     * @param graph
     * @param N
     */
    public static void connect(int from, int to, int[] graph, int N) {
        if (from != to && (graph[from * N + to] == 0)) {
            graph[from * N + to] = 1;
        }
    }

    /**
     * It builds a simple graph just for showing the example.
     *
     * @param adjacencyMatrix
     * @param numNodes
     */
    public static void initilizeAdjacencyMatrixSimpleGraph(int[] adjacencyMatrix, int numNodes) {
        Arrays.fill(adjacencyMatrix, 0);
        connect(0, 1, adjacencyMatrix, numNodes);
        connect(0, 4, adjacencyMatrix, numNodes);
        connect(1, 2, adjacencyMatrix, numNodes);
        connect(2, 3, adjacencyMatrix, numNodes);
        connect(2, 4, adjacencyMatrix, numNodes);
        connect(3, 4, adjacencyMatrix, numNodes);
    }

    private static int[] generateIntRandomArray(int numNodes) {
        Random r = new Random();
        int bound = r.nextInt(numNodes);
        IntStream streamArray = r.ints(bound, 0, numNodes);
        int[] array = streamArray.toArray();
        return array;
    }

    public static void generateRandomGraph(int[] adjacencyMatrix, int numNodes, int root) {
        Random r = new Random();
        int bound = r.nextInt(numNodes);
        IntStream fromStream = r.ints(bound, 0, numNodes);
        int[] f = fromStream.toArray();
        for (int k = 0; k < f.length; k++) {

            int from = f[k];
            if (k == 0) {
                from = root;
            }

            int[] toArray = generateIntRandomArray(numNodes);

            for (int i = 0; i < toArray.length; i++) {
                connect(from, toArray[i], adjacencyMatrix, numNodes);
            }
        }
    }

    private static void initializeVertices(int numNodes, int[] vertices, int root) {
        for (@Parallel int i = 0; i < numNodes; i++) {
            if (i == root) {
                vertices[i] = 0;
            } else {
                vertices[i] = -1;
            }
        }
    }

    private static void runBFS(int[] vertices, int[] adjacencyMatrix, int numNodes, int[] h_true, int[] currentDepth) {
        for (@Parallel int from = 0; from < numNodes; from++) {
            for (@Parallel int to = 0; to < numNodes; to++) {
                int elementAccess = from * numNodes + to;

                if (adjacencyMatrix[elementAccess] == 1) {
                    int dfirst = vertices[from];
                    int dsecond = vertices[to];
                    if ((currentDepth[0] == dfirst) && (dsecond == -1)) {
                        vertices[to] = dfirst + 1;
                        h_true[0] = 0;
                    }

                    if (BIDIRECTIONAL) {
                        if ((currentDepth[0] == dsecond) && (dfirst == -1)) {
                            vertices[from] = dsecond + 1;
                            h_true[0] = 0;
                        }
                    }
                }
            }
        }
    }

    public void tornadoBFS(int rootNode, int numNodes) throws IOException {

        vertices = new int[numNodes];
        adjacencyMatrix = new int[numNodes * numNodes];

        if (SAMPLE) {
            initilizeAdjacencyMatrixSimpleGraph(adjacencyMatrix, numNodes);
        } else {
            generateRandomGraph(adjacencyMatrix, numNodes, rootNode);
        }

        // Step 1: vertices initialisation
        initializeVertices(numNodes, vertices, rootNode);
        TaskSchedule s0 = new TaskSchedule("s0");
        s0.task("t0", BFS::initializeVertices, numNodes, vertices, rootNode);
        s0.streamOut(vertices).execute();

        modify = new int[] { 1 };
        Arrays.fill(modify, 1);

        currentDepth = new int[] { 0 };

        GenericDevice device = TornadoRuntime.getTornadoRuntime().getDefaultDevice();
        TaskSchedule s1 = new TaskSchedule("s1");
        s1.streamIn(vertices, adjacencyMatrix, modify, currentDepth).mapAllTo(device);
        s1.task("t1", BFS::runBFS, vertices, adjacencyMatrix, numNodes, modify, currentDepth);
        s1.streamOut(vertices, modify);

        boolean done = false;

        while (!done) {
            // 2. Parallel BFS
            boolean allDone = true;
            System.out.println("Current Depth: " + currentDepth[0]);
            // runBFS(vertices, adjacencyMatrix, numNodes, modify,
            // currentDepth);
            s1.execute();
            currentDepth[0]++;
            for (int i = 0; i < modify.length; i++) {
                if (modify[i] == 0) {
                    allDone &= false;
                    break;
                }
            }

            if (allDone) {
                done = true;
            }
            Arrays.fill(modify, 1);
        }

        if (PRINT_SOLUTION) {
            System.out.println("Solution: " + Arrays.toString(vertices));
        }
    }

    public static void main(String[] args) throws IOException {
        int size = 10000;
        if (SAMPLE) {
            size = 5;
        }
        new BFS().tornadoBFS(0, size);
    }

}
