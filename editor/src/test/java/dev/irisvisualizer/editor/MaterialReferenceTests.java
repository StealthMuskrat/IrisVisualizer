package dev.irisvisualizer.editor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

final class MaterialReferenceTests {
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static void check(boolean valid,String reason) { if (!valid) throw new AssertionError("Material links: "+reason); }
    private static String display(String path) { return path.replaceAll("\\.(\\d+)","[$1]"); }
    private static PackWorkspace pack(String path,byte[] raw) throws Exception {
        return PackWorkspace.newPack(Map.of(path,raw,"blocks/nested/alias.json",bytes("{\"block\":\"nested/alias\"}"),"objects/opaque.iob",new byte[]{0,-1,2}));
    }
    private static List<ReferenceDiagnostics.Link> material(PackWorkspace pack,String path) {
        var result=new ArrayList<ReferenceDiagnostics.Link>();MaterialReferenceDiagnostics.inspect(pack,ResourceTypes.base(path),new LosslessJson(pack.read(path)),result::add);return List.copyOf(result);
    }
    private static boolean has(List<ReferenceDiagnostics.Link> links,String field,ReferenceDiagnostics.State state) { return links.stream().anyMatch(l -> l.field().equals(field) && l.state()==state); }
    private static String leaf() { return "{\"block\":\"nested/ali\\u0061s\",\"backup\":{\"block\":\"nested/alias\"},\"data\":{\"block\":\"nested/alias\"},\"future\":{\"block\":\"nested/alias\"}}"; }
    static void run(Path temp) throws Exception {
        var cases=NoiseStyleSourceTests.cases().stream().filter(c -> PaletteBlockData.supports(c.target())).toList();check(cases.size()==93,"all registered material palettes");
        for (var c : cases) {
            byte[] raw=NoiseStyleFields.editContainer(c.document("{}"),c.target(),p -> new LosslessJson(p.enable("palette","[]")).objectArrayInsert("palette",0,bytes(leaf())),false);
            String owner=c.stylePath().substring(0,c.stylePath().length()-6),field=display(owner)+".palette[0]";var pack=pack(c.path(),raw);var links=material(pack,c.path());
            check(links.size()==2 && has(links,field+".block",ReferenceDiagnostics.State.EXACT_FILE) && has(links,field+".backup.block",ReferenceDiagnostics.State.EXACT_FILE),"palette and backup "+c.stylePath());
            check(ReferenceDiagnostics.inspect(pack,c.path()).containsAll(links),"shared inspector integration "+c.stylePath());
            for (String bad : List.of("null","true","\"snippet\"","{}")) {
                byte[] invalid=NoiseStyleFields.editContainer(raw,c.target(),p -> bytes(new String(p.sourceBytes(),StandardCharsets.UTF_8).replace(p.token("palette"),bad)),false);
                check(has(material(pack(c.path(),invalid),c.path()),display(owner)+".palette",ReferenceDiagnostics.State.UNSUPPORTED),"malformed known list "+c.stylePath());
            }
        }
        var inline=new ArrayList<>(InlineBlockDataTests.cases());inline.add(new InlineBlockDataTests.Case(InlineBlockData.Context.find(FloatingObjectReplacements.Context.structureRoot(),0,0),"edit.0.find.0"));check(inline.size()==16,"all inline contexts including structure find");
        for (var c : inline) {
            var links=material(pack(c.path(),c.document(leaf())),c.path());String field=display(c.jsonPath());
            check(links.size()==2 && has(links,field+".block",ReferenceDiagnostics.State.EXACT_FILE) && has(links,field+".backup.block",ReferenceDiagnostics.State.EXACT_FILE),"inline "+c.jsonPath());
        }
        String path="biomes/source.json";
        String mixed="{\"decorators\":[{\"forceBlock\":null,\"palette\":[null,\"snippet\",{\"block\":7},{\"block\":\"nested/alias\"},{\"block\":\"stone\"},{\"block\":\"minecraft:stone\"},{\"block\":\"mod:stone\"},{\"block\":\"../unsafe\"},{\"backup\":null},{}],\"whitelist\":{},\"blacklist\":[{\"block\":\"nested/alias\"}]}]}";
        var links=material(pack(path,bytes(mixed)),path);
        check(links.stream().filter(l -> l.state()==ReferenceDiagnostics.State.EXACT_FILE).count()==2 && links.stream().filter(l -> l.state()==ReferenceDiagnostics.State.UNSUPPORTED).count()==4,"malformed rows isolated, ordinary/missing/namespace keys and omitted defaults not misreported");
        for (String fake : List.of("{\"decorators.0.palette\":[{\"block\":\"nested/alias\"}]}","{\"decorators\":{\"0\":{\"palette\":[{\"block\":\"nested/alias\"}]}}}","{\"future\":{\"decorators\":[{\"palette\":[{\"block\":\"nested/alias\"}]}]}}","{\"decorators\":[{\"palette\":{\"0\":{\"block\":\"nested/alias\"}}}]}","{\"block\":\"nested/alias\",\"backup\":{\"block\":\"nested/alias\"}}"))
            check(material(pack(path,bytes(fake)),path).stream().noneMatch(l -> l.state()==ReferenceDiagnostics.State.EXACT_FILE),"unknown/dotted/numeric-object paths cannot impersonate typed material owners");
        String chain="{\"block\":\"nested/alias\"}";for (int i=0;i<9;i++) chain="{\"block\":\"nested/alias\",\"backup\":"+chain+"}";
        var root=pack("blocks/source.json",bytes(chain));links=ReferenceDiagnostics.inspect(root,"blocks/source.json");check(links.stream().filter(l -> l.state()==ReferenceDiagnostics.State.EXACT_FILE).count()==9 && links.stream().anyMatch(l -> l.state()==ReferenceDiagnostics.State.UNSUPPORTED && l.key().contains("eight")),"standalone roots and eight backup levels only");
        check(material(pack("blocks/source.json",bytes("{}")),"blocks/source.json").isEmpty(),"implicit air is not an authored alias link");
        String wide="{\"unknown\":["+String.join(",",java.util.Collections.nCopies(4100,"0"))+"],\"decorators\":[{\"forceBlock\":{\"block\":\"nested/alias\"}}]}";
        check(material(pack(path,bytes(wide)),path).stream().anyMatch(l -> l.key().contains("truncated")),"node budget explicit");
        String deep="{}";for (int i=0;i<40;i++) deep="{\"future\":"+deep+"}";
        check(material(pack(path,bytes(deep)),path).stream().anyMatch(l -> l.key().contains("truncated")),"depth budget explicit");
        String many="{\"decorators\":[{\"palette\":["+String.join(",",java.util.Collections.nCopies(1100,"{\"block\":\"nested/alias\"}"))+"]}]}";
        links=ReferenceDiagnostics.inspect(pack(path,bytes(many)),path);check(links.size()==ReferenceDiagnostics.MAX_LINKS+1 && links.getLast().key().contains("truncated"),"shared link budget");
        var saved=pack(path,bytes(mixed));saved.save(temp.resolve("material-links-source"));var cache=new PackReferenceAudit.Cache();var audit=cache.refresh(saved);var query=new PackReferenceAudit.Query("",PackReferenceAudit.View.EXACT,"blocks/nested/alias.json");
        check(cache.query(saved,query).matches()==3,"incoming material consumers plus alias self declaration");
        long revision=saved.revision(),resources=saved.resourceRevision();int history=saved.undoCount();var drafts=new InspectorDrafts();drafts.update("material.draft","old","new");
        check(cache.refresh(saved).equals(audit) && drafts.any() && saved.revision()==revision && saved.resourceRevision()==resources && saved.undoCount()==history,"scan is read-only and draft independent");
        saved.saveCopy(temp.resolve("material-links-copy"));saved.saveAs(temp.resolve("material-links-as"));saved.exportZip(temp.resolve("material-links.zip"));var copy=PackWorkspace.open(temp.resolve("material-links-copy"));var zip=PackWorkspace.importZip(temp.resolve("material-links.zip"));
        for (String file : saved.paths()) check(Arrays.equals(saved.read(file),copy.read(file)) && Arrays.equals(saved.read(file),zip.read(file)),"exact persistence "+file);
        check(cache.current(saved).equals(audit),"Save/Copy/As/ZIP retain scan");saved.replace(path,bytes("{}"));check(cache.current(saved)==null && cache.refresh(saved).findings().stream().noneMatch(f -> f.source().equals(path) && f.target()!=null),"applied edit invalidation");saved.undo();check(cache.current(saved)==null && cache.refresh(saved).equals(audit),"Undo restores material links");saved.redo();check(cache.current(saved)==null,"Redo invalidation");
        check(!ReferenceDiagnostics.supports("blocks/UPPER.JSON") && !ReferenceDiagnostics.supports("future/unknown.json"),"family boundaries");
        System.out.println("PASS: authored material alias links across 93 palette and 16 inline contexts, standalone definitions/eight backup levels, typed ancestry and malformed-row isolation, no false missing vanilla keys, bounded incoming review/cache invalidation and exact persistence without registry resolution or generation");
    }
}
