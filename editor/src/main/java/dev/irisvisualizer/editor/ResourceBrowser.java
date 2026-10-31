package dev.irisvisualizer.editor;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Pages the visible folder-first hierarchy using path metadata only. Never opens or edits a resource. */
public final class ResourceBrowser {
    public record Query(String text,ResourceFinder.Family family,boolean changedOnly) {
        public Query {
            if(text==null || text.length()>256 || text.chars().anyMatch(Character::isISOControl) || family==null)
                throw new IllegalArgumentException("Use up to 256 browser filter characters without controls");
            text=text.strip().toLowerCase(Locale.ROOT);
        }
        public boolean searching(){return !text.isEmpty();}
    }
    public record Row(String name,String path,boolean folder,int depth,boolean expanded,boolean changed) {}
    public record Page(long request,long treeGeneration,ReviewPagination.Window window,List<Row> rows) {
        public Page {
            Objects.requireNonNull(window);rows=List.copyOf(rows);
            if(rows.size()!=window.end()-window.start())throw new IllegalArgumentException("Incomplete resource browser page");
        }
    }

    public static final class Cache {
        private PackWorkspace workspace;
        private long resources=-1,applied=-1,saved=-1,generation,treeGeneration;
        private Query query;
        private List<ResourceTree.Node> tree=List.of();
        private final Set<String> expanded=new HashSet<>();
        private int visible=-1;
        private Page page;

        public void clear(){workspace=null;resources=applied=saved=-1;query=null;tree=List.of();expanded.clear();invalidate();treeGeneration++;}
        private void invalidate(){visible=-1;page=null;generation++;}
        private void prepare(PackWorkspace current,Query next) {
            Objects.requireNonNull(current);Objects.requireNonNull(next);
            boolean owner=workspace!=current,structure=owner || resources!=current.resourceRevision();
            boolean bytes=owner || applied!=current.revision() || saved!=current.saveRevision();
            if(owner)expanded.clear();
            if(structure && !expanded.isEmpty()) {
                var folders=new HashSet<>(current.directories());
                for(String path:current.paths())for(int slash=path.indexOf('/');slash>=0;slash=path.indexOf('/',slash+1))folders.add(path.substring(0,slash));
                expanded.removeIf(path->!folders.contains(path));
            }
            if(structure || !next.equals(query) || next.changedOnly() && bytes) {
                tree=ResourceTree.build(current,next.text(),next.family(),next.changedOnly());treeGeneration++;invalidate();
            } else if(bytes) {page=null;generation++;}
            workspace=current;resources=current.resourceRevision();applied=current.revision();saved=current.saveRevision();query=next;
        }
        private boolean open(ResourceTree.Node node){return node.folder() && (query.searching() || expanded.contains(node.path()));}
        private int count(List<ResourceTree.Node> nodes) {
            int count=0;for(var node:nodes){count++;if(open(node))count+=count(node.children());}return count;
        }
        public Page page(PackWorkspace current,Query next,int requested) {
            prepare(current,next);if(visible<0)visible=count(tree);
            var window=ReviewPagination.Window.at(visible,requested);if(page!=null && page.window().equals(window))return page;
            var rows=new ArrayList<Row>(window.end()-window.start());collect(tree,0,window,new int[1],rows);
            return page=new Page(generation,treeGeneration,window,rows);
        }
        private void collect(List<ResourceTree.Node> nodes,int depth,ReviewPagination.Window window,int[] index,List<Row> rows) {
            for(var node:nodes) {
                if(index[0]>=window.end())return;
                if(index[0]++>=window.start())rows.add(new Row(node.name(),node.path(),node.folder(),depth,open(node),!node.folder() && workspace.changed(node.path())));
                if(open(node))collect(node.children(),depth+1,window,index,rows);
            }
        }
        private ResourceTree.Node find(List<ResourceTree.Node> nodes,String path) {
            for(var node:nodes) {
                if(node.path().equals(path))return node;
                if(node.folder() && path.startsWith(node.path()+"/")){var found=find(node.children(),path);if(found!=null)return found;}
            }
            return null;
        }
        /** Search retains its existing forced-open ancestor behavior. No expansion occurs for an absent/stale path. */
        public boolean toggle(PackWorkspace current,Query next,String path) {
            prepare(current,next);if(query.searching() || path==null)return false;
            var node=find(tree,path);if(node==null || !node.folder())return false;
            if(!expanded.remove(path))expanded.add(node.path());invalidate();return true;
        }
        public void collapseAll(){if(!expanded.isEmpty()){expanded.clear();invalidate();}}
        /** Opens ancestors and returns the exact global visible page; filters and editor selection are never changed here. */
        public Page reveal(PackWorkspace current,Query next,String path) {
            prepare(current,next);if(path==null || find(tree,path)==null)return null;
            boolean changed=false;
            if(!query.searching())for(int slash=path.indexOf('/');slash>=0;slash=path.indexOf('/',slash+1))changed|=expanded.add(path.substring(0,slash));
            if(changed)invalidate();int row=rank(tree,path,new int[1]);
            return row<0 ? null : page(current,next,row/ReviewPagination.PAGE_SIZE);
        }
        private int rank(List<ResourceTree.Node> nodes,String path,int[] index) {
            for(var node:nodes) {
                if(node.path().equals(path))return index[0];index[0]++;
                if(open(node)){int found=rank(node.children(),path,index);if(found>=0)return found;}
            }
            return -1;
        }
    }
    private ResourceBrowser(){}
}
