package dev.irisvisualizer.editor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Deferred end-of-UI contracts for the native deposit filter's load-key/derivative OR comparison. */
final class ResourceOperationDepositTests {
    private static byte[] bytes(String text){return text.getBytes(StandardCharsets.UTF_8);}
    private static void check(boolean value,String detail){if(!value)throw new AssertionError(detail);}
    private static String text(PackWorkspace pack,String path){return new String(pack.read(path),StandardCharsets.UTF_8);}
    private static ResourceOperation.Prepared rename(PackWorkspace pack,String target)throws Exception{return ResourceOperationTests.prepare(pack,ResourceOperation.Mode.RENAME,"biomes/forest.json",target);}
    private static PackWorkspace pack(String selector,String sourceFields,String other)throws Exception{
        var files=new LinkedHashMap<String,byte[]>();
        files.put("biomes/forest.json",bytes("{"+sourceFields+"\"deposits\":[{\"includedBiomes\":["+LosslessJson.quote(selector)+"]}]}"));
        if(other!=null)files.put("biomes/other.json",bytes(other));
        files.put("generators/free.json",bytes("{}"));return PackWorkspace.newPack(files);
    }
    static void run(Path temp)throws Exception{
        String biome="{\r\n\"name\":\"forest\",\"derivative\":\"minecraft:plains\",\"deposits\":[{\"includedBiomes\":[\"fo\\u0072est\",\"minecraft:forest\",\" plains \"],\"excludedBiomes\":[\"other\"]}]\r\n}";
        String region="{\"landBiomes\":[\"forest\"],\"deposits\":[{\"excludedBiomes\":[\"forest\"]}]}";
        String dimension="{\"regions\":[\"land\"],\"deposits\":[{\"includedBiomes\":[\"forest\"]}]}";
        byte[] opaque=bytes("{\"block\":\"minecraft:stone\",\"data\":{\"large\":9007199254740993,\"name\":\"forest\"}}");
        var source=Map.of("biomes/forest.json",bytes(biome),"biomes/other.json",bytes("{\"derivative\":\"custom:forest\"}"),"regions/land.json",bytes(region),"dimensions/world.json",bytes(dimension),"blocks/opaque.json",opaque,"images/source.png",new byte[]{0,-1,1});
        var canonical=PackWorkspace.newPack(source);Path original=temp.resolve("deposit-reference-original");canonical.save(original);
        var prepared=rename(canonical,"grove");check(prepared.report.permitted(),prepared.report.issues().toString());canonical.installResourceOperation(prepared);
        check(text(canonical,"biomes/grove.json").equals(biome.replace("\"fo\\u0072est\"","\"grove\"")),"only the typed escaped self-reference changes; names, whitespace and derivative selectors stay exact");
        check(text(canonical,"regions/land.json").equals(region.replace("\"forest\"","\"grove\"")) && text(canonical,"dimensions/world.json").equals(dimension.replace("\"forest\"","\"grove\"")),"dimension and region deposit owners follow the same complete key");
        canonical.createSubfolder("biomes","nested");ResourceOperationTests.apply(canonical,ResourceOperation.Mode.MOVE,"biomes/grove.json","biomes/nested");
        check(text(canonical,"biomes/nested/grove.json").contains("\"includedBiomes\":[\"nested/grove\",\"minecraft:forest\""),"moved deposit references use complete relative biome keys");
        check(!ResourceOperationTests.prepare(canonical,ResourceOperation.Mode.DELETE,"biomes/nested/grove.json",null).report.permitted(),"deposit comparison references protect deletion");
        canonical.save(original);check(!Files.exists(original.resolve("biomes/forest.json")) && Files.exists(original.resolve("biomes/nested/grove.json")),"Save publishes the moved graph and removes the baseline-bound old file");
        canonical.saveCopy(temp.resolve("deposit-reference-copy"));canonical.saveAs(temp.resolve("deposit-reference-as"));canonical.exportZip(temp.resolve("deposit-reference.zip"));
        for(var reopened:List.of(PackWorkspace.open(original),PackWorkspace.open(temp.resolve("deposit-reference-copy")),PackWorkspace.open(temp.resolve("deposit-reference-as")),PackWorkspace.importZip(temp.resolve("deposit-reference.zip")))){
            check(reopened.paths().equals(canonical.paths()),"all persistence modes retain the exact resource graph");
            for(String path:canonical.paths())check(Arrays.equals(reopened.read(path),canonical.read(path)),"byte-exact persistence: "+path);
        }
        check(Arrays.equals(opaque,canonical.read("blocks/opaque.json")),"opaque large integers and matching ordinary text remain untouched");
        for(String derivative:List.of("forest"," minecraft:forest ","grove"," grove ")){
            var collision=pack("forest","\"derivative\":\"minecraft:plains\",","{\"derivative\":"+LosslessJson.quote(derivative)+"}");
            var blocked=rename(collision,"grove");check(!blocked.report.permitted(),"source/replacement derivative collision is guarded: "+derivative);ResourceOperationTests.rejects(()->collision.installResourceOperation(blocked));
            check(ResourceOperationTests.prepare(collision,ResourceOperation.Mode.RENAME,"generators/free.json","other").report.permitted(),"biome ambiguity does not block unrelated family maintenance");
        }
        for(String vanilla:List.of("forest"," grove ")){
            var collision=pack("forest","\"derivative\":\"minecraft:plains\",","{\"derivative\":\"custom:unrelated\",\"vanillaDerivative\":"+LosslessJson.quote(vanilla)+"}");
            check(!rename(collision,"grove").report.permitted(),"vanilla override identity participates in collision review");
        }
        check(!rename(pack("forest","",null),"the_void").report.permitted(),"an omitted derivative contributes the pinned native default without inserting JSON");
        var explicitNull=pack("forest","\"derivative\":null,\"vanillaDerivative\":null,",null);
        check(!rename(explicitNull,"the_void").report.permitted(),"the native empty-cave fallback contributes its default even when all stored derivatives are explicit null");
        ResourceOperationTests.apply(explicitNull,ResourceOperation.Mode.RENAME,"biomes/forest.json","grove");
        check(text(explicitNull,"biomes/grove.json").contains("\"derivative\":null,\"vanillaDerivative\":null"),"explicit null fields remain null rather than being materialized as defaults");
        check(rename(pack("forest","\"derivative\":\"MINECRAFT:forest\",",null),"grove").report.permitted(),"native derivative comparison remains case-sensitive");
        var namespaced=pack("minecraft:forest","\"derivative\":\"minecraft:forest\",",null);ResourceOperationTests.apply(namespaced,ResourceOperation.Mode.RENAME,"biomes/forest.json","grove");
        check(text(namespaced,"biomes/grove.json").contains("\"includedBiomes\":[\"minecraft:forest\"]"),"namespaced derivative meaning never becomes a file lookup");
        check(rename(pack("plains","\"derivative\":\"minecraft:plains\",",null),"grove").report.permitted(),"an unchanged bare derivative selector does not block an unrelated key change");
        check(!rename(pack("grove","\"derivative\":\"minecraft:plains\",",null),"grove").report.permitted(),"formerly missing selector identity cannot be captured by a new name");
        check(!rename(pack("FOREST","\"derivative\":\"minecraft:plains\",",null),"grove").report.permitted(),"comparison aliases cannot borrow case-insensitive loader meaning");
        var aliasFiles=new LinkedHashMap<String,byte[]>(source);aliasFiles.put("regions/land.json",bytes("{\"landBiomes\":[\"FOREST\"]}"));
        check(!rename(PackWorkspace.newPack(aliasFiles),"grove").report.permitted(),"incoming loader aliases cannot collapse distinct raw comparison identities");
        var snippet=PackWorkspace.newPack(Map.of("biomes/forest.json",bytes("{\"derivative\":\"minecraft:plains\"}"),"dimensions/a.json",bytes("{\"deposits\":[\"snippet/shared\",\"snippet/deposit/shared\"]}"),"snippet/deposit/shared.json",bytes("{\"includedBiomes\":[\"forest\"],\"excludedBiomes\":[\"minecraft:grove\"]}")));
        ResourceOperationTests.apply(snippet,ResourceOperation.Mode.RENAME,"biomes/forest.json","grove");
        check(text(snippet,"snippet/deposit/shared.json").equals("{\"includedBiomes\":[\"grove\"],\"excludedBiomes\":[\"minecraft:grove\"]}") && text(snippet,"dimensions/a.json").contains("\"snippet/shared\",\"snippet/deposit/shared\""),"shared deposit snippets are maintained once without changing indirections or derivative strings");
        System.out.println("PASS: canonical deposit biome filter rename/move, source/target derivative and vanilla/default collision guards, namespace/case/null preservation, alias and missing-name capture protection, typed snippet maintenance, referenced deletion and exact Save/Copy/As/ZIP");
    }
}
