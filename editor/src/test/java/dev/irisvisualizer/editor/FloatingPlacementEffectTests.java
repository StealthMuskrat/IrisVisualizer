package dev.irisvisualizer.editor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

final class FloatingPlacementEffectTests {
    private static final String PATH="biomes/nested/effects.json";
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static String text(byte[] value) { return new String(value,StandardCharsets.UTF_8); }
    private static void check(boolean condition) { if (!condition) throw new AssertionError("Floating placement effect contract failed"); }
    private static void rejects(Runnable action) { try { action.run(); } catch (IllegalArgumentException expected) { return; }throw new AssertionError("Expected rejected floating effect operation"); }
    private static byte[] document(String list,String placement) { return bytes("{\r\n \"floatingChildBiomes\":[{\""+list+"\":["+placement+",{}],\"inheritObjects\":false,\"topObjectMode\":\"INHERIT_ONLY\"},{}],\"future\":9007199254740993,\"escaped\":\"雪\\u0061\"\r\n}\r\n"); }
    private static LosslessJson placement(byte[] value,String list) { return FloatingObjectPlacements.placements(FloatingBiomes.entries(new LosslessJson(value)).getFirst(),list).getFirst(); }
    static void run(Path temp) throws Exception {
        check(FloatingPlacementEffects.FLAGS.size()==7 && FloatingPlacementEffects.SCALARS.size()==2 && FloatingPlacementEffects.ALL_CONTEXT_FIELDS.size()==6);
        check(FloatingPlacementEffects.defaultToken("fromBottom").equals("false"));
        for (String list : FloatingObjectPlacements.LIST_FIELDS) {
            var context=new FloatingPlacementEffects.Context(0,list,0);byte[] absent=document(list,"{\"place\":[\"stored/missing\"],\"mode\":\"FLOATING\",\"futurePlacement\":9007199254740993}");
            check(context.prefix().equals(FloatingObjectPlacements.placementPrefix(0,list,0)+"effects."));
            for (String field : FloatingPlacementEffects.EDITABLE) check(!FloatingPlacementEffects.help(field).isBlank() && !FloatingPlacementEffects.contextHelp(field).isBlank());
            for (String field : FloatingPlacementEffects.FLAGS) {
                boolean allowed=FloatingPlacementEffects.ALL_CONTEXT_FIELDS.contains(field) || list.equals("floatingObjects");check(FloatingPlacementEffects.editable(list,field)==allowed);
                if (!allowed) {
                    rejects(() -> FloatingPlacementEffects.enableFlag(PATH,absent,context,field));rejects(() -> FloatingPlacementEffects.setFlag(PATH,document(list,"{\""+field+"\":false}"),context,field,true));continue;
                }
                check(FloatingPlacementEffects.defaultToken(field).equals("false"));
                byte[] enabled=FloatingPlacementEffects.enableFlag(PATH,absent,context,field);check(placement(enabled,list).token(field).equals("false"));
                check(placement(enabled,list).token("mode").equals("\"FLOATING\"") && placement(enabled,list).fields().size()==4);
                check(placement(FloatingPlacementEffects.setFlag(PATH,enabled,context,field,true),list).token(field).equals("true"));
                check(placement(FloatingPlacementEffects.setFlag(PATH,enabled,context,field,false),list).token(field).equals("false"));
                rejects(() -> FloatingPlacementEffects.enableFlag(PATH,enabled,context,field));rejects(() -> FloatingPlacementEffects.setFlag(PATH,absent,context,field,true));
                for (String bad : List.of("null","[]","{}","1","\"true\"")) {
                    byte[] invalid=document(list,"{\""+field+"\":"+bad+"}");rejects(() -> FloatingPlacementEffects.enableFlag(PATH,invalid,context,field));rejects(() -> FloatingPlacementEffects.setFlag(PATH,invalid,context,field,true));
                    check(placement(FloatingPlacementEffects.enableScalar(PATH,invalid,context,"boreExtendMaxY"),list).token(field).equals(bad));
                }
                for (String path : List.of("dimensions/a.json","regions/a.json","generators/a.json","biomes/a.txt","a.json")) { rejects(() -> FloatingPlacementEffects.enableFlag(path,absent,context,field));rejects(() -> FloatingPlacementEffects.setFlag(path,enabled,context,field,true)); }
            }
            for (String field : FloatingPlacementEffects.SCALARS) {
                byte[] enabled=FloatingPlacementEffects.enableScalar(PATH,absent,context,field);check(placement(enabled,list).token(field).equals("0") && placement(enabled,list).token("bore")==null);
                String minimum=field.equals("boreExtendMinY") ? "-1" : "0";
                check(placement(FloatingPlacementEffects.setScalar(PATH,enabled,context,field,minimum),list).token(field).equals(minimum));
                check(placement(FloatingPlacementEffects.setScalar(PATH,enabled,context,field,"64"),list).token(field).equals("64"));
                check(placement(FloatingPlacementEffects.setScalar(PATH,enabled,context,field,"6.4e1"),list).token(field).equals("64"));
                for (String bad : List.of(field.equals("boreExtendMinY") ? "-2" : "-1","65","1.000000000000000001","2147483648","NaN","Infinity")) rejects(() -> FloatingPlacementEffects.setScalar(PATH,enabled,context,field,bad));
                rejects(() -> FloatingPlacementEffects.enableScalar(PATH,enabled,context,field));rejects(() -> FloatingPlacementEffects.setScalar(PATH,absent,context,field,"1"));
                for (String bad : List.of("null","[]","true","\"0\"")) { byte[] invalid=document(list,"{\""+field+"\":"+bad+"}");rejects(() -> FloatingPlacementEffects.enableScalar(PATH,invalid,context,field));rejects(() -> FloatingPlacementEffects.setScalar(PATH,invalid,context,field,"1")); }
                for (String path : List.of("dimensions/a.json","regions/a.json","generators/a.json","biomes/a.txt","a.json")) { rejects(() -> FloatingPlacementEffects.enableScalar(path,absent,context,field));rejects(() -> FloatingPlacementEffects.setScalar(path,enabled,context,field,"1")); }
            }
            for (String invalid : List.of("bottom","forcePlace","force","isDolphinTarget","overrideGlobalLoot")) { rejects(() -> FloatingPlacementEffects.enableFlag(PATH,absent,context,invalid));rejects(() -> FloatingPlacementEffects.setFlag(PATH,absent,context,invalid,true)); }
            rejects(() -> FloatingPlacementEffects.enableFlag(PATH,absent,context,"boreExtendMaxY"));rejects(() -> FloatingPlacementEffects.enableScalar(PATH,absent,context,"bore"));
            for (String bad : List.of("{}","{\"floatingChildBiomes\":null}","{\"floatingChildBiomes\":[]}","{\"floatingChildBiomes\":[null]}","{\"floatingChildBiomes\":[{\""+list+"\":null}]}","{\"floatingChildBiomes\":[{\""+list+"\":[\"snippet/placement\"]}]}")) {
                rejects(() -> FloatingPlacementEffects.enableFlag(PATH,bytes(bad),context,"bore"));rejects(() -> FloatingPlacementEffects.enableScalar(PATH,bytes(bad),context,"boreExtendMinY"));
            }
            String fixture="{\"bore\":false,\"boreExtendMinY\":0e0,\"boreExtendMaxY\":1e0,\"smartBore\":false,\"meld\":false,\"waterloggable\":false,\"underwater\":false,\"onwater\":false,\"fromBottom\":false,\"bottom\":true,\"force\":true,\"forcePlace\":false,\"carvingSupport\":\"UNKNOWN\",\"heightmap\":\"snippet/noise\",\"edit\":[{\"future\":true}],\"rotation\":{\"futureRotation\":9007199254740993},\"futurePlacement\":\"雪\\u0061\"}";
            byte[] original=document(list,fixture),copy=original.clone();
            check(text(FloatingPlacementEffects.setScalar(PATH,original,context,"boreExtendMinY","-1")).equals(text(original).replace("\"boreExtendMinY\":0e0","\"boreExtendMinY\":-1")));
            check(text(FloatingPlacementEffects.setFlag(PATH,original,context,"smartBore",true)).equals(text(original).replace("\"smartBore\":false","\"smartBore\":true")));
            for (String field : FloatingPlacementEffects.EDITABLE) check(FloatingObjectPlacements.readOnlyFields(placement(original,list),list).contains(field)!=FloatingPlacementEffects.editable(list,field));
            check(FloatingObjectPlacements.readOnlyFields(placement(original,list),list).containsAll(List.of("bottom","force","forcePlace","futurePlacement")));
            var drafts=new InspectorDrafts();String minimum=context.prefix()+"boreExtendMinY",maximum=context.prefix()+"boreExtendMaxY",sibling=new FloatingPlacementEffects.Context(0,list,1).prefix()+"boreExtendMinY",warp=FloatingObjectPlacements.placementPrefix(0,list,0)+"warp.zoom",other=new FloatingPlacementEffects.Context(1,list,0).prefix()+"boreExtendMinY",otherList=FloatingObjectPlacements.placementPrefix(0,list.equals("floatingObjects") ? "extraObjects" : "floatingObjects",0)+"chance";
            for (String key : List.of(minimum,maximum,sibling,warp,other,otherList)) drafts.update(key,"0","1");
            drafts.applied(minimum);check(drafts.count()==5 && drafts.contains(maximum) && drafts.contains(sibling));
            check(!drafts.transitionUnder(FloatingObjectPlacements.listPrefix(0,list),() -> false,() -> { throw new AssertionError(); }) && drafts.count()==5);
            try { drafts.transitionUnder(FloatingObjectPlacements.listPrefix(0,list),() -> true,() -> { throw new IOException("failed write"); });throw new AssertionError(); } catch (IOException expected) { check(drafts.count()==5); }
            check(drafts.transitionUnder(FloatingObjectPlacements.listPrefix(0,list),() -> true,() -> {}) && drafts.count()==2 && drafts.contains(other) && drafts.contains(otherList));
            check(Arrays.equals(original,copy));var saved=PackWorkspace.newPack(Map.of(PATH,original,"objects/opaque.iob",new byte[]{0,127,-1,42}));
            for (String field : FloatingPlacementEffects.FLAGS) if (FloatingPlacementEffects.editable(list,field)) saved.replace(PATH,FloatingPlacementEffects.setFlag(PATH,saved.read(PATH),context,field,true));
            saved.replace(PATH,FloatingPlacementEffects.setScalar(PATH,saved.read(PATH),context,"boreExtendMinY","-1"));saved.replace(PATH,FloatingPlacementEffects.setScalar(PATH,saved.read(PATH),context,"boreExtendMaxY","64"));
            saved.save(temp.resolve("placement-effects-"+list));saved.exportZip(temp.resolve("placement-effects-"+list+".zip"));var reopened=PackWorkspace.open(temp.resolve("placement-effects-"+list));var imported=PackWorkspace.importZip(temp.resolve("placement-effects-"+list+".zip"));
            for (String path : saved.paths()) check(Arrays.equals(saved.read(path),reopened.read(path)) && Arrays.equals(saved.read(path),imported.read(path)));
            check(placement(saved.read(PATH),list).token("bottom").equals("true") && placement(saved.read(PATH),list).token("force").equals("true") && placement(saved.read(PATH),list).token("forcePlace").equals("false"));
        }
        rejects(() -> new FloatingPlacementEffects.Context(-1,"floatingObjects",0));rejects(() -> new FloatingPlacementEffects.Context(0,"objects",0));rejects(() -> new FloatingPlacementEffects.Context(0,"floatingObjects",-1));
        System.out.println("PASS: floating excavation/water flags, implicit defaults, all-context versus floating-only authoring, exact extension bounds, malformed owner/type guards, native alias/bottom preservation, scoped drafts and Save/ZIP contracts");
    }
}
