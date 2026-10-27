package dev.irisvisualizer.editor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class WorldBoundaryTests {
    private static final String PATH="dimensions/border.json";
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static String text(byte[] value) { return new String(value,StandardCharsets.UTF_8); }
    private static byte[] document(String border) { return bytes("{\"worldBoundary\":"+border+",\"future\":9007199254740993}"); }
    private static void check(boolean value) { if (!value) throw new AssertionError("World-border contract failed"); }
    private static void rejects(Runnable action) { try { action.run(); } catch (IllegalArgumentException expected) { return; }throw new AssertionError("Expected rejected world-border operation"); }
    static void run(Path temp) throws Exception {
        check(WorldBoundaryFields.defaultToken("size").equals("16384") && WorldBoundaryFields.defaultToken("warningDistance").equals("16") && WorldBoundaryFields.defaultToken("damageBuffer").equals("5") && WorldBoundaryFields.defaultToken("damageAmount").equals("0.2"));
        byte[] absent=bytes("{\"future\":\"雪\\u0061\"}"),empty=WorldBoundaryFields.enable(PATH,absent),nullBorder=document("null");
        check(WorldBoundaryFields.object(new LosslessJson(empty)).fields().isEmpty());
        check(WorldBoundaryFields.object(new LosslessJson(WorldBoundaryFields.enable(PATH,nullBorder))).fields().isEmpty());
        check(Arrays.equals(absent,WorldBoundaryFields.disable(PATH,absent)) && Arrays.equals(nullBorder,WorldBoundaryFields.disable(PATH,nullBorder)));
        rejects(() -> WorldBoundaryFields.enable(PATH,empty));
        rejects(() -> WorldBoundaryFields.setCenter(PATH,absent,"0","0"));rejects(() -> WorldBoundaryFields.setCenter(PATH,nullBorder,"0","0"));
        for (String field : WorldBoundaryFields.SCALARS) {
            byte[] enabled=WorldBoundaryFields.enableScalar(PATH,empty,field);var border=WorldBoundaryFields.object(new LosslessJson(enabled));
            check(border.fields().equals(Set.of(field)) && border.token(field).equals(WorldBoundaryFields.defaultToken(field)));
            rejects(() -> WorldBoundaryFields.enableScalar(PATH,enabled,field));rejects(() -> WorldBoundaryFields.setScalar(PATH,empty,field,"1"));
            rejects(() -> WorldBoundaryFields.enableScalar(PATH,absent,field));rejects(() -> WorldBoundaryFields.enableScalar(PATH,nullBorder,field));
            for (String invalid : List.of("NaN","Infinity","-Infinity","1e999","","one","0x1p0")) rejects(() -> WorldBoundaryFields.setScalar(PATH,enabled,field,invalid));
            for (String invalid : List.of("null","\"1\"","true","{}","[]")) {
                byte[] unsupported=document("{\""+field+"\":"+invalid+"}");
                rejects(() -> WorldBoundaryFields.enableScalar(PATH,unsupported,field));rejects(() -> WorldBoundaryFields.setScalar(PATH,unsupported,field,"1"));
                check(DimensionFields.readOnlyPaths(new LosslessJson(unsupported)).contains("worldBoundary."+field));
            }
        }
        for (String[] pair : new String[][]{{"-29999984","29999984"},{"0","0"},{"1.25e2","-0.5"}}) {
            byte[] updated=WorldBoundaryFields.setCenter(PATH,empty,pair[0],pair[1]);var center=WorldBoundaryFields.center(WorldBoundaryFields.object(new LosslessJson(updated)));
            check(center.fields().equals(Set.of("x","z")) && Double.parseDouble(center.token("x"))==Double.parseDouble(pair[0]) && Double.parseDouble(center.token("z"))==Double.parseDouble(pair[1]));
        }
        for (String invalid : List.of("29999985","-29999985","NaN","Infinity","1e999","","0x1p0")) {
            rejects(() -> WorldBoundaryFields.setCenter(PATH,empty,invalid,"0"));rejects(() -> WorldBoundaryFields.setCenter(PATH,empty,"0",invalid));
        }
        byte[] all=document("{\"size\":16384,\"warningDistance\":16,\"damageBuffer\":5,\"damageAmount\":0.2}");
        for (String input : List.of("1","59999968","1.25","5.9999968e7")) WorldBoundaryFields.setScalar(PATH,all,"size",input);
        for (String input : List.of("0.999","0","-1","59999969")) rejects(() -> WorldBoundaryFields.setScalar(PATH,all,"size",input));
        for (String input : List.of("0","2147483647","2.147483647e9")) {
            String actual=WorldBoundaryFields.object(new LosslessJson(WorldBoundaryFields.setScalar(PATH,all,"warningDistance",input))).token("warningDistance");check(actual.equals(input.equals("0") ? "0" : "2147483647"));
        }
        for (String input : List.of("-1","2147483648","16.000000000000000001","1e999")) rejects(() -> WorldBoundaryFields.setScalar(PATH,all,"warningDistance",input));
        for (String token : List.of("16.000000000000000001","2147483648","-2147483649","1e999")) {
            byte[] unsupported=document("{\"warningDistance\":"+token+"}");rejects(() -> WorldBoundaryFields.setScalar(PATH,unsupported,"warningDistance","1"));check(DimensionFields.readOnlyPaths(new LosslessJson(unsupported)).contains("worldBoundary.warningDistance"));
        }
        // In-range integer tokens outside the authoring bounds remain repairable.
        WorldBoundaryFields.setScalar(PATH,document("{\"warningDistance\":-1}"),"warningDistance","0");
        for (String field : List.of("damageBuffer","damageAmount")) {
            for (String input : List.of("0","0.125","1.7976931348623157e308")) WorldBoundaryFields.setScalar(PATH,all,field,input);
            rejects(() -> WorldBoundaryFields.setScalar(PATH,all,field,"-0.01"));
        }
        for (String token : List.of("null","[]","true","1","\"snippet/border/test\"")) {
            byte[] unsupported=document(token);rejects(() -> WorldBoundaryFields.enable(PATH,unsupported));rejects(() -> WorldBoundaryFields.disable(PATH,unsupported));
            rejects(() -> WorldBoundaryFields.setCenter(PATH,unsupported,"0","0"));rejects(() -> WorldBoundaryFields.enableScalar(PATH,unsupported,"size"));check(DimensionFields.readOnlyPaths(new LosslessJson(unsupported)).contains("worldBoundary"));
            byte[] wrongCenter=document("{\"center\":"+token+"}");rejects(() -> WorldBoundaryFields.setCenter(PATH,wrongCenter,"0","0"));check(DimensionFields.readOnlyPaths(new LosslessJson(wrongCenter)).contains("worldBoundary.center"));
            // A malformed center never prevents unrelated valid scalar editing.
            check(WorldBoundaryFields.object(new LosslessJson(WorldBoundaryFields.enableScalar(PATH,wrongCenter,"size"))).token("center").equals(token));
        }
        for (String token : List.of("null","[]","true","{}","\"1\"")) for (String axis : WorldBoundaryFields.AXES) {
            byte[] unsupported=document("{\"center\":{\""+axis+"\":"+token+"}}");rejects(() -> WorldBoundaryFields.setCenter(PATH,unsupported,"1","2"));check(DimensionFields.readOnlyPaths(new LosslessJson(unsupported)).contains("worldBoundary.center."+axis));
        }
        String original="{\r\n \"worldBoundary\":{\"center\":{\"x\":1e1,\"z\":-2,\"future\":\"雪\\u0061\"},\"size\":16384,\"warningDistance\":16,\"damageBuffer\":5,\"damageAmount\":0.2,\"unknown\":[null,9007199254740993]},\"staticObjects\":[{\"object\":\"legacy\",\"seed\":9223372036854775807}],\"future\":true\r\n}\r\n";
        byte[] source=bytes(original),copy=source.clone(),centerChanged=WorldBoundaryFields.setCenter(PATH,source,"25.5","-30"),sizeChanged=WorldBoundaryFields.setScalar(PATH,source,"size","32768");
        check(text(centerChanged).equals(original.replace("\"x\":1e1","\"x\":25.5").replace("\"z\":-2","\"z\":-30")));
        check(text(sizeChanged).equals(original.replace("\"size\":16384","\"size\":32768")));
        check(DimensionFields.readOnlyPaths(new LosslessJson(source)).containsAll(List.of("worldBoundary.center.future","worldBoundary.unknown")) && !DimensionFields.readOnlyPaths(new LosslessJson(source)).contains("worldBoundary"));
        check(DimensionFields.readOnlyPaths(new LosslessJson(nullBorder)).stream().noneMatch(p -> p.startsWith("worldBoundary")));
        check(new LosslessJson(WorldBoundaryFields.disable(PATH,source)).token("worldBoundary").equals("null"));
        for (String path : List.of("biomes/a.json","regions/a.json","generators/a.json","dimensions/nested/a.json","a.json")) {
            rejects(() -> WorldBoundaryFields.enable(path,absent));rejects(() -> WorldBoundaryFields.disable(path,source));rejects(() -> WorldBoundaryFields.setCenter(path,source,"0","0"));rejects(() -> WorldBoundaryFields.setScalar(path,all,"size","1"));rejects(() -> WorldBoundaryFields.enableScalar(path,empty,"size"));
        }
        for (String field : List.of("future","center","size.x")) { rejects(() -> WorldBoundaryFields.defaultToken(field));rejects(() -> WorldBoundaryFields.setScalar(PATH,all,field,"1"));rejects(() -> WorldBoundaryFields.enableScalar(PATH,empty,field)); }
        check(Arrays.equals(source,copy));
        var drafts=new InspectorDrafts();String x=WorldBoundaryFields.prefix()+"center.x",z=WorldBoundaryFields.prefix()+"center.z",size=WorldBoundaryFields.prefix()+"size",other="rootStyle#dimensions.regionStyle.zoom";
        for (String key : List.of(x,z,size,other)) drafts.update(key,"1","2");
        rejects(() -> WorldBoundaryFields.setCenter(PATH,source,"2","NaN"));check(drafts.count()==4);
        WorldBoundaryFields.setCenter(PATH,source,"2","3");drafts.applied(x);drafts.applied(z);check(drafts.count()==2 && drafts.contains(size) && drafts.contains(other));
        check(!drafts.transitionUnder(WorldBoundaryFields.prefix(),() -> false,() -> { throw new AssertionError(); }) && drafts.count()==2);
        try { drafts.transitionUnder(WorldBoundaryFields.prefix(),() -> true,() -> { throw new IOException("failed write"); });throw new AssertionError(); } catch (IOException expected) { check(drafts.count()==2); }
        check(drafts.transitionUnder(WorldBoundaryFields.prefix(),() -> true,() -> {}) && drafts.count()==1 && drafts.contains(other));
        var workspace=PackWorkspace.newPack(Map.of(PATH,source,"dimensions/empty.json",empty,"dimensions/null.json",nullBorder,"dimensions/unsupported.json",document("\"snippet/border/a\""),"objects/opaque.iob",new byte[]{0,127,-1,42}));
        workspace.replace(PATH,WorldBoundaryFields.setCenter(PATH,workspace.read(PATH),"29999984","-29999984"));workspace.replace(PATH,WorldBoundaryFields.setScalar(PATH,workspace.read(PATH),"size","59999968"));
        workspace.save(temp.resolve("world-boundary"));workspace.exportZip(temp.resolve("world-boundary.zip"));var reopened=PackWorkspace.open(temp.resolve("world-boundary"));var imported=PackWorkspace.importZip(temp.resolve("world-boundary.zip"));
        for (String path : workspace.paths()) check(Arrays.equals(workspace.read(path),reopened.read(path)) && Arrays.equals(workspace.read(path),imported.read(path)));
        check(FieldCoverage.fields("worldBoundary").size()==5 && FieldCoverage.fields("worldBoundary").stream().allMatch(FieldCoverage.Field::implemented) && FieldCoverage.fields("worldBoundaryCenter").size()==2 && FieldCoverage.fields("worldBoundaryCenter").stream().allMatch(FieldCoverage.Field::implemented));
        System.out.println("PASS: optional native border lifecycle, atomic center pairs, full diameter and finite/exact native bounds, malformed/owner guards, lossless advanced data, scoped drafts and Save/ZIP");
    }
}
