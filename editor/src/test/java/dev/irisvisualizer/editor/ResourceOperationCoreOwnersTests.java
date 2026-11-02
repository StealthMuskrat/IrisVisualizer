package dev.irisvisualizer.editor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.List;

/** Prepared for the end-of-UI validation. These contracts do not authorize native previews. */
final class ResourceOperationCoreOwnersTests {
    private static byte[] bytes(String value){return ResourceOperationTests.bytes(value);}
    private static void check(boolean value,String message){ResourceOperationTests.check(value,message);}
    private static ResourceOperation.Prepared rename(PackWorkspace pack,String path,String name)throws Exception{return ResourceOperationTests.prepare(pack,ResourceOperation.Mode.RENAME,path,name);}
    private static String text(PackWorkspace pack,String path){return new String(pack.read(path),StandardCharsets.UTF_8);}
    private static PackWorkspace fixture()throws Exception{
        return PackWorkspace.newPack(Map.of(
            "dimensions/world.json",bytes("{\r\n  \"name\":\"forest\",\"regions\":[\"land\"],\"focusRegion\":\"land\",\"focus\":\"forest\",\"upperDimension\":\"upper\",\"dimensionStack\":{\"dimensions\":[\"upper\",\"world\"]},\"carving\":[{\"id\":\"forest\",\"biome\":\" forest \"}],\"treeSettings\":{\"enabled\":true,\"mode\":\"FIRST\"},\"imageMaps\":[{\"key\":\"named_binding\",\"map\":\" categorical \",\"application\":\"BIOME\",\"masks\":[{\"map\":\"categorical\"}]}],\"disabledComponents\":[\"CUSTOM:1\"]\r\n}"),
            "dimensions/upper.json",bytes("{}"),
            "regions/land.json",bytes("{\"name\":\"forest\",\"landBiomes\":[\"forest\"],\"seaBiomes\":[\"forest\"],\"shoreBiomes\":[\"forest\"],\"caveBiomes\":[\"forest\"],\"objects\":[{\"place\":[\"oak\"]}]}"),
            "biomes/forest.json",bytes("{\"name\":\"forest\",\"children\":[\"forest\"],\"carvingBiome\":\"forest\",\"generators\":[{\"generator\":\"authored\",\"min\":9007199254740993}],\"floatingChildBiomes\":[{\"biome\":\"forest\"}],\"riverPolicy\":{\"surfaceBiomes\":[\"forest\"]},\"structures\":[{\"structures\":[\"castle\"]}]}"),
            "generators/authored.json",bytes("{\"seed\":9007199254740993}"),
            "image-maps/categorical.json",bytes("{\"source\":\"pixels\",\"colors\":{\"#ffffff\":\" iris:forest \",\"#000000\":\"forest\"},\"fallbackTarget\":\"iris:forest\"}"),
            "images/pixels.png",new byte[]{0,-1,7},"objects/oak.iob",new byte[]{0,-1,8},"structures/castle.json",bytes("{}")));
    }
    static void run(Path temp)throws Exception{
        var pack=fixture();String dimension=text(pack,"dimensions/world.json"),legend=text(pack,"image-maps/categorical.json");
        var prepared=rename(pack,"biomes/forest.json","woodland");check(prepared.report.permitted(),prepared.report.issues().toString());
        check(prepared.report.references().stream().anyMatch(r->r.pointer().equals("/colors/#ffffff") && r.key().equals("forest") && r.family().equals("biomes")),"categorical keys use their dimension binding family");
        pack.installResourceOperation(prepared);
        check(text(pack,"dimensions/world.json").equals(dimension.replace("\"focus\":\"forest\"","\"focus\":\"woodland\"").replace("\"biome\":\" forest \"","\"biome\":\" woodland \"")),"dimension focus and trimmed carving biome only; names, entry IDs, layout and named masks untouched");
        check(new LosslessJson(pack.read("biomes/woodland.json")).stringToken("name").equals("forest") && text(pack,"biomes/woodland.json").contains("9007199254740993"),"owner name and exact long unchanged");
        check(text(pack,"image-maps/categorical.json").equals(legend.replace("iris:forest","iris:woodland").replace("\"forest\"","\"woodland\"")),"bare and iris-prefixed categorical links preserve surrounding spaces");
        ResourceOperationTests.apply(pack,ResourceOperation.Mode.RENAME,"regions/land.json","continent");
        check(text(pack,"dimensions/world.json").contains("\"regions\":[\"continent\"]") && text(pack,"dimensions/world.json").contains("\"focusRegion\":\"continent\""),"dimension region pool/focus maintained");
        ResourceOperationTests.apply(pack,ResourceOperation.Mode.RENAME,"dimensions/upper.json","sky");
        check(text(pack,"dimensions/world.json").contains("\"upperDimension\":\"sky\"") && text(pack,"dimensions/world.json").contains("\"dimensions\":[\"sky\",\"world\"]"),"legacy and explicit dimension-stack links maintained");
        ResourceOperationTests.apply(pack,ResourceOperation.Mode.RENAME,"dimensions/world.json","home");
        check(text(pack,"dimensions/home.json").contains("\"dimensions\":[\"sky\",\"home\"]"),"dimension self-stack link follows owner rename");
        ResourceOperationTests.apply(pack,ResourceOperation.Mode.RENAME,"generators/authored.json","terrain");
        check(text(pack,"biomes/woodland.json").contains("\"generator\":\"terrain\""),"biome generator links maintained");
        ResourceOperationTests.apply(pack,ResourceOperation.Mode.RENAME,"structures/castle.json","keep");
        check(text(pack,"biomes/woodland.json").contains("\"structures\":[\"keep\"]"),"dedicated RegistryListStructure links maintained");
        ResourceOperationTests.apply(pack,ResourceOperation.Mode.RENAME,"objects/oak.iob","tree");
        check(text(pack,"regions/continent.json").contains("\"place\":[\"tree\"]"),"region nested object pool maintained");
        ResourceOperationTests.apply(pack,ResourceOperation.Mode.RENAME,"image-maps/categorical.json","renamed_map");
        var binding=new LosslessJson(pack.read("dimensions/home.json")).objectArray("imageMaps").getFirst();
        check(binding.stringToken("map").equals(" renamed_map ") && binding.objectArray("masks").getFirst().stringToken("map").equals("categorical"),"resource map key and named mask binding key are distinct domains");
        pack.createSubfolder("biomes","nested");ResourceOperationTests.apply(pack,ResourceOperation.Mode.MOVE,"biomes/woodland.json","biomes/nested");
        check(text(pack,"image-maps/renamed_map.json").contains("iris:nested/woodland"),"categorical namespace retains complete moved resource key");
        check(!ResourceOperationTests.prepare(pack,ResourceOperation.Mode.DELETE,"biomes/nested/woodland.json",null).report.permitted(),"referenced core-owner deletion remains blocked");
        pack.save(temp.resolve("core-resource-owners"));pack.exportZip(temp.resolve("core-resource-owners.zip"));
        var reopened=PackWorkspace.open(temp.resolve("core-resource-owners"));var imported=PackWorkspace.importZip(temp.resolve("core-resource-owners.zip"));
        for(String path:pack.paths())check(Arrays.equals(pack.read(path),reopened.read(path)) && Arrays.equals(pack.read(path),imported.read(path)),"core owner graph Save/reopen/ZIP byte preservation");
        // Null/blank generator links call the default loader rather than disabling the link.
        for(String owner:List.of("{}","{\"generators\":[{}]}","{\"generators\":[{\"generator\":null}]}","{\"generators\":[{\"generator\":\"  \"}]}")){
            var defaults=PackWorkspace.newPack(Map.of("biomes/a.json",bytes(owner),"generators/default.json",bytes("{}")));
            var blocked=rename(defaults,"generators/default.json","other");check(!blocked.report.permitted() && blocked.report.references().stream().anyMatch(r->r.implicit() && "generators/default.json".equals(r.resolved())),"omitted/null/blank generator fallback participates without materialization");
            check(text(defaults,"biomes/a.json").equals(owner),"implicit fallback report leaves source untouched");
        }
        var shadow=PackWorkspace.newPack(Map.of("biomes/a.json",bytes("{}"),"generators/other.json",bytes("{}")));
        check(!rename(shadow,"generators/other.json","default").report.permitted(),"creating a formerly missing implicit generator changes resolution");
        // A clear/zero palette factory cannot acquire the nonempty class default again.
        var zero=PackWorkspace.newPack(Map.of("biomes/a.json",bytes("{\"layers\":[]}"),"blocks/grass_block.json",bytes("{\"block\":\"minecraft:grass_block\"}")));
        check(rename(zero,"blocks/grass_block.json","other").report.permitted(),"empty explicit biome layers plus slab/wall zero factories do not invent GRASS_BLOCK refs");
        var implicitLayer=PackWorkspace.newPack(Map.of("biomes/a.json",bytes("{}"),"blocks/grass_block.json",bytes("{\"block\":\"minecraft:grass_block\"}")));
        check(!rename(implicitLayer,"blocks/grass_block.json","other").report.permitted(),"omitted biome surface layer contributes its GRASS_BLOCK default");
        for(String key:List.of("stone","water","sand","clay","dirt")){
            var defaults=PackWorkspace.newPack(Map.of("dimensions/a.json",bytes("{}"),"blocks/"+key+".json",bytes("{\"block\":\"minecraft:stone\"}")));
            check(!rename(defaults,"blocks/"+key+".json","other").report.permitted(),"nested dimension/hydrology/palette default contributes "+key);
        }
        var legacy=PackWorkspace.newPack(Map.of("dimensions/a.json",bytes("{\"upperDimension\":\"none\"}"),"dimensions/none.json",bytes("{}")));
        check(rename(legacy,"dimensions/none.json","other").report.permitted(),"inactive none sentinel never binds to a same-named file");
        var sentinel=PackWorkspace.newPack(Map.of("dimensions/a.json",bytes("{\"upperDimension\":\"upper\"}"),"dimensions/upper.json",bytes("{}")));
        check(!rename(sentinel,"dimensions/upper.json","none").report.permitted(),"rename cannot turn active legacy dimension link into none sentinel");
        for(String field:List.of("\"deposits\":[{\"includedBiomes\":[\"forest\"]}]","\"structures\":[{\"caveBiomes\":[\"forest\"]}]")){
            var guarded=PackWorkspace.newPack(Map.of("biomes/forest.json",bytes("{"+field+"}"),"objects/unused.iob",new byte[]{1}));
            check(!rename(guarded,"biomes/forest.json","other").report.permitted(),"nonempty dual-domain/prefix biome comparisons stay guarded");
            check(rename(guarded,"objects/unused.iob","other").report.permitted(),"family guard does not block unrelated object maintenance");
        }
        var provider=PackWorkspace.newPack(Map.of("dimensions/a.json",bytes("{\"terrainTransform\":{\"id\":\"external\",\"version\":\"1\",\"settings\":{\"possibleResource\":\"shape\"}}}"),"objects/shape.iob",new byte[]{1}));
        check(!rename(provider,"objects/shape.iob","other").report.permitted(),"external providers cannot certify their pack dependencies from string settings");
        imageContexts();
        System.out.println("PASS: closed dimension/region/biome owner graph, nested exact and dedicated structure references, trimmed keys/named-mask distinction, categorical binding namespaces and mixed-use guards, implicit/default/null/blank generator and nested palette fallbacks without materialization, dimension sentinels, guarded dual-domain/prefix comparisons, graph move/delete and byte-exact Save/reopen/ZIP");
    }
    private static void imageContexts()throws Exception{
        for(String application:List.of("BIOME","REGION")){
            String family=application.equals("BIOME")?"biomes":"regions";
            var pack=PackWorkspace.newPack(Map.of("dimensions/a.json",bytes("{\"imageMaps\":[{\"map\":\"map\",\"application\":\""+application+"\"}]}"),family+"/target.json",bytes("{}"),"image-maps/map.json",bytes("{\"colors\":{\"#ffffff\":\"iris:target\"},\"fallbackTarget\":\"target\"}")));
            ResourceOperationTests.apply(pack,ResourceOperation.Mode.RENAME,family+"/target.json","other");check(text(pack,"image-maps/map.json").contains("iris:other"),"both categorical families follow unambiguous bindings");
        }
        for(String extra:List.of("","{\"map\":\"map\",\"application\":\"REGION\"}","{\"map\":\"map\",\"application\":\"SURFACE_BLOCK\"}","{\"map\":\"map\",\"application\":\"unknown\"}")){
            String bindings=extra.isEmpty()?"[]":"[{\"map\":\"map\",\"application\":\"BIOME\"},"+extra+"]";
            var pack=PackWorkspace.newPack(Map.of("dimensions/a.json",bytes("{\"imageMaps\":"+bindings+"}"),"biomes/target.json",bytes("{}"),"image-maps/map.json",bytes("{\"colors\":{\"#ffffff\":\"target\"}}")));
            check(!rename(pack,"biomes/target.json","other").report.permitted(),"unbound or mixed categorical binding never grants guessed family authority");
        }
        var aliasRole=PackWorkspace.newPack(Map.of("dimensions/a.json",bytes("{\"regions\":[\"land\"],\"imageMaps\":[{\"map\":\"map\",\"application\":\"BIOME\"}]}"),"regions/land.json",bytes("{\"landBiomes\":[\"target\"],\"seaBiomes\":[\"TARGET\"]}"),"biomes/target.json",bytes("{}"),"image-maps/map.json",bytes("{\"fallbackTarget\":\"target\"}")));
        check(!rename(aliasRole,"biomes/target.json","other").report.permitted(),"categorical role cannot collapse previously unequal region-pool aliases into two matching native roles");
        var regionComparison=PackWorkspace.newPack(Map.of("dimensions/a.json",bytes("{\"subterrainFeatures\":[{\"allowedRegions\":[\"land\"]}]}"),"regions/land.json",bytes("{}")));
        check(rename(regionComparison,"regions/land.json","other").report.permitted(),"canonical exact region comparison now has certified reference maintenance");
        var nativeOnly=PackWorkspace.newPack(Map.of("dimensions/a.json",bytes("{\"imageMaps\":[{\"map\":\"map\",\"application\":\"SURFACE_BLOCK\"}]}"),"biomes/target.json",bytes("{}"),"image-maps/map.json",bytes("{\"colors\":{\"#ffffff\":\"target\"}}")));
        byte[] legend=nativeOnly.read("image-maps/map.json");ResourceOperationTests.apply(nativeOnly,ResourceOperation.Mode.RENAME,"biomes/target.json","other");check(Arrays.equals(legend,nativeOnly.read("image-maps/map.json")),"surface-block categories are native registry IDs, not pack biome aliases");
        var scalar=PackWorkspace.newPack(Map.of("dimensions/a.json",bytes("{\"imageMaps\":[{\"map\":\"map\",\"application\":\"BIOME\"}]}"),"biomes/target.json",bytes("{}"),"generators/a.json",bytes("{\"composite\":[{\"style\":{\"imageMap\":\"map\"}}]}"),"image-maps/map.json",bytes("{\"fallbackTarget\":\"target\"}")));
        check(!rename(scalar,"biomes/target.json","other").report.permitted(),"mixed scalar and categorical consumers retain conservative guard");
        for(String value:List.of("minecraft:target","Iris:target","iris:")){
            var invalid=PackWorkspace.newPack(Map.of("dimensions/a.json",bytes("{\"imageMaps\":[{\"map\":\"map\",\"application\":\"BIOME\"}]}"),"biomes/target.json",bytes("{}"),"image-maps/map.json",bytes("{\"fallbackTarget\":\""+value+"\"}")));
            check(!rename(invalid,"biomes/target.json","other").report.permitted(),"unsupported categorical namespaces block confidence");
        }
    }
}
