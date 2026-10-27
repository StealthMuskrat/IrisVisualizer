package dev.irisvisualizer.editor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class FloatingObjectTests {
    private static final String PATH="biomes/nested/objects.json";
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static String text(byte[] value) { return new String(value,StandardCharsets.UTF_8); }
    private static void check(boolean condition) { if (!condition) throw new AssertionError("Floating object contract failed"); }
    private static void rejects(Runnable action) { try { action.run(); } catch (IllegalArgumentException expected) { return; }throw new AssertionError("Expected rejected floating object operation"); }
    private static byte[] document(String list,String token) { return bytes("{\r\n \"floatingChildBiomes\":[ {\""+list+"\":"+token+",\"topObjectMode\":\"INHERIT_ONLY\",\"bottomObjectMode\":\"INHERIT_ONLY\",\"inheritObjects\":false,\"future\":9007199254740993}, {\"futureEntry\":true} ],\"escaped\":\"雪\\u0061\"\r\n}\r\n"); }
    private static LosslessJson entry(byte[] value) { return FloatingBiomes.entries(new LosslessJson(value)).getFirst(); }
    private static List<LosslessJson> placements(byte[] value,String list) { return FloatingObjectPlacements.placements(entry(value),list); }
    private static LosslessJson placement(byte[] value,String list) { return placements(value,list).getFirst(); }
    private static PackWorkspace workspace(byte[] value) throws IOException { return PackWorkspace.newPack(Map.of(PATH,value,"objects/nested/tree.iob",new byte[]{0,1,2},"objects/box.iob",new byte[]{3,4},"objects/ignored.IOB",new byte[]{5},"objects/unrelated.json",bytes("{}"),"biomes/tree.json",bytes("{}"))); }
    static void run(Path temp) throws Exception {
        byte[] absent=bytes("{\"floatingChildBiomes\":[{},{}],\"future\":9007199254740993}");var catalog=workspace(absent);
        check(FloatingObjectPlacements.objectChoices(catalog).equals(List.of("box","nested/tree")));
        catalog.duplicate("objects/nested/tree.iob","nested/copied");check(FloatingObjectPlacements.objectChoices(catalog).contains("nested/copied"));
        check(FloatingObjectPlacements.modes().size()==21 && FloatingObjectPlacements.defaultToken("mode").equals("CENTER_HEIGHT"));
        for (String list : FloatingObjectPlacements.LIST_FIELDS) {
            check(placements(absent,list)==null && placements(document(list,"null"),list)==null);
            byte[] enabled=FloatingObjectPlacements.enable(PATH,absent,0,list);check(placements(enabled,list).isEmpty() && entry(enabled).fields().equals(Set.of(list)));
            check(placements(FloatingObjectPlacements.enable(PATH,document(list,"null"),0,list),list).isEmpty());rejects(() -> FloatingObjectPlacements.enable(PATH,enabled,0,list));
            check(FloatingObjectPlacements.creationMode(list).equals(list.equals("floatingObjects") ? "FLOATING" : "CENTER_HEIGHT"));
            byte[] added=FloatingObjectPlacements.change(PATH,document(list,"[]"),0,list,RiverProfiles.Action.ADD,-1,"nested/tree",FloatingObjectPlacements.creationMode(list),catalog);
            check(placement(added,list).fields().equals(Set.of("place","mode")) && placement(added,list).stringArrayPath("place").equals(List.of("nested/tree")));
            check(entry(added).stringToken("topObjectMode").equals("INHERIT_ONLY") && entry(added).token("inheritObjects").equals("false"));
            check(placements(FloatingObjectPlacements.change(PATH,added,0,list,RiverProfiles.Action.REMOVE,0,null,null,null),list).isEmpty());
            rejects(() -> FloatingObjectPlacements.change(PATH,absent,0,list,RiverProfiles.Action.ADD,-1,"box","FLOATING",catalog));
            for (String invalid : List.of("missing","nested/tree.iob","objects/nested/tree","ignored")) rejects(() -> FloatingObjectPlacements.change(PATH,enabled,0,list,RiverProfiles.Action.ADD,-1,invalid,"FLOATING",catalog));
            rejects(() -> FloatingObjectPlacements.change(PATH,enabled,0,list,RiverProfiles.Action.ADD,-1,"box","UNKNOWN",catalog));
            for (String field : FloatingObjectPlacements.SCALARS) {
                byte[] materialized=FloatingObjectPlacements.enableScalar(PATH,added,0,list,0,field);check(placement(materialized,list).token(field).equals(FloatingObjectPlacements.defaultToken(field)));
                rejects(() -> FloatingObjectPlacements.enableScalar(PATH,materialized,0,list,0,field));rejects(() -> FloatingObjectPlacements.setScalar(PATH,added,0,list,0,field,"1"));
                String minimum=field.equals("density") ? "1" : "0",maximum=field.equals("density") ? "2147483647" : "1";
                check(placement(FloatingObjectPlacements.setScalar(PATH,materialized,0,list,0,field,minimum),list).token(field).equals(minimum));
                check(placement(FloatingObjectPlacements.setScalar(PATH,materialized,0,list,0,field,maximum),list).token(field).equals(maximum));
                for (String bad : List.of(field.equals("density") ? "0" : "-1",field.equals("density") ? "2147483648" : "1.1","NaN","Infinity")) rejects(() -> FloatingObjectPlacements.setScalar(PATH,materialized,0,list,0,field,bad));
                if (field.equals("density")) rejects(() -> FloatingObjectPlacements.setScalar(PATH,materialized,0,list,0,field,"1.000000000000000001"));
                for (String bad : List.of("null","[]","true","\"1\"")) rejects(() -> FloatingObjectPlacements.setScalar(PATH,document(list,"[{\""+field+"\":"+bad+"}]"),0,list,0,field,"1"));
            }
            for (String mode : FloatingObjectPlacements.modes()) {
                check(!FloatingObjectPlacements.modeHelp(mode).isBlank());check(placement(FloatingObjectPlacements.setMode(PATH,added,0,list,0,mode),list).stringToken("mode").equals(mode));
                check(placement(FloatingObjectPlacements.setMode(PATH,document(list,"[{}]"),0,list,0,mode),list).stringToken("mode").equals(mode));
            }
            rejects(() -> FloatingObjectPlacements.setMode(PATH,added,0,list,0,"UNKNOWN"));
            for (String bad : List.of("null","[]","1")) rejects(() -> FloatingObjectPlacements.setMode(PATH,document(list,"[{\"mode\":"+bad+"}]"),0,list,0,"FLOATING"));
            for (String pool : FloatingObjectPlacements.POOLS) {
                byte[] empty=FloatingObjectPlacements.enablePool(PATH,document(list,"[{}]"),0,list,0,pool);check(FloatingObjectPlacements.pool(placement(empty,list),pool).isEmpty());
                rejects(() -> FloatingObjectPlacements.enablePool(PATH,empty,0,list,0,pool));
                byte[] one=FloatingObjectPlacements.changePool(PATH,empty,0,list,0,pool,RiverProfiles.Action.ADD,-1,"box",catalog);check(placement(one,list).stringArrayPath(pool).equals(List.of("box")));
                if (pool.equals("place")) rejects(() -> FloatingObjectPlacements.changePool(PATH,one,0,list,0,pool,RiverProfiles.Action.REMOVE,0,null,null));
                else check(placement(FloatingObjectPlacements.changePool(PATH,one,0,list,0,pool,RiverProfiles.Action.REMOVE,0,null,null),list).stringArrayPath(pool).isEmpty());
                byte[] original=document(list,"[{\""+pool+"\":[ \"legacy\\u0061\",\r\n \"box\" ],\"mode\":\"UNKNOWN\",\"densityStyle\":{\"future\":9007199254740993}}]");
                check(text(FloatingObjectPlacements.setReference(PATH,original,0,list,0,pool,0,"nested/tree",catalog)).equals(text(original).replace("\"legacy\\u0061\"","\"nested/tree\"")));
                byte[] duplicate=FloatingObjectPlacements.changePool(PATH,original,0,list,0,pool,RiverProfiles.Action.DUPLICATE,0,null,null);check(placement(duplicate,list).stringArrayPath(pool).equals(List.of("legacya","legacya","box")) && text(duplicate).indexOf("legacy\\u0061")!=text(duplicate).lastIndexOf("legacy\\u0061"));
                byte[] moved=FloatingObjectPlacements.changePool(PATH,original,0,list,0,pool,RiverProfiles.Action.DOWN,0,null,null);check(Arrays.equals(original,FloatingObjectPlacements.changePool(PATH,moved,0,list,0,pool,RiverProfiles.Action.UP,1,null,null)));
                check(placement(FloatingObjectPlacements.changePool(PATH,original,0,list,0,pool,RiverProfiles.Action.REMOVE,0,null,null),list).stringArrayPath(pool).equals(List.of("box")));
                rejects(() -> FloatingObjectPlacements.setReference(PATH,original,0,list,0,pool,0,"missing",catalog));rejects(() -> FloatingObjectPlacements.setReference(PATH,original,0,list,0,pool,2,"box",catalog));
                for (String bad : List.of("null","{}","true","[null]","[\"box\",1]","\"snippet\"")) {
                    byte[] invalid=document(list,"[{\""+pool+"\":"+bad+"}]");rejects(() -> FloatingObjectPlacements.pool(placement(invalid,list),pool));rejects(() -> FloatingObjectPlacements.enablePool(PATH,invalid,0,list,0,pool));
                    rejects(() -> FloatingObjectPlacements.changePool(PATH,invalid,0,list,0,pool,RiverProfiles.Action.ADD,-1,"box",catalog));
                    check(placement(FloatingObjectPlacements.enableScalar(PATH,invalid,0,list,0,"chance"),list).token(pool).equals(bad));
                }
            }
            String first="{\"place\":[\"missing/stored\"],\"mode\":\"UNKNOWN\",\"chance\":1e0,\"density\":1,\"densityStyle\":{\"min\":2,\"max\":4,\"style\":\"snippet/range\"},\"translate\":{\"y\":16,\"future\":true},\"rotation\":\"snippet/rotation\",\"force\":true,\"futurePlacement\":{\"escaped\":\"雪\\u0061\",\"large\":9007199254740993}}";
            byte[] original=document(list,"[ "+first+",\r\n {} ]"),copy=original.clone();
            check(text(FloatingObjectPlacements.setScalar(PATH,original,0,list,0,"chance","0.25")).equals(text(original).replace("\"chance\":1e0","\"chance\":0.25")));
            check(text(FloatingObjectPlacements.setScalar(PATH,original,0,list,0,"density","8")).equals(text(original).replace("\"density\":1,","\"density\":8,")));
            byte[] duplicate=FloatingObjectPlacements.change(PATH,original,0,list,RiverProfiles.Action.DUPLICATE,0,null,null,null);check(Arrays.equals(placements(duplicate,list).get(0).sourceBytes(),placements(duplicate,list).get(1).sourceBytes()));
            byte[] moved=FloatingObjectPlacements.change(PATH,original,0,list,RiverProfiles.Action.DOWN,0,null,null,null);check(Arrays.equals(original,FloatingObjectPlacements.change(PATH,moved,0,list,RiverProfiles.Action.UP,1,null,null,null)));
            rejects(() -> FloatingObjectPlacements.change(PATH,original,0,list,RiverProfiles.Action.UP,0,null,null,null));rejects(() -> FloatingObjectPlacements.change(PATH,original,0,list,RiverProfiles.Action.DOWN,1,null,null,null));
            check(FloatingObjectPlacements.readOnlyFields(placement(original,list),list).containsAll(List.of("force","futurePlacement")));
            check(FloatingObjectPlacements.readOnlyFields(placement(original,list),list).contains("translate")!=list.equals("floatingObjects"));
            check(FloatingObjectPlacements.readOnlyFields(placement(original,list),list).contains("rotation")!=list.equals("floatingObjects"));
            if (list.equals("floatingObjects")) {
                byte[] translation=FloatingObjectPlacements.createTranslation(PATH,added,0,list,0);check(FloatingObjectPlacements.translate(placement(translation,list)).fields().isEmpty());
                rejects(() -> FloatingObjectPlacements.createTranslation(PATH,translation,0,list,0));
                for (String axis : FloatingObjectPlacements.TRANSLATION_FIELDS) {
                    byte[] initialized=FloatingObjectPlacements.enableTranslation(PATH,translation,0,list,0,axis);check(FloatingObjectPlacements.translate(placement(initialized,list)).token(axis).equals("0"));
                    FloatingObjectPlacements.setTranslation(PATH,initialized,0,list,0,axis,"-128");FloatingObjectPlacements.setTranslation(PATH,initialized,0,list,0,axis,"128");
                    for (String bad : List.of("-129","129","1.000000000000000001","NaN","2147483648")) rejects(() -> FloatingObjectPlacements.setTranslation(PATH,initialized,0,list,0,axis,bad));
                    rejects(() -> FloatingObjectPlacements.enableTranslation(PATH,added,0,list,0,axis));rejects(() -> FloatingObjectPlacements.setTranslation(PATH,translation,0,list,0,axis,"1"));
                }
                check(text(FloatingObjectPlacements.setTranslation(PATH,original,0,list,0,"y","32")).equals(text(original).replace("\"y\":16","\"y\":32")));
                for (String bad : List.of("null","[]","true","\"snippet/translate\"")) {
                    byte[] invalid=document(list,"[{\"translate\":"+bad+"}]");rejects(() -> FloatingObjectPlacements.createTranslation(PATH,invalid,0,list,0));rejects(() -> FloatingObjectPlacements.enableTranslation(PATH,invalid,0,list,0,"y"));
                }
            } else { rejects(() -> FloatingObjectPlacements.createTranslation(PATH,added,0,list,0));rejects(() -> FloatingObjectPlacements.setTranslation(PATH,original,0,list,0,"y","32")); }
            for (String bad : List.of("{}","true","1","[null]","[{},1]","[\"snippet/object-placer\"]")) {
                byte[] invalid=document(list,bad);rejects(() -> FloatingObjectPlacements.placements(entry(invalid),list));rejects(() -> FloatingObjectPlacements.enable(PATH,invalid,0,list));rejects(() -> FloatingObjectPlacements.change(PATH,invalid,0,list,RiverProfiles.Action.ADD,-1,"box","FLOATING",catalog));
            }
            for (String root : List.of("{}","{\"floatingChildBiomes\":null}","{\"floatingChildBiomes\":[]}","{\"floatingChildBiomes\":[null]}")) rejects(() -> FloatingObjectPlacements.enable(PATH,bytes(root),0,list));
            for (String path : List.of("dimensions/a.json","regions/a.json","generators/a.json","a.json","biomes/a.txt")) {
                rejects(() -> FloatingObjectPlacements.enable(path,absent,0,list));rejects(() -> FloatingObjectPlacements.change(path,original,0,list,RiverProfiles.Action.DUPLICATE,0,null,null,null));
                rejects(() -> FloatingObjectPlacements.setScalar(path,original,0,list,0,"chance","1"));rejects(() -> FloatingObjectPlacements.setMode(path,original,0,list,0,"FLOATING"));rejects(() -> FloatingObjectPlacements.enablePool(path,original,0,list,0,"allowedCollisions"));
                rejects(() -> FloatingObjectPlacements.createTranslation(path,added,0,list,0));
            }
            var drafts=new InspectorDrafts();String chance=FloatingObjectPlacements.placementPrefix(0,list,0)+"chance",density=FloatingObjectPlacements.placementPrefix(0,list,1)+"density",poolKey=FloatingObjectPlacements.poolPrefix(0,list,0,"place")+"futureDraft",geometry=FloatingBiomes.entryPrefix(0)+"minHeightAboveSurface",other=FloatingObjectPlacements.placementPrefix(1,list,0)+"chance";
            for (String key : List.of(chance,density,poolKey,geometry,other)) drafts.update(key,"1","2");
            check(!drafts.transitionUnder(FloatingObjectPlacements.listPrefix(0,list),() -> false,() -> { throw new AssertionError(); }) && drafts.count()==5);
            try { drafts.transitionUnder(FloatingObjectPlacements.listPrefix(0,list),() -> true,() -> { throw new IOException("failed write"); });throw new AssertionError(); } catch (IOException expected) { check(drafts.count()==5); }
            check(drafts.transitionUnder(FloatingObjectPlacements.poolPrefix(0,list,0,"place"),() -> true,() -> {}) && drafts.count()==4 && drafts.contains(chance));
            check(drafts.transitionUnder(FloatingObjectPlacements.listPrefix(0,list),() -> true,() -> {}) && drafts.count()==2 && drafts.contains(geometry) && drafts.contains(other));
            check(Arrays.equals(original,copy));var saved=workspace(original);saved.replace(PATH,FloatingObjectPlacements.setScalar(PATH,saved.read(PATH),0,list,0,"chance","0.5"));
            saved.replace(PATH,FloatingObjectPlacements.setReference(PATH,saved.read(PATH),0,list,0,"place",0,"nested/tree",saved));
            if (list.equals("floatingObjects")) saved.replace(PATH,FloatingObjectPlacements.setTranslation(PATH,saved.read(PATH),0,list,0,"y","64"));
            saved.save(temp.resolve("floating-objects-"+list));saved.exportZip(temp.resolve("floating-objects-"+list+".zip"));var reopened=PackWorkspace.open(temp.resolve("floating-objects-"+list));var imported=PackWorkspace.importZip(temp.resolve("floating-objects-"+list+".zip"));
            for (String path : saved.paths()) check(Arrays.equals(saved.read(path),reopened.read(path)) && Arrays.equals(saved.read(path),imported.read(path)));
        }
        rejects(() -> FloatingObjectPlacements.enable(PATH,absent,0,"objects"));rejects(() -> FloatingObjectPlacements.enableScalar(PATH,document("extraObjects","[{}]"),0,"extraObjects",0,"forcePlace"));
        check(FieldCoverage.fields("objectPlacement").size()==41 && FieldCoverage.fields("objectPlacement").stream().filter(FieldCoverage.Field::implemented).count()==11);
        check(FieldCoverage.fields("objectTranslate").size()==4 && FieldCoverage.fields("objectTranslate").stream().allMatch(FieldCoverage.Field::implemented));
        System.out.println("PASS: four floating object collections, exact asset catalogs/pools, all modes and chance/density/snow bounds, free-floating translations, anchored guards, raw preservation, scoped drafts and Save/ZIP contracts");
    }
}
