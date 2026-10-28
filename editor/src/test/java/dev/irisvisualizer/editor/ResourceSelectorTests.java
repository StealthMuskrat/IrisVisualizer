package dev.irisvisualizer.editor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

final class ResourceSelectorTests {
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static String text(byte[] value) { return new String(value,StandardCharsets.UTF_8); }
    private static void check(boolean value) { if (!value) throw new AssertionError("Resource selector contract failed"); }
    private static void rejects(Runnable action) { try { action.run(); } catch (IllegalArgumentException expected) { return; }throw new AssertionError("Expected rejected selector operation"); }
    static void run(Path temp) throws Exception {
        var pack=PackWorkspace.newPack(Map.of("dimensions/host.json",bytes("{}"),"biomes/self.json",bytes("{}"),"biomes/nested/target.json",bytes("opaque malformed target"),"regions/nested/region.json",bytes("{}"),"biomes/upper.JSON",bytes("{}"),"images/opaque.png",new byte[]{0,1,2}));
        check(ResourceSelectors.fields("dimensions/host.json").equals(List.of("focus","focusRegion")) && ResourceSelectors.fields("biomes/nested/target.json").equals(List.of("carvingBiome")) && ResourceSelectors.fields("dimensions/nested/host.json").isEmpty());
        for (String field : List.of("focus","focusRegion","carvingBiome")) {
            String path=field.equals("carvingBiome") ? "biomes/self.json" : "dimensions/host.json",key=field.equals("focusRegion") ? "nested/region" : "nested/target";
            byte[] original=bytes("{\r\n \""+field+"\":\"legacy:missing\",\"future\":9007199254740993,\"escaped\":\"雪\\u0061\"\r\n}\r\n");String raw=text(original);
            check(text(ResourceSelectors.set(path,original,field,key,pack)).equals(raw.replace("legacy:missing",key)));
            check(text(ResourceSelectors.set(path,original,field,null,pack)).equals(raw.replace("legacy:missing","")));
            check(Arrays.equals(bytes("{}"),ResourceSelectors.set(path,bytes("{}"),field,null,pack)));
            check(new LosslessJson(ResourceSelectors.set(path,bytes("{}"),field,key,pack)).stringToken(field).equals(key));
            for (String invalid : List.of("missing","../target","alias:target",key+".json",key.toUpperCase(Locale.ROOT),"upper","")) rejects(() -> ResourceSelectors.set(path,original,field,invalid,pack));
            for (String token : List.of("null","[]","{}","true","1")) {
                byte[] wrong=bytes("{\""+field+"\":"+token+"}");check(!ResourceSelectors.supported(new LosslessJson(wrong),field));rejects(() -> ResourceSelectors.set(path,wrong,field,key,pack));rejects(() -> ResourceSelectors.set(path,wrong,field,null,pack));
                if (!field.equals("carvingBiome")) check(DimensionFields.readOnlyPaths(new LosslessJson(wrong)).contains(field));
            }
            if (!field.equals("carvingBiome")) check(!DimensionFields.readOnlyPaths(new LosslessJson(original)).contains(field));
            check(ResourceSelectors.help(field)!=null && !ResourceSelectors.help(field).isBlank());
        }
        byte[] dual=bytes("{\"focus\":\"self\",\"focusRegion\":\"nested/region\"}");
        check(new LosslessJson(ResourceSelectors.set("dimensions/host.json",dual,"focus",null,pack)).stringToken("focusRegion").equals("nested/region"));
        check(new LosslessJson(ResourceSelectors.set("biomes/self.json",bytes("{}"),"carvingBiome","self",pack)).stringToken("carvingBiome").equals("self"));
        rejects(() -> ResourceSelectors.set("regions/nested/region.json",bytes("{}"),"focus","self",pack));rejects(() -> ResourceSelectors.set("biomes/self.json",bytes("{}"),"focus","self",pack));
        var cache=new ResourceSelectors.CatalogCache();var first=cache.choices(pack,"focus");check(first==cache.choices(pack,"carvingBiome") && first.equals(List.of("nested/target","self")));
        pack.replace("biomes/self.json",ResourceSelectors.set("biomes/self.json",bytes("{}"),"carvingBiome","nested/target",pack));check(first==cache.choices(pack,"focus"));
        pack.duplicate("biomes/self.json","copied");check(first!=cache.choices(pack,"focus") && cache.choices(pack,"focus").contains("copied"));
        var other=PackWorkspace.newPack(Map.of("biomes/other.json",bytes("{}")));check(cache.choices(other,"focus").equals(List.of("other")));
        var drafts=new InspectorDrafts();drafts.update("outside-selector#zoom","1","2");pack.replace("dimensions/host.json",ResourceSelectors.set("dimensions/host.json",dual,"focus","self",pack));check(drafts.count()==1);
        pack.save(temp.resolve("resource-selectors"));pack.exportZip(temp.resolve("resource-selectors.zip"));var reopened=PackWorkspace.open(temp.resolve("resource-selectors"));var imported=PackWorkspace.importZip(temp.resolve("resource-selectors.zip"));
        for (String path : pack.paths()) check(Arrays.equals(pack.read(path),reopened.read(path)) && Arrays.equals(pack.read(path),imported.read(path)));
        for (String group : List.of("dimension","biome")) for (var field : FieldCoverage.fields(group)) if (ResourceSelectors.fields(group.equals("dimension") ? "dimensions/host.json" : "biomes/self.json").contains(field.name())) check(field.implemented());
        System.out.println("PASS: exact focus/region/carving selectors, malformed-state guards, independent clears, raw-byte preservation, catalog filename revisions and lossless Save/ZIP");
    }
}
