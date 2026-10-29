package dev.irisvisualizer.editor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

final class NoiseReferenceTests {
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static void check(boolean value,String message) { if (!value) throw new AssertionError("Noise source reference contract: "+message); }
    private static boolean has(List<ReferenceDiagnostics.Link> links,String field,ReferenceDiagnostics.State state) { return links.stream().anyMatch(link -> link.field().equals(field) && link.state()==state); }
    private static List<ReferenceDiagnostics.Link> inspect(byte[] raw,String base) throws Exception {
        var pack=PackWorkspace.newPack(Map.of(base+"/source.json",raw,"expressions/formula.json",bytes("opaque target"),"image-maps/map.json",bytes("opaque target")));
        return ReferenceDiagnostics.inspect(pack,base+"/source.json");
    }
    static void run(Path temp) throws Exception {
        // Reuse every authoring context fixture instead of maintaining another list of known style paths.
        var cases=NoiseStyleSourceTests.cases();check(cases.size()==168,"all current owner contexts are exercised");
        for (var item : cases) {
            String prefix=item.stylePath().replaceAll("\\.(\\d+)","[$1]");
            byte[] raw=item.document("{\"expression\":\"form\\u0075la\",\"imageMap\":\"map\",\"fracture\":{\"expression\":\"missing\",\"fracture\":{\"imageMap\":\"map\"}},\"future\":{\"expression\":\"ignored\"}}");
            var links=inspect(raw,item.target().resourceBase());
            check(has(links,prefix+".expression",ReferenceDiagnostics.State.EXACT_FILE),"expression at "+item.stylePath());
            check(has(links,prefix+".imageMap",ReferenceDiagnostics.State.EXACT_FILE),"image map at "+item.stylePath());
            check(has(links,prefix+".fracture.expression",ReferenceDiagnostics.State.NO_EXACT_FILE),"style fracture at "+item.stylePath());
            check(has(links,prefix+".fracture.fracture.imageMap",ReferenceDiagnostics.State.EXACT_FILE),"nested style fracture at "+item.stylePath());
            check(links.stream().noneMatch(link -> link.field().contains(".future.")),"unknown style members ignored");
            for (String bad : List.of("null","true","[]","\"snippet\"")) {
                links=inspect(item.document(bad),item.target().resourceBase());check(has(links,prefix,ReferenceDiagnostics.State.UNSUPPORTED),"malformed style isolated: "+item.stylePath());
            }
        }
        String generator="{\"composite\":[{\"enabled\":false,\"style\":{\"expression\":\"formula\",\"imageMap\":\"map\"},\"fracture\":[{\"style\":{\"expression\":\"missing\"},\"fracture\":[{\"style\":{\"imageMap\":\"map\"}}]},null]},\"snippet\",{\"style\":{\"imageMap\":\"map\"}}],\"cliffHeightGenerator\":{\"style\":{\"expression\":\"formula\"}}}";
        var links=inspect(bytes(generator),"generators");
        check(has(links,"composite[0].style.expression",ReferenceDiagnostics.State.EXACT_FILE),"disabled noise declarations still listed");
        check(has(links,"composite[0].style.imageMap",ReferenceDiagnostics.State.EXACT_FILE),"both declared sources retained");
        check(has(links,"composite[0].fracture[0].style.expression",ReferenceDiagnostics.State.NO_EXACT_FILE),"noise fracture source");
        check(has(links,"composite[0].fracture[0].fracture[0].style.imageMap",ReferenceDiagnostics.State.EXACT_FILE),"nested noise fracture source");
        check(has(links,"composite[0].fracture[1]",ReferenceDiagnostics.State.UNSUPPORTED) && has(links,"composite[1]",ReferenceDiagnostics.State.UNSUPPORTED),"malformed rows reported independently");
        check(has(links,"composite[2].style.imageMap",ReferenceDiagnostics.State.EXACT_FILE) && has(links,"cliffHeightGenerator.style.expression",ReferenceDiagnostics.State.EXACT_FILE),"later independent rows survive");
        for (String bad : List.of("null","true","{}","\"snippet\"")) check(has(inspect(bytes("{\"composite\":"+bad+"}"),"generators"),"composite",ReferenceDiagnostics.State.UNSUPPORTED),"noise list type");
        links=inspect(bytes("{\"variables\":[{\"engineValue\":\"ENGINE_HEIGHT\",\"styleValue\":{\"expression\":\"formula\"}}],\"functions\":[{\"styleValue\":{\"imageMap\":\"map\"}}],\"expression\":\"formula\",\"future\":{\"styleValue\":{\"expression\":\"ignored\"}}}"),"expressions");
        check(links.size()==2 && has(links,"variables[0].styleValue.expression",ReferenceDiagnostics.State.EXACT_FILE) && has(links,"functions[0].styleValue.imageMap",ReferenceDiagnostics.State.EXACT_FILE),"expression body text is not a resource link; engine precedence is not inferred");
        links=inspect(bytes("{\"regionStyle\":{\"expression\":null,\"imageMap\":null,\"fracture\":null},\"objects\":[{\"heightmap\":null}]}"),"dimensions");
        check(links.stream().noneMatch(link -> link.field().startsWith("regionStyle")),"null source and style fracture defaults are absent");
        links=inspect(bytes("{\"objects\":[{\"heightmap\":null}]}"),"regions");check(links.stream().noneMatch(link -> link.field().contains("heightmap")),"null heightmap delegates to native queries");
        links=inspect(bytes("{\"regionStyle\":{\"expression\":7,\"imageMap\":\"../bad\",\"fracture\":[]},\"landBiomeStyle\":{\"expression\":\"\"},\"seaBiomeStyle\":{\"expression\":\"wrong.JSON\"}}"),"dimensions");
        check(has(links,"regionStyle.expression",ReferenceDiagnostics.State.UNSUPPORTED) && has(links,"regionStyle.imageMap",ReferenceDiagnostics.State.INVALID_KEY) && has(links,"regionStyle.fracture",ReferenceDiagnostics.State.UNSUPPORTED) && has(links,"landBiomeStyle.expression",ReferenceDiagnostics.State.INVALID_KEY) && has(links,"seaBiomeStyle.expression",ReferenceDiagnostics.State.NO_EXACT_FILE),"independent invalid, malformed and exact suffix checks");
        // Literal property names and numeric object members cannot impersonate a typed nested path.
        for (var input : Map.of("dimensions","{\"regionStyle.expression\":\"formula\",\"future\":{\"regionStyle\":{\"expression\":\"formula\"}}}","expressions","{\"variables\":{\"0\":{\"styleValue\":{\"expression\":\"formula\"}}},\"variables.0.styleValue\":{\"expression\":\"formula\"}}", "generators","{\"composite\":{\"0\":{\"style\":{\"expression\":\"formula\"}}},\"future\":{\"style\":{\"expression\":\"formula\"}}}").entrySet())
            check(inspect(bytes(input.getValue()),input.getKey()).stream().noneMatch(link -> link.state()==ReferenceDiagnostics.State.EXACT_FILE),"typed ancestry: "+input.getKey());
        // Node/depth caps report incomplete inspection even when unknown data consumes the budget.
        String deep="{\"expression\":\"formula\"}";for (int i=0;i<40;i++) deep="{\"fracture\":"+deep+"}";
        check(inspect(bytes("{\"regionStyle\":"+deep+"}"),"dimensions").stream().anyMatch(link -> link.state()==ReferenceDiagnostics.State.UNSUPPORTED && link.key().contains("truncated")),"depth budget");
        String wide="{\"unknown\":["+String.join(",",java.util.Collections.nCopies(4100,"0"))+"],\"regionStyle\":{\"expression\":\"formula\"}}";
        check(inspect(bytes(wide),"dimensions").stream().anyMatch(link -> link.state()==ReferenceDiagnostics.State.UNSUPPORTED && link.key().contains("truncated")),"node budget");
        String many="{\"composite\":["+String.join(",",java.util.Collections.nCopies(1100,"{\"style\":{\"expression\":\"formula\",\"imageMap\":\"map\"}}"))+"]}";
        links=inspect(bytes(many),"generators");check(links.size()==ReferenceDiagnostics.MAX_LINKS+1 && links.getLast().key().contains("truncated"),"shared per-resource link budget");
        // Inspection remains memory-only, revision-aware, draft-safe and byte preserving through all persistence paths.
        String path="generators/source.json";byte[] raw=bytes(generator);var pack=PackWorkspace.newPack(Map.of(path,raw,"expressions/formula.json",bytes("{\"variables\":[{\"styleValue\":{\"expression\":\"formula\"}}]}"),"image-maps/map.json",bytes("{\"source\":\"missing\"}"),"objects/keep.iob",new byte[]{0,-1,3},"future/keep.dat",bytes("opaque")));
        Path root=temp.resolve("noise-links-source");pack.save(root);long revision=pack.revision(),resources=pack.resourceRevision();var cache=new PackReferenceAudit.Cache();var audit=cache.refresh(pack);int history=pack.undoCount();
        check(audit.eligibleFiles()==3 && audit.findings().stream().anyMatch(f -> f.source().equals("expressions/formula.json") && f.target()!=null && f.target().equals("expressions/formula.json")),"self references recorded without recursive loading");
        var incoming=cache.query(pack,new PackReferenceAudit.Query("",PackReferenceAudit.View.EXACT,"expressions/formula.json"));check(incoming.matches()==3,"incoming expression links");
        Files.writeString(root.resolve(path),"external edit");check(cache.refresh(pack).equals(audit) && Arrays.equals(pack.read(path),raw),"disk changes not read by scan");
        var drafts=new InspectorDrafts();drafts.update("late","old","new");var navigation=new ResourceNavigation();var selected=new AtomicReference<>(path);navigation.request(pack,"expressions/formula.json");var request=navigation.takeRequest(pack);check(!drafts.transition(() -> false,() -> selected.set(request.target())) && drafts.any() && selected.get().equals(path),"cancel retains late drafts");
        check(drafts.transition(() -> true,() -> selected.set(request.target())) && !drafts.any() && selected.get().equals("expressions/formula.json"),"confirmed deferred navigation");
        pack.saveCopy(temp.resolve("noise-links-copy"));pack.saveAs(temp.resolve("noise-links-as"));pack.exportZip(temp.resolve("noise-links.zip"));var copy=PackWorkspace.open(temp.resolve("noise-links-copy"));var imported=PackWorkspace.importZip(temp.resolve("noise-links.zip"));
        for (String file : pack.paths()) check(Arrays.equals(pack.read(file),copy.read(file)) && Arrays.equals(pack.read(file),imported.read(file)),"exact persistence: "+file);
        check(pack.revision()==revision && pack.resourceRevision()==resources && pack.undoCount()==history && !pack.dirty() && cache.current(pack).equals(audit),"inspection and persistence do not change applied state");
        pack.replace(path,bytes("{}"));check(cache.current(pack)==null,"edit invalidation");cache.refresh(pack);pack.undo();check(cache.current(pack)==null && cache.refresh(pack).equals(audit),"undo invalidation");pack.redo();check(cache.current(pack)==null,"redo invalidation");
        check(ReferenceDiagnostics.inspect(pack,"future/keep.dat").isEmpty() && !ReferenceDiagnostics.supports("generators/UPPER.JSON"),"unknown and uppercase families remain unsupported");
        System.out.println("PASS: typed noise dependency links across 168 owner contexts, recursive style/noise fractures, declared-source precedence neutrality, malformed/unknown isolation, bounded source/link inspection, memory-only incoming review, deferred navigation and byte-exact persistence without native loading or mutation");
    }
}
