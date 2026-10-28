package dev.irisvisualizer.editor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Deterministic iterative components for callers' bounded literal graphs. No resource loading. */
final class LiteralReferenceGraph {
    record Link(String source,String target) {}
    private record Frame(String node,boolean finish) {}
    static List<List<String>> cycles(List<String> files,List<Link> edges) {
        var graph=new LinkedHashMap<String,Set<String>>();var reverse=new LinkedHashMap<String,Set<String>>();
        for (String file : files) { graph.put(file,new LinkedHashSet<>());reverse.put(file,new LinkedHashSet<>()); }
        for (var edge : edges) if (graph.containsKey(edge.source()) && graph.containsKey(edge.target())) { graph.get(edge.source()).add(edge.target());reverse.get(edge.target()).add(edge.source()); }
        var visited=new HashSet<String>();var order=new ArrayList<String>();var stack=new ArrayDeque<Frame>();
        for (String root : files) {
            if (visited.contains(root)) continue;stack.push(new Frame(root,false));
            while (!stack.isEmpty()) { var frame=stack.pop();if (frame.finish()) { order.add(frame.node());continue; }if (!visited.add(frame.node())) continue;stack.push(new Frame(frame.node(),true));var neighbors=new ArrayList<>(graph.get(frame.node()));for (int i=neighbors.size()-1;i>=0;i--) if (!visited.contains(neighbors.get(i))) stack.push(new Frame(neighbors.get(i),false)); }
        }
        visited.clear();var result=new ArrayList<List<String>>();var work=new ArrayDeque<String>();
        for (int i=order.size()-1;i>=0;i--) {
            String root=order.get(i);if (!visited.add(root)) continue;var component=new ArrayList<String>();work.push(root);
            while (!work.isEmpty()) { String node=work.pop();component.add(node);for (String next : reverse.get(node)) if (visited.add(next)) work.push(next); }
            if (component.size()>1 || graph.get(root).contains(root)) { component.sort(String::compareTo);result.add(List.copyOf(component)); }
        }
        result.sort(java.util.Comparator.comparing(c -> c.getFirst()));return List.copyOf(result);
    }
}
