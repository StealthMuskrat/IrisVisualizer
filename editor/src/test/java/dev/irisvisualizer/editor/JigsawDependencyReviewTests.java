package dev.irisvisualizer.editor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

final class JigsawDependencyReviewTests {
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static void check(boolean value,String detail) { if (!value) throw new AssertionError("Jigsaw dependency review: "+detail); }
    private static void rejects(Runnable action) { try { action.run(); } catch (IllegalArgumentException expected) { return; }throw new AssertionError("Expected rejected jigsaw review input"); }
    private static final String ROOT="structures/root.json",MAIN="jigsaw-pools/main.json",OTHER="jigsaw-pools/other.json",PIECE="jigsaw-pieces/start.json",LEAF="jigsaw-pieces/leaf.json",LEAF_POOL="jigsaw-pools/leaf.json",OBJECT="objects/model.iob";
    private static final JigsawDependencyReview.Query ALL=new JigsawDependencyReview.Query("",JigsawDependencyReview.Scope.ALL,null,false);
    private static boolean has(List<ReferenceDiagnostics.Link> links,String field,ReferenceDiagnostics.State state) { return links.stream().anyMatch(link -> link.field().equals(field) && link.state()==state); }
    static Map<String,byte[]> fixture() {
        var files=new LinkedHashMap<String,byte[]>();
        files.put(ROOT,bytes("{\r\n \"startPool\":\"main\",\"future\":{\"startPool\":\"ignored\"},\"edit\":[{\"find\":[],\"replace\":{\"style\":{\"expression\":\"formula\"}}}]\r\n}"));
        files.put(MAIN,bytes("{\"fallback\":\"other\",\"pieces\":[{\"piece\":\"start\",\"chance\":0e0},{\"empty\":true}]}"));
        files.put(OTHER,bytes("{\"fallback\":\"main\",\"pieces\":[{\"piece\":\"leaf\"}]}"));
        files.put(PIECE,bytes("{\"object\":\"model\",\"connectors\":[{\"pool\":\"main\"},{\"pool\":\"missing\"}],\"future\":9007199254740993}"));
        files.put(LEAF,bytes("{\"object\":\"missing\",\"connectors\":[{\"pool\":\"leaf\"}]}"));
        files.put(LEAF_POOL,bytes("{\"pieces\":[{\"piece\":\"leaf\"}]}"));
        byte[] payload=new byte[3_000_000];Arrays.fill(payload,(byte)255);files.put(OBJECT,payload);
        files.put("expressions/formula.json",bytes("invalid target content"));files.put("future/keep.dat",bytes("opaque\u0000payload"));
        files.put("jigsaw-pools/unreachable.json",bytes("invalid unreachable content"));return files;
    }
    static void run(Path temp) throws Exception {
        // Placement links distinguish local Iris resources from native structure/template-pool registry keys.
        for (String base : StructurePlacements.CONTEXTS) {
            String path=base+"/host.json";
            var pack=PackWorkspace.newPack(Map.of(path,bytes("{\"structures\":[{\"structures\":[\"root\",\"missing\",\"../bad\"],\"caveBiomes\":[\"cave\"],\"nativeStructures\":[{\"structure\":\"minecraft:village\",\"jigsaw\":{\"startPool\":\"minecraft:village/plains\"}}]},{\"structures\":null,\"caveBiomes\":null}]}"),ROOT,bytes("{}"),"biomes/cave.json",bytes("{}")));
            var links=ReferenceDiagnostics.inspect(pack,path);
            check(has(links,"structures[0].structures[0]",ReferenceDiagnostics.State.EXACT_FILE) && has(links,"structures[0].structures[1]",ReferenceDiagnostics.State.NO_EXACT_FILE) && has(links,"structures[0].structures[2]",ReferenceDiagnostics.State.INVALID_KEY),"placement links: "+base);
            check(has(links,"structures[0].caveBiomes[0]",ReferenceDiagnostics.State.EXACT_FILE) && links.stream().noneMatch(link -> link.field().contains("nativeStructures") || link.field().startsWith("structures[1]")),"registry and nullable fields: "+base);
            pack.replace(path,bytes("{\"structures\":[{\"structures\":[1],\"caveBiomes\":[\"cave\"]}]}"));links=ReferenceDiagnostics.inspect(pack,path);
            check(has(links,"structures[0].structures",ReferenceDiagnostics.State.UNSUPPORTED) && has(links,"structures[0].caveBiomes[0]",ReferenceDiagnostics.State.EXACT_FILE),"independent malformed source list");
            for (String bad : List.of("null","true","\"snippet\"","[null]")) { pack.replace(path,bytes("{\"structures\":"+bad+"}"));check(has(ReferenceDiagnostics.inspect(pack,path),"structures",ReferenceDiagnostics.State.UNSUPPORTED),"placement container type"); }
            pack.replace(path,bytes("{\"structures\":[{\"nativeStructures\":[{\"structure\":\"minecraft:village\",\"jigsaw\":{\"startPool\":\"main\"}}]}]}"));
            check(ReferenceDiagnostics.inspect(pack,path).stream().noneMatch(link -> link.field().startsWith("structures")),"native-only backend is not an absent local backend");
            pack.replace(path,bytes("{\"structures\":[{\"structures\":[\"root\"],\"caveBiomes\":[]}]}"));
            var incoming=PackReferenceAudit.filter(PackReferenceAudit.inspect(pack),new PackReferenceAudit.Query("",PackReferenceAudit.View.ALL,ROOT));check(incoming.matches()==1,"pack review sees placement incoming link");
        }
        var files=fixture();var pack=PackWorkspace.newPack(files);Path root=temp.resolve("jigsaw-review-source");pack.save(root);
        long revision=pack.revision(),resourceRevision=pack.resourceRevision();int history=pack.undoCount();var cache=new JigsawDependencyReview.Cache();check(cache.current(pack,ROOT)==null && cache.query(pack,ROOT,ALL)==null,"explicit scan only");
        var review=cache.refresh(pack,ROOT);
        check(review.nodes().size()==7 && review.jsonFiles()==6 && review.edges().size()==11 && review.count(ReferenceDiagnostics.State.NO_EXACT_FILE)==2 && review.count(ReferenceDiagnostics.State.EXACT_FILE)==9,"known reachable graph counts");
        check(!review.incomplete() && review.notices().isEmpty() && review.omittedNotices()==0,"missing exact files are recorded diagnostics, not silently skipped");
        check(review.nodes().stream().noneMatch(node -> node.path().contains("unreachable") || node.path().startsWith("expressions/")),"unrelated resources and noise/material dependencies excluded");
        check(review.nodes().stream().filter(node -> node.path().equals(OBJECT)).findFirst().orElseThrow().status()==JigsawDependencyReview.Status.OBJECT_PRESENT,"opaque oversized binary is metadata only");
        long jsonBytes=List.of(ROOT,MAIN,OTHER,PIECE,LEAF,LEAF_POOL).stream().mapToLong(pack::size).sum();check(review.bytes()==jsonBytes,"binary payload not included in JSON inspection budget");
        check(review.cycles().equals(List.of(List.of(LEAF,LEAF_POOL),List.of(PIECE,MAIN,OTHER))) && review.fallbackCycles().equals(List.of(List.of(MAIN,OTHER))),"deterministic components and fallback-only distinction");
        check(review.route(LEAF_POOL).stream().map(JigsawDependencyReview.Node::path).toList().equals(List.of(ROOT,MAIN,OTHER,LEAF,LEAF_POOL)),"shortest recorded route");
        check(review.route(ROOT).size()==1 && review.route("jigsaw-pools/missing.json").isEmpty() && review.route(null).isEmpty(),"root, missing and absent routes");
        check(review.nodes().stream().filter(node -> node.path().equals(LEAF_POOL)).findFirst().orElseThrow().distance()==4,"breadth-first distance");
        check(JigsawDependencyReview.inspect(pack,MAIN).root().equals(MAIN) && JigsawDependencyReview.inspect(pack,PIECE).route(OBJECT).size()==2,"pool and piece roots");
        check(cache.query(pack,ROOT,ALL)==cache.query(pack,ROOT,ALL),"filter cache");
        check(cache.query(pack,ROOT,new JigsawDependencyReview.Query("",JigsawDependencyReview.Scope.OUTGOING,PIECE,false)).matches()==3,"outgoing filter");
        check(cache.query(pack,ROOT,new JigsawDependencyReview.Query("",JigsawDependencyReview.Scope.INCOMING,MAIN,false)).matches()==3,"incoming filter includes back edges");
        check(cache.query(pack,ROOT,new JigsawDependencyReview.Query(" MISSING ",JigsawDependencyReview.Scope.ALL,null,true)).matches()==2,"case-insensitive text and problem filters");
        check(cache.query(pack,ROOT,new JigsawDependencyReview.Query("",JigsawDependencyReview.Scope.INCOMING,null,false)).matches()==0,"no implicit selection");
        try { review.nodes().clear();throw new AssertionError(); } catch (UnsupportedOperationException expected) { }
        try { review.edges().clear();throw new AssertionError(); } catch (UnsupportedOperationException expected) { }
        try { review.cycles().getFirst().clear();throw new AssertionError(); } catch (UnsupportedOperationException expected) { }
        try { review.route(LEAF_POOL).clear();throw new AssertionError(); } catch (UnsupportedOperationException expected) { }
        // Scans read current applied memory; external disk edits, navigation and Save do not mutate the graph root.
        Files.writeString(root.resolve(ROOT),"external disk edit");check(cache.refresh(pack,ROOT).equals(review),"disk is not scanned");
        var selected=new AtomicReference<>(ROOT);var drafts=new InspectorDrafts();drafts.update("late","stored","draft");var navigation=new ResourceNavigation();navigation.request(pack,OBJECT);var request=navigation.takeRequest(pack);
        check(!drafts.transition(() -> false,() -> selected.set(request.target())) && drafts.any() && selected.get().equals(ROOT) && cache.current(pack,ROOT).equals(review),"cancel retains late drafts and pinned root");
        try { drafts.transition(() -> true,() -> { throw new IOException("failed navigation"); });throw new AssertionError(); } catch (IOException expected) { check(drafts.any(),"failed navigation retains drafts"); }
        check(drafts.transition(() -> true,() -> selected.set(request.target())) && selected.get().equals(OBJECT) && cache.current(pack,ROOT).equals(review),"confirmed navigation keeps root");
        pack.saveCopy(temp.resolve("jigsaw-review-copy"));pack.saveAs(temp.resolve("jigsaw-review-as"));pack.save(pack.directory());pack.exportZip(temp.resolve("jigsaw-review.zip"));
        for (var reopened : List.of(PackWorkspace.open(temp.resolve("jigsaw-review-copy")),PackWorkspace.open(temp.resolve("jigsaw-review-as")),PackWorkspace.importZip(temp.resolve("jigsaw-review.zip")))) {
            check(JigsawDependencyReview.inspect(reopened,ROOT).equals(review),"review round trip");for (String file : pack.paths()) check(Arrays.equals(pack.read(file),reopened.read(file)),"exact bytes: "+file);
        }
        check(pack.revision()==revision && pack.resourceRevision()==resourceRevision && pack.undoCount()==history && !pack.dirty() && cache.current(pack,ROOT).equals(review),"review/persistence preserve applied state");
        pack.replace(ROOT,bytes("{\"startPool\":\"missing\"}"));check(cache.current(pack,ROOT)==null,"applied edit invalidates");cache.refresh(pack,ROOT);pack.undo();check(cache.current(pack,ROOT)==null && cache.refresh(pack,ROOT).equals(review),"undo invalidates");pack.redo();check(cache.current(pack,ROOT)==null,"redo invalidates");pack.undo();cache.refresh(pack,ROOT);
        pack.duplicate(PIECE,"copy");check(cache.current(pack,ROOT)==null,"new resource invalidates");cache.refresh(pack,ROOT);rejects(() -> cache.refresh(pack,OBJECT));check(cache.current(pack,ROOT)!=null,"failed refresh retains current review");
        check(cache.current(pack,MAIN)==null && cache.current(pack,ROOT)==null,"changing root invalidates without auto-scan");cache.refresh(pack,ROOT);check(cache.current(PackWorkspace.empty(),ROOT)==null,"workspace replacement invalidates");
        // Partial scans expose every kind of budget failure; supported small files can still fit after a skip.
        var limited=JigsawDependencyReview.inspect(pack,MAIN,new JigsawDependencyReview.Limits(1,256,8_000_000,4096,1));check(limited.nodes().size()==1 && limited.notices().size()==1 && limited.omittedNotices()==1 && limited.incomplete(),"node/notice limits");
        limited=JigsawDependencyReview.inspect(pack,ROOT,new JigsawDependencyReview.Limits(512,1,8_000_000,4096,128));check(limited.jsonFiles()==1 && limited.nodes().size()==2 && limited.nodes().getLast().status()==JigsawDependencyReview.Status.SKIPPED && limited.incomplete(),"JSON file budget");
        limited=JigsawDependencyReview.inspect(pack,ROOT,new JigsawDependencyReview.Limits(512,256,pack.size(ROOT),4096,128));check(limited.jsonFiles()==1 && limited.bytes()==pack.size(ROOT) && limited.incomplete(),"byte budget");
        limited=JigsawDependencyReview.inspect(pack,ROOT,new JigsawDependencyReview.Limits(512,256,8_000_000,1,128));check(limited.edges().size()==1 && limited.incomplete(),"edge budget");
        var fit=PackWorkspace.newPack(Map.of(MAIN,bytes("{\"fallback\":\"other\",\"pieces\":[{\"piece\":\"start\"}]}"),OTHER,bytes(" ".repeat(2000)+"{}"),PIECE,bytes("{\"object\":\"model\"}"),OBJECT,new byte[]{(byte)255}));
        var fitted=JigsawDependencyReview.inspect(fit,MAIN,new JigsawDependencyReview.Limits(512,256,fit.size(MAIN)+fit.size(PIECE),4096,128));check(fitted.jsonFiles()==2 && fitted.nodes().stream().anyMatch(node -> node.path().equals(PIECE) && node.status()==JigsawDependencyReview.Status.INSPECTED) && fitted.nodes().stream().anyMatch(node -> node.status()==JigsawDependencyReview.Status.OBJECT_PRESENT),"small sibling and metadata fit after oversized byte-budget skip");
        var bad=PackWorkspace.newPack(Map.of(ROOT,bytes("{\"startPool\":\"main\"}"),MAIN,bytes("invalid UTF-8/object JSON")));var broken=JigsawDependencyReview.inspect(bad,ROOT);check(broken.incomplete() && broken.nodes().getLast().status()==JigsawDependencyReview.Status.UNAVAILABLE && broken.edges().getLast().state()==ReferenceDiagnostics.State.UNSUPPORTED,"invalid target is visited but not validated");
        bad.replace(MAIN,new byte[ReferenceDiagnostics.MAX_JSON_BYTES+1]);broken=JigsawDependencyReview.inspect(bad,ROOT);check(broken.incomplete() && broken.nodes().getLast().status()==JigsawDependencyReview.Status.SKIPPED && broken.jsonFiles()==1,"oversized JSON skipped");
        for (String malformed : List.of("{\"pieces\":null}","{\"pieces\":[{\"empty\":true,\"piece\":\"start\"}]}","{\"fallback\":1,\"pieces\":[{\"piece\":\"start\"}]}")) { bad.replace(MAIN,bytes(malformed));check(JigsawDependencyReview.inspect(bad,ROOT).incomplete(),"unsupported declarations remain explicit"); }
        var badPiece=PackWorkspace.newPack(Map.of(PIECE,bytes("{\"object\":\"model\",\"connectors\":[null]}"),OBJECT,new byte[]{1}));var partial=JigsawDependencyReview.inspect(badPiece,PIECE);check(partial.incomplete() && partial.nodes().size()==2 && partial.edges().stream().anyMatch(edge -> edge.field().equals("connectors") && edge.state()==ReferenceDiagnostics.State.UNSUPPORTED),"malformed connectors preserve independent object inspection");
        var chain=new LinkedHashMap<String,byte[]>();for (int i=0;i<100;i++) chain.put("jigsaw-pools/c"+i+".json",bytes("{\"pieces\":[{\"empty\":true}]"+(i<99 ? ",\"fallback\":\"c"+(i+1)+"\"" : "")+"}"));var longReview=JigsawDependencyReview.inspect(PackWorkspace.newPack(chain),"jigsaw-pools/c0.json");check(!longReview.incomplete() && longReview.nodes().size()==100 && longReview.route("jigsaw-pools/c99.json").size()==100 && longReview.cycles().isEmpty(),"long authored paths use bounded iterative traversal, not native fallback recursion");
        var manyFiles=new LinkedHashMap<String,byte[]>();for (int i=0;i<205;i++) manyFiles.put("jigsaw-pieces/p"+i+".json",bytes("{\"object\":\"missing\"}"));manyFiles.put(MAIN,bytes("{\"pieces\":["+java.util.stream.IntStream.range(0,205).mapToObj(i -> "{\"piece\":\"p"+i+"\"}").collect(java.util.stream.Collectors.joining(","))+"]}"));
        var many=JigsawDependencyReview.inspect(PackWorkspace.newPack(manyFiles),MAIN);var result=JigsawDependencyReview.filter(many,ALL);check(result.matches()==410 && result.edges().size()==200 && result.truncated(),"display cap independent of traversal");check(JigsawDependencyReview.filter(many,new JigsawDependencyReview.Query("p204.json",JigsawDependencyReview.Scope.ALL,null,false)).matches()==2,"later links remain searchable");
        rejects(() -> JigsawDependencyReview.inspect(pack,"structures/missing.json"));rejects(() -> JigsawDependencyReview.inspect(pack,"structures/nested/UPPER.JSON"));rejects(() -> new JigsawDependencyReview.Query(null,JigsawDependencyReview.Scope.ALL,null,false));rejects(() -> new JigsawDependencyReview.Query("x".repeat(257),JigsawDependencyReview.Scope.ALL,null,false));rejects(() -> new JigsawDependencyReview.Query("x\u0000",JigsawDependencyReview.Scope.ALL,null,false));rejects(() -> new JigsawDependencyReview.Query("",null,null,false));rejects(() -> new JigsawDependencyReview.Query("",JigsawDependencyReview.Scope.ALL,"x\u0000",false));
        rejects(() -> new JigsawDependencyReview.Limits(0,1,1,1,1));rejects(() -> new JigsawDependencyReview.Limits(513,1,1,1,1));rejects(() -> new JigsawDependencyReview.Limits(1,257,1,1,1));rejects(() -> new JigsawDependencyReview.Limits(1,1,8_000_001,1,1));rejects(() -> new JigsawDependencyReview.Limits(1,1,1,4097,1));rejects(() -> new JigsawDependencyReview.Limits(1,1,1,1,129));
        System.out.println("PASS: bounded authored jigsaw dependency traversal, placement/local-vs-native links, pinned roots, shortest routes, deterministic repeated/fallback groups, metadata-only objects, explicit node/file/byte/edge/notice/display limits, malformed-data isolation, revision-aware refresh, draft-safe navigation and byte-exact persistence without native assembly");
    }
}
