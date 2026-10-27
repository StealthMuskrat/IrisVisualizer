package dev.irisvisualizer.editor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class RiverPassageTests {
    private static final String PATH="dimensions/passages.json";
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static String text(byte[] value) { return new String(value,StandardCharsets.UTF_8); }
    private static void check(boolean value) { if (!value) throw new AssertionError("River passage contract failed"); }
    private static void rejects(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Expected rejected passage operation");
    }
    private static LosslessJson geometry(byte[] value) { return RiverGeometryFields.geometry(HydrologyFields.rivers(HydrologyFields.hydrology(new LosslessJson(value)))); }
    private static LosslessJson section(byte[] value,String name) { return RiverGeometryFields.section(geometry(value),name); }
    static void run(Path temp) throws Exception {
        Map<String,String> channel=Map.ofEntries(
            Map.entry("bedRoundness","2.4"),Map.entry("bedRoughness","0.28"),Map.entry("wallRoughness","0.24"),Map.entry("roughnessWavelength","11"),
            Map.entry("radialBase","0.86"),Map.entry("radialMinimum","0.58"),Map.entry("radialMaximum","1.18"),
            Map.entry("primaryLobeStrength","0.08"),Map.entry("detailLobeStrength","0.06"),Map.entry("ceilingRoughness","0"),
            Map.entry("aspectMinimum","0.62"),Map.entry("aspectRange","0.2"));
        Map<String,String> drops=Map.of("cascadeRunPerBlock","2","cascadeExponent","1.4","maximumCascadeStep","2","flowWidthRatio","0.45","maximumFlowDepth","2","basinWidthRatio","1.8","maximumBasinDepth","8","undergroundCascadeRunPerBlock","0");
        Map<String,double[]> channelBounds=Map.ofEntries(
            Map.entry("bedRoundness",new double[]{1,6}),Map.entry("bedRoughness",new double[]{0,1}),Map.entry("wallRoughness",new double[]{0,1}),
            Map.entry("roughnessWavelength",new double[]{3,128}),Map.entry("radialBase",new double[]{0.4,1.2}),Map.entry("radialMinimum",new double[]{0.2,1}),
            Map.entry("radialMaximum",new double[]{1,2}),Map.entry("primaryLobeStrength",new double[]{0,0.5}),Map.entry("detailLobeStrength",new double[]{0,0.5}),
            Map.entry("ceilingRoughness",new double[]{0,1}),Map.entry("aspectMinimum",new double[]{0.2,1}),Map.entry("aspectRange",new double[]{0,0.8}));
        Map<String,double[]> dropBounds=Map.of("cascadeRunPerBlock",new double[]{1,16},"cascadeExponent",new double[]{0.25,6},"maximumCascadeStep",new double[]{1,4},"flowWidthRatio",new double[]{0.25,1},"maximumFlowDepth",new double[]{1,16},"basinWidthRatio",new double[]{1,4},"maximumBasinDepth",new double[]{1,32},"undergroundCascadeRunPerBlock",new double[]{0,16});
        byte[] parent=bytes("{\"hydrology\":{\"rivers\":{\"geometry\":{}}}}");
        check(RiverGeometryFields.PASSAGE_FIELDS.equals(List.of("underground","grottos","drops")));
        for (String name : RiverGeometryFields.PASSAGE_FIELDS) {
            var defaults=name.equals("drops") ? drops : channel;
            var bounds=name.equals("drops") ? dropBounds : channelBounds;
            byte[] empty=RiverGeometryFields.enableSection(PATH,parent,name);
            check(section(empty,name).fields().isEmpty() && geometry(empty).fields().equals(Set.of(name)));
            check(new LosslessJson(empty).tokenPath("hydrology.rivers.enabled")==null);
            rejects(() -> RiverGeometryFields.enableSection(PATH,empty,name));
            String prefix="{\r\n \"hydrology\":{\"rivers\":{\"enabled\":false,\"geometry\":{\""+name+"\":";
            String suffix=",\"banks3D\":null,\"future\":{\"raw\":\"雪\\u0061\"}},\"routing\":{\"sampleSpacing\":64}}},\"unknown\":9007199254740993\r\n}\r\n";
            String leaf="{"+String.join(",",RiverGeometryFields.fields(name).stream().map(field -> "\""+field+"\":"+defaults.get(field)).toList())+",\"future\":[\"\\u0061\"]}";
            byte[] original=bytes(prefix+leaf+suffix), copy=original.clone();
            for (String field : RiverGeometryFields.fields(name)) {
                check(!RiverGeometryFields.nullable(name,field));
                check(RiverGeometryFields.defaultToken(name,field).equals(defaults.get(field)));
                check(!RiverGeometryFields.help(name,field).isBlank() && !RiverGeometryFields.description(name).isBlank());
                byte[] enabled=RiverGeometryFields.enableScalar(PATH,empty,name,field);
                check(section(enabled,name).fields().equals(Set.of(field)) && section(enabled,name).token(field).equals(defaults.get(field)));
                rejects(() -> RiverGeometryFields.enableScalar(PATH,enabled,name,field));
                rejects(() -> RiverGeometryFields.setText(PATH,empty,name,field,"1"));
                rejects(() -> RiverGeometryFields.reset(PATH,original,name,field));
                double min=bounds.get(field)[0], max=bounds.get(field)[1];
                String minimum=java.math.BigDecimal.valueOf(min).stripTrailingZeros().toPlainString();
                check(text(RiverGeometryFields.setText(PATH,original,name,field,minimum)).equals(prefix+leaf.replace("\""+field+"\":"+defaults.get(field),"\""+field+"\":"+minimum)+suffix));
                RiverGeometryFields.setText(PATH,original,name,field,Double.toString(max));
                rejects(() -> RiverGeometryFields.setText(PATH,original,name,field,Double.toString(min-1)));
                rejects(() -> RiverGeometryFields.setText(PATH,original,name,field,Double.toString(max+1)));
                for (String bad : List.of("NaN","Infinity","1e999","\"1\"","")) rejects(() -> RiverGeometryFields.setText(PATH,original,name,field,bad));
                if (RiverGeometryFields.integer(name,field)) {
                    rejects(() -> RiverGeometryFields.setText(PATH,original,name,field,minimum+".000000000000000001"));
                    check(section(RiverGeometryFields.setText(PATH,original,name,field,minimum+"e0"),name).token(field).equals(minimum));
                }
                for (String bad : List.of("null","true","[]","{}","\"1\"")) {
                    byte[] wrong=bytes(prefix+"{\""+field+"\":"+bad+"}"+suffix);
                    rejects(() -> RiverGeometryFields.setText(PATH,wrong,name,field,minimum));
                    rejects(() -> RiverGeometryFields.enableScalar(PATH,wrong,name,field));
                }
            }
            check(Arrays.equals(original,copy));
            check(DimensionFields.readOnlyPaths(new LosslessJson(original)).containsAll(List.of("hydrology.rivers.geometry."+name+".future","hydrology.rivers.geometry.banks3D","hydrology.rivers.geometry.future","unknown")));
            for (String bad : List.of("null","[]","1","true","\"x\"")) {
                byte[] wrong=bytes(prefix+bad+suffix);
                rejects(() -> RiverGeometryFields.enableSection(PATH,wrong,name));
                rejects(() -> RiverGeometryFields.enableScalar(PATH,wrong,name,RiverGeometryFields.fields(name).getFirst()));
                check(DimensionFields.readOnlyPaths(new LosslessJson(wrong)).contains("hydrology.rivers.geometry."+name));
            }
        }
        byte[] aspects=bytes("{\"hydrology\":{\"rivers\":{\"geometry\":{\"underground\":{\"aspectMinimum\":0.62,\"aspectRange\":0.2},\"grottos\":{\"aspectMinimum\":0.62,\"aspectRange\":0.2}}}}}");
        for (String field : List.of("aspectMinimum","aspectRange")) {
            rejects(() -> RiverGeometryFields.enableScalar(PATH,aspects,"underground",field));
            rejects(() -> RiverGeometryFields.setText(PATH,aspects,"underground",field,"0.5"));
            check(RiverGeometryFields.readOnlyReason("underground",field).contains("grottos only"));
            byte[] changed=RiverGeometryFields.setText(PATH,aspects,"grottos",field,"0.5");
            check(section(changed,"grottos").token(field).equals("0.5") && section(changed,"underground").token(field).equals(section(aspects,"underground").token(field)));
            check(DimensionFields.readOnlyPaths(new LosslessJson(changed)).contains("hydrology.rivers.geometry.underground."+field));
            check(!DimensionFields.readOnlyPaths(new LosslessJson(changed)).contains("hydrology.rivers.geometry.grottos."+field));
        }
        for (String missing : List.of("{}","{\"hydrology\":{}}","{\"hydrology\":{\"rivers\":{}}}","{\"hydrology\":{\"rivers\":{\"geometry\":null}}}")) {
            rejects(() -> RiverGeometryFields.enableSection(PATH,bytes(missing),"drops"));
            rejects(() -> RiverGeometryFields.enableScalar(PATH,bytes(missing),"drops","maximumBasinDepth"));
        }
        for (String path : List.of("biomes/a.json","regions/a.json","generators/a.json","dimensions/nested/a.json","a.json")) rejects(() -> RiverGeometryFields.enableSection(path,parent,"drops"));
        byte[] empty=parent;
        for (String name : RiverGeometryFields.PASSAGE_FIELDS) empty=RiverGeometryFields.enableSection(PATH,empty,name);
        byte[] defaultsOnly=empty;
        byte[] enabled=RiverGeometryFields.enableScalar(PATH,empty,"drops","undergroundCascadeRunPerBlock");
        byte[] changed=RiverGeometryFields.setText(PATH,enabled,"drops","undergroundCascadeRunPerBlock","16");
        check(section(changed,"underground").fields().isEmpty() && section(changed,"grottos").fields().isEmpty());
        check(new LosslessJson(changed).tokenPath("hydrology.rivers.enabled")==null);
        var drafts=new InspectorDrafts();
        for (String name : List.of("surface","underground","grottos")) drafts.update(RiverGeometryFields.draftPrefix(name)+"bedRoughness","0.24","0");
        String dropKey=RiverGeometryFields.draftPrefix("drops")+"maximumBasinDepth";
        drafts.update(dropKey,"8","16");
        drafts.update(HydrologyFields.draftPrefix()+"tileSize","2048","4096");
        drafts.applied(dropKey);
        check(drafts.count()==4 && drafts.contains(RiverGeometryFields.draftPrefix("underground")+"bedRoughness") && drafts.contains(RiverGeometryFields.draftPrefix("grottos")+"bedRoughness"));
        check(FieldCoverage.fields("riverChannelShape").size()==12 && FieldCoverage.fields("riverChannelShape").stream().allMatch(FieldCoverage.Field::implemented));
        check(FieldCoverage.fields("riverDropShape").size()==8 && FieldCoverage.fields("riverDropShape").stream().allMatch(FieldCoverage.Field::implemented));
        var workspace=PackWorkspace.newPack(Map.of(PATH,changed,"dimensions/defaults.json",defaultsOnly,"dimensions/aspects.json",aspects));
        workspace.save(temp.resolve("river-passages")); workspace.exportZip(temp.resolve("river-passages.zip"));
        var reopened=PackWorkspace.open(temp.resolve("river-passages")); var imported=PackWorkspace.importZip(temp.resolve("river-passages.zip"));
        for (String path : workspace.paths()) {
            check(Arrays.equals(workspace.read(path),reopened.read(path)));
            check(Arrays.equals(workspace.read(path),imported.read(path)));
        }
        for (String name : RiverGeometryFields.PASSAGE_FIELDS) check(section(reopened.read("dimensions/defaults.json"),name).fields().isEmpty());
        System.out.println("PASS: underground/grotto/drop defaults and bounds, exact integers, grotto-only aspects, lossless edits, parent/draft guards and Save/ZIP contracts");
    }
}
