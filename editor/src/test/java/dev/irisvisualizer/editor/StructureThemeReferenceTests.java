package dev.irisvisualizer.editor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;

final class StructureThemeReferenceTests {
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static void check(boolean ok,String message) { if (!ok) throw new AssertionError("Piece theme review: "+message); }
    private static void rejects(Runnable action) { try { action.run(); } catch (IllegalArgumentException expected) { return; }throw new AssertionError("Expected rejected theme review input"); }
    static void run(Path temp) throws Exception {
        var files=new LinkedHashMap<String,byte[]>();
        files.put("jigsaw-pieces/a.json",bytes("{\"themes\":[\"H\\u006ft\",\"Hot\",\"hot\"],\"unknown\":9007199254740993}"));
        files.put("jigsaw-pieces/b.json",bytes("{\"themes\":[\"hot\",\" Hot\"]}"));
        files.put("jigsaw-pieces/c.json",bytes("{}"));files.put("jigsaw-pieces/d.json",bytes("{\"themes\":null}"));files.put("jigsaw-pieces/e.json",bytes("{\"themes\":[]}"));
        files.put("jigsaw-pieces/f.json",bytes("{\"themes\":[null]}"));files.put("jigsaw-pieces/g.json",bytes("{\"themes\":[\"Hot\"],\"themes\":[]}"));files.put("jigsaw-pieces/h.json",new byte[]{(byte)0xc3,0x28});
        files.put("jigsaw-pieces/ignored.JSON",bytes("{\"themes\":[\"Hot\"]}"));files.put("future/other.json",bytes("{\"themes\":[\"Hot\"]}"));
        var pack=PackWorkspace.newPack(files);pack.save(temp.resolve("theme-review-source"));var cache=new StructureThemeReferences.Cache();check(cache.current(pack)==null,"explicit refresh only");
        long revision=pack.revision();int history=pack.undoCount();var review=cache.refresh(pack,"Hot");
        check(review.eligible()==8 && review.inspected()==8 && review.skipped()==0 && review.unfiltered()==3 && review.matchedFiles()==1 && review.hits().getFirst().path().equals("jigsaw-pieces/a.json") && review.hits().getFirst().occurrences()==2 && review.notices().size()==3 && review.incomplete(),"literal decoded tags, exact case and unsupported files");
        check(cache.current(pack)==review && pack.revision()==revision && pack.undoCount()==history && !pack.dirty(),"read-only state/cache");
        check(StructureThemeReferences.inspect(pack,"hot").matchedFiles()==2 && StructureThemeReferences.inspect(pack," Hot").matchedFiles()==1,"no normalization of piece tags");
        try { review.hits().clear();throw new AssertionError(); } catch (UnsupportedOperationException expected) { }try { review.notices().clear();throw new AssertionError(); } catch (UnsupportedOperationException expected) { }
        var limited=StructureThemeReferences.inspect(pack,"Hot",new StructureThemeReferences.Limits(1,8_000_000,200,1));check(limited.inspected()==1 && limited.skipped()==7 && limited.notices().size()==1 && limited.omittedNotices()==6,"file/notice budgets");
        var bytesLimit=StructureThemeReferences.inspect(pack,"Hot",new StructureThemeReferences.Limits(256,2,200,32));check(bytesLimit.inspected()==1 && bytesLimit.bytes()==2 && bytesLimit.skipped()==7,"later small files fit remaining byte budget");
        var many=new LinkedHashMap<String,byte[]>();for (int i=0;i<225;i++) many.put("jigsaw-pieces/p"+i+".json",bytes("{\"themes\":[\"Hot\"]}"));var wide=PackWorkspace.newPack(many);var truncated=StructureThemeReferences.inspect(wide,"Hot");check(truncated.matchedFiles()==225 && truncated.hits().size()==200 && truncated.truncated() && !truncated.incomplete(),"explicit result truncation");
        var oversized=PackWorkspace.newPack(java.util.Map.of("jigsaw-pieces/large.json",new byte[1_000_001]));check(StructureThemeReferences.inspect(oversized,"Hot").skipped()==1,"oversized source not read");
        rejects(() -> cache.refresh(pack,""));rejects(() -> cache.refresh(pack,null));rejects(() -> cache.refresh(pack,"x".repeat(257)));rejects(() -> cache.refresh(pack,"x\u0000"));rejects(() -> new StructureThemeReferences.Limits(0,1,1,1));rejects(() -> new StructureThemeReferences.Limits(1,8_000_001,1,1));
        pack.save(pack.directory());pack.saveCopy(temp.resolve("theme-review-copy"));pack.saveAs(temp.resolve("theme-review-as"));check(cache.current(pack)==review,"unchanged saves retain source review");
        var drafts=new InspectorDrafts();drafts.update("late","stored","draft");var navigation=new ResourceNavigation();navigation.request(pack,review.hits().getFirst().path());var request=navigation.takeRequest(pack);var selected=new java.util.concurrent.atomic.AtomicReference<>("structures/owner.json");
        check(!drafts.transition(() -> false,() -> selected.set(request.target())) && selected.get().equals("structures/owner.json") && drafts.any(),"deferred navigation cancellation");
        pack.replace("jigsaw-pieces/a.json",bytes("{}"));check(cache.current(pack)==null,"applied edits invalidate without rescanning");pack.undo();check(cache.current(pack)==null && cache.refresh(pack,"Hot").matchedFiles()==1,"Undo requires explicit refresh");pack.redo();check(cache.current(pack)==null,"Redo invalidates");check(cache.current(wide)==null,"workspace identity guard");cache.clear();check(cache.current(pack)==null,"explicit clear");
        pack.exportZip(temp.resolve("theme-review.zip"));var imported=PackWorkspace.importZip(temp.resolve("theme-review.zip"));for (String path : pack.paths()) check(Arrays.equals(pack.read(path),imported.read(path)),"exact export "+path);
        check(drafts.any(),"review/navigation/export do not clear drafts");
        System.out.println("PASS: bounded literal piece-theme mentions, decoded exact tags/case, absent/null/empty-list counts, malformed/UTF-8/duplicate isolation, file/byte/hit/notice limits, explicit revision/workspace cache, draft-safe navigation and exact opaque/unknown ZIP preservation without graph membership claims");
    }
}
