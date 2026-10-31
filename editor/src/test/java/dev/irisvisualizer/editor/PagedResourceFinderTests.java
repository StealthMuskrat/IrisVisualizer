package dev.irisvisualizer.editor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

final class PagedResourceFinderTests {
    private static final ResourceFinder.Query ALL=new ResourceFinder.Query("",ResourceFinder.Family.ALL,false);
    private static final ResourceFinder.Query CHANGED=new ResourceFinder.Query("",ResourceFinder.Family.ALL,true);
    private static void check(boolean condition,String reason) {if(!condition)throw new AssertionError("Paged resource finder: "+reason);}
    private static byte[] bytes(String text) {return text.getBytes(StandardCharsets.UTF_8);}
    private static void invalid(Runnable action) {try {action.run();}catch(IllegalArgumentException expected){return;}throw new AssertionError("Expected invalid finder page");}
    private static List<String> paths(ResourceFinder.Page page) {return page.result().hits().stream().map(ResourceFinder.Hit::path).toList();}
    private static Set<String> logicalFolders(PackWorkspace pack) {
        var result=new TreeSet<>(pack.directories());var nodes=new ArrayList<>(pack.paths());nodes.addAll(pack.directories());
        for(String path:nodes)for(int slash=path.lastIndexOf('/');slash>=0;slash=path.lastIndexOf('/',slash-1))result.add(path.substring(0,slash));return result;
    }
    private static List<String> sorted(Collection<String> paths) {return paths.stream().sorted(Comparator.comparing((String p)->p.toLowerCase(Locale.ROOT)).thenComparing(p->p)).toList();}
    static void run(Path temp)throws Exception {
        // Independent rank buckets cross every page boundary, including mixed upper/lower and Unicode paths.
        var files=new LinkedHashMap<String,byte[]>();var expected=new ArrayList<String>();
        var exact=List.of("notes/alpha");var stems=new ArrayList<String>();var prefixes=new ArrayList<String>();var contains=new ArrayList<String>();var folders=new ArrayList<String>();
        for(int i=0;i<245;i++)stems.add("biomes/f"+String.format("%03d",i)+"/alpha.json");
        for(int i=0;i<135;i++)prefixes.add("blocks/alpha"+String.format("%03d",i)+".json");
        for(int i=0;i<125;i++)contains.add("expressions/zalpha"+String.format("%03d",i)+".json");
        for(int i=0;i<111;i++)folders.add("images/alpha/a"+String.format("%03d",i)+".PNG");
        stems.add("biomes/雪/alpha.json");stems.add("biomes/Upper/alpha.json");stems.add("biomes/lower/alpha.json");
        for(var group:List.of(exact,stems,prefixes,contains,folders))expected.addAll(sorted(group));
        for(String path:expected)files.put(path,new byte[]{0,-1,4});files.put("future/opaque.bin",new byte[]{1,-1});
        var pack=PackWorkspace.newPack(files);var cache=new ResourceFinder.Cache();var query=new ResourceFinder.Query("ALPHA",ResourceFinder.Family.ALL,false);long revision=pack.revision(),resources=pack.resourceRevision();var history=pack.historySteps(false);
        var first=cache.page(pack,query,0);check(first.result().matches()==expected.size() && first.result().hits().size()==100 && first.window().pages()==7 && first==cache.page(pack,query,-1),"bounded stable first page and negative clamping");
        check(cache.query(pack,query)==first.result(),"legacy first-page API shares exact result/cache");var visited=new ArrayList<String>();long request=first.request();
        for(int page=0;page<first.window().pages();page++) {
            var current=cache.page(pack,query,page);check(current.request()==request && current==cache.page(pack,query,page) && paths(current).equals(expected.subList(current.window().start(),current.window().end())),"every page preserves global rank and normalized-path order");
            check(current.result().hits().size()<=100 && !current.result().incompleteIndex(),"only one bounded complete page of indexed matches");visited.addAll(paths(current));
        }
        check(visited.equals(expected) && new HashSet<>(visited).size()==expected.size(),"all indexed matches reachable exactly once");var last=cache.page(pack,query,Integer.MAX_VALUE);check(last.window().page()==6 && last.window().end()==expected.size() && last.result().hits().size()==20,"overflow request clamps to short final page");
        check(cache.page(pack,query,0).equals(first) && cache.page(pack,query,0).result().hits().getFirst().rank()==1,"return to first page is equal metadata without retaining every page");
        var biome=cache.page(pack,new ResourceFinder.Query("alpha",ResourceFinder.Family.BIOMES,false),2);check(biome.result().matches()==248 && paths(biome).equals(sorted(stems).subList(200,248)),"family filter recomputes complete ranked pages");
        var mult=cache.page(pack,new ResourceFinder.Query("f2 alpha",ResourceFinder.Family.BIOMES,false),0);check(mult.result().matches()==45 && mult.result().hits().stream().allMatch(h->h.path().contains("/f2")),"literal all-term matching across indexed subset");
        try {first.result().hits().clear();throw new AssertionError();}catch(UnsupportedOperationException expectedError){}
        invalid(()->new ResourceFinder.Window(-1,1,100));invalid(()->new ResourceFinder.Window(1,100,100));invalid(()->new ResourceFinder.Window(0,-1,100));invalid(()->ResourceFinder.Window.at(1,0,0));invalid(()->ResourceFinder.Window.at(1,0,101));invalid(()->new ResourceFinder.Page(1,ResourceFinder.Window.at(200,0,100),new ResourceFinder.Result(0,0,0,List.of(),false)));
        var huge=ResourceFinder.Window.at(Integer.MAX_VALUE,Integer.MAX_VALUE,100);check(huge.end()==Integer.MAX_VALUE && huge.start()==2147483600 && huge.pages()==21474837,"overflow-safe metadata arithmetic");var emptyWindow=ResourceFinder.Window.at(0,Integer.MAX_VALUE,100);check(emptyWindow.page()==0 && emptyWindow.pages()==1 && emptyWindow.firstRow()==0 && emptyWindow.end()==0,"empty result stays one empty page");
        // Keyboard selection crosses pages in both directions, clamps edges, and never retains an invisible old hit.
        var page0=cache.page(pack,query,0);var cursor=ResourceFinder.move(page0,page0.result().hits().getLast().path(),1);check(cursor.equals(new ResourceFinder.Cursor(1,0)),"Down crosses first page boundary");var page1=cache.page(pack,query,1);check(ResourceFinder.move(page1,page1.result().hits().getFirst().path(),-1).equals(new ResourceFinder.Cursor(0,99)),"Up crosses back to prior page's final result");
        check(ResourceFinder.move(page0,page0.result().hits().getFirst().path(),-1).equals(new ResourceFinder.Cursor(0,0)),"Up clamps at global first result");last=cache.page(pack,query,6);check(ResourceFinder.move(last,last.result().hits().getLast().path(),1).equals(new ResourceFinder.Cursor(6,19)),"Down clamps at global last result");
        check(ResourceFinder.move(page1,"invisible",-1).equals(new ResourceFinder.Cursor(1,0)) && ResourceFinder.move(page1,null,1).equals(new ResourceFinder.Cursor(1,0)),"stale selection begins on visible page without skipping a result");invalid(()->ResourceFinder.move(page0,null,2));
        var empty=cache.page(pack,new ResourceFinder.Query("absent",ResourceFinder.Family.ALL,false),99);check(ResourceFinder.move(empty,null,1).equals(new ResourceFinder.Cursor(0,-1)),"empty keyboard selection is explicit");
        // Small page caps use the same ranking and reachability; index budgets still expose unsearched paths honestly.
        var tiny=new ResourceFinder.Cache(new ResourceFinder.Limits(PackWorkspace.MAX_FILES,ResourceFinder.MAX_INDEX_CHARACTERS,2));var second=tiny.page(pack,query,1);check(paths(second).equals(expected.subList(2,4)) && second.window().pageSize()==2 && tiny.query(pack,query).hits().size()==2,"custom result cap becomes page capacity");
        var limited=new ResourceFinder.Cache(new ResourceFinder.Limits(3,100000,2));var limitedFirst=limited.page(pack,ALL,0);var limitedLast=limited.page(pack,ALL,Integer.MAX_VALUE);check(limitedFirst.result().indexedFiles()==3 && limitedFirst.result().matches()==3 && limitedLast.result().hits().size()==1 && limitedLast.result().incompleteIndex(),"file-budget prefix is paged without claiming skipped files searched");
        String initial=pack.paths().iterator().next();var noIndex=new ResourceFinder.Cache(new ResourceFinder.Limits(10,1,100)).page(pack,ALL,99);check(noIndex.result().indexedFiles()==0 && noIndex.result().incompleteIndex() && noIndex.window().matches()==0,"character budget can produce an explicitly incomplete empty index");
        var oneIndex=new ResourceFinder.Cache(new ResourceFinder.Limits(10,initial.toLowerCase(Locale.ROOT).length(),100)).page(pack,ALL,0);check(oneIndex.result().indexedFiles()==1 && paths(oneIndex).equals(List.of(initial)),"exact normalized character budget");
        check(pack.revision()==revision && pack.resourceRevision()==resources && pack.historySteps(false).equals(history),"search and navigation metadata have no workspace mutation");
        // All-file pages survive byte edits/Save; changed-only pages observe edits, Undo/Redo, Save As and partial Save receipts.
        Path root=temp.resolve("paged-finder-root");pack.save(root);var stable=cache.page(pack,ALL,3);String original=expected.getFirst();pack.replace(original,bytes("new applied bytes"));check(cache.page(pack,ALL,3)==stable,"byte edit retains all-file ranked page");var changed=cache.page(pack,CHANGED,9);check(changed.window().page()==0 && paths(changed).equals(List.of(original)),"changed-only filter clamps old page");pack.undo();check(cache.page(pack,CHANGED,9).result().hits().isEmpty(),"Undo recomputes changed-only membership");pack.redo();check(cache.page(pack,CHANGED,0).result().matches()==1,"Redo restores changed membership");pack.save(root);check(cache.page(pack,CHANGED,0).result().matches()==0,"Save removes clean resources from changed-only pages");
        var partial=PackWorkspace.newPack(Map.of("blocks/a.json",bytes("a"),"blocks/z.json",bytes("z")));Path partialRoot=temp.resolve("paged-finder-partial");partial.save(partialRoot);partial.replace("blocks/a.json",bytes("A"));partial.replace("blocks/z.json",bytes("Z"));var partialCache=new ResourceFinder.Cache();var before=partialCache.page(partial,CHANGED,0);long saved=partial.saveRevision(),applied=partial.revision();var writes=new AtomicInteger();
        try {partial.save(partialRoot,(base,path,data,baseline)->{if(writes.incrementAndGet()==2)throw new IOException("Injected later-file publication failure");return FileSaves.write(base,path,data,baseline);});throw new AssertionError();}catch(IOException expectedFailure){}
        var after=partialCache.page(partial,CHANGED,0);check(partial.dirty() && partial.revision()==applied && partial.saveRevision()>saved && after.request()!=before.request() && paths(after).equals(paths(before)) && after==partialCache.page(partial,CHANGED,0),"partial Save receipt invalidates query identity even while dirty flags remain conservative");partial.saveAs(temp.resolve("paged-finder-partial-as"));check(partialCache.page(partial,CHANGED,0).result().hits().isEmpty(),"Save As adoption resets changed-only matches");
        var current=cache.page(pack,ALL,6);pack.createSubfolder("images","new_folder");check(cache.page(pack,ALL,6).request()!=current.request(),"folder/resource revision invalidates index identity");var beforeDuplicate=cache.page(pack,ALL,0);String added=pack.duplicate("images/alpha/a000.PNG","copy");check(cache.page(pack,ALL,0).request()!=beforeDuplicate.request() && cache.page(pack,new ResourceFinder.Query("copy",ResourceFinder.Family.IMAGES,false),0).result().matches()==1,"new resource path refreshes index");
        var other=PackWorkspace.newPack(Map.of("blocks/only.json",bytes("{}")));check(paths(cache.page(other,ALL,99)).equals(List.of("blocks/only.json")) && cache.page(pack,ALL,0).result().totalFiles()==pack.paths().size(),"owner switch cannot leak prior paths");var beforeClear=cache.page(pack,ALL,0);cache.clear();check(cache.page(pack,ALL,0).request()!=beforeClear.request(),"clear changes query identity");
        // Late drafts are guarded even for a selected result on the last page; failures retain drafts and metadata.
        var finalPage=cache.page(pack,query,6);String target=finalPage.result().hits().getLast().path();var nav=new ResourceNavigation();nav.request(pack,target);var drafts=new InspectorDrafts();drafts.update("late","stored","typing");var selected=new AtomicReference<>(original);var open=nav.takeRequest(pack);check(!drafts.transition(()->false,()->selected.set(open.target())) && drafts.any() && selected.get().equals(original) && cache.page(pack,query,6)==finalPage,"last-page Open cancellation retains drafts and page");
        try {drafts.transition(()->true,()->{throw new IOException("Injected Open failure");});throw new AssertionError();}catch(IOException expectedFailure){check(drafts.any(),"failed Open keeps drafts");}
        check(drafts.transition(()->true,()->selected.set(open.target())) && selected.get().equals(target) && !drafts.any(),"explicit guarded Open selects exact ranked target");nav.request(pack,target);check(nav.takeRequest(other)==null,"stale owner cannot open a page target");
        var sourcePaths=List.copyOf(pack.paths());var snapshotFolders=logicalFolders(pack);int undo=pack.undoCount();long finalRevision=pack.revision();pack.saveCopy(temp.resolve("paged-finder-copy"));pack.exportZip(temp.resolve("paged-finder.zip"));pack.saveAs(temp.resolve("paged-finder-as"));
        for(var reopened:List.of(PackWorkspace.open(temp.resolve("paged-finder-copy")),PackWorkspace.importZip(temp.resolve("paged-finder.zip")),PackWorkspace.open(temp.resolve("paged-finder-as")))){check(reopened.paths().equals(new TreeSet<>(sourcePaths)) && logicalFolders(reopened).equals(snapshotFolders),"exact file/folder catalog round trip");for(String path:sourcePaths)check(Arrays.equals(pack.read(path),reopened.read(path)),"exact unknown/opaque resource bytes");}
        check(pack.undoCount()==undo && pack.revision()==finalRevision && pack.paths().contains(added),"paging and storage retain applied history/revision");
        System.out.println("PASS: complete ranked path-only Quick Open pages with one bounded 100-hit result, stable exact/filename/path ranking and normalized Unicode order, first-page API compatibility, all indexed matches reachable once, rank/page/family/token/index limits and overflow-safe empty/short/final clamping, cross-page Up/Down and stale/edge selection, workspace/resource/query/Undo/Redo/Save/partial-Save/Save-As cache identity, deferred last-page draft-safe opening and exact opaque/unknown Save/Copy/As/ZIP without payload parsing or native previews");
    }
}
