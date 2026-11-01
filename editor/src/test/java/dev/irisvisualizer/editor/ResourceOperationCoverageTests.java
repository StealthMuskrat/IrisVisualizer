package dev.irisvisualizer.editor;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

/** Deferred end-of-UI contracts for the expanded closed reference-owner schema. */
final class ResourceOperationCoverageTests {
    private static byte[] bytes(String value){return ResourceOperationTests.bytes(value);}
    private static void check(boolean value,String message){ResourceOperationTests.check(value,message);}
    private static ResourceOperation.Prepared rename(PackWorkspace pack,String path,String name)throws Exception{
        return ResourceOperationTests.prepare(pack,ResourceOperation.Mode.RENAME,path,name);
    }
    private static PackWorkspace fixture()throws Exception{
        return PackWorkspace.newPack(Map.of(
                "generators/base.json",bytes("{\"seed\":9007199254740993,\"interpolator\":{\"function\":\"BILINEAR\",\"horizontalScale\":7},\"composite\":[{\"style\":{\"expression\":\"formula\",\"imageMap\":\"height\",\"fracture\":{\"expression\":\"formula\"}},\"fracture\":[{\"style\":{\"imageMap\":\"height\"}}]}],\"cliffHeightGenerator\":{\"style\":{\"expression\":\"formula\"}}}"),
                "expressions/formula.json",bytes("{\"expression\":\"formula + height\",\"variables\":[{\"name\":\"formula\",\"styleValue\":{\"expression\":\"formula\",\"imageMap\":\"height\"}}],\"functions\":[{\"name\":\"height\",\"args\":2,\"styleValue\":{\"fracture\":{\"expression\":\"formula\"}}}]}"),
                "image-maps/height.json",bytes("{\"source\":\"pixels\",\"origin\":{\"x\":1,\"z\":2},\"colors\":{\"#ffffff\":\"formula\"},\"fallbackTarget\":\"height\"}"),
                "images/pixels.png",new byte[]{0,-1,9},
                "blocks/alias.json",bytes("{\"block\":\"minecraft:stone\",\"data\":{\"axis\":\"y\",\"nested\":{\"block\":\"alias\",\"imageMap\":\"height\"}},\"tileData\":{\"unknown\":9007199254740993},\"backup\":{\"block\":\"minecraft:air\"}}"),
                "structures/start.json",bytes("{\"edit\":[{\"find\":[{\"block\":\"alias\"}],\"replace\":{\"palette\":[{\"block\":\"ALIAS\"}],\"style\":{\"imageMap\":\"height\",\"fracture\":{\"expression\":\"formula\"}}}}]}")));
    }
    static void run(Path temp)throws Exception{
        var pack=fixture();byte[] generator=pack.read("generators/base.json"),expression=pack.read("expressions/formula.json");
        var plan=rename(pack,"expressions/formula.json","renamed");
        check(plan.report.permitted(),plan.report.issues().toString());
        check(plan.report.references().stream().filter(r->r.replacement()!=null).count()==6,"recursive style, fracture, expression-load/function and jigsaw material expression links");
        pack.installResourceOperation(plan);
        check(Arrays.equals(pack.read("generators/base.json"),bytes(new String(generator,java.nio.charset.StandardCharsets.UTF_8).replace("\"expression\":\"formula\"","\"expression\":\"renamed\""))),"only nested expression-reference tokens change in generators");
        check(Arrays.equals(pack.read("expressions/renamed.json"),bytes(new String(expression,java.nio.charset.StandardCharsets.UTF_8).replace("\"expression\":\"formula\"","\"expression\":\"renamed\""))),"expression code, variable/function names and source spelling preserved");
        check(new LosslessJson(pack.read("image-maps/height.json")).stringToken("fallbackTarget").equals("height"),"categorical target is unrelated to the map/expression filename");
        ResourceOperationTests.apply(pack,ResourceOperation.Mode.RENAME,"image-maps/height.json","renamed_map");
        check(new LosslessJson(pack.read("image-maps/renamed_map.json")).stringToken("fallbackTarget").equals("height"),"map rename never rewrites categorical labels");
        check(ResourceOperationTests.prepare(pack,ResourceOperation.Mode.DELETE,"image-maps/renamed_map.json",null).report.issues().stream().anyMatch(i->i.message().contains("referenced")),"new typed map links block deletion");
        ResourceOperationTests.apply(pack,ResourceOperation.Mode.RENAME,"images/pixels.png","renamed_pixels");
        check(new LosslessJson(pack.read("image-maps/renamed_map.json")).stringToken("source").equals("renamed_pixels"),"PNG source key updated using native image extension");
        pack.createSubfolder("images","nested");ResourceOperationTests.apply(pack,ResourceOperation.Mode.MOVE,"images/renamed_pixels.png","images/nested");
        check(new LosslessJson(pack.read("image-maps/renamed_map.json")).stringToken("source").equals("nested/renamed_pixels"),"nested PNG move rewrites complete key");
        check(!ResourceOperationTests.prepare(pack,ResourceOperation.Mode.DELETE,"images/nested/renamed_pixels.png",null).report.permitted(),"referenced PNG deletion blocked");
        byte[] originalBlock=pack.read("blocks/alias.json");ResourceOperationTests.apply(pack,ResourceOperation.Mode.RENAME,"blocks/alias.json","new_alias");
        check(Arrays.equals(originalBlock,pack.read("blocks/new_alias.json")),"opaque state/tile maps, unknown nested values and namespaced vanilla states stay byte-exact");
        var structure=new LosslessJson(pack.read("structures/start.json")).objectArray("edit").getFirst();
        check(structure.objectArray("find").getFirst().stringToken("block").equals("new_alias"),"custom block find link follows native alias-first precedence");
        check(new LosslessJson(bytes(structure.token("replace"))).objectArray("palette").getFirst().stringToken("block").equals("new_alias"),"case-insensitive alias in palette updated");
        pack.createSubfolder("generators","nested");ResourceOperationTests.apply(pack,ResourceOperation.Mode.MOVE,"generators/base.json","generators/nested");
        check(pack.paths().contains("generators/nested/base.json"),"generator owner itself supports same-family moves");
        pack.save(temp.resolve("expanded-resource-owners"));pack.exportZip(temp.resolve("expanded-resource-owners.zip"));
        var reopened=PackWorkspace.open(temp.resolve("expanded-resource-owners"));var imported=PackWorkspace.importZip(temp.resolve("expanded-resource-owners.zip"));
        for(String path:pack.paths())check(Arrays.equals(pack.read(path),reopened.read(path)) && Arrays.equals(pack.read(path),imported.read(path)),"expanded owner graph Save/reopen/ZIP preservation");
        // An image loader resolves PNG only. A JPEG with the same stem must never receive a PNG's references.
        var jpeg=PackWorkspace.newPack(Map.of("images/pixels.jpg",new byte[]{4},"images/pixels.png",new byte[]{5},"image-maps/map.json",bytes("{\"source\":\"pixels\"}")));
        ResourceOperationTests.apply(jpeg,ResourceOperation.Mode.RENAME,"images/pixels.jpg","jpeg_only");
        check(new LosslessJson(jpeg.read("image-maps/map.json")).stringToken("source").equals("pixels"),"JPEG operation does not rewrite PNG links sharing its stem");
        var dotted=PackWorkspace.newPack(Map.of("images/source.disabled.png",new byte[]{4},"image-maps/map.json",bytes("{\"source\":\"source\"}")));
        ResourceOperationTests.apply(dotted,ResourceOperation.Mode.RENAME,"images/source.disabled.png","next");
        check(new LosslessJson(dotted.read("image-maps/map.json")).stringToken("source").equals("next"),"native root dotted-prefix PNG aliases maintained");
        var refusedNull=PackWorkspace.newPack(Map.of("jigsaw-pools/source.json",bytes("{}"),"structures/start.json",bytes("{\"startPool\":\"source\"}")));
        check(!rename(refusedNull,"jigsaw-pools/source.json","null").report.permitted(),"native literal-null refusal cannot become a replacement reference key");
        var nestedNull=PackWorkspace.newPack(Map.of("images/pixels.png",new byte[]{1},"image-maps/map.json",bytes("{\"source\":\"pixels\"}")));nestedNull.createSubfolder("images","nested");
        ResourceOperationTests.apply(nestedNull,ResourceOperation.Mode.MOVE,"images/pixels.png","images/nested");ResourceOperationTests.apply(nestedNull,ResourceOperation.Mode.RENAME,"images/nested/pixels.png","null");
        check(new LosslessJson(nestedNull.read("image-maps/map.json")).stringToken("source").equals("nested/null"),"nested resource key ending in null is not the refused literal key");
        // Missing/default fields are not inserted to permit an otherwise unsafe alias rename.
        var implicit=PackWorkspace.newPack(Map.of("blocks/air.json",bytes("{\"block\":\"minecraft:air\"}"),"blocks/default.json",bytes("{}")));
        byte[] missing=implicit.read("blocks/default.json");var blocked=rename(implicit,"blocks/air.json","new_air");
        check(!blocked.report.permitted() && blocked.report.references().stream().anyMatch(r->r.implicit() && r.resolved().equals("blocks/air.json")),"omitted block default participates in incoming references");
        ResourceOperationTests.rejects(()->implicit.installResourceOperation(blocked));check(Arrays.equals(missing,implicit.read("blocks/default.json")),"blocked implicit reference never materializes defaults");
        var newImplicit=PackWorkspace.newPack(Map.of("blocks/other.json",bytes("{\"block\":\"minecraft:air\"}"),"blocks/default.json",bytes("{}")));
        check(!rename(newImplicit,"blocks/other.json","air").report.permitted(),"new alias cannot silently shadow an omitted vanilla default");
        var paletteImplicit=PackWorkspace.newPack(Map.of("blocks/stone.json",bytes("{\"block\":\"minecraft:stone\"}"),"structures/a.json",bytes("{\"edit\":[{}]}")));
        check(!rename(paletteImplicit,"blocks/stone.json","other").report.permitted(),"omitted replace palette contributes its implicit STONE alias");
        var nestedImplicit=PackWorkspace.newPack(Map.of("blocks/air.json",bytes("{\"block\":\"minecraft:air\"}"),"structures/a.json",bytes("{\"edit\":[{\"replace\":{\"palette\":[{}]}}]}")));
        check(!rename(nestedImplicit,"blocks/air.json","other").report.permitted(),"explicit empty block entry contributes its own omitted air alias");
        var unrelated=PackWorkspace.newPack(Map.of("blocks/stone.json",bytes("{\"block\":\"minecraft:stone\"}"),"blocks/opaque.json",bytes("{\"block\":\"minecraft:air\",\"data\":{\"alias\":\"stone\"},\"tileData\":{\"ref\":\"stone\"}}"),"image-maps/colors.json",bytes("{\"colors\":{\"#ffffff\":\"stone\"},\"fallbackTarget\":\"stone\"}")));
        byte[] opaque=unrelated.read("blocks/opaque.json"),legend=unrelated.read("image-maps/colors.json");ResourceOperationTests.apply(unrelated,ResourceOperation.Mode.RENAME,"blocks/stone.json","other");
        check(Arrays.equals(opaque,unrelated.read("blocks/opaque.json")) && Arrays.equals(legend,unrelated.read("image-maps/colors.json")),"state/NBT and vanilla categorical surface targets never guessed as block aliases");
        for(String invalid:java.util.List.of("{\"composite\":\"snippet\"}","{\"composite\":[{\"style\":{\"fracture\":\"snippet\"}}]}","{\"composite\":[{\"style\":{\"imageMap\":5}}]}")){
            var malformed=PackWorkspace.newPack(Map.of("generators/a.json",bytes(invalid)));check(!rename(malformed,"generators/a.json","b").report.permitted(),"new nested malformed/snippet owners block mutation");
        }
        for(String invalid:java.util.List.of("{\"data\":[]}","{\"tileData\":\"snippet\"}","{\"future\":\"kept\"}")){
            var malformed=PackWorkspace.newPack(Map.of("blocks/a.json",bytes(invalid)));check(!rename(malformed,"blocks/a.json","b").report.permitted(),"unknown block fields and invalid opaque-map shapes block mutation");
        }
        System.out.println("PASS: expanded closed generator/expression/image-map/block ownership with recursive styles/fractures, variables/functions, material replacements and alias-first block references; exact token and opaque/vanilla/categorical preservation; PNG-only and dotted image resolution without JPEG confusion; implicit air/STONE/default-palette blockers without field materialization; malformed/snippet/unknown rejection and exact graph Save/reopen/ZIP");
    }
}
