package dev.irisvisualizer.editor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

final class WorkspaceTextSearchTests {
    private static byte[] bytes(String s) { return s.getBytes(StandardCharsets.UTF_8); }
    private static void check(boolean ok) { if (!ok) throw new AssertionError("Workspace text search contract failed"); }
    private static void rejects(Runnable op) { try { op.run(); } catch (IllegalArgumentException expected) { return; }throw new AssertionError("Expected invalid text search input"); }
    private static WorkspaceTextSearch.Query query(String s) { return new WorkspaceTextSearch.Query(s,ResourceFinder.Family.ALL,false); }
    static void run(Path temp) throws Exception {
        String path="expressions/literal.json",source="{\r\n  \"expression\":\"雪😀 Needle Needle\",\r\"unknown\":\"Needle\",\n\"escaped\":\"\\u004e eedle\"\n}";
        var pack=PackWorkspace.newPack(Map.of(path,bytes(source),"future/malformed.json",bytes("bad JSON Needle"),"future/UPPER.JSON",bytes("Needle"),"objects/opaque.iob",bytes("Needle"),"notes.txt",bytes("Needle")));
        Path folder=temp.resolve("text-search-source");pack.save(folder);var cache=new WorkspaceTextSearch.Cache();var q=query("Needle");long revision=pack.revision();var result=cache.query(pack,q);
        check(result.matches()==5 && result.hits().size()==5 && result.eligibleFiles()==3 && result.scannedFiles()==3 && !result.incomplete() && !result.truncated() && !pack.dirty() && pack.undoCount()==0 && pack.revision()==revision && cache.query(pack,q)==result);
        var first=result.hits().getFirst();check(first.path().equals(path) && first.line()==2 && first.column()==source.substring(source.indexOf('\n')+1,source.indexOf("Needle")).length()+1 && first.offset()==source.indexOf("Needle") && first.excerpt().equals("  \"expression\":\"雪😀 Needle Needle\",") && !first.clippedBefore() && !first.clippedAfter());
        check(result.hits().get(1).line()==2 && result.hits().get(2).line()==3 && result.hits().get(3).path().equals("future/UPPER.JSON"));
        check(cache.query(pack,query("needle")).matches()==0 && cache.query(pack,query("\\u004e")).matches()==1 && cache.query(pack,query("雪😀")).matches()==1 && cache.query(pack,query(".*")).matches()==0 && cache.query(pack,query(" Needle ")).matches()==1);
        check(cache.query(pack,new WorkspaceTextSearch.Query("Needle",ResourceFinder.Family.EXPRESSIONS,false)).matches()==3 && cache.query(pack,new WorkspaceTextSearch.Query("Needle",ResourceFinder.Family.OTHER,false)).matches()==2);
        check(new WorkspaceTextSearch.Cache().query(PackWorkspace.newPack(Map.of("x.json",bytes("aaaaa"))),query("aa")).matches()==2);
        try { result.hits().clear();throw new AssertionError(); } catch (UnsupportedOperationException expected) { }
        // Cache follows byte edits, Undo/Redo, resource changes, workspace identity and Save without a revision change.
        var changedQuery=new WorkspaceTextSearch.Query("Needle",ResourceFinder.Family.ALL,true);check(cache.query(pack,changedQuery).matches()==0);
        pack.replace(path,bytes("Needle"));var changed=cache.query(pack,changedQuery);check(changed.matches()==1 && changed.scannedFiles()==1);pack.undo();check(cache.query(pack,changedQuery).matches()==0);pack.redo();check(cache.query(pack,changedQuery).matches()==1);
        revision=pack.revision();pack.save(folder);check(pack.revision()==revision && cache.query(pack,changedQuery).matches()==0);var all=cache.query(pack,q);pack.saveCopy(temp.resolve("text-search-copy"));check(cache.query(pack,q)==all);pack.replace(path,bytes("Needle Needle"));var dirty=cache.query(pack,changedQuery);check(dirty.matches()==2);pack.saveAs(temp.resolve("text-search-as"));check(cache.query(pack,changedQuery).matches()==0);
        pack.duplicate(path,"copy");check(cache.query(pack,q).matches()==6);var other=PackWorkspace.newPack(Map.of("other.json",bytes("different")));check(cache.query(other,q).matches()==0);cache.clear();check(cache.query(pack,q).matches()==6);
        // Invalid text is skipped as a whole, including malformed UTF-8 after an apparent match. Opaque assets are never scanned.
        var bad=new LinkedHashMap<String,byte[]>();bad.put("a.json",new byte[]{'N','e','e','d','l','e',(byte)0xc3,0x28});bad.put("b.json",bytes("Needle\u0000"));bad.put("c.json",bytes("Needle\u0085"));bad.put("d.json",bytes("\tNeedle\r\n"));bad.put("opaque.iob",new byte[]{(byte)0xff});
        var invalid=cache.query(PackWorkspace.newPack(bad),q);check(invalid.invalidText()==3 && invalid.scannedFiles()==1 && invalid.matches()==1 && invalid.incomplete() && invalid.hits().getFirst().column()==2 && invalid.scannedBytes()==bad.entrySet().stream().filter(e -> e.getKey().endsWith(".json")).mapToLong(e -> e.getValue().length).sum());
        // Budget skips are explicit; later small files can still fit. Results count all non-overlapping matches in inspected files.
        var bounded=PackWorkspace.newPack(Map.of("a.json",bytes("NeedleNeedle"),"b.json",bytes("Needle"),"c.json",bytes("Needle"),"d.json",bytes("Needle")));
        var limited=new WorkspaceTextSearch.Cache(new WorkspaceTextSearch.Limits(8,12,4,1)).query(bounded,q);check(limited.eligibleFiles()==4 && limited.tooLarge()==1 && limited.scannedFiles()==2 && limited.scannedBytes()==12 && limited.byteBudgetSkipped()==1 && limited.matches()==2 && limited.hits().size()==1 && limited.truncated() && limited.incomplete() && limited.hits().getFirst().path().equals("b.json"));
        var fileLimited=new WorkspaceTextSearch.Cache(new WorkspaceTextSearch.Limits(20,100,1,200)).query(bounded,q);check(fileLimited.matches()==2 && fileLimited.scannedFiles()==1 && fileLimited.fileBudgetSkipped()==3 && fileLimited.incomplete());
        var smallLater=PackWorkspace.newPack(Map.of("a.json",bytes("123456"),"b.json",bytes("NeedleNeedle"),"c.json",bytes("Needle")));var fits=new WorkspaceTextSearch.Cache(new WorkspaceTextSearch.Limits(20,12,3,200)).query(smallLater,q);check(fits.matches()==1 && fits.byteBudgetSkipped()==1 && fits.scannedFiles()==2 && fits.scannedBytes()==12);
        // Long lines/excerpts remain bounded and never split surrogate pairs at either boundary.
        String longLine="x".repeat(119)+"😀"+"Needle"+"y".repeat(300);var longResult=cache.query(PackWorkspace.newPack(Map.of("long.json",bytes(longLine))),q);var longHit=longResult.hits().getFirst();check(longHit.column()==122 && longHit.excerpt().length()<=160 && longHit.clippedBefore() && longHit.clippedAfter() && longHit.excerpt().contains("Needle"));
        String boundary="x".repeat(40)+"😀"+"x".repeat(39)+"Needle"+"y".repeat(112)+"😀tail";var boundaryHit=cache.query(PackWorkspace.newPack(Map.of("edge.json",bytes(boundary))),q).hits().getFirst();check(!Character.isLowSurrogate(boundaryHit.excerpt().charAt(0)) && !Character.isHighSurrogate(boundaryHit.excerpt().charAt(boundaryHit.excerpt().length()-1)) && boundaryHit.excerpt().length()<=160);
        var repeated=cache.query(PackWorkspace.newPack(Map.of("many.json",bytes("Needle ".repeat(240)))),q);check(repeated.matches()==240 && repeated.hits().size()==200 && repeated.truncated() && !repeated.incomplete() && repeated.hits().getLast().offset()==199*7);
        var empty=cache.query(PackWorkspace.empty(),q);check(empty.eligibleFiles()==0 && empty.hits().isEmpty() && !empty.incomplete());
        // Deferred navigation protects late drafts. Search is read-only and opening does not write to disk.
        var pending=new ResourceNavigation();var drafts=new InspectorDrafts();drafts.update("late","stored","draft");var selected=new AtomicReference<>("future/malformed.json");var hit=cache.query(pack,q).hits().getFirst();pending.request(pack,hit.path());var request=pending.takeRequest(pack);check(!drafts.transition(() -> false,() -> selected.set(request.target())) && drafts.any() && selected.get().equals("future/malformed.json"));try { drafts.transition(() -> true,() -> { throw new IOException("open failed"); });throw new AssertionError(); } catch (IOException expected) { check(drafts.any()); }check(drafts.transition(() -> true,() -> selected.set(request.target())) && !drafts.any() && selected.get().equals(hit.path()));pending.request(pack,hit.path());check(pending.takeRequest(other)==null);
        rejects(() -> query(""));rejects(() -> query(null));rejects(() -> query("x".repeat(257)));rejects(() -> query("x\n"));rejects(() -> query("x\u0000"));rejects(() -> new WorkspaceTextSearch.Query("x",null,false));check(query("x".repeat(256)).text().length()==256);rejects(() -> new WorkspaceTextSearch.Limits(0,1,1,1));rejects(() -> new WorkspaceTextSearch.Limits(1,WorkspaceTextSearch.MAX_TOTAL_BYTES+1,1,1));rejects(() -> new WorkspaceTextSearch.Limits(1,1,WorkspaceTextSearch.MAX_FILES+1,1));rejects(() -> new WorkspaceTextSearch.Limits(1,1,1,201));
        int undo=pack.undoCount();revision=pack.revision();pack.exportZip(temp.resolve("text-search.zip"));var imported=PackWorkspace.importZip(temp.resolve("text-search.zip"));check(cache.query(pack,q).matches()==cache.query(imported,q).matches());for (String p : pack.paths()) check(Arrays.equals(pack.read(p),imported.read(p)));check(pack.undoCount()==undo && pack.revision()==revision);
        System.out.println("PASS: bounded literal JSON source search, CR/LF/CRLF UTF-16 locations, raw escapes/unknown text, strict UTF-8/control skips, explicit file/byte/result budgets, surrogate-safe snippets, applied/Save/Undo/workspace cache invalidation, draft-safe deferred navigation and exact Save/ZIP without native evaluation");
    }
}
