package minicpbp.engine.core;

import java.util.*;

public class ModelGraphExporter {

    public static Map<String, Object> export(Solver solver) {
        List<Map<String, Object>> nodes = new ArrayList<>();
        List<Map<String, Object>> edges = new ArrayList<>();

        Map<IntVar, Integer> varToId = new IdentityHashMap<>();
        int id = 0;

        Iterator<IntVar> varIt = solver.getVariables().iterator();
        while (varIt.hasNext()) {
            IntVar v = varIt.next();
            varToId.put(v, id);
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", id);
            node.put("type", "variable");
            String name = v.getName();
            node.put("name", (name != null && !name.isEmpty()) ? name : "x" + id);
            node.put("domain_min", v.min());
            node.put("domain_max", v.max());
            node.put("domain_size", v.size());
            node.put("bound", v.isBound());
            nodes.add(node);
            id++;
        }

        Iterator<Constraint> cIt = solver.getConstraints().iterator();
        while (cIt.hasNext()) {
            Constraint c = cIt.next();
            int cId = id;
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", cId);
            node.put("type", "constraint");
            String name = c.getName();
            node.put("name", (name != null && !name.isEmpty()) ? name : c.getClass().getSimpleName());
            node.put("arity", c.arity());
            node.put("active", c.isActive());
            nodes.add(node);

            IntVar[] scope = c.getScope();
            if (scope != null) {
                for (IntVar v : scope) {
                    Integer vId = varToId.get(v);
                    if (vId == null) vId = varToId.get(v.getBaseVar());
                    if (vId != null) {
                        Map<String, Object> edge = new LinkedHashMap<>();
                        edge.put("source", vId);
                        edge.put("target", cId);
                        edge.put("type", "scope");
                        edges.add(edge);
                    }
                }
            }
            id++;
        }

        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("nodes", nodes);
        graph.put("edges", edges);
        return graph;
    }
}
