package dev.irisvisualizer.editor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class FloatingPlacementOverrideTests {
    private static final String PATH="biomes/nested/overrides.json";
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static String text(byte[] value) { return new String(value,StandardCharsets.UTF_8); }
    private static void check(boolean condition) { if (!condition) throw new AssertionError("Floating placement override contract failed"); }
    private static void rejects(Runnable action) { try { action.run(); } catch (IllegalArgumentException expected) { return; }throw new AssertionError("Expected rejected floating override operation"); }
    private static byte[] document(String list,String placement) { return bytes("{\r\n \"floatingChildBiomes\":[{\""+list+"\":["+placement+",{}],\"inheritObjects\":false},{}],\"future\":9007199254740993,\"escaped\":\"雪\\u0061\"\r\n}\r\n"); }
    private static LosslessJson placement(byte[] value,String list) { return FloatingObjectPlacements.placements(FloatingBiomes.entries(new LosslessJson(value)).getFirst(),list).getFirst(); }
    private static LosslessJson noise(byte[] value,String list) { return FloatingPlacementOverrides.heightmap(placement(value,list)); }
    static void run(Path temp) throws Exception {
        Map<String,String> numbers=Map.of("zoom","2.5","opacity","0.25","offsetX","-13.5","offsetY","160","offsetZ","-2","seed","9223372036854775807","exponent","2","octaves","16");
        for (String list : FloatingObjectPlacements.LIST_FIELDS) {
            var context=new FloatingPlacementOverrides.Context(0,list,0);var target=context.noiseTarget();var styleTarget=NoiseStyleFields.Target.noise(target);byte[] absent=document(list,"{\"mode\":\"FLOATING\",\"futurePlacement\":9007199254740993}");
            check(target.heightmap() && target.resourceBase().equals("biomes") && target.draftPrefix().equals(context.heightmapPrefix()) && styleTarget.resourceBase().equals("biomes") && styleTarget.draftPrefix().equals(context.heightmapPrefix()+"style."));
            check(noise(absent,list)==null && noise(document(list,"{\"heightmap\":null}"),list)==null);
            byte[] enabled=FloatingPlacementOverrides.enableHeightmap(PATH,absent,context);check(noise(enabled,list).fields().isEmpty() && placement(enabled,list).stringToken("mode").equals("FLOATING"));
            rejects(() -> FloatingPlacementOverrides.enableHeightmap(PATH,enabled,context));
            byte[] restored=FloatingPlacementOverrides.restoreHeightmap(PATH,enabled,context);check(placement(restored,list).token("heightmap").equals("null"));check(noise(FloatingPlacementOverrides.enableHeightmap(PATH,restored,context),list).fields().isEmpty());
            check(Arrays.equals(absent,FloatingPlacementOverrides.restoreHeightmap(PATH,absent,context)));
            for (String field : NoiseFields.EDITABLE) {
                byte[] scalar=NoiseFields.enable(PATH,enabled,target,field);check(noise(scalar,list).fields().equals(Set.of(field)) && noise(scalar,list).token(field).equals(NoiseFields.defaultToken(field)));
                rejects(() -> NoiseFields.enable(PATH,scalar,target,field));rejects(() -> NoiseFields.enable(PATH,absent,target,field));
                if (NoiseFields.booleanField(field)) { NoiseFields.editBoolean(PATH,scalar,target,field,true);NoiseFields.editBoolean(PATH,scalar,target,field,false);rejects(() -> NoiseFields.editText(PATH,scalar,target,field,"1")); }
                else {
                    byte[] edited=NoiseFields.editText(PATH,scalar,target,field,numbers.get(field));check(Double.parseDouble(noise(edited,list).token(field))==Double.parseDouble(numbers.get(field)));
                    rejects(() -> NoiseFields.editBoolean(PATH,scalar,target,field,true));rejects(() -> NoiseFields.editText(PATH,scalar,target,field,"NaN"));
                    if (field.equals("seed")) for (String bad : List.of("9223372036854775808","-9223372036854775809","1.5")) rejects(() -> NoiseFields.editText(PATH,scalar,target,field,bad));
                    else rejects(() -> NoiseFields.editText(PATH,scalar,target,field,"Infinity"));
                    if (field.equals("zoom")) { NoiseFields.editText(PATH,scalar,target,field,"0.0001");rejects(() -> NoiseFields.editText(PATH,scalar,target,field,"0.00009")); }
                    if (field.equals("opacity")) { NoiseFields.editText(PATH,scalar,target,field,"0");NoiseFields.editText(PATH,scalar,target,field,"1");rejects(() -> NoiseFields.editText(PATH,scalar,target,field,"-0.01"));rejects(() -> NoiseFields.editText(PATH,scalar,target,field,"1.01")); }
                    if (field.equals("octaves")) for (String bad : List.of("0","17","1.5","1.000000000000000001","2147483648")) rejects(() -> NoiseFields.editText(PATH,scalar,target,field,bad));
                }
                for (String bad : List.of("null","[]","\"value\"")) {
                    byte[] invalid=document(list,"{\"heightmap\":{\""+field+"\":"+bad+"}}");
                    if (NoiseFields.booleanField(field)) rejects(() -> NoiseFields.editBoolean(PATH,invalid,target,field,true));else rejects(() -> NoiseFields.editText(PATH,invalid,target,field,numbers.get(field)));
                }
            }
            byte[] styled=NoiseStyleFields.enable(PATH,enabled,styleTarget,"style");check(noise(styled,list).stringPath("style.style").equals("IRIS"));
            check(noise(NoiseStyleFields.enable(PATH,document(list,"{\"heightmap\":{\"style\":{}}}"),styleTarget,"style"),list).stringPath("style.style").equals("FLAT"));
            for (String choice : NoiseStyleFields.choices()) check(noise(NoiseStyleFields.editStyle(PATH,styled,styleTarget,choice),list).stringPath("style.style").equals(choice));
            for (String field : NoiseStyleFields.EDITABLE) if (!field.equals("style")) { byte[] scalar=NoiseStyleFields.enable(PATH,styled,styleTarget,field);NoiseStyleFields.editText(PATH,scalar,styleTarget,field,field.equals("cellularFrequency") ? "0" : "1");rejects(() -> NoiseStyleFields.editText(PATH,scalar,styleTarget,field,"NaN")); }
            for (byte[] invalid : List.of(absent,document(list,"{\"heightmap\":null}"),document(list,"{\"heightmap\":\"snippet/noise\"}"))) { rejects(() -> NoiseFields.enable(PATH,invalid,target,"seed"));rejects(() -> NoiseStyleFields.enable(PATH,invalid,styleTarget,"style")); }
            for (String bad : List.of("[]","true","1","\"snippet/noise\"")) { byte[] invalid=document(list,"{\"heightmap\":"+bad+"}");rejects(() -> noise(invalid,list));rejects(() -> FloatingPlacementOverrides.enableHeightmap(PATH,invalid,context));rejects(() -> FloatingPlacementOverrides.restoreHeightmap(PATH,invalid,context)); }
            for (String path : List.of("generators/a.json","dimensions/a.json","regions/a.json","biomes/a.txt","a.json")) { rejects(() -> FloatingPlacementOverrides.enableHeightmap(path,absent,context));rejects(() -> FloatingPlacementOverrides.restoreHeightmap(path,enabled,context));rejects(() -> NoiseFields.enable(path,enabled,target,"seed"));rejects(() -> NoiseStyleFields.enable(path,enabled,styleTarget,"style")); }
            if (list.equals("floatingObjects")) {
                check(FloatingPlacementOverrides.forceKey(placement(absent,list))==null);byte[] flag=FloatingPlacementOverrides.enableForce(PATH,absent,context);check(placement(flag,list).token("forcePlace").equals("false") && placement(flag,list).token("force")==null);
                rejects(() -> FloatingPlacementOverrides.enableForce(PATH,flag,context));rejects(() -> FloatingPlacementOverrides.setForce(PATH,absent,context,true));
                for (String key : FloatingPlacementOverrides.FORCE_KEYS) {
                    byte[] source=document(list,"{\""+key+"\":false,\"futurePlacement\":9007199254740993}");check(FloatingPlacementOverrides.forceKey(placement(source,list)).equals(key));
                    check(text(FloatingPlacementOverrides.setForce(PATH,source,context,true)).equals(text(source).replace("\""+key+"\":false","\""+key+"\":true")) && !FloatingObjectPlacements.readOnlyFields(placement(source,list),list).contains(key));
                    rejects(() -> FloatingPlacementOverrides.enableForce(PATH,source,context));
                    for (String bad : List.of("null","[]","1","\"true\"")) { byte[] invalid=document(list,"{\""+key+"\":"+bad+"}");rejects(() -> FloatingPlacementOverrides.enableForce(PATH,invalid,context));rejects(() -> FloatingPlacementOverrides.setForce(PATH,invalid,context,true));check(FloatingObjectPlacements.readOnlyFields(placement(invalid,list),list).contains(key)); }
                }
            } else { rejects(() -> FloatingPlacementOverrides.enableForce(PATH,absent,context));rejects(() -> FloatingPlacementOverrides.setForce(PATH,document(list,"{\"force\":false}"),context,true));check(FloatingObjectPlacements.readOnlyFields(placement(document(list,"{\"force\":false}"),list),list).contains("force")); }
            for (String pair : List.of("\"forcePlace\":true,\"force\":false","\"force\":false,\"forcePlace\":true","\"forcePlace\":true,\"force\":true","\"forcePlace\":null,\"force\":false")) {
                byte[] dual=document(list,"{"+pair+"}");rejects(() -> FloatingPlacementOverrides.forceKey(placement(dual,list)));rejects(() -> FloatingPlacementOverrides.enableForce(PATH,dual,context));rejects(() -> FloatingPlacementOverrides.setForce(PATH,dual,context,false));
                check(FloatingObjectPlacements.readOnlyFields(placement(dual,list),list).containsAll(List.of("forcePlace","force")));
                check(text(FloatingPlacementOverrides.enableHeightmap(PATH,dual,context)).contains(pair));
            }
            String force=list.equals("floatingObjects") ? "force" : "forcePlace";byte[] original=document(list,"{\""+force+"\":false,\"heightmap\":{\"seed\":9007199254740993,\"offsetY\":1e2,\"enabled\":true,\"octaves\":1,\"style\":{\"style\":\"UNKNOWN\",\"zoom\":1e0,\"expression\":\"x+z\"},\"fracture\":[{\"future\":true}],\"cliffHeightGenerator\":{\"future\":9007199254740993},\"futureNoise\":\"雪\\u0061\"},\"futurePlacement\":9007199254740993}"),copy=original.clone();
            check(text(NoiseFields.editText(PATH,original,target,"seed","-9223372036854775808")).equals(text(original).replace("\"seed\":9007199254740993","\"seed\":-9223372036854775808")));
            check(text(NoiseFields.editText(PATH,original,target,"offsetY","160")).equals(text(original).replace("\"offsetY\":1e2","\"offsetY\":160")));
            check(FloatingPlacementOverrides.readOnlyHeightmapPaths(placement(original,list)).containsAll(List.of("heightmap.fracture[0].future","heightmap.cliffHeightGenerator","heightmap.futureNoise")));
            var drafts=new InspectorDrafts();String seed=target.draftPrefix()+"seed",style=styleTarget.draftPrefix()+"zoom",warp=FloatingObjectPlacements.placementPrefix(0,list,0)+"warp.zoom",other=new FloatingPlacementOverrides.Context(1,list,0).heightmapPrefix()+"seed";
            for (String key : List.of(seed,style,warp,other)) drafts.update(key,"1","2");
            check(!drafts.transitionUnder(context.heightmapPrefix(),() -> false,() -> { throw new AssertionError(); }) && drafts.count()==4);
            try { drafts.transitionUnder(context.heightmapPrefix(),() -> true,() -> { throw new IOException("failed write"); });throw new AssertionError(); } catch (IOException expected) { check(drafts.count()==4); }
            check(drafts.transitionUnder(context.heightmapPrefix(),() -> true,() -> {}) && drafts.count()==2 && drafts.contains(warp) && drafts.contains(other));
            check(Arrays.equals(original,copy));var saved=PackWorkspace.newPack(Map.of(PATH,original,"objects/opaque.iob",new byte[]{0,127,-1,42}));saved.replace(PATH,NoiseFields.editText(PATH,saved.read(PATH),target,"seed","-9223372036854775808"));saved.replace(PATH,NoiseFields.editText(PATH,saved.read(PATH),target,"offsetY","160"));saved.replace(PATH,NoiseStyleFields.editStyle(PATH,saved.read(PATH),styleTarget,"SIMPLEX"));
            if (list.equals("floatingObjects")) saved.replace(PATH,FloatingPlacementOverrides.setForce(PATH,saved.read(PATH),context,true));
            saved.save(temp.resolve("placement-overrides-"+list));saved.exportZip(temp.resolve("placement-overrides-"+list+".zip"));var reopened=PackWorkspace.open(temp.resolve("placement-overrides-"+list));var imported=PackWorkspace.importZip(temp.resolve("placement-overrides-"+list+".zip"));
            for (String path : saved.paths()) check(Arrays.equals(saved.read(path),reopened.read(path)) && Arrays.equals(saved.read(path),imported.read(path)));
            for (String root : List.of("{}","{\"floatingChildBiomes\":null}","{\"floatingChildBiomes\":[null]}","{\"floatingChildBiomes\":[{\""+list+"\":[\"snippet/placement\"]}]}")) { rejects(() -> FloatingPlacementOverrides.enableHeightmap(PATH,bytes(root),context));rejects(() -> NoiseFields.enable(PATH,bytes(root),target,"seed")); }
            rejects(() -> new NoiseFields.Target("composite",0,context));rejects(() -> new NoiseFields.Target("heightmap",0,context));rejects(() -> new NoiseStyleFields.Target("generators","style",target));
        }
        for (var target : List.of(NoiseFields.Target.composite(0),NoiseFields.Target.cliffHeight())) {
            String member=target.index()>=0 ? "{\"composite\":[{\"octaves\":1}]}" : "{\"cliffHeightGenerator\":{\"octaves\":1}}";byte[] source=bytes(member);
            for (String bad : List.of("1.000000000000000001","2147483648")) rejects(() -> NoiseFields.editText("generators/a.json",source,target,"octaves",bad));
            NoiseFields.editText("generators/a.json",source,target,"octaves","1.6e1");rejects(() -> NoiseFields.enable(PATH,source,target,"seed"));
        }
        rejects(() -> new NoiseFields.Target("heightmap",-1));
        System.out.println("PASS: floating virtual heightmap lifecycles, all noise/style controls and exact seeds/octaves, canonical/alternate force edits, ambiguous/anchored/type/owner guards, raw preservation, scoped drafts and Save/ZIP contracts");
    }
}
