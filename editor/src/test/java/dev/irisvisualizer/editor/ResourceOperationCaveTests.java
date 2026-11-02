package dev.irisvisualizer.editor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Deferred end-of-UI contracts for normalized cave selectors and their distinct loader consumer. */
final class ResourceOperationCaveTests {
    private static byte[] bytes(String text){return text.getBytes(StandardCharsets.UTF_8);}
    private static void check(boolean value,String detail){if(!value)throw new AssertionError(detail);}
    private static String text(PackWorkspace pack,String path){return new String(pack.read(path),StandardCharsets.UTF_8);}
    private static ResourceOperation.Prepared rename(PackWorkspace pack,String source,String key)throws Exception{return ResourceOperationTests.prepare(pack,ResourceOperation.Mode.RENAME,source,key);}
    private static PackWorkspace pack(String key,String selector)throws Exception{
        return PackWorkspace.newPack(Map.of("biomes/"+key+".json",bytes("{\"structures\":[{\"caveBiomes\":["+LosslessJson.quote(selector)+"]}]}"),"generators/free.json",bytes("{}")));
    }
    static void run(Path temp)throws Exception{
        String dimension="{\r\n\"name\":\"forest\",\"structures\":[{\"caveBiomes\":[\"FoReSt\",\" FOREST \",\"iris:other\",\"other\",\"\",null]}]\r\n}";
        String region="{\"landBiomes\":[\"FOREST\"],\"structures\":[{\"caveBiomes\":[\"FOREST\"]}]}";
        String biome="{\"name\":\"forest\",\"structures\":[{\"caveBiomes\":[\"FOREST\"]}]}";
        byte[] opaque=bytes("{\"block\":\"minecraft:stone\",\"data\":{\"name\":\"forest\",\"large\":9007199254740993}}");
        var canonical=PackWorkspace.newPack(Map.of("dimensions/world.json",bytes(dimension),"regions/land.json",bytes(region),"biomes/forest.json",bytes(biome),"biomes/other.json",bytes("{}"),"blocks/opaque.json",opaque));
        var plan=rename(canonical,"biomes/forest.json","grove");check(plan.report.permitted(),plan.report.issues().toString());canonical.installResourceOperation(plan);
        check(text(canonical,"dimensions/world.json").equals(dimension.replace("\"FoReSt\"","\"grove\"").replace("\" FOREST \"","\" grove \"")),"case-normalized comparison tokens change while whitespace, unrelated qualified selectors, null and ordinary names remain exact");
        check(text(canonical,"regions/land.json").equals(region.replace("FOREST","grove")),"case-only loader aliases agree with normalized matching");
        check(text(canonical,"biomes/grove.json").equals(biome.replace("FOREST","grove")),"self-comparisons follow the moved owner without replacing ordinary text");
        canonical.createSubfolder("biomes","nested");ResourceOperationTests.apply(canonical,ResourceOperation.Mode.MOVE,"biomes/grove.json","biomes/nested");
        check(text(canonical,"dimensions/world.json").contains(" nested/grove ") && text(canonical,"dimensions/world.json").contains("iris:other"),"local cave selectors follow complete moved keys while unrelated qualified values remain exact");
        check(!ResourceOperationTests.prepare(canonical,ResourceOperation.Mode.DELETE,"biomes/nested/grove.json",null).report.permitted(),"normalized comparison references protect deletion");
        canonical.save(temp.resolve("cave-selector-save"));canonical.saveCopy(temp.resolve("cave-selector-copy"));canonical.saveAs(temp.resolve("cave-selector-as"));canonical.exportZip(temp.resolve("cave-selector.zip"));
        for(var reopened:List.of(PackWorkspace.open(temp.resolve("cave-selector-save")),PackWorkspace.open(temp.resolve("cave-selector-copy")),PackWorkspace.open(temp.resolve("cave-selector-as")),PackWorkspace.importZip(temp.resolve("cave-selector.zip")))){
            check(reopened.paths().equals(canonical.paths()),"all persistence modes keep the complete resource graph");
            for(String path:canonical.paths())check(Arrays.equals(reopened.read(path),canonical.read(path)),"byte-exact cave selector persistence: "+path);
        }
        check(Arrays.equals(canonical.read("blocks/opaque.json"),opaque),"opaque matching text and exact large integers remain untouched");
        for(String selector:List.of("forest","iris:forest"," FOREST ")){
            var dotted=pack("forest.extra",selector);check(!rename(dotted,"biomes/forest.extra.json","grove").report.permitted(),"a dotted loader alias must not gain new canonical comparison meaning");
        }
        var dottedExact=pack("forest.extra","FOREST.EXTRA");check(rename(dottedExact,"biomes/forest.extra.json","grove").report.permitted(),"complete dotted keys retain case-insensitive exact comparison meaning");
        var incomingAlias=PackWorkspace.newPack(Map.of("biomes/forest.extra.json",bytes("{\"structures\":[{\"caveBiomes\":[\"forest.extra\"]}]}"),"regions/land.json",bytes("{\"landBiomes\":[\"forest\"]}")));
        check(!rename(incomingAlias,"biomes/forest.extra.json","grove").report.permitted(),"a requested dotted alias cannot collapse unequal normalized identities");
        var upperFallback=pack("FOREST.extra","FOREST");
        check(!rename(upperFallback,"biomes/FOREST.extra.json","grove").report.permitted(),"the original compatibility lookup retains case-sensitive root fallback semantics");
        check(!ResourceOperationTests.prepare(upperFallback,ResourceOperation.Mode.DELETE,"biomes/FOREST.extra.json",null).report.permitted(),"a compatibility lookup dependency still protects deletion when its normalized virtual lookup is missing");
        var capture=PackWorkspace.newPack(Map.of("biomes/free.json",bytes("{\"structures\":[{\"caveBiomes\":[\"FOREST\"]}]}"),"biomes/forest.other.json",bytes("{}")));
        var captured=rename(capture,"biomes/free.json","forest");check(!captured.report.permitted() && captured.report.issues().stream().anyMatch(issue->issue.message().contains("compatibility lookup")),"new uppercase raw lookup capture is checked independently of normalized matching");
        check(!rename(pack("forest","iris:grove"),"biomes/forest.json","grove").report.permitted(),"a missing qualified comparison cannot start matching after rename");
        for(String selector:List.of("iris:forest"," IriS:FOREST ","forest.json:other","grove.json:other"))check(rename(pack("forest",selector),"biomes/forest.json","grove").report.permitted(),"raw folder-filter rejection is stable for supported qualified comparisons and unrelated filename-like prefixes");
        for(String selector:List.of("biomes:forest","biomes:FOREST "))check(!rename(pack("forest",selector),"biomes/forest.json","grove").report.permitted(),"exact raw biomes: prefix still passes the folder filter and guards affected Windows lookups");
        var prefix=pack("forest","fo");ResourceOperationTests.apply(prefix,ResourceOperation.Mode.RENAME,"biomes/forest.json","grove");
        check(text(prefix,"biomes/grove.json").contains("\"caveBiomes\":[\"fo\"]"),"a partial prefix is inert and preserved; native matching is not startsWith");
        for(String selector:List.of("C:forest","x:forest","iris:other:forest","\u212A","iris:")){
            var guarded=pack("forest",selector);check(!rename(guarded,"biomes/forest.json","grove").report.permitted(),"uncertified drive/compound/Unicode/empty suffix forms stay guarded");
            check(rename(guarded,"generators/free.json","other").report.permitted(),"comparison uncertainty does not block unrelated generator maintenance");
        }
        var whitespace=pack("forest"," \t ");check(rename(whitespace,"biomes/forest.json","grove").report.permitted(),"blank entries remain inactive without being removed or materialized");
        qualified(temp);
        System.out.println("PASS: normalized cave-selector rename/move with case aliases, unrelated qualified-value/whitespace preservation, independent compatibility lookup checks, dotted/unequal alias and new-capture guards, no partial-prefix inference, referenced deletion and exact Save/Copy/As/ZIP");
    }
    private static void qualified(Path temp)throws Exception{
        String dimension="{\r\n\"name\":\"forest\",\"structures\":[{\"caveBiomes\":[\"iris:forest\",\" IriS:FOREST \",\"BIOMES:forest\",\" biomes:forest\",\"iris:other\",\"biomes:other\",\"forest.json:other\",null]}]\r\n}";
        String region="{\"landBiomes\":[\"FoReSt\"],\"structures\":[{\"caveBiomes\":[\"iris:FoReSt\",\"other\"]}]}";
        String biome="{\"name\":\"forest\",\"structures\":[{\"caveBiomes\":[\"iris:forest\"]}]}";
        var pack=PackWorkspace.newPack(Map.of("dimensions/a.json",bytes(dimension),"regions/r.json",bytes(region),"biomes/forest.json",bytes(biome),"biomes/other.json",bytes("{}"),"images/forest.png",new byte[]{0,-1}));
        Path original=temp.resolve("qualified-cave-original");pack.save(original);var prepared=rename(pack,"biomes/forest.json","grove");check(prepared.report.permitted(),prepared.report.issues().toString());pack.installResourceOperation(prepared);
        String expected=dimension.replace("iris:forest","iris:grove").replace("IriS:FOREST","IriS:grove").replace("BIOMES:forest","BIOMES:grove").replace(" biomes:forest"," biomes:grove");
        check(text(pack,"dimensions/a.json").equals(expected),"qualified rewrites preserve exact namespace case, whitespace, unrelated qualified values and ordinary names");
        check(text(pack,"regions/r.json").equals(region.replace("FoReSt","grove")) && text(pack,"biomes/grove.json").equals(biome.replace("iris:forest","iris:grove")),"case-only pool aliases and biome/region placements retain normalized comparison meaning");
        pack.createSubfolder("biomes","nested");ResourceOperationTests.apply(pack,ResourceOperation.Mode.MOVE,"biomes/grove.json","biomes/nested");check(text(pack,"dimensions/a.json").equals(expected.replace("iris:grove","iris:nested/grove").replace("IriS:grove","IriS:nested/grove").replace("BIOMES:grove","BIOMES:nested/grove").replace(" biomes:grove"," biomes:nested/grove")),"qualified comparison suffixes follow complete nested keys without trimming or changing namespace spelling");
        check(!ResourceOperationTests.prepare(pack,ResourceOperation.Mode.DELETE,"biomes/nested/grove.json",null).report.permitted(),"qualified comparisons still protect deletion");
        pack.save(original);pack.saveCopy(temp.resolve("qualified-cave-copy"));pack.saveAs(temp.resolve("qualified-cave-as"));pack.exportZip(temp.resolve("qualified-cave.zip"));
        for(var reopened:List.of(PackWorkspace.open(original),PackWorkspace.open(temp.resolve("qualified-cave-copy")),PackWorkspace.open(temp.resolve("qualified-cave-as")),PackWorkspace.importZip(temp.resolve("qualified-cave.zip")))){check(reopened.paths().equals(pack.paths()),"qualified maintenance retains the exact graph");for(String path:pack.paths())check(Arrays.equals(pack.read(path),reopened.read(path)),"qualified selector exact persistence: "+path);}
        for(String prefix:List.of("iris:","BIOMES:"," biomes:")){
            check(!rename(pack("forest",prefix+"grove"),"biomes/forest.json","grove").report.permitted(),"new qualified comparison captures stay blocked");
            check(!rename(pack("forest.extra",prefix+"forest"),"biomes/forest.extra.json","grove").report.permitted(),"qualified dotted aliases cannot gain canonical comparison meaning");
            check(rename(pack("forest.extra",prefix+"FOREST.EXTRA"),"biomes/forest.extra.json","grove").report.permitted(),"complete qualified dotted keys can follow a rename");
        }
        String escaped="{\"structures\":[{\"caveBiomes\":[\"iris:fo\\u0072est\"]}]}";
        var escapedPack=PackWorkspace.newPack(Map.of("biomes/forest.json",bytes(escaped)));ResourceOperationTests.apply(escapedPack,ResourceOperation.Mode.RENAME,"biomes/forest.json","grove");check(text(escapedPack,"biomes/grove.json").equals(escaped.replace("iris:fo\\u0072est","iris:grove")),"escaped qualified keys are decoded for matching and only the edited token is replaced");
        var unsafe=pack("forest","biomes:forest");check(rename(unsafe,"generators/free.json","other").report.permitted(),"literal folder-prefix uncertainty does not block unrelated resource families");
        check(StructurePlacements.help("caveBiomes").contains("case-sensitive biomes:"),"inspector guidance explains the separate raw compatibility folder filter");
    }
}
