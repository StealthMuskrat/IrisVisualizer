package dev.irisvisualizer.editor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;

/** Deferred editable-list paging contracts; no native widgets or generation are initialized. */
final class InspectorListPagesTests {
    private static byte[] bytes(String value){return value.getBytes(StandardCharsets.UTF_8);}
    private static void check(boolean value,String message){if(!value)throw new AssertionError("Inspector list pages: "+message);}
    private static void reject(Runnable action){try{action.run();}catch(IllegalArgumentException expected){return;}throw new AssertionError("Expected invalid list-page request");}
    static void run(Path temp)throws Exception{
        String dimension="dimensions/owner.json",biome="biomes/owner.json";
        var regions=new ArrayList<String>();var children=new ArrayList<String>();var links=new ArrayList<String>();
        for(int row=0;row<651;row++){regions.add("\"r\"");children.add(row%2==0?"\"a\"":"\"b\"");links.add("{\"generator\":\"g\",\"min\":0,\"max\":32,\"future\":"+row+"}");}
        var files=new LinkedHashMap<String,byte[]>();files.put(dimension,bytes("{\"regions\":["+String.join(",",regions)+"],\"future\":9007199254740993}"));
        files.put(biome,bytes("{\"children\":["+String.join(",",children)+"],\"generators\":["+String.join(",",links)+"],\"unknown\":\"雪\\u0023\"}"));
        for(String path:List.of("regions/r.json","regions/other.json","biomes/a.json","biomes/b.json","generators/g.json"))files.put(path,bytes("{}"));
        files.put("objects/opaque.iob",new byte[]{0,1,-1});files.put("unknown/future.json",bytes("malformed future data"));
        var pack=PackWorkspace.newPack(files);var pages=new InspectorListPages();var scope=InspectorListPages.strings("regions");var visited=new ArrayList<Integer>();
        long revision=pack.revision(),saved=pack.saveRevision();boolean dirty=pack.dirty();int history=pack.undoCount();
        for(int requested=0;requested<4;requested++){
            var window=pages.navigate(pack,dimension,scope,651,requested).window();check(window.end()-window.start()<=200,"editable row count is bounded");
            for(int row=window.start();row<window.end();row++)visited.add(row);
        }
        check(visited.size()==651 && visited.getFirst()==0 && visited.getLast()==650 && visited.stream().distinct().count()==651,"all global rows appear once across complete pages");
        check(pages.navigate(pack,dimension,scope,651,Integer.MAX_VALUE).window().page()==3,"large page jump clamps without truncating the list");
        check(pack.revision()==revision && pack.saveRevision()==saved && pack.dirty()==dirty && pack.undoCount()==history,"paging has no applied/storage/history authority");
        for(var entry:files.entrySet())check(Arrays.equals(pack.read(entry.getKey()),entry.getValue()),"all bytes remain unchanged by navigation");
        scopeKeysAndDrafts(pack,biome,pages);mutations(pack,biome,pages);
        // Owner/path scopes reset independently; scalar changes and Save As do not reset the selected list page.
        var childScope=InspectorListPages.strings("children");pages.navigate(pack,biome,childScope,651,2);pages.navigate(pack,biome,InspectorListPages.GENERATORS,651,3);
        check(pages.select(pack,biome,childScope,651).window().page()==2,"independent list pages in the same inspector");
        long generation=pages.select(pack,biome,childScope,651).generation();var accepted=pages.select(pack,biome,childScope,651);
        reject(()->pages.select(pack,"missing.json",childScope,651));reject(()->pages.select(pack,biome,childScope,-1));reject(()->pages.reveal(pack,biome,childScope,651,651));
        check(pages.select(pack,biome,childScope,651).equals(accepted),"invalid page/reveal requests retain accepted presentation state");
        check(pages.select(pack,dimension,InspectorListPages.strings("regions"),651).window().page()==0,"changing resources starts at the first page");
        var other=PackWorkspace.newPack(files);check(pages.select(other,dimension,InspectorListPages.strings("regions"),651).window().page()==0 && pages.select(other,dimension,InspectorListPages.strings("regions"),651).generation()!=generation,"equal paths/counts in another workspace have new ownership");
        pages.clear();check(pages.select(pack,biome,childScope,651).window().page()==0,"explicit Clear releases prior pages");
        pages.reveal(pack,biome,childScope,651,600);pages.poll(pack,null);
        check(!pages.consumeReveal(pack,biome,childScope,600) && pages.select(pack,biome,childScope,651).window().page()==0,"hidden/empty selection releases old row intent before a path can be reused");
        pages.navigate(pack,biome,childScope,651,3);pages.poll(other,biome);check(pages.select(other,biome,childScope,651).window().page()==0,"hidden equal-path workspace replacement releases old ownership");
        persistence(temp,pack,dimension,biome,pages);
        System.out.println("PASS: complete global-index editable list pages, 651-row coverage without truncation, canonical row scopes and bounded clean-buffer retention with off-page drafts, independent list/resource/workspace state, invalid-request stability, committed duplicate/move/remove/add reveal and one-shot scrolling, cancelled structural draft guards, exact late-row edits and Save/Copy/As/ZIP without UI metadata or applied draft text; no native generation");
    }
    private static void scopeKeysAndDrafts(PackWorkspace pack,String path,InspectorListPages pages){
        var scope=InspectorListPages.strings("children");var links=InspectorListPages.GENERATORS;
        check(scope.row("children#0")==0 && scope.row("children#650")==650 && links.row("generator#200.min")==200 && links.row("generator#200.max")==200,"keys retain global indices across pages");
        for(String key:List.of("children#new","children#01","children#-1","children#2147483648","children#1.min","childrenExtra#1"))check(scope.row(key)<0,"unrelated/malformed/new-form keys do not become row keys");
        for(String key:List.of("generator#new.min","generator#01.min","generator#1.","generators#1.min"))check(links.row(key)<0,"generator forms and other prefixes remain separate");
        reject(()->InspectorListPages.strings(""));reject(()->InspectorListPages.strings("children#"));
        check(InspectorListPages.editableString("r") && InspectorListPages.editableString("x".repeat(4096)) && !InspectorListPages.editableString("x".repeat(4097)) && !InspectorListPages.editableString("prefix\u0000suffix") && !InspectorListPages.editableString(null),"NUL and oversize entries cannot become truncated native text buffers or phantom drafts");
        byte[] opaque=bytes("{\"regions\":[\"r\",\"\\u0000suffix\"],\"future\":9007199254740993}");
        String decoded=new LosslessJson(opaque).stringArrayPath("regions").get(1);check(!InspectorListPages.editableString(decoded) && new LosslessJson(opaque).token("future").equals("9007199254740993"),"escaped NUL remains source data rather than an editable truncated key");
        byte[] removed=DimensionFields.editArray("dimensions/owner.json",opaque,"regions",1,null,pack);check(new LosslessJson(removed).stringArrayPath("regions").equals(List.of("r")) && new LosslessJson(removed).token("future").equals("9007199254740993"),"explicit removal of a NUL entry retains the valid reference and unknown data");
        var drafts=new InspectorDrafts();var values=new HashMap<String,String>();for(int row=0;row<651;row++)values.put("children#"+row,"stored");
        drafts.update("children#1","stored","unfinished first");drafts.update("children#600","stored","unfinished last");drafts.update("children#new","","new entry");
        values.put("children#1","unfinished first");values.put("children#600","unfinished last");values.put("children#new","new entry");values.put("other#1","keep");
        var window=pages.navigate(pack,path,scope,651,1).window();values.keySet().removeIf(key->scope.evict(key,window,drafts));
        check(values.size()==204 && values.get("children#1").equals("unfinished first") && values.get("children#600").equals("unfinished last") && values.containsKey("children#new") && values.containsKey("other#1"),"retain at most one page of clean row buffers plus explicit pending/form/unrelated input");
        check(drafts.count()==3 && drafts.countMatching(key->scope.row(key)>=0)==2 && drafts.countMatching(key->scope.offPage(key,window))==2,"off-page draft counts exclude the always-visible new-entry form");
        var previous=pages.select(pack,path,scope,651);var before=pack.read(path);
        try{check(!drafts.transitionUnder("children#",()->false,()->pack.replace(path,BiomeChildren.change(path,pack.read(path),199,BiomeChildren.Action.DOWN))),"declined structural edit is not applied");}catch(java.io.IOException failure){throw new AssertionError(failure);}
        check(pages.select(pack,path,scope,651).equals(previous) && drafts.count()==3 && Arrays.equals(before,pack.read(path)),"cancelled off-page draft guard retains page/drafts/applied data");
    }
    private static void mutations(PackWorkspace pack,String path,InspectorListPages pages)throws Exception{
        var scope=InspectorListPages.GENERATORS;byte[] original=pack.read(path);
        byte[] duplicated=BiomeGeneratorLinks.change(path,original,199,BiomeGeneratorLinks.Action.DUPLICATE);pack.replace(path,duplicated);
        var view=pages.reveal(pack,path,scope,652,200);check(view.window().page()==1 && view.revealRow()==200,"duplicate across a page boundary reveals its new global row");
        var rows=new LosslessJson(pack.read(path)).objectArray("generators");check(rows.get(200).token("future").equals("199") && rows.get(201).token("future").equals("200"),"ordered data uses global indices without replacing siblings");
        var foreign=PackWorkspace.newPack(java.util.Map.of(path,original));check(!pages.consumeReveal(foreign,path,scope,200) && pages.consumeReveal(pack,path,scope,200) && !pages.consumeReveal(pack,path,scope,200),"scroll intent is owner-bound and consumed once");
        pack.replace(path,BiomeGeneratorLinks.change(path,pack.read(path),200,BiomeGeneratorLinks.Action.UP));check(pages.reveal(pack,path,scope,652,199).window().page()==0,"up moves can return to the previous page");
        pack.replace(path,BiomeGeneratorLinks.change(path,pack.read(path),199,BiomeGeneratorLinks.Action.REMOVE));pages.reveal(pack,path,scope,651,199);
        check(new LosslessJson(pack.read(path)).objectArray("generators").size()==651,"removing the duplicate restores full count");
        pack.replace(path,BiomeChildren.change(path,pack.read(path),199,BiomeChildren.Action.DOWN));check(pages.reveal(pack,path,InspectorListPages.strings("children"),651,200).window().page()==1,"child reorder across page boundary reveals its result");
        var children=new LosslessJson(pack.read(path)).stringArrayPath("children");check(children.get(199).equals("a") && children.get(200).equals("b"),"child move does not renumber the wrong page-local entries");
        check(pages.reveal(pack,path,scope,201,200).window().page()==1 && pages.reveal(pack,path,scope,200,199).window().page()==0,"last-page removal selects the nearest surviving row");
        check(pages.reveal(pack,path,scope,0,0).window().matches()==0 && pages.select(pack,path,scope,0).revealRow()==-1,"empty lists keep one empty page without a stale scroll target");
        check(pages.reveal(pack,path,scope,651,650).window().page()==3,"append-to-end reveal reaches the last partial page");
        check(pages.select(pack,path,scope,1).window().page()==0 && pages.select(pack,path,scope,1).revealRow()==-1,"unexpected size reduction clamps UI position and releases a removed-row scroll intent");
    }
    private static void persistence(Path temp,PackWorkspace pack,String dimension,String biome,InspectorListPages pages)throws Exception{
        var drafts=new InspectorDrafts();drafts.update("children#1","a","unapplied text");
        pack.replace(dimension,DimensionFields.editArray(dimension,pack.read(dimension),"regions",600,"other",pack));
        pack.replace(biome,BiomeGeneratorLinks.rangeText(biome,pack.read(biome),600,"1e0","32.0"));
        pages.reveal(pack,biome,InspectorListPages.GENERATORS,651,600);long revision=pack.revision();int history=pack.undoCount();
        check(new LosslessJson(pack.read(dimension)).stringArrayPath("regions").get(600).equals("other") && new LosslessJson(pack.read(dimension)).stringArrayPath("regions").get(599).equals("r"),"late-page reference edit targets only its global row");
        var rows=new LosslessJson(pack.read(biome)).objectArray("generators");check(rows.get(600).token("min").equals("1e0") && rows.get(599).token("min").equals("0"),"late-page paired values preserve exact tokens and neighboring rows");
        var expected=new LinkedHashMap<String,byte[]>();for(String path:pack.paths())expected.put(path,pack.read(path));
        Path save=temp.resolve("inspector-list-save"),copy=temp.resolve("inspector-list-copy"),as=temp.resolve("inspector-list-as"),zip=temp.resolve("inspector-list.zip");pack.save(save);pack.saveCopy(copy);pack.exportZip(zip);pack.saveAs(as);
        for(var reopened:List.of(PackWorkspace.open(save),PackWorkspace.open(copy),PackWorkspace.open(as),PackWorkspace.importZip(zip))){check(reopened.paths().equals(pack.paths()),"paging adds no saved files");for(var entry:expected.entrySet())check(Arrays.equals(reopened.read(entry.getKey()),entry.getValue()),"exact late-row/unknown/opaque bytes through Save/Copy/As/ZIP");}
        check(pack.revision()==revision && pack.undoCount()==history && drafts.any() && pages.select(pack,biome,InspectorListPages.GENERATORS,651).window().page()==3,"Save modes retain page, applied history and unrelated unapplied text");
    }
}
