package dev.irisvisualizer.editor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class StructureDefinitionTests {
    private static final String PATH="structures/nested/definition.json";
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static String text(byte[] bytes) { return new String(bytes,StandardCharsets.UTF_8); }
    private static void check(boolean ok) { if (!ok) throw new AssertionError("Structure definition contract failed"); }
    private static void rejects(Runnable action) { try { action.run(); } catch (IllegalArgumentException expected) { return; }throw new AssertionError("Expected rejected structure authoring"); }
    static void run(Path temp) throws Exception {
        byte[] empty=bytes("{}");var root=new LosslessJson(empty);
        check(StructureDefinitions.supports(PATH) && ResourceTypes.base(PATH).equals("structures") && ResourceTypes.folderFamily("structures/nested") && ResourceTypes.referenceKeys(PackWorkspace.empty(),"structures").isEmpty());
        check(StructureDefinitions.value(root,"maxDepth").equals("7") && StructureDefinitions.value(root,"maxSizeChunks").equals("8") && StructureDefinitions.value(root,"requireCaps").equals("false") && StructureDefinitions.value(root,"mode").equals("SPATIAL_JIGSAW") && StructureDefinitions.value(root,"placeMode").equals("STRUCTURE_PIECE") && Arrays.equals(root.sourceBytes(),empty));
        var files=new LinkedHashMap<String,byte[]>();files.put(PATH,empty);files.put("jigsaw-pools/nested/start.json",bytes("{not a valid pool"));files.put("jigsaw-pools/escaped_雪.json",bytes("{}"));files.put("jigsaw-pools/UPPER.JSON",bytes("{}"));files.put("objects/raw.iob",new byte[]{0,-1,5});
        var pack=PackWorkspace.newPack(files);var cache=new StructureDefinitions.PoolCache();var pools=cache.choices(pack);
        check(pools.equals(List.of("escaped_雪","nested/start")) && cache.choices(pack)==pools && ResourceFinder.Family.STRUCTURES.matches(PATH) && ResourceFinder.Family.OTHER.matches("jigsaw-pools/nested/start.json"));
        var match=cache.filter(pack,"START");check(match.count()==1 && match.keys().equals(List.of("nested/start")) && cache.filter(pack," start ")==match);
        try { match.keys().clear();throw new AssertionError(); } catch (UnsupportedOperationException expected) { }
        rejects(() -> cache.filter(pack,"x".repeat(257)));rejects(() -> cache.filter(pack,"x\u0000"));rejects(() -> cache.filter(pack,null));
        byte[] template=StructureDefinitions.template(pack,"nested/start");check(new LosslessJson(template).fields().equals(java.util.Set.of("startPool")));
        check(new LosslessJson(template).stringToken("startPool").equals("nested/start") && Arrays.equals(StructureDefinitions.setPool(PATH,template,"nested/start",pack),template));
        rejects(() -> StructureDefinitions.template(pack,"missing"));rejects(() -> StructureDefinitions.setPool(PATH,empty,null,pack));rejects(() -> StructureDefinitions.setPool(PATH,bytes("{\"startPool\":null}"),"nested/start",pack));
        var links=ReferenceDiagnostics.inspect(pack,PATH);check(links.size()==1 && links.getFirst().state()==ReferenceDiagnostics.State.ABSENT_REQUIRED);
        pack.replace(PATH,template);links=ReferenceDiagnostics.inspect(pack,PATH);check(links.size()==1 && links.getFirst().target().equals("jigsaw-pools/nested/start.json") && links.getFirst().state()==ReferenceDiagnostics.State.EXACT_FILE);
        check(PackReferenceAudit.inspect(pack).findings().stream().anyMatch(f -> f.source().equals(PATH) && f.target().equals("jigsaw-pools/nested/start.json")));
        for (String token : List.of("null","1","[]","{}")) {
            pack.replace(PATH,bytes("{\"startPool\":"+token+"}"));check(ReferenceDiagnostics.inspect(pack,PATH).getFirst().state()==ReferenceDiagnostics.State.UNSUPPORTED);
        }
        for (String key : List.of("missing","../unsafe","")) {
            pack.replace(PATH,new LosslessJson(empty).enableStringPath("startPool",key));var link=ReferenceDiagnostics.inspect(pack,PATH).getFirst();check(link.state()==(key.equals("missing") ? ReferenceDiagnostics.State.NO_EXACT_FILE : ReferenceDiagnostics.State.INVALID_KEY));
        }
        String original="{\r\n \"maxDepth\":7e0,\"maxSizeChunks\":8,\"mode\":\"SPATIAL_\\u004aIGSAW\",\"requireCaps\":false,\"startPool\":\"missing\",\"cellSize\":\"snippet/cell\",\"planarWorkcells\":null,\"themeSets\":[{\"future\":9007199254740993}],\"edit\":[{\"future\":true}],\"loot\":{\"unknown\":true},\"vanillaSource\":\"mod:kept\",\"future\":\"雪\\u0061\"\r\n}";
        byte[] raw=bytes(original);
        for (String field : StructureDefinitions.NUMBERS) {
            int low=StructureDefinitions.minimum(field),high=StructureDefinitions.maximum(field);
            for (int value : List.of(low,high)) check(StructureDefinitions.value(new LosslessJson(StructureDefinitions.setInteger(PATH,raw,field,Integer.toString(value))),field).equals(Integer.toString(value)));
            for (String bad : List.of(Integer.toString(low-1),Integer.toString(high+1),"1.00000000000000001","2147483648","NaN","Infinity","1e999999","")) rejects(() -> StructureDefinitions.setInteger(PATH,raw,field,bad));
            check(StructureDefinitions.value(new LosslessJson(StructureDefinitions.setInteger(PATH,empty,field,"1e1")),field).equals("10"));
            for (String bad : List.of("null","true","\"7\"","[]","{}","7.0000000001")) rejects(() -> StructureDefinitions.setInteger(PATH,bytes("{\""+field+"\":"+bad+"}"),field,"2"));
        }
        check(Arrays.equals(StructureDefinitions.setInteger(PATH,raw,"maxDepth","7"),raw));
        check(text(StructureDefinitions.setInteger(PATH,raw,"maxDepth","30")).equals(original.replace("\"maxDepth\":7e0","\"maxDepth\":30")));
        check(Arrays.equals(StructureDefinitions.setChoice(PATH,raw,"mode","SPATIAL_JIGSAW"),raw));
        for (String field : StructureDefinitions.ENUMS) {
            check(!StructureDefinitions.help(field).isBlank());
            for (String choice : StructureDefinitions.choices(field)) check(StructureDefinitions.value(new LosslessJson(StructureDefinitions.setChoice(PATH,raw,field,choice)),field).equals(choice));
            rejects(() -> StructureDefinitions.setChoice(PATH,raw,field,"UNKNOWN"));rejects(() -> StructureDefinitions.setChoice(PATH,raw,field,null));
            for (String bad : List.of("1","true","[]","{}")) rejects(() -> StructureDefinitions.setChoice(PATH,bytes("{\""+field+"\":"+bad+"}"),field,StructureDefinitions.choices(field).getFirst()));
            byte[] nullField=bytes("{\""+field+"\":null}");
            if (field.equals("placeMode")) rejects(() -> StructureDefinitions.setChoice(PATH,nullField,field,"STRUCTURE_PIECE"));
            else {
                check(StructureDefinitions.value(new LosslessJson(nullField),field).equals(StructureDefinitions.defaultValue(field)));
                check(new LosslessJson(StructureDefinitions.reset(PATH,nullField,field)).token(field)==null);
                check(StructureDefinitions.value(new LosslessJson(StructureDefinitions.setChoice(PATH,nullField,field,StructureDefinitions.choices(field).getFirst())),field).equals(StructureDefinitions.choices(field).getFirst()));
            }
        }
        for (String field : StructureDefinitions.EDITABLE) if (!field.equals("startPool")) {
            check(Arrays.equals(StructureDefinitions.reset(PATH,empty,field),empty));
            byte[] explicit=field.equals("requireCaps") ? StructureDefinitions.setCaps(PATH,empty,false) : StructureDefinitions.NUMBERS.contains(field) ? StructureDefinitions.setInteger(PATH,empty,field,StructureDefinitions.defaultValue(field)) : StructureDefinitions.setChoice(PATH,empty,field,StructureDefinitions.defaultValue(field));
            check(new LosslessJson(explicit).token(field)!=null && new LosslessJson(StructureDefinitions.reset(PATH,explicit,field)).token(field)==null);
        }
        check(text(StructureDefinitions.setCaps(PATH,raw,true)).equals(original.replace("\"requireCaps\":false","\"requireCaps\":true")) && Arrays.equals(StructureDefinitions.setCaps(PATH,raw,false),raw));
        for (String token : List.of("null","1","\"true\"","[]")) rejects(() -> StructureDefinitions.setCaps(PATH,bytes("{\"requireCaps\":"+token+"}"),true));
        check(StructureDefinitions.readOnlyFields(new LosslessJson(raw)).containsAll(List.of("cellSize","planarWorkcells","themeSets","edit","loot","vanillaSource","future")));
        for (String field : List.of("cellSize","themeSets","edit","loot","vanillaSource","future","startPool")) rejects(() -> StructureDefinitions.reset(PATH,raw,field));
        for (String path : List.of("biomes/a.json","dimensions/a.json","jigsaw-pools/a.json","structures/a.txt","structures/../a.json", "a.json")) {
            rejects(() -> StructureDefinitions.setInteger(path,raw,"maxDepth","8"));rejects(() -> StructureDefinitions.setChoice(path,raw,"mode","SPATIAL_JIGSAW"));rejects(() -> StructureDefinitions.setCaps(path,raw,true));rejects(() -> StructureDefinitions.setPool(path,raw,"nested/start",pack));rejects(() -> StructureDefinitions.reset(path,raw,"maxDepth"));
        }
        byte[] portable=StructureDefinitions.setChoice(PATH,raw,"compatibility","VANILLA_PORTABLE");
        check(StructureDefinitions.advisories(new LosslessJson(portable)).size()==2);portable=StructureDefinitions.setCaps(PATH,portable,true);check(StructureDefinitions.advisories(new LosslessJson(portable)).size()==3);
        portable=StructureDefinitions.setChoice(PATH,portable,"branchFailurePolicy","TERMINATE_BRANCH");check(StructureDefinitions.advisories(new LosslessJson(portable)).size()==2);
        check(StructureDefinitions.advisories(new LosslessJson(bytes("{\"compatibility\":null}"))).isEmpty());
        check(StructureDefinitions.advisories(new LosslessJson(bytes("{\"compatibility\":\"unknown\"}"))).getFirst().contains("unavailable"));
        check(StructureDefinitions.advisories(new LosslessJson(bytes("{\"compatibility\":\"VANILLA_PORTABLE\",\"requireCaps\":null,\"branchFailurePolicy\":17,\"themeSets\":null}"))).size()==3);
        // Creating/duplicating definitions is byte-preserving and exposes them to placement selectors.
        pack.replace(PATH,portable);String created=pack.createResource("structures","new_structure","nested/start");
        check(created.equals("structures/new_structure.json") && new LosslessJson(pack.read(created)).fields().equals(java.util.Set.of("startPool")));
        pack.createSubfolder("structures","more");String duplicate=pack.duplicate(PATH,"more/copied");check(Arrays.equals(pack.read(PATH),pack.read(duplicate)) && StructurePlacements.references(pack,"structures").contains("more/copied"));
        var currentPools=cache.choices(pack);check(currentPools!=pools);pack.replace("jigsaw-pools/nested/start.json",bytes("changed opaque pool"));check(cache.choices(pack)==currentPools);
        var many=new LinkedHashMap<String,byte[]>();for (int i=0;i<225;i++) many.put("jigsaw-pools/pool_"+i+".json",empty);var wide=PackWorkspace.newPack(many);var page=cache.filter(wide,"");check(page.count()==225 && page.keys().size()==200 && cache.filter(wide,"POOL_224").keys().equals(List.of("pool_224")));cache.clear();check(cache.filter(wide,"")!=page);
        check(FieldCoverage.groups(PATH).equals(List.of("structureDefinition")) && FieldCoverage.fields("structureDefinition").size()==15 && FieldCoverage.fields("structureDefinition").stream().filter(FieldCoverage.Field::implemented).count()==8 && FieldCoverage.readOnlyReason("structureDefinition","themeSets").contains("pending"));
        check(FieldCoverage.fields("structureDefinition").stream().anyMatch(field -> field.name().equals("vanillaSource") && field.classification().equals("generation") && !field.implemented() && field.reason().contains("suppression")));
        var drafts=new InspectorDrafts();drafts.update("other","old","draft");var navigation=new ResourceNavigation();navigation.request(pack,"jigsaw-pools/nested/start.json");var request=navigation.takeRequest(pack);var selected=new java.util.concurrent.atomic.AtomicReference<>(PATH);check(!drafts.transition(() -> false,() -> selected.set(request.target())) && selected.get().equals(PATH) && drafts.any());
        Path folder=temp.resolve("structure-definitions");pack.save(folder);long revision=pack.revision();int history=pack.undoCount();pack.saveCopy(temp.resolve("structure-definitions-copy"));pack.exportZip(temp.resolve("structure-definitions.zip"));
        for (var reopened : List.of(PackWorkspace.open(folder),PackWorkspace.open(temp.resolve("structure-definitions-copy")),PackWorkspace.importZip(temp.resolve("structure-definitions.zip")))) for (String path : pack.paths()) check(Arrays.equals(pack.read(path),reopened.read(path)));
        check(pack.revision()==revision && pack.undoCount()==history && drafts.any());
        System.out.println("PASS: structure root defaults/enums/bounds, exact integer and nullable-enum guards, lossless reset/no-op/unknown preservation, bounded cached pool filtering and exact links, independent portability findings, creation/duplication/catalogs, draft-safe navigation and byte-exact Save/Copy/ZIP with previews disabled");
    }
}
