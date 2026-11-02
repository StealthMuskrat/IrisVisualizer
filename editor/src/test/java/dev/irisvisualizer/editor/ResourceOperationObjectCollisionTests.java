package dev.irisvisualizer.editor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Deferred UI-stage contracts for native placed-object load-key collision comparisons. */
final class ResourceOperationObjectCollisionTests {
    private static byte[] bytes(String text){return text.getBytes(StandardCharsets.UTF_8);}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static String text(PackWorkspace pack,String path){return new String(pack.read(path),StandardCharsets.UTF_8);}
    private static PackWorkspace pack(String placement)throws Exception{return PackWorkspace.newPack(Map.of("biomes/a.json",bytes("{\"objects\":["+placement+"]}"),"objects/tree.iob",new byte[]{0,-1,9},"objects/other.iob",new byte[]{1,-1},"generators/free.json",bytes("{}")));}
    private static ResourceOperation.Prepared rename(PackWorkspace pack)throws Exception{return ResourceOperationTests.prepare(pack,ResourceOperation.Mode.RENAME,"objects/tree.iob","next");}
    static void run(Path temp)throws Exception{
        String biome="{\r\n \"name\":\"tree\", \"objects\":[{\"place\":[\"tree\"],\"allowedCollisions\":[\"tr\\u0065e\",\"other\"],\"forbiddenCollisions\":[\"tree\",\"other\"]}],\"floatingChildBiomes\":[{\"biome\":\"a\",\"extraObjects\":[{\"allowedCollisions\":[\"tree\"]}]}]\r\n}";
        String region="{\"name\":\"tree\",\"objects\":[{\"forbiddenCollisions\":[\"tree\"]}]}";
        String snippet="{\"place\":[\"tree\"],\"forbiddenCollisions\":[\"tree\"],\"allowedCollisions\":[\"other\"]}";
        String mod="{\"name\":\"tree\",\"biomeObjectPlacementInjectors\":[{\"biome\":\"a\",\"place\":[{\"allowedCollisions\":[\"tree\"]}]}]}";
        var files=new LinkedHashMap<String,byte[]>();files.put("biomes/a.json",bytes(biome));files.put("biomes/shared.json",bytes("{\"objects\":[\"snippet/shared\"]}"));files.put("regions/r.json",bytes(region));files.put("regions/shared.json",bytes("{\"objects\":[\"snippet/shared\"]}"));files.put("snippet/object-placer/shared.json",bytes(snippet));files.put("mods/read-only.json",bytes(mod));
        byte[] object={0,-1,9},opaque=bytes("{\"block\":\"minecraft:stone\",\"data\":{\"name\":\"tree\",\"large\":9007199254740993}}");files.put("objects/tree.iob",object);files.put("objects/other.iob",new byte[]{1,-1});files.put("blocks/opaque.json",opaque);files.put("images/tree.png",new byte[]{3,-1});
        var canonical=PackWorkspace.newPack(files);Path original=temp.resolve("object-collision-original");canonical.save(original);
        var prepared=rename(canonical);check(prepared.report.permitted(),prepared.report.issues().toString());check(prepared.report.references().stream().filter(r->r.path().equals("snippet/object-placer/shared.json") && r.pointer().equals("/forbiddenCollisions/0")).count()==1,"shared placement payload is scanned once");canonical.installResourceOperation(prepared);
        check(text(canonical,"biomes/a.json").equals(biome.replace("\"place\":[\"tree\"]","\"place\":[\"next\"]").replace("\"tr\\u0065e\"","\"next\"").replace("\"forbiddenCollisions\":[\"tree\",","\"forbiddenCollisions\":[\"next\",").replace("\"allowedCollisions\":[\"tree\"]","\"allowedCollisions\":[\"next\"]")),"only typed placement/collision tokens change; names, list order and formatting remain exact");
        check(text(canonical,"regions/r.json").equals(region.replace("\"forbiddenCollisions\":[\"tree\"]","\"forbiddenCollisions\":[\"next\"]")),"region comparisons follow canonical object identity");
        check(text(canonical,"snippet/object-placer/shared.json").equals(snippet.replace("\"tree\"","\"next\"")) && text(canonical,"biomes/shared.json").equals("{\"objects\":[\"snippet/shared\"]}"),"typed snippet payload changes without rewriting its indirection");
        check(text(canonical,"mods/read-only.json").equals(mod.replace("\"allowedCollisions\":[\"tree\"]","\"allowedCollisions\":[\"next\"]")),"required reference maintenance changes only the collision token in a read-only owner");
        canonical.createSubfolder("objects","nested");ResourceOperationTests.apply(canonical,ResourceOperation.Mode.MOVE,"objects/next.iob","objects/nested");check(text(canonical,"regions/r.json").contains("\"nested/next\"") && Arrays.equals(canonical.read("objects/nested/next.iob"),object),"move maintains complete keys without decoding or editing object bytes");
        check(!ResourceOperationTests.prepare(canonical,ResourceOperation.Mode.DELETE,"objects/nested/next.iob",null).report.permitted(),"referenced collision objects cannot be deleted");
        canonical.save(original);check(!Files.exists(original.resolve("objects/tree.iob")) && Files.exists(original.resolve("objects/nested/next.iob")),"Save publishes references and moved bytes then removes the baseline-bound original file");canonical.saveCopy(temp.resolve("object-collision-copy"));canonical.saveAs(temp.resolve("object-collision-as"));canonical.exportZip(temp.resolve("object-collision.zip"));
        for(var reopened:List.of(PackWorkspace.open(original),PackWorkspace.open(temp.resolve("object-collision-copy")),PackWorkspace.open(temp.resolve("object-collision-as")),PackWorkspace.importZip(temp.resolve("object-collision.zip")))){check(reopened.paths().equals(canonical.paths()),"saved graph has exactly the reviewed paths");for(String path:canonical.paths())check(Arrays.equals(canonical.read(path),reopened.read(path)),"byte-exact collision maintenance persistence: "+path);check(Arrays.equals(reopened.read("blocks/opaque.json"),opaque),"opaque matching names/large integers stay unchanged");}
        for(String alias:List.of("TREE","Tree")){
            var comparisonAlias=pack("{\"place\":[\"tree\"],\"forbiddenCollisions\":["+LosslessJson.quote(alias)+"]}");check(!rename(comparisonAlias).report.permitted(),"case-insensitive loader aliases cannot become active exact collision comparisons");
            var poolAlias=pack("{\"place\":["+LosslessJson.quote(alias)+"],\"allowedCollisions\":[\"tree\"]}");check(!rename(poolAlias).report.permitted(),"incoming marker load-key aliases cannot collapse into the replacement comparison identity");
        }
        var dotted=PackWorkspace.newPack(Map.of("objects/tree.variant.iob",object,"biomes/a.json",bytes("{\"objects\":[{\"place\":[\"tree\"],\"forbiddenCollisions\":[\"tree.variant\"]}]}")));check(!ResourceOperationTests.prepare(dotted,ResourceOperation.Mode.RENAME,"objects/tree.variant.iob","next").report.permitted(),"dotted fallback aliases retain exact comparison protection");
        var capture=pack("{\"forbiddenCollisions\":[\"next\"]}");check(!rename(capture).report.permitted(),"newly captured missing comparison names block mutation");
        var comparisonOnly=pack("{\"allowedCollisions\":[\"tree\"]}");check(!ResourceOperationTests.prepare(comparisonOnly,ResourceOperation.Mode.DELETE,"objects/tree.iob",null).report.permitted(),"comparison-only references protect deletion without a placement pool");
        var unrelated=pack("{\"place\":[\"TREE\"],\"allowedCollisions\":[\"other\"],\"forbiddenCollisions\":[\"other\"]}");check(rename(unrelated).report.permitted(),"unrelated comparisons do not reject an otherwise permitted loader-alias rename");
        var blank=pack("{\"allowedCollisions\":[\"\",\" \"],\"forbiddenCollisions\":[]}");check(rename(blank).report.permitted(),"empty/blank comparisons preserve the former inactive behavior");
        for(String value:List.of(" tree ","minecraft:tree")){var uncertain=pack("{\"allowedCollisions\":["+LosslessJson.quote(value)+"]}");check(!rename(uncertain).report.permitted(),"unresolved comparison spellings keep the affected-family guard");check(ResourceOperationTests.prepare(uncertain,ResourceOperation.Mode.RENAME,"generators/free.json","noise").report.permitted(),"uncertain object comparisons do not block unrelated families");}
        var procedural=pack("{\"forbiddenCollisions\":[\"tree\"]}");procedural.createSubfolder("objects","procedural");check(!ResourceOperationTests.prepare(procedural,ResourceOperation.Mode.MOVE,"objects/tree.iob","objects/procedural").report.permitted(),"entering procedural marker namespace cannot disable existing comparisons");
        var reserved=PackWorkspace.newPack(Map.of("objects/procedural/tree.iob",object,"biomes/a.json",bytes("{\"objects\":[{\"forbiddenCollisions\":[\"procedural/tree\"]}]}")));check(!ResourceOperationTests.prepare(reserved,ResourceOperation.Mode.MOVE,"objects/procedural/tree.iob","objects").report.permitted(),"leaving procedural marker namespace cannot activate a previously excluded identity");
        var ordinaryPrefix=pack("{\"forbiddenCollisions\":[\"tree\"]}");ordinaryPrefix.createSubfolder("objects","procedurality");check(ResourceOperationTests.prepare(ordinaryPrefix,ResourceOperation.Mode.MOVE,"objects/tree.iob","objects/procedurality").report.permitted(),"the exact case-sensitive procedural/ marker prefix does not become a fuzzy prefix rule");
        check(FloatingObjectPlacements.help("allowedCollisions").contains("exact, case-sensitive") && FloatingObjectPlacements.help("forbiddenCollisions").contains("override"),"inspector guidance matches native comparison and precedence");
        System.out.println("PASS: exact native object collision keys maintained across rename/move, shared snippets and read-only owners, opaque asset preservation, alias/case/dotted/new-capture/procedural namespace guards, referenced deletion and exact Save/Copy/As/ZIP");
    }
}
