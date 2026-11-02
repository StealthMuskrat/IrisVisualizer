package dev.irisvisualizer.editor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Deferred end-of-UI contracts for native typed snippet indirection, not native preview execution. */
final class ResourceOperationSnippetTests {
    private static byte[] bytes(String value){return ResourceOperationTests.bytes(value);}
    private static void check(boolean value,String message){ResourceOperationTests.check(value,message);}
    private static String text(PackWorkspace pack,String path){return new String(pack.read(path),StandardCharsets.UTF_8);}
    private static ResourceOperation.Prepared rename(PackWorkspace pack,String path,String name)throws Exception{return ResourceOperationTests.prepare(pack,ResourceOperation.Mode.RENAME,path,name);}
    private static PackWorkspace fixture()throws Exception{
        return PackWorkspace.newPack(Map.ofEntries(
            Map.entry("biomes/forest.json",bytes("{\"layers\":[\"snippet/common\",\"snippet/biome-palette/common\"],\"generators\":[\"snippet/terrain\"],\"objects\":[\"snippet/object-placer/trees\"]}")),
            Map.entry("snippet/biome-palette/common.json",bytes("{\"palette\":[{\"block\":\"alias\"}],\"style\":\"snippet/shared_style\"}")),
            Map.entry("snippet/style/shared_style.json",bytes("{\"expression\":\"formula\",\"imageMap\":\"height\",\"fracture\":\"snippet/style/fracture\"}")),
            Map.entry("snippet/style/fracture.json",bytes("{\"expression\":\"formula\"}")),
            Map.entry("snippet/generator-layer/terrain.json",bytes("{\"generator\":\"land\"}")),
            Map.entry("snippet/object-placer/trees.json",bytes("{\"place\":[\"oak\"],\"edit\":[\"snippet/paint\"]}")),
            Map.entry("snippet/object-block-replacer/paint.json",bytes("{\"find\":[{\"block\":\"alias\"}],\"replace\":\"snippet/colors\"}")),
            Map.entry("snippet/palette/colors.json",bytes("{\"palette\":[{\"block\":\"alias\"}]}")),
            Map.entry("generators/land.json",bytes("{\"composite\":[\"snippet/noise\"]}")),
            Map.entry("snippet/generator/noise.json",bytes("{\"style\":\"snippet/shared_style\"}")),
            Map.entry("mods/styles.json",bytes("{\"styleReplacers\":[\"snippet/replacement\"]}")),
            Map.entry("snippet/noise-style-replacer/replacement.json",bytes("{\"replace\":\"snippet/shared_style\"}")),
            Map.entry("expressions/formula.json",bytes("{\"expression\":\"formula\"}")),
            Map.entry("image-maps/height.json",bytes("{\"source\":\"pixels\"}")),
            Map.entry("images/pixels.png",new byte[]{0,-1,8}),Map.entry("objects/oak.iob",new byte[]{0,-1,7}),
            Map.entry("blocks/alias.json",bytes("{\"block\":\"minecraft:stone\"}"))));
    }
    static void run(Path temp)throws Exception{
        var pack=fixture();byte[] biome=pack.read("biomes/forest.json"),generator=pack.read("generators/land.json"),mod=pack.read("mods/styles.json");
        var plan=rename(pack,"blocks/alias.json","other_alias");check(plan.report.permitted(),plan.report.issues().toString());
        check(plan.report.changedFiles()==3 && plan.report.references().stream().filter(r->r.replacement()!=null).count()==3,"shared snippet payload references are collected once, independent of duplicate consumers");
        pack.installResourceOperation(plan);
        for(String path:List.of("snippet/biome-palette/common.json","snippet/object-block-replacer/paint.json","snippet/palette/colors.json"))check(text(pack,path).contains("\"block\":\"other_alias\""),"resource links inside nested/shared snippet payload maintained");
        check(Arrays.equals(biome,pack.read("biomes/forest.json")) && Arrays.equals(generator,pack.read("generators/land.json")) && Arrays.equals(mod,pack.read("mods/styles.json")),"snippet indirection strings remain byte-exact in all consumers");
        var expressions=rename(pack,"expressions/formula.json","equation");check(expressions.report.permitted() && expressions.report.changedFiles()==2,expressions.report.issues().toString());pack.installResourceOperation(expressions);
        check(text(pack,"snippet/style/shared_style.json").contains("\"expression\":\"equation\"") && text(pack,"snippet/style/fracture.json").contains("\"expression\":\"equation\""),"nested snippet style references maintained without duplicate splices");
        check(text(pack,"expressions/equation.json").equals("{\"expression\":\"formula\"}"),"ordinary expression program stays exact");
        ResourceOperationTests.apply(pack,ResourceOperation.Mode.RENAME,"generators/land.json","terrain");
        check(text(pack,"snippet/generator-layer/terrain.json").contains("\"generator\":\"terrain\"") && Arrays.equals(biome,pack.read("biomes/forest.json")),"resource key change does not rename an identically spelled snippet name");
        ResourceOperationTests.apply(pack,ResourceOperation.Mode.RENAME,"objects/oak.iob","tree");check(text(pack,"snippet/object-placer/trees.json").contains("\"place\":[\"tree\"]"),"object links in typed placement snippets maintained");
        ResourceOperationTests.apply(pack,ResourceOperation.Mode.RENAME,"image-maps/height.json","new_height");check(text(pack,"snippet/style/shared_style.json").contains("\"imageMap\":\"new_height\""),"map links in snippets remain typed scalar consumers");
        check(!ResourceOperationTests.prepare(pack,ResourceOperation.Mode.DELETE,"expressions/equation.json",null).report.permitted(),"snippet payload incoming references prevent deletion");
        ResourceOperationTests.rejects(()->pack.captureResourceOperation(ResourceOperation.Mode.RENAME,"snippet/style/shared_style.json","forbidden"));
        pack.save(temp.resolve("typed-snippet-owners"));pack.exportZip(temp.resolve("typed-snippet-owners.zip"));
        var reopened=PackWorkspace.open(temp.resolve("typed-snippet-owners"));var imported=PackWorkspace.importZip(temp.resolve("typed-snippet-owners.zip"));for(String path:pack.paths())check(Arrays.equals(pack.read(path),reopened.read(path)) && Arrays.equals(pack.read(path),imported.read(path)),"snippet-backed graph Save/reopen/ZIP preservation");
        failures();depthAndLinkBudgets();
        System.out.println("PASS: source-bound typed snippet owners and native shorthand/exact Windows paths, once-only shared/nested payload rewrites and read-only mod consumers, indirection/default/opaque preservation, no snippet file-operation authority, missing/unknown/unsafe/alias-root/cyclic/deep/over-budget blockers, referenced deletion and byte-exact graph round trips");
    }
    private static PackWorkspace style(String reference,Map<String,byte[]> extra)throws Exception{
        var files=new TreeMap<String,byte[]>(extra);files.put("generators/a.json",bytes("{\"composite\":[{\"style\":"+LosslessJson.quote(reference)+"}]}"));return PackWorkspace.newPack(files);
    }
    private static void failures()throws Exception{
        for(String reference:List.of("missing","snippet/missing","snippet/../unsafe","snippet/a\\b","Snippet/a","snippet/é")){
            var pack=style(reference,Map.of());check(!rename(pack,"generators/a.json","b").report.permitted(),"unresolved/unsafe/nonlocal snippet strings cannot authorize mutation");
        }
        var longKey=style("snippet/"+"a".repeat(50_000),Map.of());
        check(rename(longKey,"generators/a.json","b").report.issues().stream().allMatch(i->i.message().length()<600),"long snippet names cannot produce unbounded UI diagnostic messages");
        var dotted=style("snippet/a",Map.of("snippet/style/a.disabled.json",bytes("{}")));check(!rename(dotted,"generators/a.json","b").report.permitted(),"snippet paths never inherit resource-loader dotted-prefix aliases");
        var caseMatch=style("snippet/shared",Map.of("snippet/style/SHARED.json",bytes("{}")));check(rename(caseMatch,"generators/a.json","b").report.permitted(),"exact Windows snippet file matching is case-insensitive");
        var typedPrefix=style("snippet/other/name",Map.of("snippet/style/other/name.json",bytes("{}")));check(rename(typedPrefix,"generators/a.json","b").report.permitted(),"foreign-looking prefix remains inside the expected snippet kind exactly as native substring rules prescribe");
        for(String payload:List.of("[]","\"snippet/style/b\"","{\"future\":\"kept\"}","{\"expression\":5}","{\"snippet\":null}","{\"fields\":null}")){
            var pack=style("snippet/a",Map.of("snippet/style/a.json",bytes(payload),"snippet/style/b.json",bytes("{}")));check(!rename(pack,"generators/a.json","b").report.permitted(),"unsupported snippet payload shape/field/reference type blocks complete review");
        }
        var unknown=style("snippet/a",Map.of("snippet/unknown/a.json",bytes("{}")));check(!rename(unknown,"generators/a.json","b").report.permitted(),"unknown snippet kind is not inferred from payload fields");
        var cycle=style("snippet/a",Map.of("snippet/style/a.json",bytes("{\"fracture\":\"snippet/b\"}"),"snippet/style/b.json",bytes("{\"fracture\":\"snippet/a\"}")));
        check(rename(cycle,"generators/a.json","b").report.issues().stream().anyMatch(i->i.message().contains("Cyclic")),"cross-file cycle detected without recursive worker traversal");
        var self=style("snippet/a",Map.of("snippet/style/a.json",bytes("{\"fracture\":\"snippet/a\"}")));check(!rename(self,"generators/a.json","b").report.permitted(),"self snippet cycle blocked");
        var implicit=PackWorkspace.newPack(Map.of("biomes/a.json",bytes("{\"generators\":[\"snippet/default_layer\"]}"),"snippet/generator-layer/default_layer.json",bytes("{}"),"generators/default.json",bytes("{}")));
        var plan=rename(implicit,"generators/default.json","other");check(!plan.report.permitted() && plan.report.references().stream().anyMatch(r->r.implicit() && r.path().equals("snippet/generator-layer/default_layer.json")),"snippet payload class defaults participate without materialization");
        var scalar=style("snippet/shared",Map.of("snippet/style/shared.json",bytes("{\"imageMap\":\"map\"}"),"dimensions/a.json",bytes("{\"imageMaps\":[{\"map\":\"map\",\"application\":\"BIOME\"}]}"),"biomes/target.json",bytes("{}"),"image-maps/map.json",bytes("{\"fallbackTarget\":\"target\"}")));
        check(!rename(scalar,"biomes/target.json","other").report.permitted(),"snippet scalar use participates in mixed categorical-role guards");
    }
    private static void depthAndLinkBudgets()throws Exception{
        for(int count:new int[]{ResourceOperation.MAX_SNIPPET_DEPTH,ResourceOperation.MAX_SNIPPET_DEPTH+1}){
            var files=new TreeMap<String,byte[]>();for(int i=0;i<count;i++)files.put("snippet/style/n"+i+".json",bytes(i+1==count?"{}":"{\"fracture\":\"snippet/n"+(i+1)+"\"}"));
            var pack=style("snippet/n0",files);check(rename(pack,"generators/a.json","b").report.permitted()==(count==ResourceOperation.MAX_SNIPPET_DEPTH),"finite snippet depth boundary is explicit");
        }
        for(int extra:new int[]{8,9}){
            var files=new TreeMap<String,byte[]>();files.put("objects/free.iob",new byte[]{1});files.put("snippet/biome-palette/shared.json",bytes("{}"));
            for(int i=0;i<5;i++){int count=i==4?extra:4094;String[] values=new String[count];Arrays.fill(values,"\"snippet/shared\"");files.put("biomes/b"+i+".json",bytes("{\"layers\":["+String.join(",",values)+"]}"));}
            var pack=PackWorkspace.newPack(files);check(rename(pack,"objects/free.iob","other").report.permitted()==(extra==8),"16,384-link scan boundary never silently omits snippet dependencies");
        }
    }
}
