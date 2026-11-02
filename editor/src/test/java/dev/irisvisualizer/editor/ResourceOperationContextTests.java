package dev.irisvisualizer.editor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.List;
import java.util.TreeMap;

/** Deferred end-of-UI contracts for consumer identity, symbolic namespaces and lookup modes. */
final class ResourceOperationContextTests {
    private static byte[] bytes(String text){return ResourceOperationTests.bytes(text);}
    private static void check(boolean value,String detail){ResourceOperationTests.check(value,detail);}
    private static String text(PackWorkspace pack,String path){return new String(pack.read(path),StandardCharsets.UTF_8);}
    private static ResourceOperation.Prepared rename(PackWorkspace pack,String path,String key)throws Exception{return ResourceOperationTests.prepare(pack,ResourceOperation.Mode.RENAME,path,key);}
    static void run(Path temp)throws Exception{
        exactRegions(temp);carvingNamespaces(temp);lookupModes(temp);symbolicBudgets();
        System.out.println("PASS: canonical exact region filter rename/move with raw load-key alias and missing-name capture protection; dimension/snippet carving ID precedence with trim/case semantics, fallback biome maintenance and source/target collision guards; quiet block literal-null aliases and uncertain reserved lookup modes; finite symbolic context budgets, referenced deletion and exact Save/reopen/ZIP preservation");
    }
    private static void exactRegions(Path temp)throws Exception{
        String dimension="{\r\n\"name\":\"land\",\"regions\":[\"land\"],\"focusRegion\":\"land\",\"subterrainFeatures\":[{\"allowedRegions\":[\"land\",\"other\"]}]\r\n}";
        var pack=PackWorkspace.newPack(Map.of("dimensions/world.json",bytes(dimension),"regions/land.json",bytes("{\"name\":\"land\"}"),"regions/other.json",bytes("{}")));
        var plan=rename(pack,"regions/land.json","ground");check(plan.report.permitted(),plan.report.issues().toString());
        check(plan.report.changedFiles()==1 && plan.report.references().stream().filter(r->r.replacement()!=null).count()==3,"region pool/focus/exact set consumer share one rewritten owner");pack.installResourceOperation(plan);
        check(text(pack,"dimensions/world.json").equals(dimension.replace("\"regions\":[\"land\"]","\"regions\":[\"ground\"]").replace("\"focusRegion\":\"land\"","\"focusRegion\":\"ground\"").replace("\"allowedRegions\":[\"land\",","\"allowedRegions\":[\"ground\",")),"only typed lookup/comparison strings change; name and formatting stay exact");
        pack.createSubfolder("regions","nested");ResourceOperationTests.apply(pack,ResourceOperation.Mode.MOVE,"regions/ground.json","regions/nested");
        check(text(pack,"dimensions/world.json").contains("\"allowedRegions\":[\"nested/ground\",\"other\"]"),"exact comparisons follow complete moved resource keys");
        check(!ResourceOperationTests.prepare(pack,ResourceOperation.Mode.DELETE,"regions/nested/ground.json",null).report.permitted(),"comparison/lookup consumers retain incoming deletion protection");roundTrip(pack,temp,"exact-region-consumers");
        for(String value:List.of("LAND","land")){
            String lookup=value.equals("land")?"LAND":"land";
            var aliases=PackWorkspace.newPack(Map.of("dimensions/a.json",bytes("{\"regions\":[\""+lookup+"\"],\"subterrainFeatures\":[{\"allowedRegions\":[\""+value+"\"]}]}"),"regions/land.json",bytes("{}")));
            check(!rename(aliases,"regions/land.json","next").report.permitted(),"case-insensitive loading must not collapse unequal exact-name identities");
        }
        var dotted=PackWorkspace.newPack(Map.of("dimensions/a.json",bytes("{\"regions\":[\"land\"],\"subterrainFeatures\":[{\"allowedRegions\":[\"land.disabled\"]}]}"),"regions/land.disabled.json",bytes("{}")));
        check(!rename(dotted,"regions/land.disabled.json","next").report.permitted(),"root dotted alias collapse cannot change an exact comparison");
        var harmless=PackWorkspace.newPack(Map.of("dimensions/a.json",bytes("{\"regions\":[\"LAND\"],\"subterrainFeatures\":[{\"allowedRegions\":[\"other\"]}]}"),"regions/land.json",bytes("{}"),"regions/other.json",bytes("{}")));
        check(rename(harmless,"regions/land.json","next").report.permitted(),"unrelated exact comparisons do not block a normal loader alias rename");
        var capture=PackWorkspace.newPack(Map.of("dimensions/a.json",bytes("{\"subterrainFeatures\":[{\"allowedRegions\":[\"next\"]}]}"),"regions/land.json",bytes("{}")));
        check(!rename(capture,"regions/land.json","next").report.permitted(),"formerly missing comparison identity cannot start selecting the renamed region");
        var snippet=PackWorkspace.newPack(Map.of("dimensions/a.json",bytes("{\"subterrainFeatures\":[\"snippet/feature\"]}"),"snippet/subterrain-feature/feature.json",bytes("{\"allowedRegions\":[\"land\"]}"),"regions/land.json",bytes("{}")));
        ResourceOperationTests.apply(snippet,ResourceOperation.Mode.RENAME,"regions/land.json","ground");check(text(snippet,"snippet/subterrain-feature/feature.json").equals("{\"allowedRegions\":[\"ground\"]}"),"shared exact-comparison snippet payload is maintained without touching its indirection");
    }
    private static PackWorkspace carving(String entryId,String reference,String otherDimension)throws Exception{
        var files=new TreeMap<String,byte[]>();files.put("biomes/forest.json",bytes("{\"name\":\"forest\",\"floatingChildBiomes\":[{\"biome\":\"forest\",\"carving\":"+LosslessJson.quote(reference)+"}]}"));
        files.put("dimensions/a.json",bytes("{\"carving\":[{\"id\":"+LosslessJson.quote(entryId)+",\"biome\":\"forest\"}]}"));
        if(otherDimension!=null)files.put("dimensions/b.json",bytes(otherDimension));return PackWorkspace.newPack(files);
    }
    private static void carvingNamespaces(Path temp)throws Exception{
        var fallback=carving("cave_entry","forest",null);ResourceOperationTests.apply(fallback,ResourceOperation.Mode.RENAME,"biomes/forest.json","grove");
        check(text(fallback,"biomes/grove.json").contains("\"carving\":\"grove\"") && text(fallback,"biomes/grove.json").contains("\"name\":\"forest\""),"unambiguous fallback biome token maintained while ordinary name remains exact");
        check(text(fallback,"dimensions/a.json").equals("{\"carving\":[{\"id\":\"cave_entry\",\"biome\":\"grove\"}]}"),"entry ID is not a biome key; its separate biome link is maintained");
        fallback.createSubfolder("biomes","nested");ResourceOperationTests.apply(fallback,ResourceOperation.Mode.MOVE,"biomes/grove.json","biomes/nested");check(text(fallback,"biomes/nested/grove.json").contains("\"carving\":\"nested/grove\""),"fallback carving links follow moved complete biome keys");roundTrip(fallback,temp,"carving-fallback-links");
        var identifier=carving("cave_entry","cave_entry",null);ResourceOperationTests.apply(identifier,ResourceOperation.Mode.RENAME,"biomes/forest.json","grove");
        check(text(identifier,"biomes/grove.json").contains("\"carving\":\"cave_entry\"") && text(identifier,"dimensions/a.json").contains("\"biome\":\"grove\""),"symbolic entry name stays unchanged while entry's biome link follows rename");
        for(String id:List.of("forest"," forest ","grove"," grove ")){
            var collision=carving(id,"forest","{}");var plan=rename(collision,"biomes/forest.json","grove");check(!plan.report.permitted(),"source/replacement collisions honor native trimmed ID precedence across dimensions");ResourceOperationTests.rejects(()->collision.installResourceOperation(plan));
        }
        var caseSensitive=carving("GROVE","FOREST",null);check(rename(caseSensitive,"biomes/forest.json","grove").report.permitted(),"symbolic ID matching stays case-sensitive even though file resolution is case-insensitive");
        var snippet=PackWorkspace.newPack(Map.of("dimensions/a.json",bytes("{\"carving\":[\"snippet/entry\"]}"),"snippet/dimension-carving-entry/entry.json",bytes("{\"id\":\" grove \",\"biome\":\"forest\"}"),"biomes/forest.json",bytes("{\"floatingChildBiomes\":[{\"carving\":\"forest\"}]}")));
        check(!rename(snippet,"biomes/forest.json","grove").report.permitted(),"typed snippet IDs contribute to the complete precedence context");
        var capture=PackWorkspace.newPack(Map.of("biomes/forest.json",bytes("{\"floatingChildBiomes\":[{\"carving\":\"grove\"}]}")));
        check(!rename(capture,"biomes/forest.json","grove").report.permitted(),"missing fallback lookup cannot be captured by a new resource name");
        var standardNull=PackWorkspace.newPack(Map.of("biomes/null.json",bytes("{\"floatingChildBiomes\":[{\"carving\":\"null\"}]}")));
        check(rename(standardNull,"biomes/null.json","other").report.permitted(),"known standard carving fallback never treats literal null as a quiet biome lookup");
        check(!ResourceOperationTests.prepare(identifier,ResourceOperation.Mode.DELETE,"biomes/grove.json",null).report.permitted(),"symbolic entry's independent biome reference still blocks deletion");
    }
    private static void lookupModes(Path temp)throws Exception{
        var quiet=PackWorkspace.newPack(Map.of("blocks/null.json",bytes("{\"block\":\"minecraft:stone\"}"),"blocks/use.json",bytes("{\"block\":\"n\\u0075ll\",\"data\":{\"plain\":\"null\"},\"tileData\":{\"name\":\"null\"}}")));
        check(!ResourceOperationTests.prepare(quiet,ResourceOperation.Mode.DELETE,"blocks/null.json",null).report.permitted(),"quiet block lookup literal-null alias is an incoming reference");
        ResourceOperationTests.apply(quiet,ResourceOperation.Mode.RENAME,"blocks/null.json","named");
        check(text(quiet,"blocks/use.json").equals("{\"block\":\"named\",\"data\":{\"plain\":\"null\"},\"tileData\":{\"name\":\"null\"}}"),"escaped null alias is rewritten as a string; opaque null-spelled values remain exact");
        ResourceOperationTests.apply(quiet,ResourceOperation.Mode.RENAME,"blocks/named.json","null");check(new LosslessJson(quiet.read("blocks/use.json")).stringToken("block").equals("null"),"quiet block loader can retain references when the replacement key is literal null");roundTrip(quiet,temp,"quiet-null-block-alias");
        var missing=PackWorkspace.newPack(Map.of("blocks/source.json",bytes("{\"block\":\"minecraft:stone\"}"),"blocks/use.json",bytes("{\"block\":\"null\"}")));
        check(!rename(missing,"blocks/source.json","null").report.permitted(),"quiet missing literal-null alias cannot silently start resolving");
        var jsonNull=PackWorkspace.newPack(Map.of("blocks/null.json",bytes("{\"block\":\"minecraft:stone\"}"),"blocks/use.json",bytes("{\"block\":null}")));
        check(rename(jsonNull,"blocks/null.json","other").report.permitted(),"JSON null is not the literal string null and never names an alias");
        for(String name:List.of("null","null.disabled")){
            var uncertain=PackWorkspace.newPack(Map.of("jigsaw-pools/"+name+".json",bytes("{}"),"structures/a.json",bytes("{\"startPool\":\"null\"}")));
            check(!rename(uncertain,"jigsaw-pools/"+name+".json","other").report.permitted(),"uncertified quiet/standard mode cannot hide a possible null-key incoming reference");
        }
        var reservedCapture=PackWorkspace.newPack(Map.of("jigsaw-pools/source.json",bytes("{}"),"structures/a.json",bytes("{\"startPool\":\"null\"}")));
        check(!rename(reservedCapture,"jigsaw-pools/source.json","null").report.permitted(),"reserved-mode uncertainty also guards newly resolvable keys");
        check(rename(reservedCapture,"jigsaw-pools/source.json","other").report.permitted(),"unchanged reserved-key uncertainty does not block an unrelated name");
    }
    private static void symbolicBudgets()throws Exception{
        for(int extra:new int[]{384,385}){
            var files=new TreeMap<String,byte[]>();files.put("biomes/forest.json",bytes("{\"floatingChildBiomes\":[{\"carving\":\"forest\"}]}"));files.put("generators/free.json",bytes("{}"));
            int next=0;for(int doc=0;doc<9;doc++){
                int count=doc==8?extra:2000;var body=new StringBuilder("{\"carving\":[");for(int i=0;i<count;i++){if(i>0)body.append(',');body.append("{\"id\":\"i").append(next++).append("\"}");}body.append("]}");files.put("dimensions/d"+doc+".json",bytes(body.toString()));
            }
            var pack=PackWorkspace.newPack(files);check(rename(pack,"biomes/forest.json","grove").report.permitted()==(extra==384),"complete symbolic ID cardinality boundary never grants partial authority");
            check(rename(pack,"generators/free.json","other").report.permitted(),"biome-only symbolic context limits do not block unrelated generator operations");
        }
        for(boolean overflow:new boolean[]{false,true}){
            var files=new TreeMap<String,byte[]>();files.put("biomes/forest.json",bytes("{\"floatingChildBiomes\":[{\"carving\":\"forest\"}]}"));
            files.put("dimensions/a.json",bytes("{\"carving\":[{\"id\":\""+"a".repeat(500_000)+"\"}]}"));files.put("dimensions/b.json",bytes("{\"carving\":[{\"id\":\""+"b".repeat(500_000)+"\"}]}"));
            if(overflow)files.put("dimensions/c.json",bytes("{\"carving\":[{\"id\":\"c\"}]}"));
            var pack=PackWorkspace.newPack(files);check(rename(pack,"biomes/forest.json","grove").report.permitted()!=overflow,"symbolic UTF-16 character budget is complete and explicit");
        }
    }
    private static void roundTrip(PackWorkspace pack,Path temp,String name)throws Exception{
        pack.save(temp.resolve(name));pack.exportZip(temp.resolve(name+".zip"));var reopened=PackWorkspace.open(temp.resolve(name));var imported=PackWorkspace.importZip(temp.resolve(name+".zip"));
        for(String path:pack.paths())check(Arrays.equals(pack.read(path),reopened.read(path)) && Arrays.equals(pack.read(path),imported.read(path)),"context-aware operation bytes persist exactly");
    }
}
