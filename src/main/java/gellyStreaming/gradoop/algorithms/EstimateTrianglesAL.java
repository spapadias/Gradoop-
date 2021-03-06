package gellyStreaming.gradoop.algorithms;

import com.google.common.util.concurrent.AtomicDouble;
import gellyStreaming.gradoop.util.GradoopIdUtil;
import gellyStreaming.gradoop.model.QueryState;
import gellyStreaming.gradoop.partitioner.FennelPartitioning;
import org.apache.flink.api.common.JobID;
import org.apache.flink.api.common.state.MapState;
import org.apache.flink.api.java.tuple.Tuple2;
import org.gradoop.common.model.impl.id.GradoopId;
import org.gradoop.temporal.model.impl.pojo.TemporalEdge;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public class EstimateTrianglesAL implements Algorithm<String, MapState<Long, HashMap<GradoopId, HashMap<GradoopId, TemporalEdge>>>> {

    // Granularity of retrieval: getting the vertexID & all its neighbours from remote partition.
    private final int QSbatchsize;
    private final boolean caching;
    private final long timeToRun;
    private final int numberOfRuns;
    private final boolean withQS;

    public EstimateTrianglesAL(int QSbatchsize, boolean caching, long timeToRun, int numberOfRuns, boolean withQS) {
        this.QSbatchsize = QSbatchsize;
        this.caching = caching;
        this.timeToRun = timeToRun;
        this.numberOfRuns = numberOfRuns;
        this.withQS = withQS;
    }

    @Override
    public String doAlgorithm(MapState<Long, HashMap<GradoopId, HashMap<GradoopId, TemporalEdge>>> localState,
                              QueryState QS, Integer localKey, Integer[] allKeys, long from, long maxValidTo) {
        if (!QS.isInitilized()) {
            System.out.println("No QS");
        }
        HashMap<GradoopId, LinkedList<GradoopId>> QSqueue = new HashMap<>();
        HashMap<GradoopId, HashSet<GradoopId>> cache = new HashMap<>();
        AtomicInteger QSqueueSize = new AtomicInteger(0);

        // Retrieve local state if not given, which happens in fully decoupled mode
        int tries1 = 0;
        while (localState == null && tries1 < 10) {
            try {
                localState = QS.getALState(localKey);
            } catch (ConcurrentModificationException e) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ignored) { }
                tries1++;
                if (tries1 >= 10) {
                    System.out.println("Error retrieving state. " + e);
                }
            } catch (Exception e) {
                tries1++;
                if (tries1 >= 10) {
                    System.out.println("Error retrieving state. " + e);
                }
            }
        }

        // Put local state with relevant timestamp in local adjacency list.
        assert localState != null;
        HashMap<GradoopId, HashSet<GradoopId>> localAdjacencyList = new HashMap<>();

        try {
            for (long timestamp : localState.keys()) {
                if (timestamp >= from && timestamp <= maxValidTo) {
                    for (GradoopId src : localState.get(timestamp).keySet()) {
                        if (!localAdjacencyList.containsKey(src)) {
                            localAdjacencyList.put(src, new HashSet<>());
                        }
                        localAdjacencyList.get(src).addAll(localState.get(timestamp).get(src).keySet());
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        // We take the average of the lambdas at the end, so we keep a sum and a counter.
        AtomicInteger lambdasCount = new AtomicInteger(0);
        AtomicDouble lambdas = new AtomicDouble(0);

        /*
        // When querying the remote states we also retrieve their total number of vertices
        Integer[] verticesInPartitions = new Integer[allKeys.length];
        for (int i = 0; i < allKeys.length; i++) {
            if (allKeys[i].equals(localKey)) {
                verticesInPartitions[i] = localAdjacencyList.keySet().size();
            } else {
                verticesInPartitions[i] = -1;
            }
        }

         */

        GradoopId[] vertexIds = localAdjacencyList.keySet().toArray(GradoopId[]::new);
        int numberLocalVertices = vertexIds.length;
        String output = null;
        Random random = new Random();


        // Normally this is 1, but to get a graph of improving results we can run it multiple times with small
        // timeperiods as timeToRun.
        for (int i = 0; i < numberOfRuns; i++){
            long runUntil = System.currentTimeMillis() + timeToRun;

            while (System.currentTimeMillis() < runUntil) {
                // Get random vertex id 1.
                GradoopId id1 = vertexIds[random.nextInt(numberLocalVertices)];

                Set<GradoopId> neighboursVertex1 = localAdjacencyList.get(id1);
                int degree1 = neighboursVertex1.size();

                // From id1's neighbours, gets random vertex id 2
                GradoopId id2 = neighboursVertex1.toArray(GradoopId[]::new)[random.nextInt(degree1)];

                // If id2 is in local state we can calculate lambda right away.
                if (localAdjacencyList.containsKey(id2)) {
                    Set<GradoopId> neighboursVertex2 = localAdjacencyList.get(id2);
                    int degree2 = neighboursVertex2.size();
                    Set<GradoopId> intersection = neighboursVertex2.stream()
                            .filter(neighboursVertex1::contains)
                            .collect(Collectors.toSet());
                    double lambda = (intersection.size() * (degree1 * degree2)) / (3. * (degree1 + degree2));
                    lambdas.getAndAdd(lambda);
                    lambdasCount.getAndIncrement();
                }
                // If id2 has been previously retrieved from a remote partition and stored in cache
                // we can calculate lambda right away.
                else if (cache.containsKey(id2)) {
                    Set<GradoopId> neighboursVertex2 = cache.get(id2);
                    int degree2 = neighboursVertex2.size();
                    Set<GradoopId> intersection = neighboursVertex2.stream()
                            .filter(neighboursVertex1::contains)
                            .collect(Collectors.toSet());
                    double lambda = (intersection.size() * (degree1 * degree2)) / (3. * (degree1 + degree2));
                    lambdas.getAndAdd(lambda);
                    lambdasCount.getAndIncrement();
                }
                // If id2 and its neighbours need to be retrieved from a remote partition, we place
                // this request in the queue.
                else if (withQS) {
                    if (!QSqueue.containsKey(id2)) {
                        QSqueue.put(id2, new LinkedList<>());
                    }
                    QSqueue.get(id2).add(id1);
                    QSqueueSize.getAndIncrement();
                }
                // If we choose not to use QS and only estimate the triangle based on the local information.
                else {
                    // What to do when not using QS and finding no lambdas.
                    //lambdasCount.getAndIncrement();
                }
                // When queue reaches max size, we start querying the other remote partitions until we
                // have found all vertices in queue, or until we've visited each remote partition and
                // none of them had the vertexID (can happen in windowed mode).
                if (QSqueueSize.get() >= QSbatchsize ){//&& System.currentTimeMillis() < runUntil) {
                    for (int partition : allKeys) {
                        if (partition != localKey) {

                            int indexPartition = -1;
                            for (int j = 0; j < allKeys.length; j++) {
                                if (allKeys[j] == partition) {
                                    indexPartition = j;
                                }
                            }
                            GradoopId[] toQuery = QSqueue.keySet().toArray(GradoopId[]::new);
                            int tries = 0;
                            while (tries < 10) {
                                try {
                                    HashMap<GradoopId, HashMap<GradoopId, TemporalEdge>> retrieved;
                                    // If we hadn't retrieved the number of vertices in the remote partition yet,
                                    // we combine this query with the state request.
                                    /*
                                    if (verticesInPartitions[indexPartition] == -1) {
                                        Tuple2<HashMap<GradoopId, HashMap<GradoopId, TemporalEdge>>, Integer> temp =
                                                QS.getALVerticesFromToPlusTotal(partition, toQuery, from, maxValidTo);
                                        retrieved = temp.f0;
                                        verticesInPartitions[indexPartition] = temp.f1;
                                    } else {

                                     */
                                        retrieved =
                                                QS.getALVerticesFromTo(partition, toQuery, from, maxValidTo);
                                    //}
                                    for (GradoopId id3 : retrieved.keySet()) {
                                        Set<GradoopId> neighbours3 = retrieved.get(id3).keySet();
                                        // Save retrieved vertices and their neighbors in cache.
                                        if (caching) {
                                            if (!cache.containsKey(id3)) {
                                                cache.put(id3, new HashSet<>());
                                            }
                                            cache.get(id3).addAll(neighbours3);
                                        }
                                        int degree3 = neighbours3.size();
                                        // Check neighbour between the 2 previously randomly chosen vertexids.
                                        for (GradoopId id4 : QSqueue.get(id3)) {
                                            Set<GradoopId> neighbours4 = localAdjacencyList.get(id4);
                                            int degree4 = neighbours4.size();
                                            Set<GradoopId> intersection = neighbours3.stream()
                                                    .filter(neighbours4::contains)
                                                    .collect(Collectors.toSet());
                                            double lambda = (intersection.size() * (degree3 * degree4)) / (3. * (degree3 + degree4));
                                            lambdas.getAndAdd(lambda);
                                            lambdasCount.getAndIncrement();
                                        }
                                        QSqueue.remove(id3);
                                    }
                                    break;
                                } catch (ConcurrentModificationException e) {
                                    try {
                                        Thread.sleep(1000);
                                    } catch (InterruptedException ignored) { }
                                    tries++;
                                    if (tries >= 10) {
                                        System.out.println("Error retrieving state. " + e);
                                    }
                                }
                                catch (Exception e) {
                                    tries++;
                                    if (tries >= 10) {
                                        System.out.println("Error retrieving state. " + e);
                                    }
                                }
                            }
                            //if (System.currentTimeMillis() >= runUntil) {
                            //    break;
                           // }
                        }
                    }
                    // If queue still has elements, this means these vertices couldnt be found in all remote partitions.
                    // In this case we assume 0 triangles.
                    if (QSqueue.size() > 0 && System.currentTimeMillis() > runUntil) {
                        lambdasCount.getAndAdd(QSqueue.size());
                        System.out.println("we added " + QSqueue.size());
                    }
                    QSqueueSize.set(0);
                    QSqueue = new HashMap<>();
                }
            }
            //System.out.println("In " + (System.currentTimeMillis()-runUntil+timeToRun) + "ms we sampled \t" + lambdasCount.get() + "\t times in partition " + localKey);
            /*
            int totalVertices = 0;
            int partitionsCounted = 0;
            for (Integer verticesInPartition : verticesInPartitions) {
                if (verticesInPartition != -1) {
                    totalVertices = totalVertices + verticesInPartition;
                    partitionsCounted++;
                }
            }
            int approxVertices = 0;
            if (partitionsCounted == allKeys.length) {
                approxVertices = totalVertices;
            } else {
                approxVertices = (int) ((double) totalVertices / partitionsCounted * allKeys.length);
            }

             */
            int approxVertices = localAdjacencyList.keySet().size();
            double result = (lambdas.get() / lambdasCount.get()) * approxVertices;
            if (lambdasCount.get() == 0) {
                result = 0;
            }
            output = "In partition " + localKey + " we estimated \t" + (long)result + "\t triangles over \t"+lambdasCount.get()+"\t times sampling";
            System.out.println(output);
        }

        return output;
    }


}

