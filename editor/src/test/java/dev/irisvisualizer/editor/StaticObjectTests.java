package dev.irisvisualizer.editor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

final class StaticObjectTests {
    private static final String PATH="dimensions/static.json";
    private static final StaticObjects.Context CONTEXT=new StaticObjects.Context(0);
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static String text(byte[] value) { return new String(value,StandardCharsets.UTF_8); }
    private static void check(boolean condition) { if (!condition) throw new AssertionError("Static object contract failed"); }
    private static void rejects(Runnable action) { try { action.run(); } catch (IllegalArgumentException expected) { return; }throw new AssertionError("Expected rejected static object operation"); }
    private static byte[] document(String entry) { return bytes("{\r\n \"staticObjects\":[ "+entry+", {\"futureSibling\":1e2} ],\"futureRoot\":9007199254740993,\"escaped\":\"雪\\u0061\"\r\n}\r\n"); }
    private static LosslessJson entry(byte[] raw) { return new LosslessJson(raw).objectArray("staticObjects").getFirst(); }
    static void run(Path temp) throws Exception {
        byte[] asset={0,127,-1,42};var workspace=PackWorkspace.newPack(Map.of(PATH,bytes("{}"),"objects/nested/tree.iob",asset,"objects/other.iob",asset,"biomes/unchanged.json",bytes("{\"keep\":1e2}")));
        check(StaticObjects.entries(new LosslessJson(bytes("{}")))==null);
        byte[] empty=StaticObjects.enable(PATH,bytes("{}"));check(new LosslessJson(empty).objectArray("staticObjects").isEmpty());rejects(() -> StaticObjects.enable(PATH,empty));
        byte[] added=StaticObjects.change(PATH,empty,RiverProfiles.Action.ADD,-1,"nested/tree","-29999984","-64","29999984",workspace);
        check(entry(added).fields().equals(java.util.Set.of("object","position")) && entry(added).stringToken("object").equals("nested/tree"));
        check(StaticObjects.object(entry(added),"position").token("x").equals("-29999984"));check(StaticObjects.object(entry(added),"rotation")==null);
        check(new LosslessJson(StaticObjects.change(PATH,added,RiverProfiles.Action.REMOVE,0,null,null,null,null,workspace)).objectArray("staticObjects").isEmpty());
        byte[] duplicate=StaticObjects.change(PATH,added,RiverProfiles.Action.DUPLICATE,0,null,null,null,null,workspace);check(new LosslessJson(duplicate).objectArray("staticObjects").size()==2);
        byte[] raw=document("{\"object\":\"legacy\\u0061\",\"position\":{\"x\":1e2,\"y\":0e0,\"z\":-2,\"futurePosition\":true},\"rotation\":{\"x\":0e0,\"futureRotation\":\"keep\"},\"seed\":9007199254740993,\"edit\":[{\"futureRule\":1e9}],\"futureEntry\":9007199254740993}"),copy=raw.clone();
        check(text(StaticObjects.setReference(PATH,raw,CONTEXT,"other",workspace)).equals(text(raw).replace("\"legacy\\u0061\"","\"other\"")));
        rejects(() -> StaticObjects.setReference(PATH,raw,CONTEXT,"missing",workspace));rejects(() -> StaticObjects.change(PATH,empty,RiverProfiles.Action.ADD,-1,"nested/tree.iob","0","0","0",workspace));
        byte[] edited=StaticObjects.setPosition(PATH,raw,CONTEXT,"29999984","319","-29999984");check(StaticObjects.object(entry(edited),"position").token("y").equals("319"));
        edited=StaticObjects.setRotation(PATH,edited,CONTEXT,"-360","22.5","360");check(StaticObjects.object(entry(edited),"rotation").token("y").equals("22.5"));
        for (String seed : List.of("-9223372036854775808","9223372036854775807","9007199254740993","1e3")) {
            byte[] next=StaticObjects.setSeed(PATH,raw,CONTEXT,seed);check(entry(next).token("seed").equals(new java.math.BigDecimal(seed).toBigIntegerExact().toString()));
        }
        for (String bad : List.of("9223372036854775808","-9223372036854775809","1.000000000000000001","NaN","Infinity")) rejects(() -> StaticObjects.setSeed(PATH,raw,CONTEXT,bad));
        for (String bad : List.of("29999985","-29999985","2147483648","1.000000000000000001","NaN")) { rejects(() -> StaticObjects.setPosition(PATH,raw,CONTEXT,bad,"0","0"));rejects(() -> StaticObjects.setPosition(PATH,raw,CONTEXT,"0","0",bad)); }
        for (String bad : List.of("-65","320","2147483648","1.000000000000000001")) rejects(() -> StaticObjects.setPosition(PATH,raw,CONTEXT,"0",bad,"0"));
        for (String bad : List.of("-360.001","360.001","NaN","Infinity")) for (int axis=0;axis<3;axis++) { int at=axis;rejects(() -> StaticObjects.setRotation(PATH,raw,CONTEXT,at==0 ? bad : "0",at==1 ? bad : "0",at==2 ? bad : "0")); }
        byte[] missing=document("{}");check(StaticObjects.object(entry(StaticObjects.setPosition(PATH,missing,CONTEXT,"1","2","3")),"position").token("z").equals("3"));
        check(StaticObjects.object(entry(StaticObjects.setRotation(PATH,missing,CONTEXT,"0","0","0")),"rotation").fields().size()==3);
        byte[] height=bytes("{\"dimensionHeight\":{\"min\":32,\"max\":96,\"future\":true},\"staticObjects\":[{}]}");
        StaticObjects.setPosition(PATH,height,CONTEXT,"0","32","0");StaticObjects.setPosition(PATH,height,CONTEXT,"0","95","0");rejects(() -> StaticObjects.setPosition(PATH,height,CONTEXT,"0","31","0"));rejects(() -> StaticObjects.setPosition(PATH,height,CONTEXT,"0","96","0"));
        byte[] emptyHeight=bytes("{\"dimensionHeight\":{},\"staticObjects\":[{}]}");StaticObjects.setPosition(PATH,emptyHeight,CONTEXT,"0","16","0");rejects(() -> StaticObjects.setPosition(PATH,emptyHeight,CONTEXT,"0","32","0"));
        for (String badHeight : List.of("null","\"snippet/height\"","{\"min\":0.5}","{\"min\":32,\"max\":16}","{\"max\":2147483648}")) rejects(() -> StaticObjects.setPosition(PATH,bytes("{\"dimensionHeight\":"+badHeight+",\"staticObjects\":[{}]}"),CONTEXT,"0","0","0"));
        check(Arrays.equals(missing,StaticObjects.setScale(PATH,missing,CONTEXT,null)));
        for (String value : List.of("0.01","1","50")) { check(entry(StaticObjects.setScale(PATH,missing,CONTEXT,value)).token("scale").equals(value));check(new LosslessJson(StaticObjects.setDimensionScale(PATH,bytes("{}"),value)).token("allObjectScaleFactor").equals(value)); }
        byte[] scaled=StaticObjects.setScale(PATH,missing,CONTEXT,"1");check(entry(StaticObjects.setScale(PATH,scaled,CONTEXT,null)).token("scale").equals("null"));
        check(entry(StaticObjects.setScale(PATH,document("{\"scale\":null}"),CONTEXT,"2")).token("scale").equals("2"));
        for (String bad : List.of("0","-1","0.0099","50.0001","NaN","Infinity")) { rejects(() -> StaticObjects.setScale(PATH,missing,CONTEXT,bad));rejects(() -> StaticObjects.setDimensionScale(PATH,bytes("{}"),bad)); }
        check(StaticObjects.interpolations().equals(List.of("NONE","TRILINEAR","TRICUBIC","TRIHERMITE")));
        for (String interpolation : StaticObjects.interpolations()) check(entry(StaticObjects.setInterpolation(PATH,missing,CONTEXT,interpolation)).stringToken("scaleInterpolation").equals(interpolation));
        rejects(() -> StaticObjects.setInterpolation(PATH,missing,CONTEXT,"UNKNOWN"));
        for (String flag : List.of("bore","smartBore")) { byte[] next=StaticObjects.setBoolean(PATH,missing,CONTEXT,flag,false);check(entry(next).token(flag).equals("false"));check(entry(StaticObjects.setBoolean(PATH,next,CONTEXT,flag,true)).token(flag).equals("true")); }
        rejects(() -> StaticObjects.setBoolean(PATH,raw,CONTEXT,"forcePlace",true));
        for (String bad : List.of("null","[]","true","\"snippet/transform\"")) {
            rejects(() -> StaticObjects.setPosition(PATH,document("{\"position\":"+bad+"}"),CONTEXT,"0","0","0"));rejects(() -> StaticObjects.setRotation(PATH,document("{\"rotation\":"+bad+"}"),CONTEXT,"0","0","0"));
        }
        for (String bad : List.of("null","[]","true","\"1\"")) {
            rejects(() -> StaticObjects.setSeed(PATH,document("{\"seed\":"+bad+"}"),CONTEXT,"0"));
            if (!bad.equals("\"1\"")) rejects(() -> StaticObjects.setReference(PATH,document("{\"object\":"+bad+"}"),CONTEXT,"other",workspace));
            rejects(() -> StaticObjects.setDimensionScale(PATH,bytes("{\"allObjectScaleFactor\":"+bad+"}"),"1"));
            for (String flag : List.of("bore","smartBore")) if (!bad.equals("true")) rejects(() -> StaticObjects.setBoolean(PATH,document("{\""+flag+"\":"+bad+"}"),CONTEXT,flag,true));
        }
        check(entry(StaticObjects.setReference(PATH,document("{\"object\":\"1\"}"),CONTEXT,"other",workspace)).stringToken("object").equals("other"));
        for (String bad : List.of("[]","true","\"1\"")) { rejects(() -> StaticObjects.setScale(PATH,document("{\"scale\":"+bad+"}"),CONTEXT,"1"));rejects(() -> StaticObjects.setScale(PATH,document("{\"scale\":"+bad+"}"),CONTEXT,null)); }
        for (String bad : List.of("null","{}","[null]","[{},1]","[\"snippet/static\"]")) {
            byte[] invalid=bytes("{\"staticObjects\":"+bad+"}");rejects(() -> StaticObjects.entries(new LosslessJson(invalid)));rejects(() -> StaticObjects.enable(PATH,invalid));rejects(() -> StaticObjects.setSeed(PATH,invalid,CONTEXT,"0"));rejects(() -> StaticObjects.change(PATH,invalid,RiverProfiles.Action.ADD,-1,"other","0","0","0",workspace));
        }
        byte[] moved=StaticObjects.change(PATH,raw,RiverProfiles.Action.DOWN,0,null,null,null,null,workspace);check(Arrays.equals(raw,StaticObjects.change(PATH,moved,RiverProfiles.Action.UP,1,null,null,null,null,workspace)));
        for (String wrong : List.of("biomes/static.json","regions/static.json","generators/static.json","dimensions/nested/static.json","dimensions/static.txt")) {
            rejects(() -> StaticObjects.enable(wrong,bytes("{}")));rejects(() -> StaticObjects.change(wrong,raw,RiverProfiles.Action.DUPLICATE,0,null,null,null,null,workspace));rejects(() -> StaticObjects.setReference(wrong,raw,CONTEXT,"other",workspace));
            rejects(() -> StaticObjects.setPosition(wrong,raw,CONTEXT,"0","0","0"));rejects(() -> StaticObjects.setRotation(wrong,raw,CONTEXT,"0","0","0"));rejects(() -> StaticObjects.setScale(wrong,raw,CONTEXT,"1"));rejects(() -> StaticObjects.setDimensionScale(wrong,bytes("{}"),"1"));rejects(() -> StaticObjects.setInterpolation(wrong,raw,CONTEXT,"NONE"));rejects(() -> StaticObjects.setBoolean(wrong,raw,CONTEXT,"bore",false));rejects(() -> StaticObjects.setSeed(wrong,raw,CONTEXT,"0"));
        }
        rejects(() -> new StaticObjects.Context(-1));rejects(() -> StaticObjects.setSeed(PATH,raw,new StaticObjects.Context(2),"0"));
        for (String token : List.of("\"futureSibling\":1e2","\"futureRoot\":9007199254740993","\"futureEntry\":9007199254740993","\"futurePosition\":true","\"futureRotation\":\"keep\"","\"seed\":9007199254740993","\"edit\":[{\"futureRule\":1e9}]","\"escaped\":\"雪\\u0061\"")) check(text(edited).contains(token));check(Arrays.equals(raw,copy));
        var drafts=new InspectorDrafts();String pos=CONTEXT.prefix()+"position.x",rot=CONTEXT.prefix()+"rotation.y",seed=CONTEXT.prefix()+"seed",dimension=StaticObjects.scalePrefix()+"value",other="biomeObjects#.0.seed";
        for (String key : List.of(pos,rot,seed,dimension,other)) drafts.update(key,"1","2");check(!drafts.transitionUnder(StaticObjects.listPrefix(),() -> false,() -> { throw new AssertionError(); }) && drafts.count()==5);
        try { drafts.transitionUnder(StaticObjects.listPrefix(),() -> true,() -> { throw new IOException("failed write"); });throw new AssertionError(); } catch (IOException expected) { check(drafts.count()==5); }
        check(drafts.transitionUnder(CONTEXT.prefix()+"position.",() -> true,() -> {}) && drafts.count()==4 && drafts.contains(rot));check(drafts.transitionUnder(StaticObjects.listPrefix(),() -> true,() -> {}) && drafts.count()==2 && drafts.contains(dimension) && drafts.contains(other));
        workspace.replace(PATH,edited);workspace.save(temp.resolve("static-objects"));workspace.exportZip(temp.resolve("static-objects.zip"));var reopened=PackWorkspace.open(temp.resolve("static-objects"));var imported=PackWorkspace.importZip(temp.resolve("static-objects.zip"));
        for (String path : workspace.paths()) check(Arrays.equals(workspace.read(path),reopened.read(path)) && Arrays.equals(workspace.read(path),imported.read(path)));
        check(Arrays.equals(workspace.read("objects/nested/tree.iob"),asset));check(FieldCoverage.groups(PATH).containsAll(List.of("staticObject","staticObjectRotation","position")));
        check(FieldCoverage.fields("staticObject").stream().filter(FieldCoverage.Field::implemented).count()==9);check(FieldCoverage.fields("position").stream().allMatch(FieldCoverage.Field::implemented));
        System.out.println("PASS: static object list/reference lifecycle, absolute world position and exclusive dimension heights, atomic fixed rotation, nullable scale fallback, interpolation/bore flags, exact 64-bit seeds, owner/type guards, raw ordering/preservation, scoped drafts and Save/ZIP");
    }
}
