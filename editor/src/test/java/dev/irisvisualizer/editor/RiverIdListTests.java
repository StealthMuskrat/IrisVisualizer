package dev.irisvisualizer.editor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class RiverIdListTests {
    private static final String ALPHA = "dimensions/alpha.json";
    private static final String BETA = "dimensions/beta.json";
    private static final String HYDROLOGY = "{\"rivers\":{\"enabled\":false,\"profiles\":[{\"id\":\"cold\\u005fwater\",\"fluidPalette\":null,\"future\":true},{},{\"id\":\"Hot Water\"}]},\"surfacePools\":[{\"id\":\"hot\"},{\"future\":true}]}";
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static String text(byte[] source) { return new String(source,StandardCharsets.UTF_8); }
    private static void check(boolean condition) { if (!condition) throw new AssertionError("River ID catalog/list contract failed"); }
    private static void rejects(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Expected rejected river ID operation");
    }
    private static LosslessJson policy(byte[] source) { return new LosslessJson(new LosslessJson(source).token("riverPolicy").getBytes(StandardCharsets.UTF_8)); }
    private static RiverIdLists.Catalog catalog(PackWorkspace workspace,String dimension,String field) { return RiverIdLists.catalog(workspace,"regions/area.json",dimension,field); }
    static void run(Path temp) throws Exception {
        var workspace = PackWorkspace.newPack(Map.of(
                ALPHA,bytes("{\"hydrology\":"+HYDROLOGY+",\"riverPolicy\":{}}"),
                BETA,bytes("{\"hydrology\":{\"rivers\":{\"profiles\":[{\"id\":\"beta\"}]},\"surfacePools\":[{\"id\":\"pond\"}]}}"),
                "dimensions/implicit.json",bytes("{}"),"dimensions/cases.json",bytes("{}"),
                "regions/area.json",bytes("{\"riverPolicy\":{}}"),"biomes/area.json",bytes("{\"riverPolicy\":{}}")));
        check(RiverIdLists.EDITABLE.equals(List.of("profiles","surfacePools")));
        check(RiverIdLists.dimensions(workspace).equals(List.of(ALPHA,BETA,"dimensions/cases.json","dimensions/implicit.json")));
        long revision = workspace.revision(); byte[] alpha = workspace.read(ALPHA);
        check(catalog(workspace,ALPHA,"profiles").ids().equals(List.of("cold_water","default","Hot Water")));
        check(catalog(workspace,ALPHA,"surfacePools").ids().equals(List.of("hot","lava_pool")));
        check(catalog(workspace,"dimensions/implicit.json","profiles").ids().equals(List.of("default")));
        check(catalog(workspace,"dimensions/implicit.json","surfacePools").status()==RiverIdLists.Status.READY && catalog(workspace,"dimensions/implicit.json","surfacePools").ids().isEmpty());
        check(workspace.revision()==revision && Arrays.equals(workspace.read(ALPHA),alpha));
        check(RiverIdLists.catalog(workspace,"regions/area.json",null,"profiles").status()==RiverIdLists.Status.NEED_DIMENSION);
        check(RiverIdLists.catalog(workspace,"biomes/area.json",null,"surfacePools").status()==RiverIdLists.Status.NEED_DIMENSION);
        check(RiverIdLists.catalog(workspace,ALPHA,BETA,"profiles").status()==RiverIdLists.Status.UNAVAILABLE);
        check(RiverIdLists.catalog(workspace,ALPHA,null,"profiles").ids().equals(List.of("cold_water","default","Hot Water")));
        check(catalog(workspace,"dimensions/missing.json","profiles").status()==RiverIdLists.Status.UNAVAILABLE);
        check(catalog(workspace,"regions/area.json","profiles").status()==RiverIdLists.Status.UNAVAILABLE);
        try { catalog(workspace,ALPHA,"profiles").ids().add("mutate"); throw new AssertionError("Mutable ID snapshot"); }
        catch (UnsupportedOperationException expected) { }
        String cases = "dimensions/cases.json";
        for (String field : RiverIdLists.EDITABLE) {
            check(!RiverIdLists.description(field).isBlank());
            String opening = field.equals("profiles") ? "{\"hydrology\":{\"rivers\":{\"profiles\":" : "{\"hydrology\":{\"surfacePools\":";
            String closing = field.equals("profiles") ? "}}}" : "}}";
            workspace.replace(cases,bytes(opening+"[]"+closing));
            check(catalog(workspace,cases,field).status()==RiverIdLists.Status.READY && catalog(workspace,cases,field).ids().isEmpty());
            workspace.replace(cases,bytes(opening+"[{}]"+closing));
            check(catalog(workspace,cases,field).ids().equals(List.of(field.equals("profiles") ? "default" : "lava_pool")));
            workspace.replace(cases,bytes(opening+"[{\"id\":\"Case\"},{\"id\":\"case\"},{\"id\":\" case \"}]"+closing));
            check(catalog(workspace,cases,field).ids().equals(List.of("Case","case"," case "))); // Literal IDs are not normalized.
            for (String bad : List.of("null","{}","\"snippet\"","[{},{}]","[{\"id\":\"dup\"},{\"id\":\"d\\u0075p\"}]","[{},\"snippet\"]","[{\"id\":null}]","[{\"id\":1}]","[{\"id\":true}]","[{\"id\":\" \"}]")) {
                workspace.replace(cases,bytes(opening+bad+closing));
                var unavailable = catalog(workspace,cases,field);
                check(unavailable.status()==RiverIdLists.Status.UNAVAILABLE && unavailable.ids().isEmpty());
            }
            workspace.replace(cases,bytes(opening+"[{\"id\":\""+"x".repeat(4097)+"\"}]"+closing));
            check(catalog(workspace,cases,field).status()==RiverIdLists.Status.UNAVAILABLE);
        }
        workspace.replace(cases,bytes("{\"hydrology\":{\"rivers\":null,\"surfacePools\":[{}]}}"));
        check(catalog(workspace,cases,"profiles").status()==RiverIdLists.Status.UNAVAILABLE && catalog(workspace,cases,"surfacePools").ids().equals(List.of("lava_pool")));
        workspace.replace(cases,bytes("{\"hydrology\":{\"rivers\":{\"profiles\":[{}]},\"surfacePools\":null}}"));
        check(catalog(workspace,cases,"profiles").ids().equals(List.of("default")) && catalog(workspace,cases,"surfacePools").status()==RiverIdLists.Status.UNAVAILABLE);
        for (String bad : List.of("{","{\"hydrology\":null}","{\"hydrology\":\"snippet\"}","{\"hydrology\":[]}")) {
            workspace.replace(cases,bytes(bad));
            for (String field : RiverIdLists.EDITABLE) check(catalog(workspace,cases,field).status()==RiverIdLists.Status.UNAVAILABLE);
        }
        var oversized = PackWorkspace.newPack(Map.of("dimensions/large.json",bytes("{\"padding\":\""+"x".repeat(ReferenceDiagnostics.MAX_JSON_BYTES)+"\"}")));
        check(catalog(oversized,"dimensions/large.json","profiles").status()==RiverIdLists.Status.UNAVAILABLE);
        var cache = new RiverIdLists.CatalogCache();
        var before = cache.inspect(workspace,"regions/area.json",BETA,"profiles");
        check(before==cache.inspect(workspace,"regions/area.json",BETA,"profiles"));
        byte[] beta = workspace.read(BETA);
        workspace.replace(BETA,bytes("{\"hydrology\":{\"rivers\":{\"profiles\":[{\"id\":\"changed\"}]}}}"));
        var after = cache.inspect(workspace,"regions/area.json",BETA,"profiles");
        check(after!=before && before.ids().equals(List.of("beta")) && after.ids().equals(List.of("changed")));
        byte[] inherited = bytes("{\"riverPolicy\":{\"profiles\":[]}}");
        rejects(() -> RiverIdLists.edit("regions/area.json",inherited,"profiles",0,"beta",workspace,BETA));
        check(policy(RiverIdLists.edit("regions/area.json",inherited,"profiles",0,"changed",workspace,BETA)).stringArrayPath("profiles").equals(List.of("changed")));
        check(cache.inspect(workspace,"regions/area.json",ALPHA,"profiles").ids().equals(List.of("cold_water","default","Hot Water")));
        var otherWorkspace = PackWorkspace.newPack(Map.of(ALPHA,bytes("{}")));
        check(cache.inspect(otherWorkspace,"regions/area.json",ALPHA,"profiles").ids().equals(List.of("default")));
        workspace.replace(BETA,beta);
        var prefixes = new HashSet<String>();
        for (String context : CaveProfileFields.CONTEXTS) {
            String owner = context.equals("dimensions") ? ALPHA : context+"/area.json";
            String chosen = context.equals("dimensions") ? null : ALPHA;
            for (String field : RiverIdLists.EDITABLE) {
                check(prefixes.add(RiverIdLists.draftPrefix(context,field)));
                String first = field.equals("profiles") ? "\"cold\\u005fwater\"" : "\"h\\u006ft\"";
                String second = "\"unresolved\"", third = field.equals("profiles") ? "\"default\"" : "\"lava_pool\"";
                String gap = ",\r\n  ", start = "[\r\n  ", end = "\r\n ]";
                String list = start+first+gap+second+gap+third+end;
                String prefix = "{\r\n \"riverPolicy\":{\""+field+"\":";
                String suffix = ",\"widthMultiplier\":1e0,\"surfaceBiomes\":[\"unresolved-biome\"],\"future\":\"雪\\u0061\"},\"hydrology\":"+HYDROLOGY+",\"unknown\":9007199254740993\r\n}\r\n";
                byte[] source = bytes(prefix+list+suffix), copy = source.clone();
                check(text(RiverIdLists.setInherited(owner,source,field,true)).equals(prefix+"null"+suffix));
                check(text(RiverIdLists.setInherited(owner,source,field,false)).equals(prefix+"[]"+suffix));
                byte[] absent = bytes("{\"riverPolicy\":{\"future\":true}}");
                check(Arrays.equals(RiverIdLists.setInherited(owner,absent,field,true),absent));
                byte[] empty = RiverIdLists.setInherited(owner,absent,field,false);
                check(policy(empty).fields().equals(Set.of(field,"future")) && policy(empty).token(field).equals("[]"));
                byte[] nullField = bytes(prefix+"null"+suffix);
                check(Arrays.equals(RiverIdLists.setInherited(owner,nullField,field,true),nullField));
                String id = field.equals("profiles") ? "Hot Water" : "hot";
                check(text(RiverIdLists.edit(owner,source,field,1,id,workspace,chosen)).equals(prefix+start+first+gap+"\""+id+"\""+gap+third+end+suffix));
                check(policy(RiverIdLists.edit(owner,empty,field,0,id,workspace,chosen)).stringArrayPath(field).equals(List.of(id)));
                byte[] one = RiverIdLists.edit(owner,empty,field,0,id,workspace,chosen);
                check(policy(RiverIdLists.edit(owner,one,field,0,null,workspace,null)).token(field).equals("[]"));
                byte[] duplicate = RiverIdLists.change(owner,source,field,0,RiverBiomeLists.Action.DUPLICATE);
                check(text(duplicate).equals(prefix+start+first+gap+first+", "+second+gap+third+end+suffix));
                check(Arrays.equals(RiverIdLists.edit(owner,duplicate,field,1,null,workspace,null),source));
                byte[] moved = RiverIdLists.change(owner,source,field,0,RiverBiomeLists.Action.DOWN);
                check(text(moved).equals(prefix+start+second+gap+first+gap+third+end+suffix));
                check(Arrays.equals(RiverIdLists.change(owner,moved,field,1,RiverBiomeLists.Action.UP),source));
                check(policy(RiverIdLists.change(owner,source,field,1,RiverBiomeLists.Action.DUPLICATE)).stringArrayPath(field).get(2).equals("unresolved"));
                for (String invalid : List.of("","missing","namespace:alias","default.json","hydrology/"+id)) rejects(() -> RiverIdLists.edit(owner,source,field,0,invalid,workspace,chosen));
                String betaId = field.equals("profiles") ? "beta" : "pond";
                rejects(() -> RiverIdLists.edit(owner,source,field,0,betaId,workspace,chosen));
                if (!context.equals("dimensions")) {
                    rejects(() -> RiverIdLists.edit(owner,source,field,0,id,workspace,null));
                    check(policy(RiverIdLists.edit(owner,source,field,0,betaId,workspace,BETA)).stringArrayPath(field).getFirst().equals(betaId));
                } else rejects(() -> RiverIdLists.edit(owner,source,field,0,betaId,workspace,BETA));
                for (String bad : List.of("{}","1","\"snippet\"","[{},\"x\"]","[null]")) {
                    byte[] wrong = bytes(prefix+bad+suffix);
                    rejects(() -> RiverIdLists.setInherited(owner,wrong,field,true));
                    rejects(() -> RiverIdLists.setInherited(owner,wrong,field,false));
                    rejects(() -> RiverIdLists.edit(owner,wrong,field,0,id,workspace,chosen));
                    rejects(() -> RiverIdLists.change(owner,wrong,field,0,RiverBiomeLists.Action.DUPLICATE));
                }
                for (String missing : List.of("{}","{\"riverPolicy\":null}","{\"riverPolicy\":\"snippet\"}")) {
                    rejects(() -> RiverIdLists.setInherited(owner,bytes(missing),field,false));
                    rejects(() -> RiverIdLists.edit(owner,bytes(missing),field,0,id,workspace,chosen));
                }
                rejects(() -> RiverIdLists.edit(owner,absent,field,0,id,workspace,chosen));
                rejects(() -> RiverIdLists.edit(owner,nullField,field,0,id,workspace,chosen));
                rejects(() -> RiverIdLists.change(owner,source,field,0,RiverBiomeLists.Action.UP));
                rejects(() -> RiverIdLists.change(owner,source,field,2,RiverBiomeLists.Action.DOWN));
                rejects(() -> RiverIdLists.edit(owner,source,field,-1,null,workspace,chosen));
                rejects(() -> RiverIdLists.change(owner,source,field,3,RiverBiomeLists.Action.DUPLICATE));
                check(Arrays.equals(source,copy) && !DimensionFields.readOnlyPaths(new LosslessJson(source)).contains("riverPolicy."+field));
                check(DimensionFields.readOnlyPaths(new LosslessJson(bytes(prefix+"[null]"+suffix))).contains("riverPolicy."+field));
            }
        }
        check(prefixes.size()==6 && RiverIdLists.emptyLabel("profiles").contains("first configured"));
        byte[] sample = bytes("{\"riverPolicy\":{\"profiles\":[\"unresolved\"]}}");
        for (String owner : List.of("generators/test.json","dimensions/nested/test.json","test.json")) rejects(() -> RiverIdLists.setInherited(owner,sample,"profiles",false));
        rejects(() -> RiverIdLists.setInherited("biomes/area.json",sample,"surfaceBiomes",true));
        var drafts = new InspectorDrafts();
        for (String prefix : prefixes) drafts.update(prefix+"0","old","new");
        String scope = RiverIdLists.draftPrefix("biomes","profiles");
        drafts.update(scope+"new","","default");
        drafts.update(RiverBiomeLists.draftPrefix("biomes","surfaceBiomes")+"0","old","new");
        drafts.update(RiverPolicyFields.draftPrefix("biomes")+"widthMultiplier","1","2");
        check(!drafts.transitionUnder(scope,() -> false,() -> { throw new AssertionError("Cancelled ID selection transition ran"); }) && drafts.count()==9);
        try { drafts.transitionUnder(scope,() -> true,() -> { throw new IOException("Failed ID transition"); }); throw new AssertionError("Expected failure"); }
        catch (IOException expected) { check(drafts.count()==9); }
        drafts.transitionUnder(scope,() -> true,() -> { });
        check(drafts.count()==7 && drafts.contains(RiverBiomeLists.draftPrefix("biomes","surfaceBiomes")+"0") && drafts.contains(RiverIdLists.draftPrefix("biomes","surfacePools")+"0"));
        for (String owner : List.of(ALPHA,"regions/area.json","biomes/area.json")) {
            byte[] changed = bytes("{\"hydrology\":"+HYDROLOGY+",\"riverPolicy\":{\"widthMultiplier\":1e0,\"future\":\"雪\"},\"unknown\":9007199254740993}");
            changed = RiverIdLists.setInherited(owner,changed,"profiles",false);
            changed = RiverIdLists.setInherited(owner,changed,"surfacePools",false);
            changed = RiverIdLists.edit(owner,changed,"profiles",0,"cold_water",workspace,owner.equals(ALPHA) ? null : ALPHA);
            changed = RiverIdLists.edit(owner,changed,"surfacePools",0,"hot",workspace,owner.equals(ALPHA) ? null : ALPHA);
            changed = RiverIdLists.setInherited(owner,changed,"profiles",true);
            workspace.replace(owner,changed);
        }
        workspace.replace(cases,bytes("{}"));
        workspace.save(temp.resolve("river-id-lists")); workspace.exportZip(temp.resolve("river-id-lists.zip"));
        var reopened = PackWorkspace.open(temp.resolve("river-id-lists")); var imported = PackWorkspace.importZip(temp.resolve("river-id-lists.zip"));
        for (String path : workspace.paths()) {
            check(Arrays.equals(workspace.read(path),reopened.read(path)));
            check(Arrays.equals(workspace.read(path),imported.read(path)));
        }
        check(policy(reopened.read("biomes/area.json")).token("profiles").equals("null") && policy(reopened.read("biomes/area.json")).stringArrayPath("surfacePools").equals(List.of("hot")));
        check(new LosslessJson(reopened.read(ALPHA)).token("hydrology").equals(HYDROLOGY));
        check(FieldCoverage.fields("riverPolicy").size()==26 && FieldCoverage.fields("riverPolicy").stream().allMatch(FieldCoverage.Field::implemented));
        System.out.println("PASS: river literal ID catalogs/defaults, explicit dimension contexts, cache freshness, lossless selection states/lists, draft guards and Save/ZIP contracts");
    }
}
