package dev.irisvisualizer.editor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class SurfaceRiverTests {
    private static final String PATH="dimensions/surface-rivers.json";
    private record Spec(String section,String field,String kind,String defaultToken,double minimum,double maximum) {}
    // Independent source expectations; these values are not read from generated metadata.
    private static final List<Spec> SPECS="""
        root enabled boolean true 0 0
        sources density double 0.5 0 64
        sources minimumElevation int 88 -2048 2048
        sources minimumPerTile int 0 0 64
        sources minimumSpacing int 384 0 8192
        channel sink int 0 0 3
        channel maximumIncision int 10 1 32
        channel roughness double 0.25 0 1
        channel roughnessWavelength int 16 4 64
        channel springWidthRatio double 2.5 1 4
        channel springLength int 24 4 96
        channel smoothingRadius int 16 0 64
        channel outlineMinimumRatio double 0.6 0.2 1
        channel outlineMaximumRatio double 1.4 1 3
        channel springExtraDepth double 1 0 8
        banks shoreWidth double 1.5 0 16
        banks blendSlope double 3 0.5 12
        banks minimumBlendWidth int 4 1 64
        banks maximumBlendWidth int 32 1 64
        banks exposeCutStrata boolean true 0 0
        banks shoreRise double 0 0 4
        banks blendBaseWidth double 0 0 32
        flow cascadeRun int 2 1 8
        flow waterfallMinimumDrop int 6 2 32
        flow waterfallThalwegFraction double 0.65 0 0.95
        flow plungeBasinMinimumDrop int 2 1 8
        flow plungeBasinLengthRatio double 2 0 8
        flow plungeBasinDepth int 1 0 8
        mouths flareRatio double 1.6 1 4
        mouths maximumOceanApron int 8 0 32
        mouths inletLength int 64 0 256
        mouths inletDepth int 3 0 16
        mouths maximumIncision int 32 0 128
        mouths inletCourseFraction double 0.5 0.05 1
        mouths inletRampSlope double 1 0.1 4
        """.lines().map(String::trim).filter(line -> !line.isEmpty()).map(line -> {
            String[] words=line.split(" ");
            return new Spec(words[0].equals("root") ? "" : words[0],words[1],words[2],words[3],Double.parseDouble(words[4]),Double.parseDouble(words[5]));
        }).toList();
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static String text(byte[] value) { return new String(value,StandardCharsets.UTF_8); }
    private static void check(boolean value) { if (!value) throw new AssertionError("Surface river contract failed"); }
    private static void rejects(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Expected rejected surface-river operation");
    }
    private static LosslessJson surface(byte[] value) { return SurfaceRiverFields.surface(HydrologyFields.rivers(HydrologyFields.hydrology(new LosslessJson(value)))); }
    private static LosslessJson section(byte[] value,String name) { return SurfaceRiverFields.section(surface(value),name); }
    static void run(Path temp) throws Exception {
        byte[] parent=bytes("{\"hydrology\":{\"rivers\":{\"enabled\":false}}}");
        byte[] enabled=SurfaceRiverFields.enableSurface(PATH,parent);
        check(surface(enabled).fields().isEmpty() && new LosslessJson(enabled).tokenPath("hydrology.rivers.enabled").equals("false"));
        check(!SurfaceRiverFields.description("surface").isBlank());
        rejects(() -> SurfaceRiverFields.enableSurface(PATH,enabled));
        byte[] allSections=enabled;
        for (String name : SurfaceRiverFields.SECTIONS) allSections=SurfaceRiverFields.enableSection(PATH,allSections,name);
        byte[] empty=allSections;
        check(surface(empty).fields().equals(Set.copyOf(SurfaceRiverFields.SECTIONS)));
        check(SPECS.size()==35);
        byte[] lastOriginal=null;
        for (String name : List.of("", "sources", "channel", "banks", "flow", "mouths")) {
            check(SurfaceRiverFields.fields(name).equals(SPECS.stream().filter(spec -> spec.section().equals(name)).map(Spec::field).toList()));
            if (!name.isEmpty()) {
                check(section(empty,name).fields().isEmpty() && !SurfaceRiverFields.description(name).isBlank());
                rejects(() -> SurfaceRiverFields.enableSection(PATH,empty,name));
            }
            String prefix="{\r\n \"hydrology\":{\"rivers\":{\"enabled\":false,\"surface\":"+(name.isEmpty() ? "" : "{\""+name+"\":");
            String suffix=(name.isEmpty() ? "" : ",\"enabled\":true,\"futureSurface\":[\"\\u0061\"]}")+",\"geometry\":{\"surface\":{\"bedRoughness\":null}},\"routing\":{\"sampleSpacing\":64},\"profiles\":[{\"id\":\"literal ID\"}]}},\"riverPolicy\":{\"surfaceBiomes\":[]},\"unknown\":9007199254740993\r\n}\r\n";
            var specs=SPECS.stream().filter(spec -> spec.section().equals(name)).toList();
            String leaf="{"+String.join(",",specs.stream().map(spec -> "\""+spec.field()+"\":"+spec.defaultToken()).toList())+",\"future\":\"雪\\u0061\"}";
            byte[] original=bytes(prefix+leaf+suffix), copy=original.clone();
            for (var spec : specs) {
                String field=spec.field();
                check(SurfaceRiverFields.defaultToken(name,field).equals(spec.defaultToken()) && !SurfaceRiverFields.help(name,field).isBlank());
                check(SurfaceRiverFields.integer(name,field)==spec.kind().equals("int"));
                check(SurfaceRiverFields.booleanField(name,field)==spec.kind().equals("boolean"));
                byte[] one=SurfaceRiverFields.enableScalar(PATH,empty,name,field);
                var oneFields=section(one,name).fields();
                check(oneFields.equals(name.isEmpty() ? Set.of("enabled","sources","channel","banks","flow","mouths") : Set.of(field)));
                check(section(one,name).token(field).equals(spec.defaultToken()));
                rejects(() -> SurfaceRiverFields.enableScalar(PATH,one,name,field));
                if (spec.kind().equals("boolean")) {
                    check(text(SurfaceRiverFields.setBoolean(PATH,original,name,field,false)).equals(prefix+leaf.replace("\""+field+"\":true","\""+field+"\":false")+suffix));
                    check(section(SurfaceRiverFields.setBoolean(PATH,empty,name,field,false),name).token(field).equals("false"));
                    rejects(() -> SurfaceRiverFields.setText(PATH,original,name,field,"1"));
                } else {
                    String minimum=java.math.BigDecimal.valueOf(spec.minimum()).stripTrailingZeros().toPlainString();
                    check(text(SurfaceRiverFields.setText(PATH,original,name,field,minimum)).equals(prefix+leaf.replace("\""+field+"\":"+spec.defaultToken(),"\""+field+"\":"+minimum)+suffix));
                    SurfaceRiverFields.setText(PATH,original,name,field,Double.toString(spec.maximum()));
                    rejects(() -> SurfaceRiverFields.setText(PATH,original,name,field,Double.toString(spec.minimum()-1)));
                    rejects(() -> SurfaceRiverFields.setText(PATH,original,name,field,Double.toString(spec.maximum()+1)));
                    rejects(() -> SurfaceRiverFields.setText(PATH,empty,name,field,minimum));
                    rejects(() -> SurfaceRiverFields.setBoolean(PATH,original,name,field,false));
                    for (String bad : List.of("NaN","Infinity","1e999","\"1\"","")) rejects(() -> SurfaceRiverFields.setText(PATH,original,name,field,bad));
                    if (spec.kind().equals("int")) {
                        rejects(() -> SurfaceRiverFields.setText(PATH,original,name,field,minimum+".000000000000000001"));
                        check(section(SurfaceRiverFields.setText(PATH,original,name,field,minimum+"e0"),name).token(field).equals(minimum));
                    }
                }
                for (String bad : List.of("null","true","[]","{}","\"1\"")) {
                    if (spec.kind().equals("boolean") && bad.equals("true")) continue;
                    byte[] wrong=bytes(prefix+"{\""+field+"\":"+bad+"}"+suffix);
                    if (spec.kind().equals("boolean")) rejects(() -> SurfaceRiverFields.setBoolean(PATH,wrong,name,field,false));
                    else rejects(() -> SurfaceRiverFields.setText(PATH,wrong,name,field,spec.defaultToken()));
                    rejects(() -> SurfaceRiverFields.enableScalar(PATH,wrong,name,field));
                }
            }
            check(Arrays.equals(original,copy));
            check(DimensionFields.readOnlyPaths(new LosslessJson(original)).contains("hydrology.rivers.surface."+(name.isEmpty() ? "" : name+".")+"future"));
            lastOriginal=original;
            if (!name.isEmpty()) for (String bad : List.of("null","[]","1","true","\"snippet\"")) {
                byte[] wrong=bytes(prefix+bad+suffix);
                rejects(() -> SurfaceRiverFields.enableSection(PATH,wrong,name));
                rejects(() -> SurfaceRiverFields.enableScalar(PATH,wrong,name,SurfaceRiverFields.fields(name).getFirst()));
                check(DimensionFields.readOnlyPaths(new LosslessJson(wrong)).contains("hydrology.rivers.surface."+name));
                check(surface(SurfaceRiverFields.setBoolean(PATH,wrong,"","enabled",false)).token(name).equals(bad));
            }
        }
        byte[] nested=bytes("{\"hydrology\":{\"rivers\":{\"surface\":{\"channel\":{\"width\":{\"min\":4},\"depth\":null,\"roughness\":0.25},\"banks\":{\"excavation\":\"snippet\",\"shoreMaterial\":{\"enabled\":true},\"bankMaterial\":null},\"bed\":{},\"erosion\":{},\"ponds\":{}}}}}");
        check(DimensionFields.readOnlyPaths(new LosslessJson(nested)).containsAll(List.of("hydrology.rivers.surface.channel.width","hydrology.rivers.surface.channel.depth","hydrology.rivers.surface.banks.excavation","hydrology.rivers.surface.banks.shoreMaterial","hydrology.rivers.surface.banks.bankMaterial","hydrology.rivers.surface.ponds")));
        check(text(SurfaceRiverFields.setText(PATH,nested,"channel","roughness","0")).equals(text(nested).replace("\"roughness\":0.25","\"roughness\":0")));
        rejects(() -> SurfaceRiverFields.enableScalar(PATH,nested,"channel","width"));
        rejects(() -> SurfaceRiverFields.setText(PATH,nested,"banks","excavation","1"));
        rejects(() -> SurfaceRiverFields.enableSection(PATH,nested,"ponds"));
        rejects(() -> SurfaceRiverFields.enableSection(PATH,enabled,""));
        for (String missing : List.of("{}","{\"hydrology\":null}","{\"hydrology\":{}}","{\"hydrology\":{\"rivers\":null}}")) rejects(() -> SurfaceRiverFields.enableSurface(PATH,bytes(missing)));
        rejects(() -> SurfaceRiverFields.enableSection(PATH,parent,"channel"));
        rejects(() -> SurfaceRiverFields.setBoolean(PATH,parent,"","enabled",true));
        rejects(() -> SurfaceRiverFields.enableScalar(PATH,enabled,"channel","roughness"));
        for (String bad : List.of("null","[]","1","true","\"snippet\"")) {
            byte[] wrong=bytes("{\"hydrology\":{\"rivers\":{\"surface\":"+bad+"}}}");
            rejects(() -> SurfaceRiverFields.enableSurface(PATH,wrong));
            rejects(() -> SurfaceRiverFields.enableSection(PATH,wrong,"sources"));
            rejects(() -> SurfaceRiverFields.setBoolean(PATH,wrong,"","enabled",false));
            check(DimensionFields.readOnlyPaths(new LosslessJson(wrong)).contains("hydrology.rivers.surface"));
        }
        for (String path : List.of("regions/a.json","biomes/a.json","generators/a.json","dimensions/nested/a.json","a.json")) {
            rejects(() -> SurfaceRiverFields.enableSurface(path,parent));
            rejects(() -> SurfaceRiverFields.enableSection(path,enabled,"sources"));
            rejects(() -> SurfaceRiverFields.setBoolean(path,enabled,"","enabled",true));
        }
        // Independent controls do not impose cross-field ordering or sample runtime geometry.
        byte[] one=SurfaceRiverFields.enableScalar(PATH,empty,"banks","minimumBlendWidth");
        one=SurfaceRiverFields.enableScalar(PATH,one,"banks","maximumBlendWidth");
        byte[] independent=SurfaceRiverFields.setText(PATH,one,"banks","minimumBlendWidth","64");
        check(section(independent,"banks").token("maximumBlendWidth").equals("32"));
        var drafts=new InspectorDrafts();
        for (String name : List.of("channel","mouths")) drafts.update(SurfaceRiverFields.draftPrefix(name)+"maximumIncision","10","12");
        drafts.update(RiverGeometryFields.draftPrefix("surface")+"bedRoundness","2","3");
        drafts.update(HydrologyFields.draftPrefix()+"tileSize","2048","4096");
        drafts.applied(SurfaceRiverFields.draftPrefix("channel")+"maximumIncision");
        check(drafts.count()==3 && drafts.contains(SurfaceRiverFields.draftPrefix("mouths")+"maximumIncision"));
        Map<String,Integer> coverage=Map.of("surfaceRiver",8,"surfaceRiverSource",4,"surfaceRiverChannel",10,"surfaceRiverBank",8,"surfaceRiverFlow",6,"surfaceRiverMouth",7);
        for (var entry : coverage.entrySet()) check(FieldCoverage.fields(entry.getKey()).stream().filter(FieldCoverage.Field::implemented).count()==entry.getValue());
        var workspace=PackWorkspace.newPack(Map.of(PATH,independent,"dimensions/defaults.json",empty,"dimensions/nested-data.json",nested,"dimensions/unknown.json",lastOriginal));
        workspace.save(temp.resolve("surface-rivers")); workspace.exportZip(temp.resolve("surface-rivers.zip"));
        var reopened=PackWorkspace.open(temp.resolve("surface-rivers")); var imported=PackWorkspace.importZip(temp.resolve("surface-rivers.zip"));
        for (String path : workspace.paths()) {
            check(Arrays.equals(workspace.read(path),reopened.read(path)));
            check(Arrays.equals(workspace.read(path),imported.read(path)));
        }
        check(surface(reopened.read("dimensions/defaults.json")).token("enabled")==null);
        for (String name : SurfaceRiverFields.SECTIONS) check(section(reopened.read("dimensions/defaults.json"),name).fields().isEmpty());
        check(new LosslessJson(reopened.read(PATH)).tokenPath("hydrology.rivers.enabled").equals("false"));
        System.out.println("PASS: surface-river defaults/all scalar bounds, exact integers, explicit parents, independent drafts, nested read-only preservation and Save/ZIP contracts");
    }
}
