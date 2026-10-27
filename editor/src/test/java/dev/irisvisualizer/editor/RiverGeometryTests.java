package dev.irisvisualizer.editor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class RiverGeometryTests {
    private static final String PATH="dimensions/geometry.json";
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static String text(byte[] source) { return new String(source,StandardCharsets.UTF_8); }
    private static void check(boolean condition) { if (!condition) throw new AssertionError("River geometry contract failed"); }
    private static void rejects(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Expected rejected geometry operation");
    }
    private static LosslessJson geometry(byte[] source) { return RiverGeometryFields.geometry(HydrologyFields.rivers(HydrologyFields.hydrology(new LosslessJson(source)))); }
    private static LosslessJson section(byte[] source,String name) { return RiverGeometryFields.section(geometry(source),name); }
    static void run(Path temp) throws Exception {
        Map<String,String> meanders=Map.of("primaryWavelength","64","detailWavelength","12","primaryStrength","0.34","detailStrength","0.42","maximumOffsetRatio","0.48","smoothingPasses","1","maximumTurnDegrees","82");
        Map<String,String> surface=Map.of("bedRoundness","2","bedRoughness","null","wallRoughness","null","roughnessWavelength","null","radialBase","0.86","radialMinimum","0.58","radialMaximum","1.18","primaryLobeStrength","0.08","detailLobeStrength","0.06","ceilingRoughness","0");
        Map<String,double[]> meanderBounds=Map.of("primaryWavelength",new double[]{8,512},"detailWavelength",new double[]{4,128},"primaryStrength",new double[]{0,2},"detailStrength",new double[]{0,2},"maximumOffsetRatio",new double[]{0,1},"smoothingPasses",new double[]{0,4},"maximumTurnDegrees",new double[]{10,150});
        Map<String,double[]> surfaceBounds=Map.of("bedRoundness",new double[]{1,6},"bedRoughness",new double[]{0,1},"wallRoughness",new double[]{0,1},"roughnessWavelength",new double[]{3,128},"radialBase",new double[]{0.4,1.2},"radialMinimum",new double[]{0.2,1},"radialMaximum",new double[]{1,2},"primaryLobeStrength",new double[]{0,0.5},"detailLobeStrength",new double[]{0,0.5},"ceilingRoughness",new double[]{0,1});
        byte[] parent=bytes("{\"hydrology\":{\"rivers\":{\"enabled\":false}}}");
        byte[] created=RiverGeometryFields.enableGeometry(PATH,parent);
        check(geometry(created).fields().isEmpty());
        rejects(() -> RiverGeometryFields.enableGeometry(PATH,created));
        byte[] withMeanders=RiverGeometryFields.enableSection(PATH,created,"meanders");
        byte[] empty=RiverGeometryFields.enableSection(PATH,withMeanders,"surface");
        check(section(empty,"meanders").fields().isEmpty() && section(empty,"surface").fields().isEmpty());
        check(new LosslessJson(empty).tokenPath("hydrology.rivers.enabled").equals("false"));
        String prefix="{\r\n \"hydrology\":{\"rivers\":{\"enabled\":false,\"geometry\":{";
        String tail=",\"banks3D\":{\"future\":true},\"future\":\"雪\\u0061\"},\"routing\":{\"sampleSpacing\":64},\"profiles\":[]}},\"unknown\":9007199254740993\r\n}\r\n";
        byte[] preserved=null;
        for (String name : RiverGeometryFields.GEOMETRY_FIELDS) {
            Map<String,String> defaults=name.equals("meanders") ? meanders : surface;
            Map<String,double[]> bounds=name.equals("meanders") ? meanderBounds : surfaceBounds;
            check(RiverGeometryFields.fields(name).size()==defaults.size());
            check(!RiverGeometryFields.description(name).isBlank());
            String sibling=name.equals("meanders") ? "surface" : "meanders";
            String before=prefix+"\""+sibling+"\":null,\""+name+"\":";
            String leaf="{"+String.join(",",RiverGeometryFields.fields(name).stream().map(field -> "\""+field+"\":"+defaults.get(field)).toList())+",\"future\":[\"\\u0061\"]}";
            byte[] source=bytes(before+leaf+tail), copy=source.clone();
            for (String field : RiverGeometryFields.fields(name)) {
                boolean nullable=RiverGeometryFields.nullable(name,field);
                check(RiverGeometryFields.defaultToken(name,field).equals(defaults.get(field)) && !RiverGeometryFields.help(name,field).isBlank());
                if (nullable) {
                    rejects(() -> RiverGeometryFields.enableScalar(PATH,empty,name,field));
                    check(Arrays.equals(RiverGeometryFields.reset(PATH,empty,name,field),empty));
                    check(Arrays.equals(RiverGeometryFields.reset(PATH,source,name,field),source));
                } else {
                    byte[] enabled=RiverGeometryFields.enableScalar(PATH,empty,name,field);
                    check(section(enabled,name).fields().equals(Set.of(field)) && section(enabled,name).token(field).equals(defaults.get(field)));
                    rejects(() -> RiverGeometryFields.enableScalar(PATH,enabled,name,field));
                    rejects(() -> RiverGeometryFields.reset(PATH,source,name,field));
                    rejects(() -> RiverGeometryFields.setText(PATH,empty,name,field,"1"));
                }
                double min=bounds.get(field)[0], max=bounds.get(field)[1];
                String minimum=java.math.BigDecimal.valueOf(min).stripTrailingZeros().toPlainString();
                byte[] changed=RiverGeometryFields.setText(PATH,source,name,field,minimum);
                check(text(changed).equals(before+leaf.replace("\""+field+"\":"+defaults.get(field),"\""+field+"\":"+minimum)+tail));
                RiverGeometryFields.setText(PATH,source,name,field,Double.toString(max));
                rejects(() -> RiverGeometryFields.setText(PATH,source,name,field,Double.toString(min-1)));
                rejects(() -> RiverGeometryFields.setText(PATH,source,name,field,Double.toString(max+1)));
                for (String invalid : List.of("NaN","Infinity","1e999","\"1\"","")) rejects(() -> RiverGeometryFields.setText(PATH,source,name,field,invalid));
                if (RiverGeometryFields.integer(name,field)) rejects(() -> RiverGeometryFields.setText(PATH,source,name,field,minimum+".000000000000000001"));
                if (nullable) {
                    check(Arrays.equals(RiverGeometryFields.reset(PATH,changed,name,field),source));
                    check(section(RiverGeometryFields.setText(PATH,empty,name,field,minimum),name).fields().equals(Set.of(field)));
                }
                for (String bad : List.of("true","[]","{}","\"1\"")) {
                    byte[] wrong=bytes(before+"{\""+field+"\":"+bad+"}"+tail);
                    rejects(() -> RiverGeometryFields.setText(PATH,wrong,name,field,minimum));
                    rejects(() -> RiverGeometryFields.reset(PATH,wrong,name,field));
                }
            }
            check(Arrays.equals(source,copy));
            preserved=source;
            for (String bad : List.of("null","[]","true","\"x\"","1")) {
                byte[] wrong=bytes(before+bad+tail);
                rejects(() -> RiverGeometryFields.enableSection(PATH,wrong,name));
                rejects(() -> RiverGeometryFields.enableScalar(PATH,wrong,name,RiverGeometryFields.fields(name).getFirst()));
                check(DimensionFields.readOnlyPaths(new LosslessJson(wrong)).contains("hydrology.rivers.geometry."+name));
            }
        }
        byte[] prim=RiverGeometryFields.enableScalar(PATH,empty,"meanders","primaryWavelength");
        check(section(RiverGeometryFields.setText(PATH,prim,"meanders","primaryWavelength","9e0"),"meanders").token("primaryWavelength").equals("9"));
        byte[] active=RiverGeometryFields.setText(PATH,empty,"surface","bedRoughness","0");
        active=RiverGeometryFields.setText(PATH,active,"surface","roughnessWavelength","3");
        active=RiverGeometryFields.reset(PATH,active,"surface","roughnessWavelength");
        check(section(active,"surface").token("bedRoughness").equals("0") && section(active,"surface").token("roughnessWavelength").equals("null"));
        byte[] inactive=bytes(prefix+"\"surface\":{\"aspectMinimum\":0.62,\"aspectRange\":0.2}"+tail);
        for (String field : List.of("aspectMinimum","aspectRange")) {
            rejects(() -> RiverGeometryFields.setText(PATH,inactive,"surface",field,"1"));
            rejects(() -> RiverGeometryFields.enableScalar(PATH,inactive,"surface",field));
            check(RiverGeometryFields.readOnlyReason("surface",field).contains("grotto"));
            check(DimensionFields.readOnlyPaths(new LosslessJson(inactive)).contains("hydrology.rivers.geometry.surface."+field));
        }
        check(DimensionFields.readOnlyPaths(new LosslessJson(inactive)).containsAll(List.of("hydrology.rivers.geometry.banks3D.future","hydrology.rivers.geometry.future","unknown")));
        for (String missing : List.of("{}","{\"hydrology\":{}}","{\"hydrology\":null}","{\"hydrology\":{\"rivers\":null}}")) rejects(() -> RiverGeometryFields.enableGeometry(PATH,bytes(missing)));
        rejects(() -> RiverGeometryFields.enableSection(PATH,parent,"surface"));
        rejects(() -> RiverGeometryFields.enableScalar(PATH,created,"meanders","primaryWavelength"));
        for (String bad : List.of("null","[]","true","1","\"x\"")) {
            byte[] wrong=bytes("{\"hydrology\":{\"rivers\":{\"geometry\":"+bad+"}}}");
            rejects(() -> RiverGeometryFields.enableGeometry(PATH,wrong));
            rejects(() -> RiverGeometryFields.enableSection(PATH,wrong,"surface"));
            check(DimensionFields.readOnlyPaths(new LosslessJson(wrong)).contains("hydrology.rivers.geometry"));
        }
        for (String path : List.of("regions/a.json","biomes/a.json","generators/a.json","dimensions/nested/a.json","a.json")) {
            rejects(() -> RiverGeometryFields.enableGeometry(path,parent));
            rejects(() -> RiverGeometryFields.setText(path,empty,"surface","bedRoughness","0"));
        }
        rejects(() -> RiverGeometryFields.enableSection(PATH,created,"banks3D"));
        var drafts=new InspectorDrafts();
        String meanderKey=RiverGeometryFields.draftPrefix("meanders")+"primaryWavelength", surfaceKey=RiverGeometryFields.draftPrefix("surface")+"bedRoughness";
        drafts.update(meanderKey,"64","128"); drafts.update(surfaceKey,"","0");
        drafts.update(HydrologyFields.draftPrefix()+"tileSize","2048","4096");
        drafts.update(RiverPolicyFields.draftPrefix("dimensions")+"widthMultiplier","1","2");
        RiverGeometryFields.setText(PATH,empty,"surface","bedRoughness","0"); drafts.applied(surfaceKey);
        check(drafts.count()==3 && drafts.contains(meanderKey));
        check(FieldCoverage.fields("riverGeometry").size()==6 && FieldCoverage.fields("riverGeometry").stream().filter(FieldCoverage.Field::implemented).count()==6);
        check(FieldCoverage.fields("riverMeanders").size()==7 && FieldCoverage.fields("riverMeanders").stream().allMatch(FieldCoverage.Field::implemented));
        check(FieldCoverage.fields("riverSurfaceShape").size()==12 && FieldCoverage.fields("riverSurfaceShape").stream().filter(FieldCoverage.Field::implemented).count()==10);
        check(FieldCoverage.fields("riverSurfaceShape").stream().filter(field -> !field.implemented()).allMatch(field -> field.classification().equals("read-only")));
        var workspace=PackWorkspace.newPack(Map.of(PATH,active,"dimensions/defaults.json",empty,"dimensions/unknown.json",preserved));
        workspace.save(temp.resolve("river-geometry")); workspace.exportZip(temp.resolve("river-geometry.zip"));
        var reopened=PackWorkspace.open(temp.resolve("river-geometry")); var imported=PackWorkspace.importZip(temp.resolve("river-geometry.zip"));
        for (String path : workspace.paths()) {
            check(Arrays.equals(workspace.read(path),reopened.read(path)));
            check(Arrays.equals(workspace.read(path),imported.read(path)));
        }
        check(section(reopened.read("dimensions/defaults.json"),"surface").fields().isEmpty());
        check(new LosslessJson(reopened.read(PATH)).tokenPath("hydrology.rivers.enabled").equals("false"));
        System.out.println("PASS: river geometry defaults/bounds, nullable channel fallbacks, inactive-field and parent guards, lossless edits, draft isolation and Save/ZIP contracts");
    }
}
