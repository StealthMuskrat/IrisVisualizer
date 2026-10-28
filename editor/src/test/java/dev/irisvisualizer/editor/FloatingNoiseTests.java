package dev.irisvisualizer.editor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class FloatingNoiseTests {
    private static final String PATH="biomes/nested/noise.json";
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static String text(byte[] value) { return new String(value,StandardCharsets.UTF_8); }
    private static void check(boolean condition) { if (!condition) throw new AssertionError("Floating noise contract failed"); }
    private static void rejects(Runnable action) { try { action.run(); } catch (IllegalArgumentException expected) { return; }throw new AssertionError("Expected rejected floating noise operation"); }
    private static byte[] document(String field,String token) { return bytes("{\r\n \"floatingChildBiomes\":[ {\""+field+"\":"+token+",\"rarity\":7,\"carving\":\"dimension-entry\",\"maxTopHeight\":5}, {\"biome\":\"missing/stored\"} ],\"future\":9007199254740993,\"escaped\":\"雪\\u0061\"\r\n}\r\n"); }
    private static LosslessJson entry(byte[] value) { return FloatingBiomes.entries(new LosslessJson(value)).getFirst(); }
    private static LosslessJson style(byte[] value,NoiseStyleFields.Target target) { return NoiseStyleFields.object(entry(value),target); }
    static void run(Path temp) throws Exception {
        byte[] absent=bytes("{\r\n \"floatingChildBiomes\":[{},{}],\"future\":9007199254740993\r\n}\r\n");var prefixes=new HashSet<String>();
        for (String field : FloatingNoiseStyles.EDITABLE) {
            var target=NoiseStyleFields.Target.floating(0,field);boolean optional=FloatingNoiseStyles.optional(field),edge=field.equals("edgeTaperVariationStyle");
            check(target.floating() && target.floatingContext().index()==0 && target.member().equals(field) && target.optionalFloating()==optional && prefixes.add(target.draftPrefix()));
            check(NoiseStyleFields.contextDefault(target).equals(optional ? "FLAT" : "SIMPLEX") && !NoiseStyleFields.contextDescription(target).isBlank());
            byte[] enabled=NoiseStyleFields.enable(PATH,absent,target,"style");check(style(enabled,target).stringToken("style").equals(optional ? "FLAT" : "SIMPLEX"));
            check(style(enabled,target).fields().equals(edge ? Set.of("style","zoom") : Set.of("style")));
            if (edge) check(style(enabled,target).token("zoom").equals("0.18") && NoiseStyleFields.contextDefaultLabel(target).contains("zoom 0.18"));
            check(entry(enabled).fields().equals(Set.of(field)) && new LosslessJson(enabled).token("future").equals("9007199254740993"));
            check(FloatingBiomes.entries(new LosslessJson(enabled)).get(1).fields().isEmpty());
            rejects(() -> NoiseStyleFields.enable(PATH,enabled,target,"style"));
            for (String scalar : List.of("zoom","exponent","cellularFrequency","cellularZoom")) {
                byte[] seeded=NoiseStyleFields.enable(PATH,absent,target,scalar);check(style(seeded,target).stringToken("style").equals(optional ? "FLAT" : "SIMPLEX"));
                check(style(seeded,target).token(scalar).equals(edge && scalar.equals("zoom") ? "0.18" : NoiseStyleFields.defaultToken(scalar)));
                if (edge) check(style(seeded,target).token("zoom").equals("0.18"));
            }
            byte[] empty=document(field,"{}");check(style(NoiseStyleFields.enable(PATH,empty,target,"style"),target).stringToken("style").equals("FLAT"));
            check(style(NoiseStyleFields.enable(PATH,empty,target,"zoom"),target).token("zoom").equals("1"));
            byte[] explicitNull=document(field,"null");
            if (optional) {
                check(style(explicitNull,target)==null);check(style(NoiseStyleFields.enable(PATH,explicitNull,target,"style"),target).stringToken("style").equals("FLAT"));
                check(Arrays.equals(explicitNull,FloatingNoiseStyles.disable(PATH,explicitNull,0,field)) && Arrays.equals(absent,FloatingNoiseStyles.disable(PATH,absent,0,field)));
            } else { rejects(() -> NoiseStyleFields.object(entry(explicitNull),target));rejects(() -> NoiseStyleFields.enable(PATH,explicitNull,target,"style"));rejects(() -> FloatingNoiseStyles.disable(PATH,enabled,0,field)); }
            String raw="{\"style\":\"UNKNOWN\",\"zoom\":1e0,\"exponent\":1,\"cellularFrequency\":0,\"cellularZoom\":1,\"expression\":\"kept\",\"imageMap\":null,\"cacheSize\":9007199254740993,\"fracture\":{\"escaped\":\"雪\\u0061\"},\"futureNoise\":true}";
            byte[] original=document(field,raw),copy=original.clone();
            for (String choice : NoiseStyleFields.choices()) check(text(NoiseStyleFields.editStyle(PATH,original,target,choice)).equals(text(original).replace("\"UNKNOWN\"","\""+choice+"\"")));
            check(text(NoiseStyleFields.editText(PATH,original,target,"zoom","2.5")).equals(text(original).replace("\"zoom\":1e0","\"zoom\":2.5")));
            NoiseStyleFields.editText(PATH,original,target,"zoom","0.00001");NoiseStyleFields.editText(PATH,original,target,"exponent","0.01562");NoiseStyleFields.editText(PATH,original,target,"exponent","64");
            for (String[] bad : new String[][]{{"zoom","0"},{"zoom","0.000001"},{"exponent","0.015"},{"exponent","65"},{"cellularFrequency","-1"},{"zoom","NaN"},{"cellularZoom","Infinity"}}) rejects(() -> NoiseStyleFields.editText(PATH,original,target,bad[0],bad[1]));
            byte[] inactive=NoiseStyleFields.editText(PATH,original,target,"cellularZoom","0");rejects(() -> NoiseStyleFields.editText(PATH,inactive,target,"cellularFrequency","1"));
            byte[] active=NoiseStyleFields.editText(PATH,original,target,"cellularFrequency","1");rejects(() -> NoiseStyleFields.editText(PATH,active,target,"cellularZoom","0"));rejects(() -> NoiseStyleFields.editText(PATH,active,target,"cellularZoom","4.9e-324"));
            var readOnly=FloatingNoiseStyles.readOnlyPaths(entry(original),0);check(readOnly.containsAll(List.of("floatingChildBiomes[0]."+field+".cacheSize","floatingChildBiomes[0]."+field+".fracture.escaped","floatingChildBiomes[0]."+field+".futureNoise")));
            check(!FloatingBiomes.readOnlyFields(entry(original)).contains(field));
            if (optional) {
                byte[] disabled=FloatingNoiseStyles.disable(PATH,original,0,field);check(text(disabled).equals(text(original).replace(raw,"null")));
                byte[] restored=NoiseStyleFields.enable(PATH,disabled,target,"style");check(style(restored,target).stringToken("style").equals("FLAT") && entry(restored).stringToken("carving").equals("dimension-entry"));
                check(FloatingNoiseStyles.readOnlyPaths(entry(disabled),0).stream().noneMatch(p -> p.endsWith("."+field)));
            }
            for (String bad : List.of("[]","true","1","\"snippet/style\"")) {
                byte[] invalid=document(field,bad);rejects(() -> NoiseStyleFields.enable(PATH,invalid,target,"style"));rejects(() -> NoiseStyleFields.editStyle(PATH,invalid,target,"SIMPLEX"));rejects(() -> FloatingNoiseStyles.disable(PATH,invalid,0,field));
                check(FloatingNoiseStyles.readOnlyPaths(entry(invalid),0).contains("floatingChildBiomes[0]."+field));
            }
            for (String scalar : List.of("zoom","exponent","cellularFrequency","cellularZoom")) {
                byte[] invalid=document(field,"{\"style\":\"SIMPLEX\",\""+scalar+"\":\"1\"}");rejects(() -> NoiseStyleFields.editText(PATH,invalid,target,scalar,"1"));
                check(style(NoiseStyleFields.editStyle(PATH,invalid,target,"FLAT"),target).token(scalar).equals("\"1\""));
            }
            for (String list : List.of("null","{}","[null]","[\"snippet/floating\"]")) {
                byte[] invalid=bytes("{\"floatingChildBiomes\":"+list+"}");rejects(() -> NoiseStyleFields.enable(PATH,invalid,target,"style"));rejects(() -> FloatingNoiseStyles.disable(PATH,invalid,0,field));
            }
            rejects(() -> NoiseStyleFields.enable(PATH,bytes("{}"),target,"style"));rejects(() -> NoiseStyleFields.enable(PATH,bytes("{\"floatingChildBiomes\":[]}"),target,"style"));
            for (String path : List.of("dimensions/a.json","regions/a.json","generators/a.json","a.json","biomes/a.txt")) {
                rejects(() -> NoiseStyleFields.enable(path,absent,target,"style"));rejects(() -> NoiseStyleFields.editText(path,original,target,"zoom","1"));rejects(() -> FloatingNoiseStyles.disable(path,original,0,field));
            }
            rejects(() -> new NoiseStyleFields.Target("dimensions",target.objectField(),null));rejects(() -> new NoiseStyleFields.Target("biomes",target.objectField(),null,0));
            var drafts=new InspectorDrafts();String key=target.draftPrefix()+"zoom",otherStyle=NoiseStyleFields.Target.floating(0,field.equals("footprintStyle") ? "bottomStyle" : "footprintStyle").draftPrefix()+"zoom",otherEntry=NoiseStyleFields.Target.floating(1,field).draftPrefix()+"zoom",heightKey=FloatingBiomes.entryPrefix(0)+"minHeightAboveSurface",outside=BiomeSlabWall.noisePrefix("wall")+"zoom";
            for (String draft : List.of(key,otherStyle,otherEntry,heightKey,outside)) drafts.update(draft,"1","2");
            check(!drafts.transitionUnder(target.draftPrefix(),() -> false,() -> { throw new AssertionError(); }) && drafts.count()==5);
            try { drafts.transitionUnder(target.draftPrefix(),() -> true,() -> { throw new IOException("failed write"); });throw new AssertionError(); } catch (IOException expected) { check(drafts.count()==5); }
            check(drafts.transitionUnder(target.draftPrefix(),() -> true,() -> {}) && drafts.count()==4 && drafts.contains(otherStyle) && drafts.contains(otherEntry) && drafts.contains(heightKey));
            check(drafts.transitionUnder(FloatingBiomes.prefix(),() -> true,() -> {}) && drafts.count()==1 && drafts.contains(outside));
            check(Arrays.equals(original,copy));var workspace=PackWorkspace.newPack(Map.of(PATH,original,"biomes/defaults.json",enabled,"biomes/empty.json",empty));
            workspace.replace(PATH,NoiseStyleFields.editText(PATH,workspace.read(PATH),target,"zoom","3"));
            if (optional) { workspace.replace(PATH,FloatingNoiseStyles.disable(PATH,workspace.read(PATH),0,field));workspace.replace(PATH,NoiseStyleFields.enable(PATH,workspace.read(PATH),target,"style")); }
            workspace.save(temp.resolve("floating-noise-"+field));workspace.exportZip(temp.resolve("floating-noise-"+field+".zip"));var reopened=PackWorkspace.open(temp.resolve("floating-noise-"+field));var imported=PackWorkspace.importZip(temp.resolve("floating-noise-"+field+".zip"));
            for (String path : workspace.paths()) check(Arrays.equals(workspace.read(path),reopened.read(path)) && Arrays.equals(workspace.read(path),imported.read(path)));
        }
        rejects(() -> NoiseStyleFields.Target.floating(-1,"footprintStyle"));rejects(() -> NoiseStyleFields.Target.floating(0,"bottomPalette"));
        for (String path : List.of("floatingChildBiomes.01.footprintStyle","floatingChildBiomes.-1.footprintStyle","floatingChildBiomes.2147483648.footprintStyle","floatingChildBiomes.0.footprintStyle.zoom","floatingChildBiomes.0.futureStyle")) rejects(() -> new NoiseStyleFields.Target("biomes",path,null));
        check(FloatingNoiseStyles.context("floatingChildBiomes.0.footprintStyle").index()==0);
        for (String bad : List.of("[]","true","1","\"snippet\"")) rejects(() -> new LosslessJson(bytes("{\"optional\":"+bad+"}")).nullObject("optional"));
        check(Arrays.equals(bytes("{\"other\":1}"),new LosslessJson(bytes("{\"other\":1}")).nullObject("optional")));
        check(FieldCoverage.fields("floatingBiome").stream().filter(f -> FloatingNoiseStyles.EDITABLE.contains(f.name())).allMatch(FieldCoverage.Field::implemented));
        System.out.println("PASS: eight floating noise targets, SIMPLEX/FLAT and edge zoom defaults, nullable enable/disable, all style choices/cellular guards, raw preservation, typed owner/draft isolation and Save/ZIP contracts");
    }
}
