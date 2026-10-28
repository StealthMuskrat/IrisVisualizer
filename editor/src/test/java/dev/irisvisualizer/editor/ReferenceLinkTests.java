package dev.irisvisualizer.editor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

final class ReferenceLinkTests {
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static void check(boolean value) { if (!value) throw new AssertionError("Reference link contract failed"); }
    private static void state(List<ReferenceDiagnostics.Link> links,String field,ReferenceDiagnostics.State expected) { check(links.stream().anyMatch(link -> link.field().equals(field) && link.state()==expected)); }
    static void run(Path temp) throws Exception {
        var pack=PackWorkspace.newPack(Map.of("dimensions/host.json",bytes("{}"),"dimensions/top.json",bytes("malformed"),"dimensions/nested/top.json",bytes("{}"),"regions/r.json",bytes("{}"),"biomes/b.json",bytes("{}"),"generators/g.json",bytes("{}"),"objects/nested/a.iob",new byte[]{1,2},"objects/upper.IOB",new byte[]{3}));
        String placement="{\"place\":[\"nested/a\"],\"allowedCollisions\":[\"nested/a\"],\"forbiddenCollisions\":[\"missing\"]}";
        String dimension="{\"regions\":[\"r\"],\"focus\":\"b\",\"focusRegion\":\"r\",\"upperDimension\":\"top\",\"dimensionStack\":{\"dimensions\":[\"top\",\"host\"]},\"staticObjects\":[{\"object\":\"nested/a\"}]}";
        pack.replace("dimensions/host.json",bytes(dimension));long revision=pack.revision();var links=ReferenceDiagnostics.inspect(pack,"dimensions/host.json");
        check(links.size()==7 && links.stream().allMatch(link -> link.state()==ReferenceDiagnostics.State.EXACT_FILE));state(links,"dimensionStack.dimensions[1]",ReferenceDiagnostics.State.EXACT_FILE);check(pack.revision()==revision && Arrays.equals(pack.read("dimensions/host.json"),bytes(dimension)));
        try { links.clear();throw new AssertionError(); } catch (UnsupportedOperationException expected) {}
        StringBuilder floating=new StringBuilder("{\"biome\":\"b\"");for (String field : FloatingObjectPlacements.LIST_FIELDS) floating.append(",\"").append(field).append("\":[").append(placement).append("]");floating.append("}");
        String biome="{\"generators\":[{\"generator\":\"g\"}],\"children\":[\"b\"],\"carvingBiome\":\"b\",\"objects\":["+placement+"],\"floatingChildBiomes\":["+floating+"]}";
        pack.replace("biomes/b.json",bytes(biome));links=ReferenceDiagnostics.inspect(pack,"biomes/b.json");check(links.size()==19);
        for (String field : FloatingObjectPlacements.LIST_FIELDS) {state(links,"floatingChildBiomes[0]."+field+"[0].place[0]",ReferenceDiagnostics.State.EXACT_FILE);state(links,"floatingChildBiomes[0]."+field+"[0].forbiddenCollisions[0]",ReferenceDiagnostics.State.NO_EXACT_FILE);}
        state(links,"floatingChildBiomes[0].biome",ReferenceDiagnostics.State.EXACT_FILE);state(links,"objects[0].allowedCollisions[0]",ReferenceDiagnostics.State.EXACT_FILE);
        pack.replace("regions/r.json",bytes("{\"landBiomes\":[\"b\"],\"objects\":["+placement+"]}"));state(ReferenceDiagnostics.inspect(pack,"regions/r.json"),"objects[0].place[0]",ReferenceDiagnostics.State.EXACT_FILE);
        for (String token : List.of("null","\"\"","\"NoNe\"")) {pack.replace("dimensions/host.json",bytes("{\"regions\":[],\"upperDimension\":"+token+"}"));check(ReferenceDiagnostics.inspect(pack,"dimensions/host.json").stream().noneMatch(link -> link.field().equals("upperDimension")));}
        for (String selector : List.of("focus","focusRegion")) {
            pack.replace("dimensions/host.json",bytes("{\"regions\":[],\""+selector+"\":\"   \"}"));check(ReferenceDiagnostics.inspect(pack,"dimensions/host.json").stream().noneMatch(link -> link.field().equals(selector)));
            pack.replace("dimensions/host.json",bytes("{\"regions\":[],\""+selector+"\":null}"));state(ReferenceDiagnostics.inspect(pack,"dimensions/host.json"),selector,ReferenceDiagnostics.State.UNSUPPORTED);
        }
        pack.replace("dimensions/host.json",bytes("{\"regions\":[],\"upperDimension\":\"   \",\"focus\":\"alias:b\",\"focusRegion\":\"missing\",\"dimensionStack\":{\"dimensions\":[\"nested/top\"]},\"staticObjects\":[{\"object\":\"upper\"}]}"));links=ReferenceDiagnostics.inspect(pack,"dimensions/host.json");
        state(links,"upperDimension",ReferenceDiagnostics.State.INVALID_KEY);state(links,"focus",ReferenceDiagnostics.State.INVALID_KEY);state(links,"focusRegion",ReferenceDiagnostics.State.NO_EXACT_FILE);state(links,"dimensionStack.dimensions[0]",ReferenceDiagnostics.State.INVALID_KEY);state(links,"staticObjects[0].object",ReferenceDiagnostics.State.NO_EXACT_FILE);
        for (String bad : List.of("null","true","[]","\"snippet\"")) {
            pack.replace("dimensions/host.json",bytes("{\"regions\":[\"r\"],\"dimensionStack\":"+bad+",\"staticObjects\":[{\"object\":1}]}"));links=ReferenceDiagnostics.inspect(pack,"dimensions/host.json");state(links,"regions[0]",ReferenceDiagnostics.State.EXACT_FILE);state(links,"dimensionStack",ReferenceDiagnostics.State.UNSUPPORTED);state(links,"staticObjects[0].object",ReferenceDiagnostics.State.UNSUPPORTED);
        }
        pack.replace("biomes/b.json",bytes("{\"generators\":[],\"carvingBiome\":null,\"objects\":[{\"place\":[\"nested/a\",1]}],\"floatingChildBiomes\":[null]}"));links=ReferenceDiagnostics.inspect(pack,"biomes/b.json");state(links,"carvingBiome",ReferenceDiagnostics.State.UNSUPPORTED);state(links,"objects[0].place",ReferenceDiagnostics.State.UNSUPPORTED);state(links,"floatingChildBiomes",ReferenceDiagnostics.State.UNSUPPORTED);
        pack.replace("biomes/b.json",bytes("{\"generators\":[],\"floatingChildBiomes\":[{\"biome\":null,\"extraObjects\":null}]}"));check(ReferenceDiagnostics.inspect(pack,"biomes/b.json").isEmpty());
        pack.replace("dimensions/host.json",bytes("{\"regions\":["+String.join(",",Collections.nCopies(ReferenceDiagnostics.MAX_LINKS+20,"\"r\""))+"]}"));links=ReferenceDiagnostics.inspect(pack,"dimensions/host.json");check(links.size()==ReferenceDiagnostics.MAX_LINKS+1 && links.getLast().key().contains("truncated"));
        pack.replace("dimensions/host.json",bytes(" ".repeat(ReferenceDiagnostics.MAX_JSON_BYTES+1)));check(ReferenceDiagnostics.inspect(pack,"dimensions/host.json").getFirst().key().contains("1 MB"));
        pack.replace("dimensions/host.json",bytes("{"));state(ReferenceDiagnostics.inspect(pack,"dimensions/host.json"),"JSON",ReferenceDiagnostics.State.UNSUPPORTED);
        pack.replace("dimensions/host.json",bytes("{\"regions\":[],\"focus\":\"copied\"}"));state(ReferenceDiagnostics.inspect(pack,"dimensions/host.json"),"focus",ReferenceDiagnostics.State.NO_EXACT_FILE);pack.duplicate("biomes/b.json","copied");state(ReferenceDiagnostics.inspect(pack,"dimensions/host.json"),"focus",ReferenceDiagnostics.State.EXACT_FILE);
        check(ReferenceDiagnostics.inspect(pack,"objects/nested/a.iob").isEmpty());
        System.out.println("PASS: bounded exact selector/stack/floating/object reference links, malformed-list isolation, native sentinel distinctions, immutable nonmutating inspection and catalog refresh");
    }
}
