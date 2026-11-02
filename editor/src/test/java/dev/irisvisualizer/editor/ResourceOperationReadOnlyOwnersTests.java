package dev.irisvisualizer.editor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Deferred end-of-UI contracts: automatic reference maintenance never grants read-only editing authority. */
final class ResourceOperationReadOnlyOwnersTests {
    private static byte[] bytes(String value){return ResourceOperationTests.bytes(value);}
    private static void check(boolean value,String message){ResourceOperationTests.check(value,message);}
    private static String text(PackWorkspace pack,String path){return new String(pack.read(path),StandardCharsets.UTF_8);}
    private static ResourceOperation.Prepared rename(PackWorkspace pack,String path,String name)throws Exception{return ResourceOperationTests.prepare(pack,ResourceOperation.Mode.RENAME,path,name);}
    private static PackWorkspace fixture()throws Exception{
        return PackWorkspace.newPack(Map.ofEntries(
            Map.entry("Schema.json",bytes("[\"alias\",\"shape\",\"formula\",\"objects\"]")),
            Map.entry("loot/table.json",bytes("{\"name\":\"alias\",\"loot\":[{\"type\":\"alias\",\"customNbt\":{\"object\":\"shape\",\"nested\":{\"expression\":\"formula\"},\"long\":9007199254740993}}]}")),
            Map.entry("spawners/day.json",bytes("{\"spawns\":[{\"entity\":\"named\"}],\"group\":\"NORMAL\"}")),
            Map.entry("entities/named.json",bytes("{\"type\":\"COW\",\"customName\":\"alias\",\"passengers\":[{\"type\":\"PIG\",\"helmet\":{\"type\":\"STONE\",\"customNbt\":{\"block\":\"alias\"}}}],\"rawCommands\":[{\"commands\":[\"alias\",\"shape\"]}]}")),
            Map.entry("markers/point.json",bytes("{\"spawners\":[\"day\"]}")),
            Map.entry("mods/modify.json",bytes("{\r\n\"name\":\"alias\",\"blockReplacers\":[{\"find\":[{\"block\":\"alias\"}],\"replace\":{\"palette\":[{\"block\":\"alias\"}]}}],\"styleReplacers\":[{\"replace\":{\"expression\":\"formula\",\"imageMap\":\"height\"}}],\"biomeObjectPlacementInjectors\":[{\"place\":[{\"place\":[\"shape\"]}]}]\r\n}")),
            Map.entry("blocks/alias.json",bytes("{\"block\":\"minecraft:stone\"}")),
            Map.entry("objects/shape.iob",new byte[]{0,-1,9}),
            Map.entry("expressions/formula.json",bytes("{\"expression\":\"alias + shape\"}")),
            Map.entry("image-maps/height.json",bytes("{\"source\":\"pixels\"}")),
            Map.entry("images/pixels.png",new byte[]{0,-1,8})));
    }
    static void run(Path temp)throws Exception{
        var pack=fixture();String originalMod=text(pack,"mods/modify.json");
        var originals=new java.util.TreeMap<String,byte[]>();for(String path:pack.paths())originals.put(path,pack.read(path));
        var plan=rename(pack,"blocks/alias.json","renamed");check(plan.report.permitted(),plan.report.issues().toString());
        check(plan.report.changedFiles()==1 && plan.report.references().stream().filter(r->r.replacement()!=null).count()==2,"two typed mod material references share one rewritten read-only owner");
        pack.installResourceOperation(plan);
        check(text(pack,"mods/modify.json").equals(originalMod.replace("\"block\":\"alias\"","\"block\":\"renamed\"")),"only mod material reference tokens change; display name and formatting remain exact");
        for(String path:List.of("Schema.json","loot/table.json","spawners/day.json","entities/named.json","markers/point.json"))check(Arrays.equals(originals.get(path),pack.read(path)),"read-only item IDs/NBT/commands/foreign references and ordering metadata remain byte-exact");
        ResourceOperationTests.apply(pack,ResourceOperation.Mode.RENAME,"objects/shape.iob","tree");
        check(text(pack,"mods/modify.json").contains("\"place\":[\"tree\"]"),"nested object placement in read-only modification maintained");
        ResourceOperationTests.apply(pack,ResourceOperation.Mode.RENAME,"expressions/formula.json","equation");
        ResourceOperationTests.apply(pack,ResourceOperation.Mode.RENAME,"image-maps/height.json","new_height");
        check(text(pack,"mods/modify.json").contains("\"expression\":\"equation\"") && text(pack,"mods/modify.json").contains("\"imageMap\":\"new_height\""),"read-only replacement styles participate in typed noise-source maintenance");
        check(text(pack,"expressions/equation.json").equals("{\"expression\":\"alias + shape\"}"),"expression program is not a pack resource string");
        for(String path:List.of("loot/table.json","spawners/day.json","entities/named.json","markers/point.json","mods/modify.json")){
            String family=path.substring(0,path.indexOf('/'));
            check(ResourceOperation.ownerModel(path)!=null && !ResourceOperation.targetFamily(family) && ResourceTypes.fileFamily(path)==null,"reference ownership grants no resource mutation authority: "+family);
            ResourceOperationTests.rejects(()->pack.captureResourceOperation(ResourceOperation.Mode.RENAME,path,"forbidden"));
        }
        pack.save(temp.resolve("readonly-reference-owners"));pack.exportZip(temp.resolve("readonly-reference-owners.zip"));
        var reopened=PackWorkspace.open(temp.resolve("readonly-reference-owners"));var imported=PackWorkspace.importZip(temp.resolve("readonly-reference-owners.zip"));
        for(String path:pack.paths())check(Arrays.equals(pack.read(path),reopened.read(path)) && Arrays.equals(pack.read(path),imported.read(path)),"read-only owner reference edits Save/reopen/ZIP exactly");
        var selectors=PackWorkspace.newPack(Map.of("mods/selectors.json",bytes("{\"forDimension\":\"world\",\"removeBiomes\":[\"forest\"],\"removeRegions\":[\"land\"],\"removeObjects\":[\"shape\"]}"),"dimensions/world.json",bytes("{}"),"biomes/forest.json",bytes("{}"),"regions/land.json",bytes("{}"),"objects/shape.iob",new byte[]{1},"expressions/free.json",bytes("{}")));
        for(String path:List.of("dimensions/world.json","biomes/forest.json","regions/land.json","objects/shape.iob"))check(!rename(selectors,path,"other").report.permitted(),"uncertified mod selector retains its family guard");
        check(rename(selectors,"expressions/free.json","other").report.permitted(),"unrelated family is not blocked by a mod selector");
        for(String payload:List.of("{}","null","[\"alias\",{}]","[null]")){
            var malformed=PackWorkspace.newPack(Map.of("Schema.json",bytes(payload),"objects/shape.iob",new byte[]{1}));check(!rename(malformed,"objects/shape.iob","other").report.permitted(),"metadata exemption is restricted to the exact field-order array shape");
        }
        for(String path:List.of("loots/table.json","unknown/owner.json")){
            var unknown=PackWorkspace.newPack(Map.of(path,bytes("{}"),"objects/shape.iob",new byte[]{1}));check(!rename(unknown,"objects/shape.iob","other").report.permitted(),"unknown JSON owner spelling remains blocking");
        }
        for(String payload:List.of("{\"fields\":null}","{\"loot.implicit.0\":null}")){
            var metadataKey=PackWorkspace.newPack(Map.of("entities/named.json",bytes(payload),"objects/shape.iob",new byte[]{1}));
            check(!rename(metadataKey,"objects/shape.iob","other").report.permitted(),"schema control metadata never authorizes an unknown serialized field, including null values");
        }
        var unknownField=PackWorkspace.newPack(Map.of("entities/named.json",bytes("{\"future\":{\"object\":\"shape\"}}"),"objects/shape.iob",new byte[]{1}));
        check(!rename(unknownField,"objects/shape.iob","other").report.permitted(),"unknown fields in read-only owners cannot authorize maintenance");
        unknownField.saveCopy(temp.resolve("readonly-owner-blocked-copy"));unknownField.exportZip(temp.resolve("readonly-owner-blocked.zip"));
        check(Arrays.equals(unknownField.read("entities/named.json"),PackWorkspace.importZip(temp.resolve("readonly-owner-blocked.zip")).read("entities/named.json")),"operation blocker never rewrites unknown data or gates Save/Export");
        System.out.println("PASS: read-only loot/spawner/entity/marker/mod owners, nested typed material/object/style maintenance without edit/file-operation authority, opaque item/NBT/command/foreign-string preservation, exact ordering-metadata shape, guarded selectors and unknown-owner/field blockers independent of Save/Export, byte-exact graph round trips");
    }
}
