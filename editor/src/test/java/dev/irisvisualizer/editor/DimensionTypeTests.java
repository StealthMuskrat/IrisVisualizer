package dev.irisvisualizer.editor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class DimensionTypeTests {
    private static final String PATH="dimensions/type.json";
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static String text(byte[] value) { return new String(value,StandardCharsets.UTF_8); }
    private static byte[] document(String options) { return bytes("{\"dimensionOptions\":"+options+",\"future\":9007199254740993}"); }
    private static LosslessJson options(byte[] source) { return DimensionTypeFields.object(new LosslessJson(source)); }
    private static void check(boolean value) { if (!value) throw new AssertionError("Dimension-type contract failed"); }
    private static void rejects(Runnable action) { try { action.run(); } catch (IllegalArgumentException expected) { return; }throw new AssertionError("Expected rejected dimension-type operation"); }
    static void run(Path temp) throws Exception {
        check(DimensionTypeFields.stateChoices().equals(List.of("DEFAULT","TRUE","FALSE")) && DimensionTypeFields.environments().equals(List.of("NORMAL","NETHER","THE_END","CUSTOM")));
        byte[] absent=bytes("{\"future\":\"雪\\u0061\"}"),empty=DimensionTypeFields.enable(PATH,absent);
        check(options(empty).fields().isEmpty() && new LosslessJson(empty).stringToken("future").equals("雪a"));rejects(() -> DimensionTypeFields.enable(PATH,empty));
        for (String field : DimensionTypeFields.EDITABLE) {
            byte[] enabled=DimensionTypeFields.enableField(PATH,empty,field);var object=options(enabled);check(object.fields().equals(Set.of(field)));
            if (DimensionTypeFields.STATES.contains(field)) check(object.stringToken(field).equals("DEFAULT"));else check(object.token(field).equals("-1"));
            rejects(() -> DimensionTypeFields.enableField(PATH,enabled,field));rejects(() -> DimensionTypeFields.enableField(PATH,absent,field));
            if (DimensionTypeFields.STATES.contains(field)) {
                for (String state : DimensionTypeFields.stateChoices()) check(options(DimensionTypeFields.setState(PATH,enabled,field,state)).stringToken(field).equals(state));
                for (String state : List.of("default","true","OTHER","")) rejects(() -> DimensionTypeFields.setState(PATH,enabled,field,state));
                rejects(() -> DimensionTypeFields.setState(PATH,empty,field,"TRUE"));
                byte[] legacy=document("{\""+field+"\":\"future-state\"}");check(options(DimensionTypeFields.setState(PATH,legacy,field,"FALSE")).stringToken(field).equals("FALSE"));
            } else {
                rejects(() -> DimensionTypeFields.setNumber(PATH,empty,field,"1"));
                for (String invalid : List.of("NaN","Infinity","1e999","","0x1p0")) rejects(() -> DimensionTypeFields.setNumber(PATH,enabled,field,invalid));
                check(options(DimensionTypeFields.setNumber(PATH,enabled,field,"-1.0")).token(field).equals("-1"));
            }
            for (String token : List.of("true","[]","{}","\"1\"","null")) {
                if (DimensionTypeFields.STATES.contains(field) && token.equals("\"1\"")) continue;
                if (DimensionTypeFields.NUMBERS.contains(field) && DimensionTypeFields.nullable(field) && token.equals("null")) continue;
                byte[] wrong=document("{\""+field+"\":"+token+"}");
                rejects(() -> DimensionTypeFields.enableField(PATH,wrong,field));
                if (DimensionTypeFields.STATES.contains(field)) rejects(() -> DimensionTypeFields.setState(PATH,wrong,field,"TRUE"));
                else { rejects(() -> DimensionTypeFields.setNumber(PATH,wrong,field,"1"));if (DimensionTypeFields.nullable(field)) rejects(() -> DimensionTypeFields.setNull(PATH,wrong,field)); }
                check(DimensionFields.readOnlyPaths(new LosslessJson(wrong)).contains("dimensionOptions."+field));
            }
        }
        for (String[] spec : new String[][]{{"coordinateScale","0.00001","30000000","0","30000000.000001"},{"ambientLight","0","1","-0.1","1.000000000000001"},{"fixedTime","0","9223372036854775807","-2","9223372036854775808"},{"cloudHeight","-2032","2031","-2033","2032"},{"monsterSpawnBlockLightLimit","0","15","-2","16"}}) {
            String field=spec[0];byte[] source=DimensionTypeFields.enableField(PATH,empty,field);
            DimensionTypeFields.setNumber(PATH,source,field,spec[1]);DimensionTypeFields.setNumber(PATH,source,field,spec[2]);
            rejects(() -> DimensionTypeFields.setNumber(PATH,source,field,spec[3]));rejects(() -> DimensionTypeFields.setNumber(PATH,source,field,spec[4]));
        }
        for (String field : List.of("fixedTime","cloudHeight","monsterSpawnBlockLightLimit")) {
            byte[] source=DimensionTypeFields.enableField(PATH,empty,field);
            for (String invalid : List.of("1.000000000000000001","-1.000000000000000001","9223372036854775808")) rejects(() -> DimensionTypeFields.setNumber(PATH,source,field,invalid));
            for (String token : List.of("1.000000000000000001","9223372036854775808","1e999")) {
                byte[] wrong=document("{\""+field+"\":"+token+"}");rejects(() -> DimensionTypeFields.setNumber(PATH,wrong,field,"1"));check(DimensionFields.readOnlyPaths(new LosslessJson(wrong)).contains("dimensionOptions."+field));
            }
        }
        byte[] time=DimensionTypeFields.enableField(PATH,empty,"fixedTime");
        for (String value : List.of("9007199254740993","9223372036854775807","9.007199254740993e15")) {
            String expected=value.contains("e") ? "9007199254740993" : value;check(options(DimensionTypeFields.setNumber(PATH,time,"fixedTime",value)).token("fixedTime").equals(expected));
        }
        for (String field : List.of("fixedTime","cloudHeight")) {
            byte[] source=DimensionTypeFields.enableField(PATH,empty,field),cleared=DimensionTypeFields.setNull(PATH,source,field);check(options(cleared).token(field).equals("null"));
            check(Arrays.equals(cleared,DimensionTypeFields.setNull(PATH,cleared,field)));
            check(options(DimensionTypeFields.setNumber(PATH,cleared,field,"-1")).token(field).equals("-1"));check(options(DimensionTypeFields.setNumber(PATH,cleared,field,"0")).token(field).equals("0"));
            rejects(() -> DimensionTypeFields.setNull(PATH,empty,field));
        }
        for (String field : List.of("coordinateScale","ambientLight","monsterSpawnBlockLightLimit")) rejects(() -> DimensionTypeFields.setNull(PATH,empty,field));
        // Signed native integer tokens outside new authoring bounds remain repairable within their storage width.
        DimensionTypeFields.setNumber(PATH,document("{\"cloudHeight\":2147483647}"),"cloudHeight","0");DimensionTypeFields.setNumber(PATH,document("{\"fixedTime\":-9223372036854775808}"),"fixedTime","0");
        byte[] root=DimensionTypeFields.enableRoot(PATH,absent,"environment");check(new LosslessJson(root).stringToken("environment").equals("NORMAL"));
        for (String environment : DimensionTypeFields.environments()) check(new LosslessJson(DimensionTypeFields.setEnvironment(PATH,root,environment)).stringToken("environment").equals(environment));
        check(new LosslessJson(DimensionTypeFields.setEnvironment(PATH,bytes("{\"environment\":\"legacy\"}"),"CUSTOM")).stringToken("environment").equals("CUSTOM"));
        rejects(() -> DimensionTypeFields.setEnvironment(PATH,root,"OVERWORLD"));rejects(() -> DimensionTypeFields.setEnvironment(PATH,absent,"NORMAL"));rejects(() -> DimensionTypeFields.enableRoot(PATH,root,"environment"));
        byte[] bright=DimensionTypeFields.enableRoot(PATH,root,"fullbright");check(new LosslessJson(bright).token("fullbright").equals("false"));check(new LosslessJson(DimensionTypeFields.setFullbright(PATH,bright,true)).token("fullbright").equals("true"));rejects(() -> DimensionTypeFields.setFullbright(PATH,absent,true));
        for (String token : List.of("null","true","1","{}","[]")) {
            byte[] wrong=bytes("{\"environment\":"+token+"}");rejects(() -> DimensionTypeFields.enableRoot(PATH,wrong,"environment"));rejects(() -> DimensionTypeFields.setEnvironment(PATH,wrong,"NORMAL"));check(DimensionFields.readOnlyPaths(new LosslessJson(wrong)).contains("environment"));
        }
        for (String token : List.of("null","1","{}","[]","\"false\"")) {
            byte[] wrong=bytes("{\"fullbright\":"+token+"}");rejects(() -> DimensionTypeFields.enableRoot(PATH,wrong,"fullbright"));rejects(() -> DimensionTypeFields.setFullbright(PATH,wrong,true));check(DimensionFields.readOnlyPaths(new LosslessJson(wrong)).contains("fullbright"));
        }
        for (String token : List.of("null","[]","true","1","\"snippet/options/type\"")) {
            byte[] wrong=document(token);rejects(() -> DimensionTypeFields.enable(PATH,wrong));rejects(() -> DimensionTypeFields.enableField(PATH,wrong,"fixedTime"));rejects(() -> DimensionTypeFields.setNumber(PATH,wrong,"fixedTime","0"));rejects(() -> DimensionTypeFields.setNull(PATH,wrong,"fixedTime"));rejects(() -> DimensionTypeFields.setState(PATH,wrong,"natural","TRUE"));
            check(DimensionFields.readOnlyPaths(new LosslessJson(wrong)).contains("dimensionOptions"));
            check(new LosslessJson(DimensionTypeFields.enableRoot(PATH,wrong,"fullbright")).token("dimensionOptions").equals(token));
        }
        String original="{\r\n \"environment\":\"NETHER\",\"fullbright\":false,\"borrowedDimensionType\":\"mod:legacy\",\"dimensionOptions\":{\"fixedTime\":9007199254740993,\"cloudHeight\":null,\"ambientLight\":0.1,\"natural\":\"DEFAULT\",\"unknown\":\"雪\\u0061\"},\"worldBoundary\":{\"size\":16384},\"large\":9223372036854775807\r\n}\r\n";
        byte[] source=bytes(original),copy=source.clone();check(text(DimensionTypeFields.setNumber(PATH,source,"fixedTime","9223372036854775807")).equals(original.replace("\"fixedTime\":9007199254740993","\"fixedTime\":9223372036854775807")));
        check(text(DimensionTypeFields.setNull(PATH,source,"fixedTime")).equals(original.replace("\"fixedTime\":9007199254740993","\"fixedTime\":null")));
        check(text(DimensionTypeFields.setState(PATH,source,"natural","FALSE")).equals(original.replace("\"natural\":\"DEFAULT\"","\"natural\":\"FALSE\"")));
        check(text(DimensionTypeFields.setFullbright(PATH,source,true)).equals(original.replace("\"fullbright\":false","\"fullbright\":true")));
        check(DimensionFields.readOnlyPaths(new LosslessJson(source)).contains("dimensionOptions.unknown") && !DimensionFields.readOnlyPaths(new LosslessJson(source)).contains("borrowedDimensionType") && !DimensionFields.readOnlyPaths(new LosslessJson(source)).contains("dimensionOptions"));check(Arrays.equals(source,copy));
        for (String path : List.of("biomes/a.json","regions/a.json","generators/a.json","dimensions/nested/a.json","a.json")) {
            rejects(() -> DimensionTypeFields.enable(path,absent));rejects(() -> DimensionTypeFields.enableField(path,empty,"fixedTime"));rejects(() -> DimensionTypeFields.setNumber(path,source,"fixedTime","0"));rejects(() -> DimensionTypeFields.setNull(path,source,"fixedTime"));rejects(() -> DimensionTypeFields.setState(path,source,"natural","TRUE"));rejects(() -> DimensionTypeFields.enableRoot(path,absent,"environment"));rejects(() -> DimensionTypeFields.setEnvironment(path,source,"NORMAL"));rejects(() -> DimensionTypeFields.setFullbright(path,source,true));
        }
        rejects(() -> DimensionTypeFields.enableField(PATH,empty,"future"));rejects(() -> DimensionTypeFields.setNumber(PATH,source,"natural","0"));rejects(() -> DimensionTypeFields.setState(PATH,source,"fixedTime","TRUE"));rejects(() -> DimensionTypeFields.enableRoot(PATH,absent,"borrowedDimensionType"));
        // Shared exact-nullable storage protects every token width/type and preserves absent null.
        check(Arrays.equals(absent,new LosslessJson(absent).nullableInteger("time",null)));
        check(new LosslessJson(new LosslessJson(absent).nullableInteger("time",Long.MAX_VALUE)).token("time").equals("9223372036854775807"));
        for (String token : List.of("9223372036854775808","0.0001","\"1\"","true","[]","{}")) rejects(() -> new LosslessJson(bytes("{\"time\":"+token+"}")).nullableInteger("time",null));
        var drafts=new InspectorDrafts();String timeKey=DimensionTypeFields.prefix()+"fixedTime",cloudKey=DimensionTypeFields.prefix()+"cloudHeight",other=WorldBoundaryFields.prefix()+"size";
        for (String key : List.of(timeKey,cloudKey,other)) drafts.update(key,"1","2");rejects(() -> DimensionTypeFields.setNumber(PATH,source,"fixedTime","9223372036854775808"));check(drafts.count()==3);
        DimensionTypeFields.setNumber(PATH,source,"fixedTime","0");drafts.applied(timeKey);check(drafts.count()==2 && drafts.contains(cloudKey) && drafts.contains(other));
        check(!drafts.transitionUnder(DimensionTypeFields.prefix(),() -> false,() -> { throw new AssertionError(); }) && drafts.count()==2);
        try { drafts.transitionUnder(DimensionTypeFields.prefix(),() -> true,() -> { throw new IOException("failed write"); });throw new AssertionError(); } catch (IOException expected) { check(drafts.count()==2); }
        check(drafts.transitionUnder(DimensionTypeFields.prefix(),() -> true,() -> {}) && drafts.count()==1 && drafts.contains(other));
        var workspace=PackWorkspace.newPack(Map.of(PATH,source,"dimensions/empty.json",empty,"dimensions/unsupported.json",document("\"snippet/options/type\""),"objects/opaque.iob",new byte[]{0,127,-1,42}));
        workspace.replace(PATH,DimensionTypeFields.setNumber(PATH,workspace.read(PATH),"fixedTime","9223372036854775807"));workspace.replace(PATH,DimensionTypeFields.setNull(PATH,workspace.read(PATH),"cloudHeight"));workspace.replace(PATH,DimensionTypeFields.setFullbright(PATH,workspace.read(PATH),true));
        workspace.save(temp.resolve("dimension-type"));workspace.exportZip(temp.resolve("dimension-type.zip"));var reopened=PackWorkspace.open(temp.resolve("dimension-type"));var imported=PackWorkspace.importZip(temp.resolve("dimension-type.zip"));
        for (String path : workspace.paths()) check(Arrays.equals(workspace.read(path),reopened.read(path)) && Arrays.equals(workspace.read(path),imported.read(path)));
        check(FieldCoverage.fields("dimensionTypeOptions").size()==13 && FieldCoverage.fields("dimensionTypeOptions").stream().allMatch(FieldCoverage.Field::implemented));
        System.out.println("PASS: all 13 dimension-type options, four environments/fullbright, exact 64-bit nullable time and cloud lifecycle, native inherit/bound rules, malformed/owner guards, lossless bytes, scoped drafts and Save/ZIP");
    }
}
