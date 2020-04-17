package gellyStreaming.gradoop.model;

import org.apache.flink.api.common.functions.AggregateFunction;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.streaming.api.datastream.WindowedStream;
import org.apache.flink.streaming.api.functions.windowing.ProcessWindowFunction;
import org.apache.flink.streaming.api.windowing.windows.TimeWindow;
import org.apache.flink.util.Collector;
import org.gradoop.common.model.impl.id.GradoopId;
import org.gradoop.common.model.impl.id.GradoopIdSet;
import org.gradoop.temporal.model.impl.pojo.TemporalEdge;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

public class GradoopSnapshotStream {

    private WindowedStream<TemporalEdge, GradoopId, TimeWindow> windowedStream;
    private WindowedStream<TemporalEdge, GradoopIdSet, TimeWindow> windowedStream2;

    public GradoopSnapshotStream(WindowedStream<TemporalEdge, GradoopIdSet, TimeWindow> stream) {
        this.windowedStream2 = stream;
        //windowedStream2.process(new createEL2()).print();
        //windowedStream2.aggregate(new SetAggregate(), new countEdges<GradoopIdSet>()).print();
        windowedStream2.aggregate(new SetAggregate(), new countTriangles<GradoopIdSet>()).print();
    }
    

    public GradoopSnapshotStream(WindowedStream<TemporalEdge, GradoopId, TimeWindow> stream, String strategy) {
        this.windowedStream = stream;

        switch (strategy) {
            case "EL":
                windowedStream.process(new createEL()).print();
                break;
            case "AL":
                createAL();
                break;
            case "CSR":
                createCSR();
                break;
            default:
                throw new IllegalArgumentException("Illegal state strategy, choose CSR, AL, or EL.");
        }
    }

    private static class SetAggregate implements AggregateFunction<TemporalEdge, HashSet<TemporalEdge>, HashSet<TemporalEdge>> {
        @Override
        public HashSet<TemporalEdge> createAccumulator() {
            return new HashSet<>();
        }

        @Override
        public HashSet<TemporalEdge> add(TemporalEdge temporalEdge, HashSet<TemporalEdge> temporalEdges) {
            temporalEdges.add(temporalEdge);
            return temporalEdges;
        }

        @Override
        public HashSet<TemporalEdge> getResult(HashSet<TemporalEdge> temporalEdges) {
            return temporalEdges;
        }

        @Override
        public HashSet<TemporalEdge> merge(HashSet<TemporalEdge> temporalEdges, HashSet<TemporalEdge> acc1) {
            temporalEdges.addAll(acc1);
            return temporalEdges;
        }
    }

    private static class countEdges<K> extends ProcessWindowFunction<HashSet<TemporalEdge>, String, K, TimeWindow> {
        @Override
        public void process(K key, Context context, Iterable<HashSet<TemporalEdge>> iterable, Collector<String> collector) {
            HashSet<TemporalEdge> edges = new HashSet<>();
            iterable.forEach(edges::addAll);
            collector.collect("There are "+edges.size()+" edges in "+context.window().toString());
        }
    }

    private static class countTriangles<K> extends ProcessWindowFunction<HashSet<TemporalEdge>, String, K, TimeWindow> {

