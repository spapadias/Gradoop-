package gellyStreaming.gradoop.model;

import org.apache.flink.runtime.state.KeyGroupRangeAssignment;
import org.apache.flink.util.MathUtils;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.Queue;

public class KeyGen
{
    private int partitions;
    private int maxPartitions;
    private HashMap<Integer, Queue<Integer>> cache = new HashMap<Integer, Queue<Integer>>();
    private int lastPosition = 1;

    public KeyGen(final int partitions, final int maxPartitions)
    {
        this.partitions    = partitions;
        this.maxPartitions = maxPartitions;
    }

    public KeyGen(final int partitions)
    {
        this.partitions    = partitions;
        this.maxPartitions = 128;
    }

    Integer next(int targetPartition)
    {
        Queue<Integer> queue;
        if (cache.containsKey(targetPartition))
            queue = cache.get(targetPartition);
        else
            queue = new LinkedList<Integer>();

        // Check if queue is empty
        if (queue.size() == 0)
        {
            boolean found = false;
            while (!found)
            {
                for (int id = lastPosition ; id < 100 ; id++)
                {
                    //System.out.println("Hey " + id);

                    int partition = (MathUtils.murmurHash(id) %
                            maxPartitions) * partitions / maxPartitions;

                    if (cache.containsKey(partition))
                        queue = cache.get(partition);
                    else
                        queue = new LinkedList<Integer>();
                    // Add element to the queue
                    queue.add(id);

                    if (partition == targetPartition) {
                        found = true;
                        break; // break the for loop
                    }
                }
            }
        }

        return queue.poll(); // Return the first elements and deletes it -->similar to dequeue of scala's mutable.Queue
    }

    public static void main(String[] args) throws Exception
    {
        //Generate intermediate keys
        final int p = 8;
        int numPartitions = p;
        int numKeys       = p;
        int parallelism   = p;

        KeyGen keyGenerator = new KeyGen(numPartitions,
                KeyGroupRangeAssignment.computeDefaultMaxParallelism(parallelism));
        int[] procID = new int[numKeys];

        for (int i = 0; i < numKeys ; i++)
            procID[i] = keyGenerator.next(i);

        for (int elem : procID)
            System.out.println(elem);
    }
}
