package dev.irisvisualizer.editor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class SurfaceShapingTests {
    private static final String PATH="dimensions/surface-shaping.json";
    private record Spec(String section,String field,String kind,String value,double min,double max) {}
    private static final List<Spec> SPECS="""
        erosion enabled boolean true 0 0
        erosion smoothingRadius int 12 0 64
        erosion thalwegFraction double 0.45 0 0.95
        erosion blendCurve double 1 0.25 4
        erosion bedNoise double 0.5 0 2
        erosion terraceSteps int 4 2 16
        erosion cliffFraction double 0.5 0 1
        bed allowGravityBlocks boolean true 0 0
        bed padding int 2 0 8
        banks.excavation maximumDepth int 8 0 64
        banks.excavation maximumWidth int 16 1 64
        banks.excavation maximumVolumePerBlock int 256 0 8192
        """.lines().map(String::trim).filter(line -> !line.isEmpty()).map(line -> {
            String[] words=line.split(" "); return new Spec(words[0],words[1],words[2],words[3],Double.parseDouble(words[4]),Double.parseDouble(words[5]));
        }).toList();
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static String text(byte[] bytes) { return new String(bytes,StandardCharsets.UTF_8); }
    private static void check(boolean value) { if (!value) throw new AssertionError("Surface shaping contract failed"); }
    private static void rejects(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Expected rejected surface shaping operation");
    }
    private static LosslessJson surface(byte[] value) { return SurfaceRiverFields.surface(HydrologyFields.rivers(HydrologyFields.hydrology(new LosslessJson(value)))); }
    private static LosslessJson section(byte[] value,String name) { return SurfaceRiverFields.section(surface(value),name); }
    static void run(Path temp) throws Exception {
        byte[] parent=bytes("{\"hydrology\":{\"rivers\":{\"enabled\":false,\"surface\":{}}}}");
        byte[] banks=SurfaceRiverFields.enableSection(PATH,parent,"banks");
        byte[] all=banks;
        for (String name : List.of("erosion","bed","banks.excavation")) all=SurfaceRiverFields.enableSection(PATH,all,name);
        byte[] empty=all;
        check(surface(empty).fields().equals(Set.of("banks","erosion","bed")) && surface(empty).token("enabled")==null);
        for (String name : List.of("erosion","bed","banks.excavation")) {
            check(section(empty,name).fields().isEmpty() && !SurfaceRiverFields.description(name).isBlank());
            rejects(() -> SurfaceRiverFields.enableSection(PATH,empty,name));
            String prefix="{\r\n \"hydrology\":{\"rivers\":{\"enabled\":false,\"surface\":{"+(name.equals("banks.excavation") ? "\"banks\":{\"excavation\":" : "\""+name+"\":");
            String suffix=(name.equals("banks.excavation") ? ",\"shoreMaterial\":{\"future\":true}}" : "")+",\"enabled\":true,\"ponds\":{\"terminal\":{\"minimumRadius\":4}},\"futureSurface\":true},\"geometry\":{\"surface\":{\"bedRoughness\":null}},\"profiles\":[{\"id\":\"literal ID\"}]}},\"unknown\":9007199254740993\r\n}\r\n";
            var specs=SPECS.stream().filter(spec -> spec.section().equals(name)).toList();
            String leaf="{"+String.join(",",specs.stream().map(spec -> "\""+spec.field()+"\":"+spec.value()).toList())+(name.equals("erosion") ? ",\"style\":\"FUTURE\\u005fSTYLE\",\"bedProfile\":\"FUTURE\\u005fBED\"" : "")+",\"future\":\"雪\\u0061\"}";
            byte[] original=bytes(prefix+leaf+suffix), copy=original.clone();
            for (var spec : specs) {
                String field=spec.field();
                check(SurfaceRiverFields.defaultToken(name,field).equals(spec.value()) && !SurfaceRiverFields.help(name,field).isBlank());
                check(SurfaceRiverFields.integer(name,field)==spec.kind().equals("int"));
                byte[] one=SurfaceRiverFields.enableScalar(PATH,empty,name,field);
                check(section(one,name).fields().equals(Set.of(field)) && section(one,name).token(field).equals(spec.value()));
                rejects(() -> SurfaceRiverFields.enableScalar(PATH,one,name,field));
                if (spec.kind().equals("boolean")) {
                    check(text(SurfaceRiverFields.setBoolean(PATH,original,name,field,false)).equals(prefix+leaf.replace("\""+field+"\":true","\""+field+"\":false")+suffix));
                    check(section(SurfaceRiverFields.setBoolean(PATH,empty,name,field,false),name).fields().equals(Set.of(field)));
                    rejects(() -> SurfaceRiverFields.setText(PATH,original,name,field,"1"));
                } else {
                    String minimum=java.math.BigDecimal.valueOf(spec.min()).stripTrailingZeros().toPlainString();
                    check(text(SurfaceRiverFields.setText(PATH,original,name,field,minimum)).equals(prefix+leaf.replace("\""+field+"\":"+spec.value(),"\""+field+"\":"+minimum)+suffix));
                    SurfaceRiverFields.setText(PATH,original,name,field,Double.toString(spec.max()));
                    rejects(() -> SurfaceRiverFields.setText(PATH,original,name,field,Double.toString(spec.min()-1)));
                    rejects(() -> SurfaceRiverFields.setText(PATH,original,name,field,Double.toString(spec.max()+1)));
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
                    else rejects(() -> SurfaceRiverFields.setText(PATH,wrong,name,field,spec.value()));
                }
            }
            if (name.equals("erosion")) {
                for (String field : List.of("style","bedProfile")) {
                    var expected=field.equals("style") ? List.of("SMOOTH","LINEAR","CONCAVE","TERRACED","CLIFF") : List.of("BOWL","FLAT","V","U");
                    check(SurfaceRiverFields.choices(name,field).equals(expected));
                    check(SurfaceRiverFields.defaultToken(name,field).equals(field.equals("style") ? "SMOOTH" : "BOWL"));
                    byte[] one=SurfaceRiverFields.enableScalar(PATH,empty,name,field);
                    check(section(one,name).fields().equals(Set.of(field)) && section(one,name).stringToken(field).equals(SurfaceRiverFields.defaultToken(name,field)));
                    for (String choice : expected) {
                        String old=field.equals("style") ? "FUTURE\\u005fSTYLE" : "FUTURE\\u005fBED";
                        check(text(SurfaceRiverFields.setEnum(PATH,original,name,field,choice)).equals(prefix+leaf.replace(old,choice)+suffix));
                        check(!SurfaceRiverFields.choiceHelp(name,field,choice).isBlank());
                    }
                    rejects(() -> SurfaceRiverFields.setEnum(PATH,original,name,field,"unknown"));
                    rejects(() -> SurfaceRiverFields.setEnum(PATH,original,name,field,null));
                    rejects(() -> SurfaceRiverFields.setEnum(PATH,empty,name,field,expected.getFirst()));
                    rejects(() -> SurfaceRiverFields.setText(PATH,original,name,field,"1"));
                    rejects(() -> SurfaceRiverFields.setBoolean(PATH,original,name,field,false));
                    for (String bad : List.of("null","true","[]","{}","1")) rejects(() -> SurfaceRiverFields.setEnum(PATH,bytes(prefix+"{\""+field+"\":"+bad+"}"+suffix),name,field,expected.getFirst()));
                }
                byte[] changed=SurfaceRiverFields.setText(PATH,original,name,"terraceSteps","16");
                check(section(changed,name).token("style").equals(section(original,name).token("style")));
            }
            check(Arrays.equals(original,copy));
            check(DimensionFields.readOnlyPaths(new LosslessJson(original)).contains("hydrology.rivers.surface."+name+".future"));
            for (String bad : List.of("null","[]","1","true","\"snippet\"")) {
                byte[] wrong=bytes(prefix+bad+suffix);
                rejects(() -> SurfaceRiverFields.enableSection(PATH,wrong,name));
                rejects(() -> SurfaceRiverFields.enableScalar(PATH,wrong,name,SurfaceRiverFields.fields(name).getFirst()));
                check(DimensionFields.readOnlyPaths(new LosslessJson(wrong)).contains("hydrology.rivers.surface."+name));
                check(surface(SurfaceRiverFields.setBoolean(PATH,wrong,"","enabled",false)).token("enabled").equals("false"));
            }
        }
        for (String missing : List.of("{}","{\"hydrology\":{}}","{\"hydrology\":{\"rivers\":{}}}")) for (String name : List.of("erosion","bed","banks.excavation")) rejects(() -> SurfaceRiverFields.enableSection(PATH,bytes(missing),name));
        rejects(() -> SurfaceRiverFields.enableSection(PATH,parent,"banks.excavation"));
        for (String bad : List.of("null","[]","1","true","\"snippet\"")) rejects(() -> SurfaceRiverFields.enableSection(PATH,bytes("{\"hydrology\":{\"rivers\":{\"surface\":{\"banks\":"+bad+"}}}}"),"banks.excavation"));
        for (String path : List.of("regions/a.json","biomes/a.json","generators/a.json","dimensions/nested/a.json","a.json")) {
            rejects(() -> SurfaceRiverFields.enableSection(path,parent,"erosion"));
            rejects(() -> SurfaceRiverFields.enableSection(path,banks,"banks.excavation"));
            rejects(() -> SurfaceRiverFields.enableScalar(path,empty,"erosion","style"));
        }
        byte[] palettes=bytes("{\"hydrology\":{\"rivers\":{\"surface\":{\"bed\":{\"padding\":2,\"paddingPalette\":[\"clay\",\"dirt\"],\"material\":{\"future\":true}},\"banks\":{\"excavation\":{\"maximumDepth\":8,\"future\":true},\"shoreMaterial\":\"unresolved\"},\"ponds\":{\"source\":{},\"terminal\":{\"minimumRadius\":4}}}}}}");
        check(text(SurfaceRiverFields.setText(PATH,palettes,"bed","padding","0")).equals(text(palettes).replace("\"padding\":2","\"padding\":0")));
        check(DimensionFields.readOnlyPaths(new LosslessJson(palettes)).containsAll(List.of("hydrology.rivers.surface.bed.paddingPalette","hydrology.rivers.surface.bed.material.future","hydrology.rivers.surface.banks.excavation.future","hydrology.rivers.surface.banks.shoreMaterial")));
        rejects(() -> SurfaceRiverFields.enableScalar(PATH,palettes,"bed","paddingPalette"));
        rejects(() -> SurfaceRiverFields.setText(PATH,palettes,"banks.excavation","future","1"));
        var drafts=new InspectorDrafts();
        for (String key : List.of(SurfaceRiverFields.draftPrefix("erosion")+"smoothingRadius",SurfaceRiverFields.draftPrefix("bed")+"padding",SurfaceRiverFields.draftPrefix("banks.excavation")+"maximumDepth",SurfaceRiverFields.draftPrefix("channel")+"smoothingRadius",HydrologyFields.draftPrefix()+"tileSize")) drafts.update(key,"0","1");
        SurfaceRiverFields.enableScalar(PATH,empty,"erosion","style"); check(drafts.count()==5);
        drafts.applied(SurfaceRiverFields.draftPrefix("erosion")+"smoothingRadius");
        check(drafts.count()==4 && drafts.contains(SurfaceRiverFields.draftPrefix("channel")+"smoothingRadius"));
        for (var entry : Map.of("surfaceRiverErosion",9,"surfaceRiverBed",2,"riverExcavation",3).entrySet()) check(FieldCoverage.fields(entry.getKey()).stream().filter(FieldCoverage.Field::implemented).count()==entry.getValue());
        var workspace=PackWorkspace.newPack(Map.of(PATH,palettes,"dimensions/defaults.json",empty));
        workspace.save(temp.resolve("surface-shaping")); workspace.exportZip(temp.resolve("surface-shaping.zip"));
        var reopened=PackWorkspace.open(temp.resolve("surface-shaping")); var imported=PackWorkspace.importZip(temp.resolve("surface-shaping.zip"));
        for (String path : workspace.paths()) {
            check(Arrays.equals(workspace.read(path),reopened.read(path)));
            check(Arrays.equals(workspace.read(path),imported.read(path)));
        }
        for (String name : List.of("erosion","bed","banks.excavation")) check(section(reopened.read("dimensions/defaults.json"),name).fields().isEmpty());
        check(new LosslessJson(reopened.read("dimensions/defaults.json")).tokenPath("hydrology.rivers.enabled").equals("false"));
        System.out.println("PASS: erosion/bed/excavation defaults/bounds and all enum choices, nested parent guards, lossless materials/unknowns, draft isolation and Save/ZIP contracts");
    }
}
