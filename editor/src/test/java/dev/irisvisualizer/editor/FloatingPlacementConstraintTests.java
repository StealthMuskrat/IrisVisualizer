package dev.irisvisualizer.editor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

final class FloatingPlacementConstraintTests {
    private static final String PATH="biomes/nested/constraints.json";
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static String text(byte[] value) { return new String(value,StandardCharsets.UTF_8); }
    private static void check(boolean condition) { if (!condition) throw new AssertionError("Floating placement constraint contract failed"); }
    private static void rejects(Runnable action) { try { action.run(); } catch (IllegalArgumentException expected) { return; }throw new AssertionError("Expected rejected placement constraint operation"); }
    private static byte[] document(String list,String placement) { return bytes("{\r\n \"floatingChildBiomes\":[{\""+list+"\":["+placement+",{}],\"inheritObjects\":false},{}],\"future\":9007199254740993,\"escaped\":\"雪\\u0061\"\r\n}\r\n"); }
    private static LosslessJson placement(byte[] value,String list) { return FloatingObjectPlacements.placements(FloatingBiomes.entries(new LosslessJson(value)).getFirst(),list).getFirst(); }
    private static LosslessJson object(byte[] value,String list,String field) { return FloatingPlacementConstraints.object(placement(value,list),field); }
    static void run(Path temp) throws Exception {
        check(FloatingPlacementConstraints.carvingModes().equals(List.of("SURFACE_ONLY","CARVING_ONLY","ANYWHERE")));
        check(FloatingPlacementConstraints.warpDefault().equals("FLAT") && FloatingPlacementConstraints.multiplierDefault().equals("1"));
        for (String list : FloatingObjectPlacements.LIST_FIELDS) {
            var context=new FloatingPlacementConstraints.Context(0,list,0);var target=context.warpTarget();byte[] absent=document(list,"{}");
            check(target.placementWarp() && target.member().equals("warp") && target.warpContext().equals(context) && target.draftPrefix().equals(context.prefix("warp")));
            check(NoiseStyleFields.contextDefault(target).equals("FLAT") && !target.strictBounds());
            byte[] warp=NoiseStyleFields.enable(PATH,absent,target,"style");check(placement(warp,list).stringPath("warp.style").equals("FLAT"));
            check(placement(NoiseStyleFields.enable(PATH,document(list,"{\"warp\":{}}"),target,"style"),list).stringPath("warp.style").equals("FLAT"));
            for (String choice : NoiseStyleFields.choices()) check(placement(NoiseStyleFields.editStyle(PATH,warp,target,choice),list).stringPath("warp.style").equals(choice));
            for (String field : NoiseStyleFields.EDITABLE) if (!field.equals("style")) {
                byte[] scalar=NoiseStyleFields.enable(PATH,warp,target,field);NoiseStyleFields.editText(PATH,scalar,target,field,field.equals("cellularFrequency") ? "0" : "1");rejects(() -> NoiseStyleFields.editText(PATH,scalar,target,field,"NaN"));
            }
            byte[] multiplier=FloatingPlacementConstraints.enableMultiplier(PATH,warp,context);check(placement(multiplier,list).tokenPath("warp.multiplier").equals("1"));
            for (String valid : List.of("0.00001","0.5","1","64","1e6")) FloatingPlacementConstraints.setMultiplier(PATH,multiplier,context,valid);
            for (String bad : List.of("0","-1","0.000009","NaN","Infinity")) rejects(() -> FloatingPlacementConstraints.setMultiplier(PATH,multiplier,context,bad));
            rejects(() -> FloatingPlacementConstraints.setMultiplier(PATH,warp,context,"1"));rejects(() -> FloatingPlacementConstraints.enableMultiplier(PATH,multiplier,context));
            rejects(() -> NoiseStyleFields.enable(PATH,absent,target,"multiplier"));
            byte[] cellular=document(list,"{\"warp\":{\"cellularFrequency\":0,\"cellularZoom\":0}}");NoiseStyleFields.editText(PATH,cellular,target,"cellularZoom","0");rejects(() -> NoiseStyleFields.editText(PATH,cellular,target,"cellularFrequency","1"));
            for (String bad : List.of("null","[]","true","1","\"snippet/warp\"")) {
                byte[] invalid=document(list,"{\"warp\":"+bad+"}");rejects(() -> NoiseStyleFields.enable(PATH,invalid,target,"style"));rejects(() -> NoiseStyleFields.editStyle(PATH,invalid,target,"FLAT"));rejects(() -> FloatingPlacementConstraints.enableMultiplier(PATH,invalid,context));
            }
            for (String bad : List.of("null","[]","true","\"1\"")) rejects(() -> FloatingPlacementConstraints.setMultiplier(PATH,document(list,"{\"warp\":{\"multiplier\":"+bad+"}}"),context,"1"));
            if (!list.equals("floatingObjects")) {
                for (String field : FloatingPlacementConstraints.OBJECT_FIELDS) rejects(() -> FloatingPlacementConstraints.create(PATH,absent,context,field));
                for (String field : FloatingPlacementConstraints.FLAGS) { rejects(() -> FloatingPlacementConstraints.enableFlag(PATH,absent,context,field));rejects(() -> FloatingPlacementConstraints.setFlag(PATH,absent,context,field,true)); }
                for (String field : FloatingPlacementConstraints.SCALARS) { rejects(() -> FloatingPlacementConstraints.enableScalar(PATH,absent,context,field));rejects(() -> FloatingPlacementConstraints.setScalar(PATH,absent,context,field,"2")); }
                rejects(() -> FloatingPlacementConstraints.setBounds(PATH,absent,context,"clamp","-2048","2048"));rejects(() -> FloatingPlacementConstraints.setCarving(PATH,absent,context,"ANYWHERE"));
            } else {
                for (String field : FloatingPlacementConstraints.FLAGS) {
                    byte[] enabled=FloatingPlacementConstraints.enableFlag(PATH,absent,context,field);check(placement(enabled,list).token(field).equals(FloatingPlacementConstraints.defaultToken(field)));
                    FloatingPlacementConstraints.setFlag(PATH,enabled,context,field,true);FloatingPlacementConstraints.setFlag(PATH,enabled,context,field,false);
                    rejects(() -> FloatingPlacementConstraints.setFlag(PATH,absent,context,field,true));rejects(() -> FloatingPlacementConstraints.enableFlag(PATH,enabled,context,field));
                    for (String bad : List.of("null","1","\"true\"")) rejects(() -> FloatingPlacementConstraints.setFlag(PATH,document(list,"{\""+field+"\":"+bad+"}"),context,field,true));
                }
                for (String field : FloatingPlacementConstraints.SCALARS) {
                    byte[] enabled=FloatingPlacementConstraints.enableScalar(PATH,absent,context,field);check(placement(enabled,list).token(field).equals("2"));
                    FloatingPlacementConstraints.setScalar(PATH,enabled,context,field,field.equals("surfaceSupportBuffer") ? "0" : "1");FloatingPlacementConstraints.setScalar(PATH,enabled,context,field,"16");
                    for (String bad : List.of(field.equals("surfaceSupportBuffer") ? "-1" : "0","17","1.000000000000000001","2147483648","NaN")) rejects(() -> FloatingPlacementConstraints.setScalar(PATH,enabled,context,field,bad));
                    rejects(() -> FloatingPlacementConstraints.enableScalar(PATH,enabled,context,field));rejects(() -> FloatingPlacementConstraints.setScalar(PATH,absent,context,field,"2"));
                    for (String bad : List.of("null","[]","true","\"2\"")) rejects(() -> FloatingPlacementConstraints.setScalar(PATH,document(list,"{\""+field+"\":"+bad+"}"),context,field,"2"));
                }
                for (String mode : FloatingPlacementConstraints.carvingModes()) { check(!FloatingPlacementConstraints.carvingHelp(mode).isBlank());check(placement(FloatingPlacementConstraints.setCarving(PATH,absent,context,mode),list).stringToken("carvingSupport").equals(mode)); }
                byte[] unknown=document(list,"{\"carvingSupport\":\"UNKNOWN\\u0061\"}");check(placement(FloatingPlacementConstraints.setCarving(PATH,unknown,context,"ANYWHERE"),list).stringToken("carvingSupport").equals("ANYWHERE"));
                rejects(() -> FloatingPlacementConstraints.setCarving(PATH,absent,context,"UNKNOWN"));
                for (String bad : List.of("null","[]","1")) rejects(() -> FloatingPlacementConstraints.setCarving(PATH,document(list,"{\"carvingSupport\":"+bad+"}"),context,"ANYWHERE"));
                for (String field : FloatingPlacementConstraints.OBJECT_FIELDS) {
                    check(object(absent,list,field)==null);byte[] enabled=FloatingPlacementConstraints.create(PATH,absent,context,field);check(object(enabled,list,field).fields().isEmpty());
                    rejects(() -> FloatingPlacementConstraints.create(PATH,enabled,context,field));rejects(() -> FloatingPlacementConstraints.setBounds(PATH,absent,context,field,"0","1"));
                    var members=field.equals("clamp") ? FloatingPlacementConstraints.CLAMP_FIELDS : FloatingPlacementConstraints.SLOPE_FIELDS;
                    String min=field.equals("clamp") ? "-2048" : "0",max=field.equals("clamp") ? "2048" : "1024";
                    byte[] bounds=FloatingPlacementConstraints.setBounds(PATH,enabled,context,field,min,max);check(object(bounds,list,field).token(members.getFirst()).equals(min) && object(bounds,list,field).token(members.getLast()).equals(max));
                    FloatingPlacementConstraints.setBounds(PATH,enabled,context,field,"0","0");
                    for (String[] bad : List.of(new String[]{field.equals("clamp") ? "-2049" : "-0.01","1"},new String[]{"0",field.equals("clamp") ? "2049" : "1024.01"},new String[]{"2","1"},new String[]{"NaN","1"},new String[]{"0","Infinity"})) rejects(() -> FloatingPlacementConstraints.setBounds(PATH,enabled,context,field,bad[0],bad[1]));
                    if (field.equals("clamp")) rejects(() -> FloatingPlacementConstraints.setBounds(PATH,enabled,context,field,"0","1.000000000000000001"));else FloatingPlacementConstraints.setBounds(PATH,enabled,context,field,"0.125","0.5");
                    for (String bad : List.of("null","[]","true","\"snippet/bounds\"")) {
                        byte[] invalid=document(list,"{\""+field+"\":"+bad+"}");rejects(() -> object(invalid,list,field));rejects(() -> FloatingPlacementConstraints.create(PATH,invalid,context,field));rejects(() -> FloatingPlacementConstraints.setBounds(PATH,invalid,context,field,"0","1"));
                    }
                    for (String member : members) for (String bad : List.of("null","[]","true","\"0\"")) rejects(() -> FloatingPlacementConstraints.setBounds(PATH,document(list,"{\""+field+"\":{\""+member+"\":"+bad+"}}"),context,field,"0","1"));
                }
            }
            for (String path : List.of("dimensions/a.json","regions/a.json","generators/a.json","biomes/a.txt","a.json")) { rejects(() -> FloatingPlacementConstraints.create(path,absent,context,"clamp"));rejects(() -> FloatingPlacementConstraints.enableMultiplier(path,warp,context));rejects(() -> NoiseStyleFields.enable(path,absent,target,"style")); }
            for (String bad : List.of("{}","{\"floatingChildBiomes\":null}","{\"floatingChildBiomes\":[null]}","{\"floatingChildBiomes\":[{\""+list+"\":null}]}","{\"floatingChildBiomes\":[{\""+list+"\":[\"snippet/placement\"]}]}")) rejects(() -> NoiseStyleFields.enable(PATH,bytes(bad),target,"style"));
            String fixture="{\"warp\":{\"style\":\"UNKNOWN\",\"zoom\":1e0,\"multiplier\":1e0,\"expression\":\"x + z\",\"futureWarp\":9007199254740993},\"clamp\":{\"minimumHeight\":-1e2,\"maximumHeight\":1e2,\"futureClamp\":true},\"slopeCondition\":{\"minimumSlope\":0,\"maximumSlope\":1e1,\"futureSlope\":\"雪\\u0061\"},\"rotateTowardsSlope\":false,\"surfaceSupportBuffer\":2,\"force\":true,\"futurePlacement\":9007199254740993}";
            byte[] original=document(list,fixture),copy=original.clone();check(text(FloatingPlacementConstraints.setMultiplier(PATH,original,context,"2")).equals(text(original).replace("\"multiplier\":1e0","\"multiplier\":2")));
            check(text(NoiseStyleFields.editText(PATH,original,target,"zoom","2")).equals(text(original).replace("\"zoom\":1e0","\"zoom\":2")));
            var paths=FloatingPlacementConstraints.readOnlyPaths(placement(original,list),context);check(paths.containsAll(List.of("warp.expression","warp.futureWarp")) && !paths.contains("warp.multiplier"));
            check(FloatingObjectPlacements.readOnlyFields(placement(original,list),list).contains("clamp")!=list.equals("floatingObjects"));
            if (list.equals("floatingObjects")) {
                check(paths.containsAll(List.of("clamp.futureClamp","slopeCondition.futureSlope")));
                check(text(FloatingPlacementConstraints.setBounds(PATH,original,context,"clamp","-200","200")).equals(text(original).replace("\"minimumHeight\":-1e2","\"minimumHeight\":-200").replace("\"maximumHeight\":1e2","\"maximumHeight\":200")));
            }
            var drafts=new InspectorDrafts();String warpKey=target.draftPrefix()+"zoom",multiplierKey=context.prefix("warp")+"multiplier",clampKey=context.prefix("clamp")+"minimumHeight",slopeKey=context.prefix("slopeCondition")+"minimumSlope",other=new FloatingPlacementConstraints.Context(1,list,0).prefix("warp")+"zoom";
            for (String key : List.of(warpKey,multiplierKey,clampKey,slopeKey,other)) drafts.update(key,"1","2");
            check(!drafts.transitionUnder(context.prefix("clamp"),() -> false,() -> { throw new AssertionError(); }) && drafts.count()==5);
            try { drafts.transitionUnder(context.prefix("clamp"),() -> true,() -> { throw new IOException("failed write"); });throw new AssertionError(); } catch (IOException expected) { check(drafts.count()==5); }
            check(drafts.transitionUnder(context.prefix("clamp"),() -> true,() -> {}) && drafts.count()==4 && drafts.contains(warpKey));
            check(drafts.transitionUnder(context.prefix("warp"),() -> true,() -> {}) && drafts.count()==2 && drafts.contains(slopeKey));
            check(drafts.transitionUnder(FloatingObjectPlacements.listPrefix(0,list),() -> true,() -> {}) && drafts.count()==1 && drafts.contains(other));
            check(Arrays.equals(original,copy));var saved=PackWorkspace.newPack(Map.of(PATH,original,"objects/opaque.iob",new byte[]{0,127,-1,42}));
            saved.replace(PATH,FloatingPlacementConstraints.setMultiplier(PATH,saved.read(PATH),context,"4"));saved.replace(PATH,NoiseStyleFields.editStyle(PATH,saved.read(PATH),target,"SIMPLEX"));
            if (list.equals("floatingObjects")) { saved.replace(PATH,FloatingPlacementConstraints.setBounds(PATH,saved.read(PATH),context,"clamp","-512","512"));saved.replace(PATH,FloatingPlacementConstraints.setBounds(PATH,saved.read(PATH),context,"slopeCondition","1","5"));saved.replace(PATH,FloatingPlacementConstraints.setFlag(PATH,saved.read(PATH),context,"rotateTowardsSlope",true));saved.replace(PATH,FloatingPlacementConstraints.setScalar(PATH,saved.read(PATH),context,"surfaceSupportBuffer","4"));saved.replace(PATH,FloatingPlacementConstraints.setCarving(PATH,saved.read(PATH),context,"ANYWHERE")); }
            saved.save(temp.resolve("placement-constraints-"+list));saved.exportZip(temp.resolve("placement-constraints-"+list+".zip"));var reopened=PackWorkspace.open(temp.resolve("placement-constraints-"+list));var imported=PackWorkspace.importZip(temp.resolve("placement-constraints-"+list+".zip"));
            for (String path : saved.paths()) check(Arrays.equals(saved.read(path),reopened.read(path)) && Arrays.equals(saved.read(path),imported.read(path)));
        }
        for (String bad : List.of("floatingChildBiomes.0.objects.0.warp","floatingChildBiomes.-1.extraObjects.0.warp","floatingChildBiomes.0.extraObjects.2147483648.warp","floatingChildBiomes.0.extraObjects.0.warp.style")) rejects(() -> new NoiseStyleFields.Target("biomes",bad,null));
        check(FieldCoverage.fields("objectLimit").size()==2 && FieldCoverage.fields("objectLimit").stream().allMatch(FieldCoverage.Field::implemented));
        System.out.println("PASS: floating clamp/slope/support/rotation/carving controls, all-context warp styles/multiplier, exact/finite bounds, absent/type/owner/context guards, scoped drafts, raw preservation and Save/ZIP contracts");
    }
}
