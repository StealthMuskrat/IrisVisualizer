package dev.irisvisualizer.editor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

final class FloatingPlacementSettingsTests {
    private static final String PATH="biomes/settings.json";
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static String text(byte[] value) { return new String(value,StandardCharsets.UTF_8); }
    private static void check(boolean condition) { if (!condition) throw new AssertionError("Floating placement settings contract failed"); }
    private static void rejects(Runnable action) { try { action.run(); } catch (IllegalArgumentException expected) { return; }throw new AssertionError("Expected rejected placement setting operation"); }
    private static byte[] document(String list,String placement) { return bytes("{\r\n \"floatingChildBiomes\":[{\""+list+"\":["+placement+",{}],\"objectShrinkFactor\":0.5,\"inheritObjects\":false},{}],\"future\":9007199254740993,\"escaped\":\"雪\\u0061\"\r\n}\r\n"); }
    private static LosslessJson placement(byte[] value,String list) { return FloatingObjectPlacements.placements(FloatingBiomes.entries(new LosslessJson(value)).getFirst(),list).getFirst(); }
    private static LosslessJson object(byte[] value,String list,String field) { return FloatingPlacementSettings.object(placement(value,list),field); }
    static void run(Path temp) throws Exception {
        check(FloatingPlacementSettings.rangeDefault("min").equals("16") && FloatingPlacementSettings.rangeDefault("max").equals("32"));
        check(FloatingPlacementSettings.styleDefault().equals("STATIC") && FloatingPlacementSettings.interpolations().size()==4);
        for (String list : FloatingObjectPlacements.LIST_FIELDS) {
            var context=new FloatingPlacementSettings.Context(0,list,0);var target=context.styleTarget();
            check(target.placementDensity() && target.member().equals("style") && target.placementContext().equals(context));
            check(target.draftPrefix().equals(context.prefix("densityStyle")+"style."));
            check(NoiseStyleFields.contextDefault(target).equals("STATIC") && !target.strictBounds());
            byte[] absent=document(list,"{\"density\":3,\"force\":true,\"futurePlacement\":9007199254740993}");
            for (String field : FloatingPlacementSettings.OBJECT_FIELDS) {
                check(object(absent,list,field)==null && object(document(list,"{\""+field+"\":null}"),list,field)==null);
                byte[] enabled=FloatingPlacementSettings.enable(PATH,absent,context,field);
                check(object(enabled,list,field).fields().isEmpty() && placement(enabled,list).token("density").equals("3"));
                rejects(() -> FloatingPlacementSettings.enable(PATH,enabled,context,field));
                byte[] disabled=FloatingPlacementSettings.disable(PATH,enabled,context,field);check(placement(disabled,list).token(field).equals("null"));
                check(object(FloatingPlacementSettings.enable(PATH,disabled,context,field),list,field).fields().isEmpty());
                for (String bad : List.of("[]","true","1","\"snippet/setting\"")) {
                    byte[] invalid=document(list,"{\""+field+"\":"+bad+"}");
                    rejects(() -> object(invalid,list,field));rejects(() -> FloatingPlacementSettings.enable(PATH,invalid,context,field));rejects(() -> FloatingPlacementSettings.disable(PATH,invalid,context,field));
                }
                for (String path : List.of("dimensions/a.json","regions/a.json","generators/a.json","biomes/a.txt","a.json")) {
                    rejects(() -> FloatingPlacementSettings.enable(path,absent,context,field));rejects(() -> FloatingPlacementSettings.disable(path,enabled,context,field));
                    rejects(() -> FloatingPlacementSettings.setDensityBounds(path,enabled,context,"0","2"));rejects(() -> FloatingPlacementSettings.setScaleBounds(path,enabled,context,"1","2"));
                    rejects(() -> FloatingPlacementSettings.enableScaleScalar(path,enabled,context,"size"));rejects(() -> FloatingPlacementSettings.setScaleScalar(path,enabled,context,"size","2"));rejects(() -> FloatingPlacementSettings.setInterpolation(path,enabled,context,"NONE"));
                }
            }
            byte[] range=FloatingPlacementSettings.enable(PATH,absent,context,"densityStyle");
            byte[] bounds=FloatingPlacementSettings.setDensityBounds(PATH,range,context,"0","2147483647");
            check(object(bounds,list,"densityStyle").token("min").equals("0") && object(bounds,list,"densityStyle").token("max").equals("2147483647"));
            check(object(FloatingPlacementSettings.setDensityBounds(PATH,range,context,"0.125","0.5"),list,"densityStyle").token("min").equals("0.125"));
            for (String[] bad : List.of(new String[]{"-1","1"},new String[]{"0","2147483648"},new String[]{"2","1"},new String[]{"NaN","1"},new String[]{"0","Infinity"})) rejects(() -> FloatingPlacementSettings.setDensityBounds(PATH,range,context,bad[0],bad[1]));
            rejects(() -> FloatingPlacementSettings.setDensityBounds(PATH,absent,context,"0","1"));
            byte[] styled=NoiseStyleFields.enable(PATH,range,target,"style");
            check(object(styled,list,"densityStyle").stringPath("style.style").equals("STATIC"));
            // An existing empty generator style uses its own class default, independently of the range default.
            byte[] emptyStyle=document(list,"{\"densityStyle\":{\"style\":{}}}");
            check(object(NoiseStyleFields.enable(PATH,emptyStyle,target,"style"),list,"densityStyle").stringPath("style.style").equals("FLAT"));
            for (String choice : NoiseStyleFields.choices()) check(object(NoiseStyleFields.editStyle(PATH,styled,target,choice),list,"densityStyle").stringPath("style.style").equals(choice));
            for (String member : NoiseStyleFields.EDITABLE) if (!member.equals("style")) {
                byte[] scalar=NoiseStyleFields.enable(PATH,styled,target,member);NoiseStyleFields.editText(PATH,scalar,target,member,member.equals("cellularFrequency") ? "0" : "1");
                rejects(() -> NoiseStyleFields.editText(PATH,scalar,target,member,"NaN"));
            }
            byte[] cellular=document(list,"{\"densityStyle\":{\"style\":{\"cellularFrequency\":0,\"cellularZoom\":0}}}");
            NoiseStyleFields.editText(PATH,cellular,target,"cellularZoom","0");rejects(() -> NoiseStyleFields.editText(PATH,cellular,target,"cellularFrequency","1"));
            byte[] ready=NoiseStyleFields.editText(PATH,cellular,target,"cellularZoom","1");NoiseStyleFields.editText(PATH,ready,target,"cellularFrequency","1");
            for (byte[] parent : List.of(absent,document(list,"{\"densityStyle\":null}"),document(list,"{\"densityStyle\":\"snippet/range\"}"))) {
                rejects(() -> NoiseStyleFields.enable(PATH,parent,target,"style"));rejects(() -> NoiseStyleFields.editStyle(PATH,parent,target,"STATIC"));
            }
            byte[] scale=FloatingPlacementSettings.enable(PATH,absent,context,"scale");
            for (String member : List.of("size","variations")) {
                byte[] scalar=FloatingPlacementSettings.enableScaleScalar(PATH,scale,context,member);
                check(object(scalar,list,"scale").token(member).equals(FloatingPlacementSettings.scaleDefault(member)));
                rejects(() -> FloatingPlacementSettings.enableScaleScalar(PATH,scalar,context,member));rejects(() -> FloatingPlacementSettings.setScaleScalar(PATH,scale,context,member,"1"));
                for (String valid : member.equals("size") ? List.of("0.01","1","50") : List.of("1","7","32","3.2e1")) FloatingPlacementSettings.setScaleScalar(PATH,scalar,context,member,valid);
                for (String bad : member.equals("size") ? List.of("0.009","50.001","NaN","Infinity") : List.of("0","33","2147483648","1.000000000000000001","NaN")) rejects(() -> FloatingPlacementSettings.setScaleScalar(PATH,scalar,context,member,bad));
                rejects(() -> FloatingPlacementSettings.setScaleScalar(PATH,document(list,"{\"scale\":{\""+member+"\":null}}"),context,member,"1"));
            }
            byte[] scaled=FloatingPlacementSettings.setScaleBounds(PATH,scale,context,"0.01","50");
            check(object(scaled,list,"scale").token("minimumScale").equals("0.01") && object(scaled,list,"scale").token("maximumScale").equals("50"));
            FloatingPlacementSettings.setScaleBounds(PATH,scale,context,"1","1");
            for (String[] bad : List.of(new String[]{"0","1"},new String[]{"1","50.01"},new String[]{"2","1"},new String[]{"NaN","2"},new String[]{"1","Infinity"})) rejects(() -> FloatingPlacementSettings.setScaleBounds(PATH,scale,context,bad[0],bad[1]));
            for (String choice : FloatingPlacementSettings.interpolations()) {
                check(!FloatingPlacementSettings.interpolationHelp(choice).isBlank());
                check(object(FloatingPlacementSettings.setInterpolation(PATH,scale,context,choice),list,"scale").stringToken("interpolation").equals(choice));
            }
            rejects(() -> FloatingPlacementSettings.setInterpolation(PATH,scale,context,"LERP"));
            byte[] unknown=document(list,"{\"scale\":{\"interpolation\":\"future\\u0061\",\"size\":1e0}}");
            check(text(FloatingPlacementSettings.setInterpolation(PATH,unknown,context,"NONE")).equals(text(unknown).replace("\"future\\u0061\"","\"NONE\"")));
            for (String bad : List.of("null","1","[]")) rejects(() -> FloatingPlacementSettings.setInterpolation(PATH,document(list,"{\"scale\":{\"interpolation\":"+bad+"}}"),context,"NONE"));
            for (String field : List.of("min","max")) for (String bad : List.of("null","true","\"16\"")) rejects(() -> FloatingPlacementSettings.setDensityBounds(PATH,document(list,"{\"densityStyle\":{\""+field+"\":"+bad+"}}"),context,"16","32"));
            for (String field : List.of("minimumScale","maximumScale")) for (String bad : List.of("null","[]","\"1\"")) rejects(() -> FloatingPlacementSettings.setScaleBounds(PATH,document(list,"{\"scale\":{\""+field+"\":"+bad+"}}"),context,"1","2"));
            String fixture="{\"density\":3,\"mode\":\"FLOATING\",\"densityStyle\":{\"min\":1e0,\"max\":2e0,\"futureRange\":9007199254740993,\"style\":{\"style\":\"UNKNOWN\",\"zoom\":1e0,\"expression\":\"x + z\",\"futureStyle\":true}},\"scale\":{\"size\":1e0,\"variations\":7,\"interpolation\":\"UNKNOWN\",\"futureScale\":\"雪\\u0061\"},\"force\":true}";
            byte[] original=document(list,fixture),copy=original.clone();
            check(text(FloatingPlacementSettings.setScaleScalar(PATH,original,context,"size","2")).equals(text(original).replace("\"size\":1e0","\"size\":2")));
            check(text(NoiseStyleFields.editText(PATH,original,target,"zoom","2")).equals(text(original).replace("\"zoom\":1e0","\"zoom\":2")));
            check(FloatingPlacementSettings.readOnlyPaths(placement(original,list)).containsAll(List.of("scale.futureScale","densityStyle.futureRange","densityStyle.style.expression","densityStyle.style.futureStyle")));
            check(!FloatingObjectPlacements.readOnlyFields(placement(original,list),list).contains("scale") && !FloatingObjectPlacements.readOnlyFields(placement(original,list),list).contains("densityStyle"));
            var drafts=new InspectorDrafts();String densityKey=context.prefix("densityStyle")+"min",styleKey=target.draftPrefix()+"zoom",scaleKey=context.prefix("scale")+"size",chance=FloatingObjectPlacements.placementPrefix(0,list,0)+"chance",other=new FloatingPlacementSettings.Context(1,list,0).prefix("scale")+"size";
            for (String key : List.of(densityKey,styleKey,scaleKey,chance,other)) drafts.update(key,"1","2");
            check(!drafts.transitionUnder(context.prefix("densityStyle"),() -> false,() -> { throw new AssertionError(); }) && drafts.count()==5);
            try { drafts.transitionUnder(context.prefix("densityStyle"),() -> true,() -> { throw new IOException("failed write"); });throw new AssertionError(); } catch (IOException expected) { check(drafts.count()==5); }
            check(drafts.transitionUnder(context.prefix("densityStyle"),() -> true,() -> {}) && drafts.count()==3 && drafts.contains(scaleKey) && drafts.contains(chance));
            check(drafts.transitionUnder(FloatingObjectPlacements.listPrefix(0,list),() -> true,() -> {}) && drafts.count()==1 && drafts.contains(other));
            check(Arrays.equals(original,copy));var saved=PackWorkspace.newPack(Map.of(PATH,original,"objects/opaque.iob",new byte[]{0,127,-1,42}));
            saved.replace(PATH,FloatingPlacementSettings.setDensityBounds(PATH,saved.read(PATH),context,"0","5"));saved.replace(PATH,FloatingPlacementSettings.setScaleScalar(PATH,saved.read(PATH),context,"size","2"));
            saved.replace(PATH,FloatingPlacementSettings.setScaleBounds(PATH,saved.read(PATH),context,"0.5","3"));saved.replace(PATH,FloatingPlacementSettings.setInterpolation(PATH,saved.read(PATH),context,"TRILINEAR"));
            saved.replace(PATH,NoiseStyleFields.editStyle(PATH,saved.read(PATH),target,"STATIC"));
            saved.save(temp.resolve("placement-settings-"+list));saved.exportZip(temp.resolve("placement-settings-"+list+".zip"));var reopened=PackWorkspace.open(temp.resolve("placement-settings-"+list));var imported=PackWorkspace.importZip(temp.resolve("placement-settings-"+list+".zip"));
            for (String path : saved.paths()) check(Arrays.equals(saved.read(path),reopened.read(path)) && Arrays.equals(saved.read(path),imported.read(path)));
            for (String bad : List.of("{}","{\"floatingChildBiomes\":null}","{\"floatingChildBiomes\":[null]}","{\"floatingChildBiomes\":[{\""+list+"\":null}]}","{\"floatingChildBiomes\":[{\""+list+"\":[\"snippet/placement\"]}]}")) {
                rejects(() -> FloatingPlacementSettings.enable(PATH,bytes(bad),context,"scale"));rejects(() -> FloatingPlacementSettings.setDensityBounds(PATH,bytes(bad),context,"0","2"));rejects(() -> NoiseStyleFields.enable(PATH,bytes(bad),target,"style"));
            }
        }
        for (String invalid : List.of("floatingChildBiomes.0.objects.0.densityStyle.style","floatingChildBiomes.-1.extraObjects.0.densityStyle.style","floatingChildBiomes.0.extraObjects.-1.densityStyle.style","floatingChildBiomes.2147483648.extraObjects.0.densityStyle.style","floatingChildBiomes.0.extraObjects.0.scale.style")) rejects(() -> new NoiseStyleFields.Target("biomes",invalid,null));
        rejects(() -> new FloatingPlacementSettings.Context(-1,"extraObjects",0));rejects(() -> new FloatingPlacementSettings.Context(0,"objects",0));rejects(() -> new FloatingPlacementSettings.Context(0,"extraObjects",-1));
        check(FieldCoverage.fields("objectScale").size()==5 && FieldCoverage.fields("objectScale").stream().allMatch(FieldCoverage.Field::implemented));
        System.out.println("PASS: floating density/scale fallbacks, all four contexts, source/class style defaults, bounded numeric and exact variation controls, all noise/interpolation choices, owner/type guards, scoped drafts, raw preservation and Save/ZIP contracts");
    }
}
