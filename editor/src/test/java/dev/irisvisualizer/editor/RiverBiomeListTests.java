package dev.irisvisualizer.editor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class RiverBiomeListTests {
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static String text(byte[] source) { return new String(source,StandardCharsets.UTF_8); }
    private static void check(boolean condition) { if (!condition) throw new AssertionError("River biome selection contract failed"); }
    private static void rejects(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Expected rejected river biome selection operation");
    }
    private static LosslessJson policy(byte[] source) { return new LosslessJson(new LosslessJson(source).token("riverPolicy").getBytes(StandardCharsets.UTF_8)); }
    private static List<ReferenceDiagnostics.Link> riverLinks(PackWorkspace workspace,String path) {
        return ReferenceDiagnostics.inspect(workspace,path).stream().filter(link -> link.field().startsWith("riverPolicy")).toList();
    }
    static void run(Path temp) throws Exception {
        var workspace = PackWorkspace.newPack(Map.of(
                "dimensions/lists.json",bytes("{}"),"regions/lists.json",bytes("{}"),"biomes/lists.json",bytes("{}"),
                "biomes/parent.json",bytes("{}"),"biomes/nested/child.json",bytes("{}"),"biomes/not_json.json",bytes("invalid target content"),"generators/flat.json",bytes("{}")));
        check(RiverBiomeLists.choices(workspace).equals(List.of("lists","nested/child","not_json","parent")));
        check(RiverBiomeLists.EDITABLE.size()==5);
        String first = "\"nested\\u002fchild\"", second = "\"unresolved\"", third = "\"parent\"", gap = ",\r\n  ";
        String arrayStart = "[\r\n  ", arrayEnd = "\r\n ]", array = arrayStart+first+gap+second+gap+third+arrayEnd;
        var prefixes = new HashSet<String>();
        for (String context : CaveProfileFields.CONTEXTS) {
            String path = context+"/lists.json";
            for (String field : RiverBiomeLists.EDITABLE) {
                check(!RiverBiomeLists.description(field).isBlank() && prefixes.add(RiverBiomeLists.draftPrefix(context,field)));
                String sibling = field.equals("surfaceBiomes") ? "mouthBiomes" : "surfaceBiomes";
                String prefix = "{\r\n \"riverPolicy\":{\""+field+"\":";
                String suffix = ",\""+sibling+"\":null,\"widthMultiplier\":1e0,\"profiles\":[\"unresolved-profile\"],\"surfacePools\":[\"unresolved-pool\"],\"future\":\"雪\\u0061\"},\"unknown\":9007199254740993\r\n}\r\n";
                byte[] original = bytes(prefix+array+suffix), copy = original.clone();
                check(text(RiverBiomeLists.setInherited(path,original,field,true)).equals(prefix+"null"+suffix));
                check(text(RiverBiomeLists.setInherited(path,original,field,false)).equals(prefix+"[]"+suffix));
                byte[] absent = bytes("{\"riverPolicy\":{\"future\":true}}");
                check(Arrays.equals(RiverBiomeLists.setInherited(path,absent,field,true),absent));
                byte[] empty = RiverBiomeLists.setInherited(path,absent,field,false);
                check(policy(empty).fields().equals(Set.of(field,"future")) && policy(empty).token(field).equals("[]"));
                byte[] explicitNull = bytes(prefix+"null"+suffix);
                check(Arrays.equals(RiverBiomeLists.setInherited(path,explicitNull,field,true),explicitNull));
                check(text(RiverBiomeLists.setInherited(path,explicitNull,field,false)).equals(prefix+"[]"+suffix));
                check(text(RiverBiomeLists.edit(path,original,field,1,"not_json",workspace)).equals(prefix+arrayStart+first+gap+"\"not_json\""+gap+third+arrayEnd+suffix));
                byte[] appended = RiverBiomeLists.edit(path,original,field,3,"lists",workspace);
                check(policy(appended).stringArrayPath(field).equals(List.of("nested/child","unresolved","parent","lists")));
                byte[] duplicate = RiverBiomeLists.change(path,original,field,0,RiverBiomeLists.Action.DUPLICATE);
                check(text(duplicate).equals(prefix+arrayStart+first+gap+first+", "+second+gap+third+arrayEnd+suffix));
                check(Arrays.equals(RiverBiomeLists.edit(path,duplicate,field,1,null,workspace),original));
                byte[] unresolvedCopy = RiverBiomeLists.change(path,original,field,1,RiverBiomeLists.Action.DUPLICATE);
                check(policy(unresolvedCopy).stringArrayPath(field).equals(List.of("nested/child","unresolved","unresolved","parent")));
                byte[] moved = RiverBiomeLists.change(path,original,field,0,RiverBiomeLists.Action.DOWN);
                check(text(moved).equals(prefix+arrayStart+second+gap+first+gap+third+arrayEnd+suffix));
                check(Arrays.equals(RiverBiomeLists.change(path,moved,field,1,RiverBiomeLists.Action.UP),original));
                check(text(RiverBiomeLists.edit(path,original,field,0,null,workspace)).equals(prefix+arrayStart+second+gap+third+arrayEnd+suffix));
                check(text(RiverBiomeLists.edit(path,original,field,1,null,workspace)).equals(prefix+arrayStart+first+gap+third+arrayEnd+suffix));
                check(text(RiverBiomeLists.edit(path,original,field,2,null,workspace)).equals(prefix+arrayStart+first+gap+second+arrayEnd+suffix));
                byte[] only = RiverBiomeLists.edit(path,empty,field,0,"nested/child",workspace);
                check(policy(RiverBiomeLists.edit(path,only,field,0,null,workspace)).token(field).equals("[]"));
                for (String key : List.of(""," ","missing","parent.json","biomes/parent","namespace:alias","nested\\child","../parent"))
                    rejects(() -> RiverBiomeLists.edit(path,original,field,0,key,workspace));
                for (byte[] inherited : List.of(absent,explicitNull)) {
                    rejects(() -> RiverBiomeLists.edit(path,inherited,field,0,"parent",workspace));
                    rejects(() -> RiverBiomeLists.change(path,inherited,field,0,RiverBiomeLists.Action.DUPLICATE));
                }
                for (String bad : List.of("{}","1","false","\"unsupported-list\"","[\"parent\",null]","[{},\"parent\"]")) {
                    byte[] wrong = bytes(prefix+bad+suffix);
                    rejects(() -> RiverBiomeLists.setInherited(path,wrong,field,true));
                    rejects(() -> RiverBiomeLists.setInherited(path,wrong,field,false));
                    rejects(() -> RiverBiomeLists.edit(path,wrong,field,0,"parent",workspace));
                    rejects(() -> RiverBiomeLists.change(path,wrong,field,0,RiverBiomeLists.Action.DUPLICATE));
                }
                for (String missing : List.of("{}","{\"riverPolicy\":null}","{\"riverPolicy\":\"unsupported-policy\"}")) {
                    rejects(() -> RiverBiomeLists.setInherited(path,bytes(missing),field,true));
                    rejects(() -> RiverBiomeLists.setInherited(path,bytes(missing),field,false));
                    rejects(() -> RiverBiomeLists.edit(path,bytes(missing),field,0,"parent",workspace));
                    rejects(() -> RiverBiomeLists.change(path,bytes(missing),field,0,RiverBiomeLists.Action.DUPLICATE));
                }
                rejects(() -> RiverBiomeLists.edit(path,original,field,-1,null,workspace));
                rejects(() -> RiverBiomeLists.edit(path,original,field,4,"parent",workspace));
                rejects(() -> RiverBiomeLists.change(path,original,field,0,RiverBiomeLists.Action.UP));
                rejects(() -> RiverBiomeLists.change(path,original,field,2,RiverBiomeLists.Action.DOWN));
                rejects(() -> RiverBiomeLists.change(path,original,field,3,RiverBiomeLists.Action.DUPLICATE));
                rejects(() -> RiverBiomeLists.change(path,original,field,0,null));
                check(Arrays.equals(original,copy));
                check(DimensionFields.readOnlyPaths(new LosslessJson(original)).equals(List.of("riverPolicy.future","unknown")));
                check(DimensionFields.readOnlyPaths(new LosslessJson(bytes(prefix+"[null]"+suffix))).contains("riverPolicy."+field));
                workspace.replace(path,original);
                long revision = workspace.revision();
                var findings = riverLinks(workspace,path);
                check(findings.size()==3 && findings.getFirst().field().equals("riverPolicy."+field+"[0]"));
                check(findings.get(0).state()==ReferenceDiagnostics.State.EXACT_FILE && findings.get(0).target().equals("biomes/nested/child.json"));
                check(findings.get(1).state()==ReferenceDiagnostics.State.NO_EXACT_FILE && findings.get(2).state()==ReferenceDiagnostics.State.EXACT_FILE);
                check(workspace.revision()==revision && Arrays.equals(workspace.read(path),original));
                workspace.replace(path,RiverBiomeLists.setInherited(path,original,field,true));
                check(riverLinks(workspace,path).isEmpty());
                workspace.replace(path,RiverBiomeLists.setInherited(path,original,field,false));
                check(riverLinks(workspace,path).isEmpty());
                workspace.replace(path,bytes(prefix+"[null]"+suffix));
                findings = riverLinks(workspace,path);
                check(findings.size()==1 && findings.getFirst().field().equals("riverPolicy."+field) && findings.getFirst().state()==ReferenceDiagnostics.State.UNSUPPORTED);
                workspace.replace(path,bytes(prefix+"[\"namespace:alias\",\"not_json\"]"+suffix));
                findings = riverLinks(workspace,path);
                check(findings.size()==2 && findings.get(0).state()==ReferenceDiagnostics.State.INVALID_KEY && findings.get(1).state()==ReferenceDiagnostics.State.EXACT_FILE);
            }
            workspace.replace(path,bytes("{}"));
            check(riverLinks(workspace,path).isEmpty());
            workspace.replace(path,bytes("{\"riverPolicy\":null}"));
            var nullPolicy = riverLinks(workspace,path);
            check(context.equals("dimensions") ? nullPolicy.size()==1 && nullPolicy.getFirst().state()==ReferenceDiagnostics.State.UNSUPPORTED : nullPolicy.isEmpty());
        }
        check(prefixes.size()==15);
        byte[] sample = bytes("{\"riverPolicy\":{\"surfaceBiomes\":[\"parent\"]}}");
        for (String path : List.of("generators/flat.json","dimensions/nested/test.json","test.json")) {
            rejects(() -> RiverBiomeLists.setInherited(path,sample,"surfaceBiomes",false));
            rejects(() -> RiverBiomeLists.edit(path,sample,"surfaceBiomes",0,"parent",workspace));
            rejects(() -> RiverBiomeLists.change(path,sample,"surfaceBiomes",0,RiverBiomeLists.Action.DUPLICATE));
        }
        for (String field : List.of("profiles","surfacePools","widthMultiplier")) rejects(() -> RiverBiomeLists.setInherited("biomes/lists.json",sample,field,false));
        var drafts = new InspectorDrafts();
        for (String prefix : prefixes) drafts.update(prefix+"0","parent","nested/child");
        String scope = RiverBiomeLists.draftPrefix("biomes","surfaceBiomes");
        drafts.update(scope+"new","","parent");
        drafts.update(RiverPolicyFields.draftPrefix("biomes")+"widthMultiplier","1","2");
        drafts.update(CaveThresholdFields.draftPrefix("biomes")+"min","0","1");
        check(!drafts.transitionUnder(scope,() -> false,() -> { throw new AssertionError("Cancelled river list transition ran"); }) && drafts.count()==18);
        try { drafts.transitionUnder(scope,() -> true,() -> { throw new IOException("Failed river list transition"); }); throw new AssertionError("Expected failed transition"); }
        catch (IOException expected) { check(drafts.count()==18); }
        byte[][] applied = {sample};
        drafts.transitionUnder(scope,() -> true,() -> applied[0]=RiverBiomeLists.change("biomes/lists.json",sample,"surfaceBiomes",0,RiverBiomeLists.Action.DUPLICATE));
        check(drafts.count()==16 && drafts.contains(RiverBiomeLists.draftPrefix("regions","surfaceBiomes")+"0") && drafts.contains(RiverPolicyFields.draftPrefix("biomes")+"widthMultiplier"));
        check(policy(applied[0]).stringArrayPath("surfaceBiomes").equals(List.of("parent","parent")));
        check(FieldCoverage.fields("riverPolicy").stream().allMatch(FieldCoverage.Field::implemented));
        for (String path : List.of("dimensions/lists.json","regions/lists.json","biomes/lists.json")) {
            byte[] updated = bytes("{\"riverPolicy\":{\"widthMultiplier\":1e0,\"profiles\":[\"unresolved-profile\"],\"future\":\"雪\"},\"unknown\":9007199254740993}");
            for (String field : RiverBiomeLists.EDITABLE) updated = RiverBiomeLists.setInherited(path,updated,field,false);
            updated = RiverBiomeLists.edit(path,updated,"surfaceBiomes",0,"nested/child",workspace);
            updated = RiverBiomeLists.setInherited(path,updated,"mouthBiomes",true);
            updated = RiverBiomeLists.edit(path,updated,"bankBiomes",0,"not_json",workspace);
            updated = RiverBiomeLists.edit(path,updated,"floodedCaveBiomes",0,"parent",workspace);
            workspace.replace(path,updated);
        }
        workspace.save(temp.resolve("river-biome-lists")); workspace.exportZip(temp.resolve("river-biome-lists.zip"));
        var reopened = PackWorkspace.open(temp.resolve("river-biome-lists")); var imported = PackWorkspace.importZip(temp.resolve("river-biome-lists.zip"));
        for (String path : workspace.paths()) {
            check(Arrays.equals(workspace.read(path),reopened.read(path)));
            check(Arrays.equals(workspace.read(path),imported.read(path)));
        }
        var saved = policy(reopened.read("biomes/lists.json"));
        check(saved.token("mouthBiomes").equals("null") && saved.token("shoreBiomes").equals("[]") && saved.stringArrayPath("surfaceBiomes").equals(List.of("nested/child")));
        byte[] unsupported = bytes("{\"riverPolicy\":{\"surfaceBiomes\":[null]}}");
        workspace.replace("regions/lists.json",unsupported); workspace.save(temp.resolve("river-biome-lists"));
        check(Arrays.equals(PackWorkspace.open(temp.resolve("river-biome-lists")).read("regions/lists.json"),unsupported));
        System.out.println("PASS: river biome inheritance/empty lists, exact references, lossless list operations, diagnostics, draft guards and Save/ZIP contracts");
    }
}
