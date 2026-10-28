package dev.irisvisualizer.editor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;

final class ReadOnlyPolicyTests {
    private static final List<String> PLACEMENT=List.of("isDolphinTarget","loot","vanillaLoot","overrideGlobalLoot","markers","trees");
    private static final String DATA="\"isDolphinTarget\":null,\"loot\":\"unknown/snippet\",\"vanillaLoot\":9007199254740993,\"overrideGlobalLoot\":\"false\",\"markers\":null,\"trees\":[{\"treeTypes\":[\"TREE\"],\"future\":-0.00e+2}],\"future\":\"雪\\u0061\"";
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static String text(byte[] bytes) { return new String(bytes,StandardCharsets.UTF_8); }
    private static void check(boolean ok) { if (!ok) throw new AssertionError("Read-only scope policy contract failed"); }
    private static void rejects(Runnable action) { try { action.run(); } catch (IllegalArgumentException expected) { return; }throw new AssertionError("Expected blocked read-only field authoring"); }
    static void run(Path temp) throws Exception {
        var dimensionFields=FieldCoverage.fields("dimension");
        check(dimensionFields.stream().anyMatch(field -> field.name().equals("treeSettings") && field.classification().equals("read-only") && !field.implemented() && field.reason().contains("sapling")));
        for (String name : PLACEMENT) check(FieldCoverage.fields("objectPlacement").stream().anyMatch(field -> field.name().equals(name) && field.classification().equals("read-only") && !field.implemented() && !field.reason().isBlank()));
        for (String group : List.of("dimension","objectPlacement")) check(FieldCoverage.fields(group).stream().noneMatch(field -> field.classification().equals("unclassified")));
        check(FieldCoverage.readOnlyReason("dimension","treeSettings").contains("TreeSVC") && FieldCoverage.readOnlyReason("objectPlacement","trees").contains("TreeBlockMaterial"));
        check(FieldCoverage.readOnlyReason("dimension","treeSettings.enabled").startsWith("Unknown") && FieldCoverage.readOnlyReason("unknown","treeSettings").startsWith("Unknown") && FieldCoverage.readOnlyReason("dimension","future").startsWith("Unknown") && FieldCoverage.readOnlyReason("dimension","fluidHeight").startsWith("Unsupported"));
        // Classification is report metadata; actual writer allowlists still enforce the boundary.
        String dimension="{\r\n \"fluidHeight\":63,\"treeSettings\":{\"enabled\":true,\"mode\":\"ALL\",\"future\":9007199254740993},\"unknown\":\"雪\\u0061\"\r\n}";
        byte[] original=bytes(dimension);
        check(DimensionFields.readOnlyPaths(new LosslessJson(original)).contains("treeSettings"));
        rejects(() -> DimensionFields.edit("dimensions/scope.json",original,"treeSettings",1));
        rejects(() -> DimensionFields.edit("dimensions/scope.json",original,"treeSettings.enabled",1));
        byte[] dimensionChanged=DimensionFields.edit("dimensions/scope.json",original,"fluidHeight",64);
        check(text(dimensionChanged).equals(dimension.replace("\"fluidHeight\":63","\"fluidHeight\":64")));
        var files=new LinkedHashMap<String,byte[]>();files.put("dimensions/scope.json",dimensionChanged);
        for (int owner : List.of(FloatingObjectPlacements.BIOME_ROOT,FloatingObjectPlacements.REGION_ROOT,0)) {
            for (String list : owner==0 ? FloatingObjectPlacements.LIST_FIELDS : List.of("objects")) {
                String entry="{\"chance\":1e-1,"+DATA+"}";
                String collection="{\""+list+"\":["+entry+",{\"untouchedSibling\":true}]}";
                String source=owner==0 ? "{\"floatingChildBiomes\":["+collection+"]}" : collection;
                String path=(owner==FloatingObjectPlacements.REGION_ROOT ? "regions" : "biomes")+"/scope_"+owner+"_"+list+".json";
                byte[] input=bytes(source);
                var stored=new LosslessJson(bytes(entry));var fields=FloatingObjectPlacements.readOnlyFields(stored,list);
                check(fields.containsAll(PLACEMENT) && fields.contains("future"));
                for (String name : PLACEMENT) {
                    rejects(() -> FloatingObjectPlacements.enableScalar(path,input,owner,list,0,name));
                    rejects(() -> FloatingObjectPlacements.setScalar(path,input,owner,list,0,name,"1"));
                    rejects(() -> FloatingObjectPlacements.enablePool(path,input,owner,list,0,name));
                }
                byte[] changed=FloatingObjectPlacements.setScalar(path,input,owner,list,0,"chance","0.25");
                check(text(changed).equals(source.replace("\"chance\":1e-1","\"chance\":0.25")));
                files.put(path,changed);
                // Missing gameplay fields must stay missing after an unrelated generation edit.
                String absent=source.replace(","+DATA,"");
                byte[] absentChanged=FloatingObjectPlacements.setScalar(path,bytes(absent),owner,list,0,"chance","0.5");
                check(text(absentChanged).equals(absent.replace("\"chance\":1e-1","\"chance\":0.5")));
            }
        }
        files.put("objects/preserved.iob",new byte[]{0,-1,7});files.put("future/malformed.json",bytes("{not JSON"));
        var pack=PackWorkspace.newPack(files);pack.save(temp.resolve("read-only-policy"));
        var drafts=new InspectorDrafts();drafts.update("late","stored","unapplied");long revision=pack.revision(),resources=pack.resourceRevision();int history=pack.undoCount();
        for (String field : PLACEMENT) FieldCoverage.readOnlyReason("objectPlacement",field);
        JsonStructure.inspect(pack.read("dimensions/scope.json"));
        check(!pack.dirty() && pack.revision()==revision && pack.resourceRevision()==resources && pack.undoCount()==history && drafts.any());
        pack.saveCopy(temp.resolve("read-only-policy-copy"));pack.saveAs(temp.resolve("read-only-policy-as"));pack.exportZip(temp.resolve("read-only-policy.zip"));
        for (var reopened : List.of(PackWorkspace.open(temp.resolve("read-only-policy")),PackWorkspace.open(temp.resolve("read-only-policy-copy")),PackWorkspace.open(temp.resolve("read-only-policy-as")),PackWorkspace.importZip(temp.resolve("read-only-policy.zip"))))
            for (var file : files.entrySet()) check(Arrays.equals(file.getValue(),reopened.read(file.getKey())));
        System.out.println("PASS: pinned read-only policy reasons, exact direct-field matching, blocked gameplay-field authoring, malformed/absent field preservation across root/floating placements, independent generation edits and exact Save/Copy/As/ZIP persistence");
    }
}
