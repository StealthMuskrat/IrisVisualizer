package dev.irisvisualizer.editor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class UndergroundRiverTests {
    private static final String PATH="dimensions/underground-rivers.json";
    private record Spec(boolean sources,String field,String kind,String value,double min,double max) {}
    private static final List<Spec> SPECS="""
        root enabled boolean true 0 0
        root connectToExistingCaves boolean true 0 0
        root mouthLevelingDistance int 64 16 512
        root tributaries int 1 0 4
        root minimumRockCover int 1 1 64
        root minimumFloorCover int 1 1 32
        root wideningSources int 8 1 64
        sources density double 0.25 0 64
        sources minimumPerTile int 0 0 64
        sources minimumSpacing int 512 0 8192
        """.lines().map(String::trim).filter(line -> !line.isEmpty()).map(line -> {
            String[] words=line.split(" "); return new Spec(words[0].equals("sources"),words[1],words[2],words[3],Double.parseDouble(words[4]),Double.parseDouble(words[5]));
        }).toList();
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static String text(byte[] value) { return new String(value,StandardCharsets.UTF_8); }
    private static void check(boolean value) { if (!value) throw new AssertionError("Underground river contract failed"); }
    private static void rejects(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Expected rejected underground operation");
    }
    private static LosslessJson underground(byte[] value) { return UndergroundRiverFields.underground(HydrologyFields.rivers(HydrologyFields.hydrology(new LosslessJson(value)))); }
    private static LosslessJson object(byte[] value,boolean sources) { return sources ? UndergroundRiverFields.sources(underground(value)) : underground(value); }
    private static LosslessJson range(byte[] value,String field) { return RiverChannelRanges.object(underground(value),RiverChannelRanges.undergroundKey(field)); }
    private static LosslessJson style(byte[] value,String field) { return NoiseStyleFields.object(range(value,field),NoiseStyleFields.Target.undergroundRange(field)); }
    static void run(Path temp) throws Exception {
        byte[] parent=bytes("{\"hydrology\":{\"rivers\":{\"enabled\":false}}}");
        byte[] root=UndergroundRiverFields.enableUnderground(PATH,parent);
        check(underground(root).fields().isEmpty());
        check(new LosslessJson(root).tokenPath("hydrology.rivers.enabled").equals("false"));
        byte[] empty=UndergroundRiverFields.enableSources(PATH,root);
        check(underground(empty).fields().equals(Set.of("sources")) && object(empty,true).fields().isEmpty());
        rejects(() -> UndergroundRiverFields.enableUnderground(PATH,root));
        rejects(() -> UndergroundRiverFields.enableSources(PATH,empty));
        for (boolean sources : List.of(false,true)) {
            var specs=SPECS.stream().filter(spec -> spec.sources()==sources).toList();
            check(UndergroundRiverFields.fields(sources).equals(specs.stream().map(Spec::field).toList()));
            String prefix="{\r\n \"hydrology\":{\"rivers\":{\"enabled\":false,\"underground\":"+(sources ? "{\"sources\":" : "");
            String suffix=(sources ? ",\"enabled\":true,\"futureRoot\":true}" : "")+",\"surface\":{\"channel\":{\"depth\":{\"min\":2,\"max\":4}}},\"geometry\":{\"underground\":{\"bedRoundness\":2.4}},\"profiles\":[{\"id\":\"literal ID\"}]}},\"unknown\":9007199254740993\r\n}\r\n";
            String leaf="{"+String.join(",",specs.stream().map(spec -> "\""+spec.field()+"\":"+spec.value()).toList())+",\"future\":\"雪\\u0061\"}";
            byte[] original=bytes(prefix+leaf+suffix),copy=original.clone();
            for (var spec : specs) {
                String field=spec.field();
                check(UndergroundRiverFields.defaultToken(sources,field).equals(spec.value()) && !UndergroundRiverFields.help(sources,field).isBlank());
                check(UndergroundRiverFields.integer(sources,field)==spec.kind().equals("int"));
                byte[] enabled=UndergroundRiverFields.enableScalar(PATH,empty,sources,field);
                check(object(enabled,sources).fields().equals(sources ? Set.of(field) : Set.of("sources",field)) && object(enabled,sources).token(field).equals(spec.value()));
                rejects(() -> UndergroundRiverFields.enableScalar(PATH,enabled,sources,field));
                if (spec.kind().equals("boolean")) {
                    check(text(UndergroundRiverFields.setBoolean(PATH,original,sources,field,false)).equals(prefix+leaf.replace("\""+field+"\":true","\""+field+"\":false")+suffix));
                    check(object(UndergroundRiverFields.setBoolean(PATH,empty,sources,field,false),sources).token(field).equals("false"));
                    rejects(() -> UndergroundRiverFields.setText(PATH,original,sources,field,"1"));
                } else {
                    String minimum=java.math.BigDecimal.valueOf(spec.min()).stripTrailingZeros().toPlainString();
                    check(text(UndergroundRiverFields.setText(PATH,original,sources,field,minimum)).equals(prefix+leaf.replace("\""+field+"\":"+spec.value(),"\""+field+"\":"+minimum)+suffix));
                    UndergroundRiverFields.setText(PATH,original,sources,field,Double.toString(spec.max()));
                    rejects(() -> UndergroundRiverFields.setText(PATH,original,sources,field,Double.toString(spec.min()-1)));
                    rejects(() -> UndergroundRiverFields.setText(PATH,original,sources,field,Double.toString(spec.max()+1)));
                    rejects(() -> UndergroundRiverFields.setText(PATH,empty,sources,field,minimum));
                    rejects(() -> UndergroundRiverFields.setBoolean(PATH,original,sources,field,false));
                    for (String bad : List.of("NaN","Infinity","1e999","\"1\"","")) rejects(() -> UndergroundRiverFields.setText(PATH,original,sources,field,bad));
                    if (spec.kind().equals("int")) {
                        rejects(() -> UndergroundRiverFields.setText(PATH,original,sources,field,minimum+".000000000000000001"));
                        check(object(UndergroundRiverFields.setText(PATH,original,sources,field,minimum+"e0"),sources).token(field).equals(minimum));
                    }
                }
                for (String bad : List.of("null","true","[]","{}","\"1\"")) {
                    if (spec.kind().equals("boolean") && bad.equals("true")) continue;
                    byte[] wrong=bytes(prefix+"{\""+field+"\":"+bad+"}"+suffix);
                    if (spec.kind().equals("boolean")) rejects(() -> UndergroundRiverFields.setBoolean(PATH,wrong,sources,field,false));
                    else rejects(() -> UndergroundRiverFields.setText(PATH,wrong,sources,field,spec.value()));
                }
            }
            check(Arrays.equals(original,copy));
            check(DimensionFields.readOnlyPaths(new LosslessJson(original)).contains("hydrology.rivers.underground."+(sources ? "sources." : "")+"future"));
        }
        Map<String,List<String>> expected=Map.of("fluidLevel",List.of("-48","50","1024"),"channelWidth",List.of("3","8","768"),"depth",List.of("1","3","768"),"headroom",List.of("6","14","768"));
        byte[] allRanges=empty;
        for (String field : RiverChannelRanges.UNDERGROUND_FIELDS) {
            String key=RiverChannelRanges.undergroundKey(field);
            var target=NoiseStyleFields.Target.undergroundRange(field);
            check(target.undergroundRange() && target.riverRange() && !target.channelRange() && !target.strictBounds() && target.rangeField().equals(key));
            check(RiverChannelRanges.styleTarget(key).equals(target) && !RiverChannelRanges.description(key).isBlank());
            byte[] created=RiverChannelRanges.enable(PATH,root,key);
            allRanges=RiverChannelRanges.enable(PATH,allRanges,key);
            check(underground(created).fields().equals(Set.of(field)) && range(created,field).fields().equals(Set.of("min","max","style")));
            check(range(created,field).token("min").equals(expected.get(field).get(0)) && range(created,field).token("max").equals(expected.get(field).get(1)));
            check(style(created,field).stringToken("style").equals("IRIS") && style(created,field).token("zoom").equals(expected.get(field).get(2)));
            check(new LosslessJson(created).tokenPath("hydrology.rivers.enabled").equals("false") && underground(created).token("enabled")==null);
            rejects(() -> RiverChannelRanges.enable(PATH,created,key));
            String prefix="{\r\n \"hydrology\":{\"rivers\":{\"enabled\":false,\"underground\":{\""+field+"\":";
            String suffix=",\"bedMaterial\":{\"future\":true},\"sources\":{\"density\":0.25}},\"surface\":{\"channel\":{\"depth\":{\"min\":2,\"max\":4}}},\"geometry\":{\"underground\":{\"bedRoundness\":2.4}},\"profiles\":[]}},\"unknown\":9007199254740993\r\n}\r\n";
            String leaf="{\"min\":1e0,\"max\":3e0,\"style\":{\"style\":\"FUTURE\\u005fSTYLE\",\"zoom\":1e0,\"exponent\":1,\"cellularFrequency\":0,\"cellularZoom\":1,\"fracture\":{\"style\":\"UNKNOWN\"},\"expression\":\"unresolved\",\"cacheSize\":17},\"future\":\"雪\\u0061\"}";
            byte[] original=bytes(prefix+leaf+suffix),copy=original.clone();
            check(text(RiverChannelRanges.editBounds(PATH,original,key,"-100","10.5")).equals(prefix+leaf.replace("\"min\":1e0","\"min\":-100").replace("\"max\":3e0","\"max\":10.5")+suffix));
            check(range(RiverChannelRanges.editBounds(PATH,original,key,"0","0"),field).token("max").equals("0"));
            rejects(() -> RiverChannelRanges.editBounds(PATH,original,key,"4","3"));
            for (String bad : List.of("NaN","Infinity","1e999","")) rejects(() -> RiverChannelRanges.editBounds(PATH,original,key,bad,"3"));
            for (String choice : NoiseStyleFields.choices()) check(text(NoiseStyleFields.editStyle(PATH,original,target,choice)).equals(prefix+leaf.replace("FUTURE\\u005fSTYLE",choice)+suffix));
            check(text(NoiseStyleFields.editText(PATH,original,target,"zoom","2.5")).equals(prefix+leaf.replace("\"zoom\":1e0","\"zoom\":2.5")+suffix));
            byte[] emptyRange=bytes(prefix+"{}"+suffix),emptyStyle=bytes(prefix+"{\"style\":{}}"+suffix);
            for (String setting : NoiseStyleFields.EDITABLE) {
                byte[] seeded=NoiseStyleFields.enable(PATH,emptyRange,target,setting);
                check(style(seeded,field).stringToken("style").equals("STATIC") && range(seeded,field).fields().equals(Set.of("style")));
                if (setting.equals("zoom")) check(style(seeded,field).token("zoom").equals("1"));
            }
            check(style(NoiseStyleFields.enable(PATH,emptyStyle,target,"style"),field).stringToken("style").equals("FLAT"));
            byte[] inactive=NoiseStyleFields.editText(PATH,original,target,"cellularZoom","0");
            rejects(() -> NoiseStyleFields.editText(PATH,inactive,target,"cellularFrequency","1"));
            check(style(NoiseStyleFields.editText(PATH,NoiseStyleFields.editText(PATH,inactive,target,"cellularZoom","1"),target,"cellularFrequency","1"),field).token("cellularFrequency").equals("1"));
            for (String bad : List.of("null","[]","1","true","\"snippet\"")) {
                byte[] wrong=bytes(prefix+bad+suffix);
                rejects(() -> RiverChannelRanges.enable(PATH,wrong,key));
                rejects(() -> RiverChannelRanges.editBounds(PATH,wrong,key,"1","2"));
                rejects(() -> NoiseStyleFields.enable(PATH,wrong,target,"style"));
                check(DimensionFields.readOnlyPaths(new LosslessJson(wrong)).contains("hydrology.rivers.underground."+field));
                check(UndergroundRiverFields.sources(underground(UndergroundRiverFields.setText(PATH,wrong,true,"density","0"))).token("density").equals("0"));
            }
            byte[] wrongStyle=bytes(prefix+"{\"min\":1,\"max\":2,\"style\":null}"+suffix);
            rejects(() -> NoiseStyleFields.enable(PATH,wrongStyle,target,"style"));
            check(range(RiverChannelRanges.editBounds(PATH,wrongStyle,key,"3","4"),field).token("style").equals("null"));
            check(DimensionFields.readOnlyPaths(new LosslessJson(original)).containsAll(List.of("hydrology.rivers.underground."+field+".future","hydrology.rivers.underground."+field+".style.fracture","hydrology.rivers.underground.bedMaterial")));
            rejects(() -> NoiseStyleFields.enable(PATH,root,target,"style"));
            rejects(() -> RiverChannelRanges.enable(PATH,parent,key));
            rejects(() -> new NoiseStyleFields.Target("biomes",target.objectField(),null));
            rejects(() -> new NoiseStyleFields.Target("dimensions",target.objectField(),null,0));
            check(Arrays.equals(original,copy));
        }
        for (String missing : List.of("{}","{\"hydrology\":{}}","{\"hydrology\":{\"rivers\":null}}")) rejects(() -> UndergroundRiverFields.enableUnderground(PATH,bytes(missing)));
        rejects(() -> UndergroundRiverFields.enableSources(PATH,parent));
        rejects(() -> UndergroundRiverFields.enableScalar(PATH,root,true,"density"));
        for (String bad : List.of("null","[]","1","true","\"snippet\"")) {
            byte[] wrongRoot=bytes("{\"hydrology\":{\"rivers\":{\"underground\":"+bad+"}}}"),wrongSource=bytes("{\"hydrology\":{\"rivers\":{\"underground\":{\"sources\":"+bad+"}}}}");
            rejects(() -> UndergroundRiverFields.enableUnderground(PATH,wrongRoot));
            rejects(() -> UndergroundRiverFields.enableSources(PATH,wrongRoot));
            rejects(() -> UndergroundRiverFields.enableSources(PATH,wrongSource));
            rejects(() -> UndergroundRiverFields.setText(PATH,wrongSource,true,"density","0"));
            check(underground(UndergroundRiverFields.setBoolean(PATH,wrongSource,false,"enabled",false)).token("sources").equals(bad));
            check(DimensionFields.readOnlyPaths(new LosslessJson(wrongRoot)).contains("hydrology.rivers.underground"));
            check(DimensionFields.readOnlyPaths(new LosslessJson(wrongSource)).contains("hydrology.rivers.underground.sources"));
        }
        for (String path : List.of("regions/a.json","biomes/a.json","generators/a.json","dimensions/nested/a.json","a.json")) {
            rejects(() -> UndergroundRiverFields.enableUnderground(path,parent));
            rejects(() -> UndergroundRiverFields.setBoolean(path,empty,false,"enabled",true));
            rejects(() -> RiverChannelRanges.enable(path,root,"underground.depth"));
            rejects(() -> NoiseStyleFields.enable(path,root,NoiseStyleFields.Target.undergroundRange("depth"),"style"));
        }
        rejects(() -> UndergroundRiverFields.enableScalar(PATH,root,false,"bedMaterial"));
        rejects(() -> NoiseStyleFields.Target.undergroundRange("depth.style"));
        rejects(() -> RiverChannelRanges.undergroundKey("width"));
        var drafts=new InspectorDrafts();
        for (String key : List.of(UndergroundRiverFields.draftPrefix(false)+"tributaries",UndergroundRiverFields.draftPrefix(true)+"density",RiverChannelRanges.draftPrefix("underground.depth")+"min",RiverChannelRanges.draftPrefix("underground.depth")+"max",NoiseStyleFields.Target.undergroundRange("depth").draftPrefix()+"zoom",RiverChannelRanges.draftPrefix("depth")+"min")) drafts.update(key,"1","2");
        RiverChannelRanges.editBounds(PATH,allRanges,"underground.depth","1","2");
        drafts.applied(RiverChannelRanges.draftPrefix("underground.depth")+"min"); drafts.applied(RiverChannelRanges.draftPrefix("underground.depth")+"max");
        check(drafts.count()==4 && drafts.contains(RiverChannelRanges.draftPrefix("depth")+"min") && drafts.contains(NoiseStyleFields.Target.undergroundRange("depth").draftPrefix()+"zoom"));
        check(FieldCoverage.fields("undergroundRiver").size()==13 && FieldCoverage.fields("undergroundRiver").stream().filter(FieldCoverage.Field::implemented).count()==12);
        check(FieldCoverage.fields("undergroundRiverSource").size()==3 && FieldCoverage.fields("undergroundRiverSource").stream().allMatch(FieldCoverage.Field::implemented));
        var workspace=PackWorkspace.newPack(Map.of(PATH,allRanges,"dimensions/defaults.json",empty));
        workspace.save(temp.resolve("underground-rivers")); workspace.exportZip(temp.resolve("underground-rivers.zip"));
        var reopened=PackWorkspace.open(temp.resolve("underground-rivers")); var imported=PackWorkspace.importZip(temp.resolve("underground-rivers.zip"));
        for (String path : workspace.paths()) {
            check(Arrays.equals(workspace.read(path),reopened.read(path)));
            check(Arrays.equals(workspace.read(path),imported.read(path)));
        }
        check(underground(reopened.read("dimensions/defaults.json")).fields().equals(Set.of("sources")) && UndergroundRiverFields.sources(underground(reopened.read("dimensions/defaults.json"))).fields().isEmpty());
        check(new LosslessJson(reopened.read(PATH)).tokenPath("hydrology.rivers.enabled").equals("false"));
        System.out.println("PASS: underground primitive defaults/all bounds, four contextual ranges/all styles, independent repair, parent/draft guards and lossless Save/ZIP contracts");
    }
}
