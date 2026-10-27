package dev.irisvisualizer.editor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

final class FloatingPlacementDeformationTests {
    private static final String PATH="biomes/nested/deformation.json";
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static String text(byte[] value) { return new String(value,StandardCharsets.UTF_8); }
    private static void check(boolean condition) { if (!condition) throw new AssertionError("Floating deformation contract failed"); }
    private static void rejects(Runnable action) { try { action.run(); } catch (IllegalArgumentException expected) { return; }throw new AssertionError("Expected rejected deformation operation"); }
    private static byte[] document(String list,String placement) { return bytes("{\r\n \"floatingChildBiomes\":[{\""+list+"\":["+placement+",{}],\"inheritObjects\":false},{}],\"future\":9007199254740993,\"escaped\":\"雪\\u0061\"\r\n}\r\n"); }
    private static LosslessJson placement(byte[] value,String list) { return FloatingObjectPlacements.placements(FloatingBiomes.entries(new LosslessJson(value)).getFirst(),list).getFirst(); }
    private static LosslessJson settings(byte[] value,String list,String field) { return FloatingPlacementDeformation.object(placement(value,list),field); }
    private static String[] bounds(String object,String field) {
        if (object.equals("stiltSettings")) return switch(field) { case "yMax","yRand","overStilt" -> new String[]{"0","64","-1","65","int"};case "organicMaxScan" -> new String[]{"1","256","0","257","int"};case "organicJitter" -> new String[]{"0","32","-1","33","int"};case "organicScratch" -> new String[]{"0","1","-0.01","1.01","double"};default -> throw new AssertionError(); };
        return switch(field) { case "radius" -> new String[]{"0","128","-1","129","int"};case "falloff" -> new String[]{"0.25","8","0.249","8.01","double"};case "organicJitter","waveAmplitude" -> new String[]{"0","64","-1","65","int"};case "waveScale" -> new String[]{"0.1","64","0.099","64.01","double"};default -> throw new AssertionError(); };
    }
    static void run(Path temp) throws Exception {
        check(FloatingPlacementDeformation.STILT_FIELDS.size()==7 && FloatingPlacementDeformation.VACUUM_FIELDS.size()==5);
        for (String list : FloatingObjectPlacements.LIST_FIELDS) {
            var context=new FloatingPlacementDeformation.Context(0,list,0);byte[] absent=document(list,"{\"mode\":\"STILT\",\"futurePlacement\":9007199254740993}");
            for (String object : FloatingPlacementDeformation.OBJECT_FIELDS) {
                check(settings(absent,list,object)==null && settings(document(list,"{\""+object+"\":null}"),list,object)==null);
                byte[] enabled=FloatingPlacementDeformation.enable(PATH,absent,context,object);check(settings(enabled,list,object).fields().isEmpty() && placement(enabled,list).stringToken("mode").equals("STILT"));
                rejects(() -> FloatingPlacementDeformation.enable(PATH,enabled,context,object));
                byte[] restored=FloatingPlacementDeformation.disable(PATH,enabled,context,object);check(placement(restored,list).token(object).equals("null") && placement(restored,list).stringToken("mode").equals("STILT"));
                check(settings(FloatingPlacementDeformation.enable(PATH,restored,context,object),list,object).fields().isEmpty());
                check(Arrays.equals(absent,FloatingPlacementDeformation.disable(PATH,absent,context,object)));
                for (String field : FloatingPlacementDeformation.scalars(object)) {
                    boolean allowed=!object.equals("stiltSettings") || !FloatingPlacementDeformation.CLASSIC_FIELDS.contains(field) || list.equals("floatingObjects");
                    check(FloatingPlacementDeformation.editable(list,object,field)==allowed);
                    if (!allowed) { rejects(() -> FloatingPlacementDeformation.enableScalar(PATH,enabled,context,object,field));rejects(() -> FloatingPlacementDeformation.setScalar(PATH,enabled,context,object,field,"1"));continue; }
                    byte[] scalar=FloatingPlacementDeformation.enableScalar(PATH,enabled,context,object,field);check(settings(scalar,list,object).token(field).equals(FloatingPlacementDeformation.defaultToken(object,field)));
                    rejects(() -> FloatingPlacementDeformation.enableScalar(PATH,scalar,context,object,field));rejects(() -> FloatingPlacementDeformation.setScalar(PATH,enabled,context,object,field,"1"));
                    var limits=bounds(object,field);
                    for (String valid : List.of(limits[0],limits[1])) check(Double.parseDouble(settings(FloatingPlacementDeformation.setScalar(PATH,scalar,context,object,field,valid),list,object).token(field))==Double.parseDouble(valid));
                    for (String bad : List.of(limits[2],limits[3],"NaN","Infinity","-Infinity")) rejects(() -> FloatingPlacementDeformation.setScalar(PATH,scalar,context,object,field,bad));
                    if (limits[4].equals("int")) { rejects(() -> FloatingPlacementDeformation.setScalar(PATH,scalar,context,object,field,"1.000000000000000001"));rejects(() -> FloatingPlacementDeformation.setScalar(PATH,scalar,context,object,field,"2147483648")); }
                    for (String bad : List.of("null","[]","true","\"1\"")) rejects(() -> FloatingPlacementDeformation.setScalar(PATH,document(list,"{\""+object+"\":{\""+field+"\":"+bad+"}}"),context,object,field,"1"));
                    rejects(() -> FloatingPlacementDeformation.enableScalar(PATH,absent,context,object,field));
                }
                for (String bad : List.of("[]","true","1","\"snippet/settings\"")) {
                    byte[] invalid=document(list,"{\""+object+"\":"+bad+"}");rejects(() -> settings(invalid,list,object));rejects(() -> FloatingPlacementDeformation.enable(PATH,invalid,context,object));rejects(() -> FloatingPlacementDeformation.disable(PATH,invalid,context,object));
                }
                for (String path : List.of("dimensions/a.json","regions/a.json","generators/a.json","biomes/a.txt","a.json")) { rejects(() -> FloatingPlacementDeformation.enable(path,absent,context,object));rejects(() -> FloatingPlacementDeformation.disable(path,enabled,context,object));rejects(() -> FloatingPlacementDeformation.enableScalar(path,enabled,context,object,"organicJitter"));rejects(() -> FloatingPlacementDeformation.setScalar(path,enabled,context,object,"organicJitter","1")); }
            }
            byte[] stilt=FloatingPlacementDeformation.enable(PATH,absent,context,"stiltSettings");
            check(FloatingPlacementDeformation.exclusions(settings(stilt,list,"stiltSettings"))==null);
            byte[] empty=FloatingPlacementDeformation.enableExclusions(PATH,stilt,context);check(FloatingPlacementDeformation.exclusions(settings(empty,list,"stiltSettings")).isEmpty());
            rejects(() -> FloatingPlacementDeformation.enableExclusions(PATH,empty,context));rejects(() -> FloatingPlacementDeformation.changeExclusion(PATH,stilt,context,RiverProfiles.Action.ADD,-1,"stone"));
            for (String valid : List.of("stone","minecraft:oak_log"," MINECRAFT:STONE ","minecraft:unknown_registry_name")) {
                byte[] added=FloatingPlacementDeformation.changeExclusion(PATH,empty,context,RiverProfiles.Action.ADD,-1,valid);check(FloatingPlacementDeformation.exclusions(settings(added,list,"stiltSettings")).equals(List.of(valid)));
                rejects(() -> FloatingPlacementDeformation.changeExclusion(PATH,added,context,RiverProfiles.Action.REMOVE,0,null));
                byte[] disabled=FloatingPlacementDeformation.disableExclusions(PATH,added,context);check(settings(disabled,list,"stiltSettings").token("exclude").equals("null"));check(FloatingPlacementDeformation.exclusions(settings(FloatingPlacementDeformation.enableExclusions(PATH,disabled,context),list,"stiltSettings")).isEmpty());
            }
            for (String invalid : List.of(""," ","mod:block","minecraft:stone[axis=y]","minecraft:a/b","minecraft:","stone block")) { check(!FloatingPlacementDeformation.materialSyntax(invalid));rejects(() -> FloatingPlacementDeformation.changeExclusion(PATH,empty,context,RiverProfiles.Action.ADD,-1,invalid)); }
            byte[] exclusions=document(list,"{\"stiltSettings\":{\"organicJitter\":3,\"exclude\":[ \"mod:legacy\\u0061\",\r\n \"STONE\" ],\"palette\":\"snippet/palette\",\"future\":9007199254740993}}");
            check(text(FloatingPlacementDeformation.setExclusion(PATH,exclusions,context,0,"minecraft:oak_log")).equals(text(exclusions).replace("\"mod:legacy\\u0061\"","\"minecraft:oak_log\"")));
            byte[] duplicate=FloatingPlacementDeformation.changeExclusion(PATH,exclusions,context,RiverProfiles.Action.DUPLICATE,0,null);check(FloatingPlacementDeformation.exclusions(settings(duplicate,list,"stiltSettings")).equals(List.of("mod:legacya","mod:legacya","STONE")) && text(duplicate).indexOf("legacy\\u0061")!=text(duplicate).lastIndexOf("legacy\\u0061"));
            byte[] moved=FloatingPlacementDeformation.changeExclusion(PATH,exclusions,context,RiverProfiles.Action.DOWN,0,null);check(Arrays.equals(exclusions,FloatingPlacementDeformation.changeExclusion(PATH,moved,context,RiverProfiles.Action.UP,1,null)));
            check(FloatingPlacementDeformation.exclusions(settings(FloatingPlacementDeformation.changeExclusion(PATH,exclusions,context,RiverProfiles.Action.REMOVE,0,null),list,"stiltSettings")).equals(List.of("STONE")));
            rejects(() -> FloatingPlacementDeformation.setExclusion(PATH,exclusions,context,2,"stone"));rejects(() -> FloatingPlacementDeformation.setExclusion(PATH,exclusions,context,0,"mod:block"));
            for (String bad : List.of("{}","true","1","\"snippet/list\"","[null]","[\"stone\",1]")) {
                byte[] invalid=document(list,"{\"stiltSettings\":{\"exclude\":"+bad+"}}");rejects(() -> FloatingPlacementDeformation.exclusions(settings(invalid,list,"stiltSettings")));rejects(() -> FloatingPlacementDeformation.enableExclusions(PATH,invalid,context));rejects(() -> FloatingPlacementDeformation.disableExclusions(PATH,invalid,context));rejects(() -> FloatingPlacementDeformation.changeExclusion(PATH,invalid,context,RiverProfiles.Action.ADD,-1,"stone"));
            }
            for (String bad : List.of("{}","{\"floatingChildBiomes\":null}","{\"floatingChildBiomes\":[null]}","{\"floatingChildBiomes\":[{\""+list+"\":null}]}","{\"floatingChildBiomes\":[{\""+list+"\":[\"snippet/placement\"]}]}")) { rejects(() -> FloatingPlacementDeformation.enable(PATH,bytes(bad),context,"stiltSettings"));rejects(() -> FloatingPlacementDeformation.enableExclusions(PATH,bytes(bad),context)); }
            byte[] original=document(list,"{\"mode\":\"UNKNOWN\",\"stiltSettings\":{\"yMax\":0,\"organicJitter\":3e0,\"exclude\":[\"stone\"],\"palette\":\"snippet/palette\",\"futureStilt\":9007199254740993},\"vacuumSettings\":{\"falloff\":2e0,\"futureVacuum\":\"雪\\u0061\"},\"futurePlacement\":true}");byte[] copy=original.clone();
            check(text(FloatingPlacementDeformation.setScalar(PATH,original,context,"stiltSettings","organicJitter","4")).equals(text(original).replace("\"organicJitter\":3e0","\"organicJitter\":4")));
            check(text(FloatingPlacementDeformation.setScalar(PATH,original,context,"vacuumSettings","falloff","4")).equals(text(original).replace("\"falloff\":2e0","\"falloff\":4")));
            var paths=FloatingPlacementDeformation.readOnlyPaths(placement(original,list),context);check(paths.containsAll(List.of("stiltSettings.palette","stiltSettings.futureStilt","vacuumSettings.futureVacuum")) && paths.contains("stiltSettings.yMax")!=list.equals("floatingObjects"));
            var drafts=new InspectorDrafts();String scalar=context.prefix("stiltSettings")+"organicJitter",exclusion=context.exclusionPrefix()+"0.value",newMaterial=context.exclusionPrefix()+"new",vacuum=context.prefix("vacuumSettings")+"falloff",other=new FloatingPlacementDeformation.Context(1,list,0).prefix("stiltSettings")+"organicJitter";
            for (String key : List.of(scalar,exclusion,newMaterial,vacuum,other)) drafts.update(key,"1","2");
            check(!drafts.transitionUnder(context.exclusionPrefix(),() -> false,() -> { throw new AssertionError(); }) && drafts.count()==5);
            try { drafts.transitionUnder(context.exclusionPrefix(),() -> true,() -> { throw new IOException("failed write"); });throw new AssertionError(); } catch (IOException expected) { check(drafts.count()==5); }
            check(drafts.transitionUnder(context.exclusionPrefix(),() -> true,() -> {}) && drafts.count()==3 && drafts.contains(scalar) && drafts.contains(vacuum));
            check(drafts.transitionUnder(context.prefix("stiltSettings"),() -> true,() -> {}) && drafts.count()==2 && drafts.contains(vacuum));
            check(drafts.transitionUnder(FloatingObjectPlacements.listPrefix(0,list),() -> true,() -> {}) && drafts.count()==1 && drafts.contains(other));
            check(Arrays.equals(original,copy));var saved=PackWorkspace.newPack(Map.of(PATH,original,"objects/opaque.iob",new byte[]{0,127,-1,42}));
            saved.replace(PATH,FloatingPlacementDeformation.setScalar(PATH,saved.read(PATH),context,"stiltSettings","organicJitter","4"));saved.replace(PATH,FloatingPlacementDeformation.setScalar(PATH,saved.read(PATH),context,"vacuumSettings","falloff","4"));saved.replace(PATH,FloatingPlacementDeformation.setExclusion(PATH,saved.read(PATH),context,0,"minecraft:oak_log"));
            saved.save(temp.resolve("placement-deformation-"+list));saved.exportZip(temp.resolve("placement-deformation-"+list+".zip"));var reopened=PackWorkspace.open(temp.resolve("placement-deformation-"+list));var imported=PackWorkspace.importZip(temp.resolve("placement-deformation-"+list+".zip"));
            for (String path : saved.paths()) check(Arrays.equals(saved.read(path),reopened.read(path)) && Arrays.equals(saved.read(path),imported.read(path)));
            check(settings(saved.read(PATH),list,"stiltSettings").stringToken("palette").equals("snippet/palette") && placement(saved.read(PATH),list).stringToken("mode").equals("UNKNOWN"));
        }
        check(FieldCoverage.fields("stiltSettings").size()==8 && FieldCoverage.fields("stiltSettings").stream().filter(FieldCoverage.Field::implemented).count()==7);
        check(FieldCoverage.fields("vacuumSettings").size()==5 && FieldCoverage.fields("vacuumSettings").stream().allMatch(FieldCoverage.Field::implemented));
        System.out.println("PASS: optional stilt/vacuum defaults, all eleven scalar bounds/types, anchored classic guards, raw material exclusion operations/null lifecycle, mode/palette preservation, scoped drafts and Save/ZIP contracts");
    }
}
