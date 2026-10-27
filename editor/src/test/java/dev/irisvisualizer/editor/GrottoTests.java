package dev.irisvisualizer.editor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class GrottoTests {
    private static final String PATH="dimensions/grottos.json";
    private record Spec(String section,String field,String kind,String value,double min,double max) {}
    private static final List<Spec> SPECS="""
        coastal enabled boolean true 0 0
        coastal poolLevel enum SEA_LEVEL 0 0
        coastal horizontalRadius int 12 1 128
        coastal verticalRadius int 7 1 64
        coastal headroom int 10 1 63
        coastal maximumVolume int 8192 1 1048576
        coastal cliffMinimumHeight Integer null 0 128
        coastal cliffSlopeFactor double 0.5 0 4
        inland enabled boolean true 0 0
        inland connectSurfaceRivers boolean false 0 0
        inland horizontalRadius int 10 1 128
        inland verticalRadius int 6 1 64
        inland headroom int 10 1 63
        inland maximumVolume int 8192 1 1048576
        """.lines().map(String::trim).filter(line -> !line.isEmpty()).map(line -> {
            String[] words=line.split(" "); return new Spec(words[0],words[1],words[2],words[3],Double.parseDouble(words[4]),Double.parseDouble(words[5]));
        }).toList();
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static String text(byte[] value) { return new String(value,StandardCharsets.UTF_8); }
    private static void check(boolean value) { if (!value) throw new AssertionError("Grotto contract failed"); }
    private static void rejects(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Expected rejected grotto operation");
    }
    private static LosslessJson grottos(byte[] value) { return GrottoFields.grottos(HydrologyFields.rivers(HydrologyFields.hydrology(new LosslessJson(value)))); }
    private static LosslessJson section(byte[] value,String name) { return GrottoFields.section(grottos(value),name); }
    static void run(Path temp) throws Exception {
        byte[] parent=bytes("{\"hydrology\":{\"rivers\":{\"enabled\":false}}}");
        byte[] root=GrottoFields.enableGrottos(PATH,parent);
        byte[] empty=GrottoFields.enableSection(PATH,GrottoFields.enableSection(PATH,root,"coastal"),"inland");
        check(grottos(root).fields().isEmpty() && grottos(empty).fields().equals(Set.of("coastal","inland")));
        check(section(empty,"coastal").fields().isEmpty() && section(empty,"inland").fields().isEmpty());
        check(new LosslessJson(empty).tokenPath("hydrology.rivers.enabled").equals("false"));
        rejects(() -> GrottoFields.enableGrottos(PATH,root));
        check(GrottoFields.choices().equals(List.of("SEA_LEVEL")) && !GrottoFields.choiceHelp("SEA_LEVEL").isBlank());
        check(GrottoFields.fallbackHelp().contains("larger of 4 and verticalRadius"));
        byte[] preserved=null;
        for (String name : GrottoFields.SECTIONS) {
            var specs=SPECS.stream().filter(spec -> spec.section().equals(name)).toList();
            check(GrottoFields.fields(name).equals(specs.stream().map(Spec::field).toList()) && !GrottoFields.description(name).isBlank());
            rejects(() -> GrottoFields.enableSection(PATH,empty,name));
            String other=name.equals("coastal") ? "inland" : "coastal";
            String prefix="{\r\n \"hydrology\":{\"rivers\":{\"enabled\":false,\"grottos\":{\""+name+"\":";
            String suffix=",\""+other+"\":{\"futureSibling\":true},\"futureRoot\":[\"\\u0061\"]},\"geometry\":{\"grottos\":{\"aspectRange\":0.2}},\"underground\":{\"sources\":{\"density\":0.25}},\"profiles\":[{\"id\":\"literal ID\"}]}},\"unknown\":9007199254740993\r\n}\r\n";
            String leaf="{"+String.join(",",specs.stream().map(spec -> "\""+spec.field()+"\":"+(spec.kind().equals("enum") ? "\"FUTURE\\u005fLEVEL\"" : spec.value())).toList())+(name.equals("coastal") ? ",\"seaCaves\":{\"enabled\":false,\"future\":true}" : "")+",\"future\":\"雪\\u0061\"}";
            byte[] original=bytes(prefix+leaf+suffix),copy=original.clone();
            for (var spec : specs) {
                String field=spec.field();
                check(GrottoFields.defaultToken(name,field).equals(spec.value()) && !GrottoFields.help(name,field).isBlank());
                check(GrottoFields.nullable(name,field)==spec.kind().equals("Integer"));
                check(GrottoFields.integer(name,field)==List.of("int","Integer").contains(spec.kind()));
                if (spec.kind().equals("Integer")) {
                    rejects(() -> GrottoFields.enableScalar(PATH,empty,name,field));
                    check(Arrays.equals(GrottoFields.reset(PATH,empty,name,field),empty));
                    check(Arrays.equals(GrottoFields.reset(PATH,original,name,field),original));
                } else {
                    byte[] enabled=GrottoFields.enableScalar(PATH,empty,name,field);
                    check(section(enabled,name).fields().equals(Set.of(field)));
                    check((spec.kind().equals("enum") ? section(enabled,name).stringToken(field) : section(enabled,name).token(field)).equals(spec.value()));
                    rejects(() -> GrottoFields.enableScalar(PATH,enabled,name,field));
                    rejects(() -> GrottoFields.reset(PATH,original,name,field));
                }
                if (spec.kind().equals("boolean")) {
                    String next=spec.value().equals("true") ? "false" : "true";
                    check(text(GrottoFields.setBoolean(PATH,original,name,field,!spec.value().equals("true"))).equals(prefix+leaf.replace("\""+field+"\":"+spec.value(),"\""+field+"\":"+next)+suffix));
                    check(section(GrottoFields.setBoolean(PATH,empty,name,field,false),name).fields().equals(Set.of(field)));
                    rejects(() -> GrottoFields.setText(PATH,original,name,field,"1"));
                } else if (spec.kind().equals("enum")) {
                    check(text(GrottoFields.setEnum(PATH,original,name,field,"SEA_LEVEL")).equals(prefix+leaf.replace("FUTURE\\u005fLEVEL","SEA_LEVEL")+suffix));
                    rejects(() -> GrottoFields.setEnum(PATH,original,name,field,"sea_level"));
                    rejects(() -> GrottoFields.setEnum(PATH,original,name,field,"UNKNOWN"));
                    rejects(() -> GrottoFields.setEnum(PATH,original,name,field,null));
                    rejects(() -> GrottoFields.setEnum(PATH,empty,name,field,"SEA_LEVEL"));
                    rejects(() -> GrottoFields.setText(PATH,original,name,field,"1"));
                    rejects(() -> GrottoFields.setBoolean(PATH,original,name,field,false));
                } else {
                    String minimum=java.math.BigDecimal.valueOf(spec.min()).stripTrailingZeros().toPlainString();
                    byte[] changed=GrottoFields.setText(PATH,original,name,field,minimum);
                    check(text(changed).equals(prefix+leaf.replace("\""+field+"\":"+spec.value(),"\""+field+"\":"+minimum)+suffix));
                    GrottoFields.setText(PATH,original,name,field,Double.toString(spec.max()));
                    rejects(() -> GrottoFields.setText(PATH,original,name,field,Double.toString(spec.min()-1)));
                    rejects(() -> GrottoFields.setText(PATH,original,name,field,Double.toString(spec.max()+1)));
                    rejects(() -> GrottoFields.setBoolean(PATH,original,name,field,false));
                    if (spec.kind().equals("Integer")) {
                        check(Arrays.equals(GrottoFields.reset(PATH,changed,name,field),original));
                        check(section(GrottoFields.setText(PATH,empty,name,field,"0"),name).fields().equals(Set.of(field)));
                    } else rejects(() -> GrottoFields.setText(PATH,empty,name,field,minimum));
                    for (String bad : List.of("NaN","Infinity","1e999","\"1\"","")) rejects(() -> GrottoFields.setText(PATH,original,name,field,bad));
                    if (!spec.kind().equals("double")) {
                        rejects(() -> GrottoFields.setText(PATH,original,name,field,minimum+".000000000000000001"));
                        check(section(GrottoFields.setText(PATH,original,name,field,minimum+"e0"),name).token(field).equals(minimum));
                    }
                }
                for (String bad : List.of("null","true","[]","{}","\"1\"")) {
                    if (spec.kind().equals("boolean") && bad.equals("true") || spec.kind().equals("Integer") && bad.equals("null") || spec.kind().equals("enum") && bad.equals("\"1\"")) continue;
                    byte[] wrong=bytes(prefix+"{\""+field+"\":"+bad+"}"+suffix);
                    if (spec.kind().equals("boolean")) rejects(() -> GrottoFields.setBoolean(PATH,wrong,name,field,false));
                    else if (spec.kind().equals("enum")) rejects(() -> GrottoFields.setEnum(PATH,wrong,name,field,"SEA_LEVEL"));
                    else rejects(() -> GrottoFields.setText(PATH,wrong,name,field,"1"));
                    rejects(() -> GrottoFields.reset(PATH,wrong,name,field));
                }
            }
            check(Arrays.equals(original,copy));
            check(DimensionFields.readOnlyPaths(new LosslessJson(original)).containsAll(List.of("hydrology.rivers.grottos."+name+".future","hydrology.rivers.grottos.futureRoot")));
            if (name.equals("coastal")) check(DimensionFields.readOnlyPaths(new LosslessJson(original)).contains("hydrology.rivers.grottos.coastal.seaCaves"));
            for (String bad : List.of("null","[]","1","true","\"snippet\"")) {
                byte[] wrong=bytes(prefix+bad+suffix);
                rejects(() -> GrottoFields.enableSection(PATH,wrong,name));
                rejects(() -> GrottoFields.enableScalar(PATH,wrong,name,"horizontalRadius"));
                rejects(() -> GrottoFields.setBoolean(PATH,wrong,name,"enabled",false));
                check(DimensionFields.readOnlyPaths(new LosslessJson(wrong)).contains("hydrology.rivers.grottos."+name));
                check(section(GrottoFields.setBoolean(PATH,wrong,other,"enabled",false),other).token("enabled").equals("false"));
            }
            preserved=original;
        }
        byte[] zero=GrottoFields.setText(PATH,empty,"coastal","cliffMinimumHeight","0");
        byte[] nullHeight=GrottoFields.reset(PATH,zero,"coastal","cliffMinimumHeight");
        check(section(zero,"coastal").token("cliffMinimumHeight").equals("0") && section(nullHeight,"coastal").token("cliffMinimumHeight").equals("null"));
        byte[] vertical=GrottoFields.enableScalar(PATH,nullHeight,"coastal","verticalRadius");
        check(section(GrottoFields.setText(PATH,vertical,"coastal","verticalRadius","64"),"coastal").token("cliffMinimumHeight").equals("null"));
        for (String missing : List.of("{}","{\"hydrology\":{}}","{\"hydrology\":{\"rivers\":null}}")) rejects(() -> GrottoFields.enableGrottos(PATH,bytes(missing)));
        rejects(() -> GrottoFields.enableSection(PATH,parent,"coastal"));
        rejects(() -> GrottoFields.enableScalar(PATH,root,"coastal","horizontalRadius"));
        rejects(() -> GrottoFields.setText(PATH,root,"coastal","cliffMinimumHeight","0"));
        for (String bad : List.of("null","[]","1","true","\"snippet\"")) {
            byte[] wrong=bytes("{\"hydrology\":{\"rivers\":{\"grottos\":"+bad+"}}}");
            rejects(() -> GrottoFields.enableGrottos(PATH,wrong));
            rejects(() -> GrottoFields.enableSection(PATH,wrong,"inland"));
            check(DimensionFields.readOnlyPaths(new LosslessJson(wrong)).contains("hydrology.rivers.grottos"));
        }
        for (String path : List.of("regions/a.json","biomes/a.json","generators/a.json","dimensions/nested/a.json","a.json")) {
            rejects(() -> GrottoFields.enableGrottos(path,parent));
            rejects(() -> GrottoFields.enableSection(path,root,"inland"));
            rejects(() -> GrottoFields.setText(path,empty,"coastal","cliffMinimumHeight","0"));
            rejects(() -> GrottoFields.reset(path,nullHeight,"coastal","cliffMinimumHeight"));
        }
        rejects(() -> GrottoFields.enableSection(PATH,root,"seaCaves"));
        rejects(() -> GrottoFields.enableScalar(PATH,empty,"coastal","seaCaves"));
        rejects(() -> GrottoFields.setEnum(PATH,empty,"inland","enabled","SEA_LEVEL"));
        var drafts=new InspectorDrafts();
        for (String key : List.of(GrottoFields.draftPrefix("coastal")+"cliffMinimumHeight",GrottoFields.draftPrefix("coastal")+"horizontalRadius",GrottoFields.draftPrefix("inland")+"horizontalRadius",RiverGeometryFields.draftPrefix("grottos")+"aspectRange",HydrologyFields.draftPrefix()+"tileSize")) drafts.update(key,"1","2");
        GrottoFields.setBoolean(PATH,empty,"inland","connectSurfaceRivers",true); check(drafts.count()==5);
        GrottoFields.reset(PATH,zero,"coastal","cliffMinimumHeight"); drafts.applied(GrottoFields.draftPrefix("coastal")+"cliffMinimumHeight");
        check(drafts.count()==4 && drafts.contains(GrottoFields.draftPrefix("inland")+"horizontalRadius"));
        check(FieldCoverage.fields("riverGrottos").size()==2 && FieldCoverage.fields("riverGrottos").stream().allMatch(FieldCoverage.Field::implemented));
        check(FieldCoverage.fields("coastalGrotto").size()==9 && FieldCoverage.fields("coastalGrotto").stream().filter(FieldCoverage.Field::implemented).count()==8);
        check(FieldCoverage.fields("inlandGrotto").size()==6 && FieldCoverage.fields("inlandGrotto").stream().allMatch(FieldCoverage.Field::implemented));
        var workspace=PackWorkspace.newPack(Map.of(PATH,zero,"dimensions/null-height.json",nullHeight,"dimensions/defaults.json",empty,"dimensions/unknown.json",preserved));
        workspace.save(temp.resolve("grotto-settings")); workspace.exportZip(temp.resolve("grotto-settings.zip"));
        var reopened=PackWorkspace.open(temp.resolve("grotto-settings")); var imported=PackWorkspace.importZip(temp.resolve("grotto-settings.zip"));
        for (String path : workspace.paths()) {
            check(Arrays.equals(workspace.read(path),reopened.read(path)));
            check(Arrays.equals(workspace.read(path),imported.read(path)));
        }
        check(section(reopened.read("dimensions/defaults.json"),"coastal").fields().isEmpty() && section(reopened.read("dimensions/defaults.json"),"inland").fields().isEmpty());
        check(section(reopened.read(PATH),"coastal").token("cliffMinimumHeight").equals("0"));
        check(section(reopened.read("dimensions/null-height.json"),"coastal").token("cliffMinimumHeight").equals("null"));
        check(new LosslessJson(reopened.read(PATH)).tokenPath("hydrology.rivers.enabled").equals("false"));
        System.out.println("PASS: coastal/inland grotto defaults/all bounds, pool enum, nullable cliff fallback states, lossless siblings, parent/draft guards and Save/ZIP contracts");
    }
}