        @Override
        public void process(K k, Context context, Iterable<HashSet<TemporalEdge>> iterable, Collector<String> collector) {
            HashSet<TemporalEdge> edges = new HashSet<>();
            iterable.forEach(edges::addAll);
            ArrayList<Tuple2<GradoopId, GradoopId>> simpleEdges = new ArrayList<>();
            for (TemporalEdge edge: edges) {
                GradoopId src = edge.getSourceId();
                GradoopId trg = edge.getTargetId();
                simpleEdges.add(new Tuple2<>(src, trg));
            }
            HashSet<ArrayList<GradoopId>> triangles = new HashSet<>();
            for (Tuple2<GradoopId, GradoopId> edge1: simpleEdges) {
                for (Tuple2<GradoopId, GradoopId> edge2 : simpleEdges) {
                    if(!edge1.equals(edge2)) {
                        if((edge2.f0.equals(edge1.f0) && (
                                simpleEdges.contains(Tuple2.of(edge1.f1, edge2.f1)) ||
                                        simpleEdges.contains(Tuple2.of(edge2.f1, edge1.f1))))
                            || (edge2.f1.equals(edge1.f0) && (
                                simpleEdges.contains(Tuple2.of(edge1.f1, edge2.f0)) ||
                                        simpleEdges.contains(Tuple2.of(edge2.f0, edge1.f1))))
                            || (edge2.f0.equals(edge1.f1) && (
                                simpleEdges.contains(Tuple2.of(edge1.f0, edge2.f1)) ||
                                        simpleEdges.contains(Tuple2.of(edge2.f1, edge1.f0))))
                            || (edge2.f1.equals(edge1.f1) && (
                                simpleEdges.contains(Tuple2.of(edge1.f0, edge2.f0)) ||
                                        simpleEdges.contains(Tuple2.of(edge2.f0, edge1.f0))))) {
                            HashSet<GradoopId> triangle = new HashSet<>();
                            triangle.add(edge1.f0);
                            triangle.add(edge1.f1);
                            triangle.add(edge2.f0);
                            triangle.add(edge2.f1);
                            ArrayList<GradoopId> triangle1 = new ArrayList<>(triangle);
                            triangle1.sort(GradoopId::compareTo);
                            triangles.add(triangle1);
                        }
                    }
                }
            }
            collector.collect("There are "+triangles.size()+" triangles in "+context.window());
        }
    }


    private static class createEL extends ProcessWindowFunction<TemporalEdge, String, GradoopId, TimeWindow> {
        public static class SetInWindow {
            public GradoopId key;
            public Set<TemporalEdge> adjacentEdges;
        }
        private ValueStateDescriptor<SetInWindow> state = new ValueStateDescriptor<>
                ("myStateDescriptor", SetInWindow.class);

        @Override
        public void clear(Context context) throws Exception {
            ValueState<SetInWindow> previous = context.windowState().getState(state);
            previous.clear();
            super.clear(context);
        }

        @Override
        public void process(GradoopId gradoopId, Context context, Iterable<TemporalEdge> iterable, Collector<String> collector) throws Exception {
            SetInWindow oldState = context.windowState().getState(state).value();
            if(oldState == null) {
                oldState = new SetInWindow();
                oldState.key = gradoopId;
                oldState.adjacentEdges = new HashSet<TemporalEdge>();
            }
            SetInWindow newState = oldState;
            iterable.forEach(temporalEdge -> newState.adjacentEdges.add(temporalEdge));
            context.windowState().getState(state).update(newState);
            collector.collect("Vertex "+gradoopId.toString()+" has "+ newState.adjacentEdges.size()+" edges in "
            +context.window().toString());
        }
    }

    private static class createEL2 extends ProcessWindowFunction<TemporalEdge, String, GradoopIdSet, TimeWindow> {
        public static class SetInWindow {
            public GradoopIdSet key;
            public Set<TemporalEdge> adjacentEdges;
        }
        private ValueStateDescriptor<SetInWindow> state = new ValueStateDescriptor<>
                ("myStateDescriptor", SetInWindow.class);

        @Override
        public void clear(Context context) throws Exception {
            ValueState<SetInWindow> previous = context.windowState().getState(state);
            previous.clear();
            super.clear(context);
        }

        @Override
        public void process(GradoopIdSet key, Context context, Iterable<TemporalEdge> iterable, Collector<String> collector) throws Exception {
            SetInWindow oldState = context.windowState().getState(state).value();
            if(oldState == null) {
                oldState = new SetInWindow();
                oldState.key = key;
                oldState.adjacentEdges = new HashSet<>();
            }
            SetInWindow newState = oldState;
            iterable.forEach(temporalEdge -> newState.adjacentEdges.add(temporalEdge));
            context.windowState().getState(state).update(newState);
            collector.collect("Graph "+key.toString() +" has "+ newState.adjacentEdges.size()+" edges in "
                    +context.window().toString());
        }
    }

    private void createAL() {

    }

    private void createCSR() {

    }
    
}
