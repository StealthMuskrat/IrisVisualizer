package dev.irisvisualizer.editor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class RiverMaterialTests {
    private static final String PATH="dimensions/materials.json";
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static String text(byte[] bytes) { return new String(bytes,StandardCharsets.UTF_8); }
    private static void check(boolean value) { if (!value) throw new AssertionError("River material contract failed"); }
    private static void rejects(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Expected rejected river material operation");
    }
    private static LosslessJson rivers(byte[] value) { return HydrologyFields.rivers(HydrologyFields.hydrology(new LosslessJson(value))); }
    private static LosslessJson object(byte[] value,String target) { return RiverMaterialFields.object(rivers(value),target); }
    private static LosslessJson palette(byte[] value,String target) { return RiverMaterialFields.palette(object(value,target),target); }
    private static LosslessJson entry(byte[] value,String target,int index) { return RiverMaterialFields.entries(palette(value,target)).get(index); }
    private static byte[] withTarget(String target,String value) {
        // Separate fixtures ensure each path edits only its own owner.
        return bytes(switch(target) {
            case "surface.banks.shoreMaterial" -> "{\"hydrology\":{\"rivers\":{\"surface\":{\"banks\":{\"shoreMaterial\":"+value+"}}}}}";
            case "surface.banks.bankMaterial" -> "{\"hydrology\":{\"rivers\":{\"surface\":{\"banks\":{\"bankMaterial\":"+value+"}}}}}";
            case "surface.bed.material" -> "{\"hydrology\":{\"rivers\":{\"surface\":{\"bed\":{\"material\":"+value+"}}}}}";
            case "underground.bedMaterial" -> "{\"hydrology\":{\"rivers\":{\"underground\":{\"bedMaterial\":"+value+"}}}}";
            case "surface.bed.paddingPalette" -> "{\"hydrology\":{\"rivers\":{\"surface\":{\"bed\":{\"paddingPalette\":"+value+"}}}}}";
            default -> throw new IllegalArgumentException();
        });
    }
    static void run(Path temp) throws Exception {
        String original="{\r\n \"hydrology\":{\"rivers\":{\"enabled\":false,\"surface\":{\"enabled\":false,\"banks\":{\"future\":\"雪\\u0061\"},\"bed\":{}},\"underground\":{\"enabled\":false}}},\"large\":9007199254740993\r\n}\r\n";
        byte[] parent=bytes(original);
        check(RiverMaterialFields.defaultToken("enabled").equals("false") && RiverMaterialFields.defaultToken("depth").equals("1") && RiverMaterialFields.defaultToken("weight").equals("1"));
        for (String target : RiverMaterialFields.MATERIAL_TARGETS) {
            byte[] created=RiverMaterialFields.createMaterial(PATH,parent,target);
            var material=object(created,target);
            if (target.endsWith("shoreMaterial")) {
                check(material.token("enabled").equals("true") && material.token("depth").equals("2"));
                check(entry(created,target,0).stringToken("block").equals("sand"));
            } else check(material.fields().isEmpty());
            check(rivers(created).token("enabled").equals("false"));
            check(text(created).contains("\"future\":\"雪\\u0061\"") && text(created).endsWith("\"large\":9007199254740993\r\n}\r\n"));
            rejects(() -> RiverMaterialFields.createMaterial(PATH,created,target));
            byte[] empty=withTarget(target,"{}");
            check(object(RiverMaterialFields.setEnabled(PATH,empty,target,true),target).token("enabled").equals("true"));
            byte[] depth=RiverMaterialFields.enableDepth(PATH,empty,target);
            check(object(depth,target).token("depth").equals("1"));
            for (String valid : List.of("1","8","8e0")) RiverMaterialFields.setDepth(PATH,depth,target,valid);
            for (String invalid : List.of("0","9","1.000000000000000001","2147483648","NaN","Infinity","")) rejects(() -> RiverMaterialFields.setDepth(PATH,depth,target,invalid));
            rejects(() -> RiverMaterialFields.setDepth(PATH,empty,target,"2"));
            rejects(() -> RiverMaterialFields.enableDepth(PATH,depth,target));
            byte[] explicitPalette=RiverMaterialFields.createPalette(PATH,empty,target);
            check(entry(explicitPalette,target,0).stringToken("block").equals("STONE"));
            check(object(explicitPalette,target).token("enabled")==null && object(explicitPalette,target).token("depth")==null);
            rejects(() -> RiverMaterialFields.createPalette(PATH,explicitPalette,target));
            for (String wrong : List.of("null","[]","1","true","\"snippet\"")) {
                byte[] bad=withTarget(target,wrong);
                rejects(() -> RiverMaterialFields.createMaterial(PATH,bad,target));
                rejects(() -> RiverMaterialFields.createPalette(PATH,bad,target));
                rejects(() -> RiverMaterialFields.setEnabled(PATH,bad,target,true));
                rejects(() -> RiverMaterialFields.enableDepth(PATH,bad,target));
                check(DimensionFields.readOnlyPaths(new LosslessJson(bad)).contains("hydrology.rivers."+target));
                byte[] badPalette=withTarget(target,"{\"palette\":"+wrong+",\"depth\":1}");
                rejects(() -> RiverMaterialFields.createPalette(PATH,badPalette,target));
                rejects(() -> RiverMaterialFields.enableEntries(PATH,badPalette,target));
                check(object(RiverMaterialFields.setDepth(PATH,badPalette,target,"2"),target).token("palette").equals(wrong));
                for (String field : List.of("enabled","depth")) {
                    String badScalar=wrong.equals("true") && field.equals("enabled") ? "0" : wrong;
                    byte[] wrongScalar=withTarget(target,"{\""+field+"\":"+badScalar+"}");
                    if (field.equals("enabled")) rejects(() -> RiverMaterialFields.setEnabled(PATH,wrongScalar,target,false));
                    else if (!wrong.equals("1")) rejects(() -> RiverMaterialFields.setDepth(PATH,wrongScalar,target,"2"));
                }
            }
        }
        String padding=RiverMaterialFields.PADDING;
        byte[] paddingDefault=RiverMaterialFields.createPalette(PATH,parent,padding);
        check(entry(paddingDefault,padding,0).stringToken("block").equals("clay") && entry(paddingDefault,padding,1).stringToken("block").equals("dirt"));
        rejects(() -> RiverMaterialFields.createMaterial(PATH,parent,padding));
        rejects(() -> RiverMaterialFields.setEnabled(PATH,paddingDefault,padding,true));
        var targets=new java.util.ArrayList<>(RiverMaterialFields.MATERIAL_TARGETS); targets.add(padding);
        for (String target : targets) {
            boolean standalone=target.equals(padding);
            String rawPalette="{\"style\":\"snippet\",\"zoom\":5e0,\"futurePalette\":true,\"palette\":[ {\"block\":\"stone\",\"weight\":1,\"data\":{\"future\":\"雪\\u0061\"},\"tileData\":{},\"backup\":{\"block\":\"unknown:ore\"},\"debug\":true,\"large\":9007199254740993},\r\n {\"block\":\"unknown:existing\",\"weight\":999} ]}";
            String raw=standalone ? rawPalette : "{\"depth\":1,\"enabled\":false,\"futureMaterial\":null,\"palette\":"+rawPalette+"}";
            byte[] source=withTarget(target,raw),copy=source.clone();
            byte[] block=RiverMaterialFields.setBlock(PATH,source,target,0,"Minecraft:DIRT[snowy=false]");
            check(text(block).equals(text(source).replace("\"block\":\"stone\"","\"block\":\"Minecraft:DIRT[snowy=false]\"")));
            check(text(RiverMaterialFields.setWeight(PATH,source,target,0,"6.4e1")).equals(text(source).replace("\"weight\":1,","\"weight\":64,")));
            for (String invalid : List.of("0","65","1000","1.000000000000000001","2147483648","NaN","Infinity","")) rejects(() -> RiverMaterialFields.setWeight(PATH,source,target,0,invalid));
            for (String invalid : List.of(""," ","water"," LAVA ","Minecraft:BUBBLE_COLUMN[drag=true]","custom:stone")) {
                rejects(() -> RiverMaterialFields.setBlock(PATH,source,target,0,invalid));
                rejects(() -> RiverMaterialFields.change(PATH,source,target,RiverMaterialFields.Action.ADD,-1,invalid));
            }
            for (String valid : List.of("stone"," minecraft:STONE ","minecraft:stone[unknown=property]","unknown_unverified_block")) {
                check(entry(RiverMaterialFields.setBlock(PATH,source,target,0,valid),target,0).stringToken("block").equals(valid));
            }
            byte[] added=RiverMaterialFields.change(PATH,source,target,RiverMaterialFields.Action.ADD,-1,"sand");
            check(entry(added,target,2).fields().equals(Set.of("block")) && entry(added,target,2).stringToken("block").equals("sand"));
            byte[] duplicate=RiverMaterialFields.change(PATH,source,target,RiverMaterialFields.Action.DUPLICATE,0,null);
            check(Arrays.equals(entry(duplicate,target,0).sourceBytes(),entry(duplicate,target,1).sourceBytes()));
            byte[] moved=RiverMaterialFields.change(PATH,source,target,RiverMaterialFields.Action.DOWN,0,null);
            check(Arrays.equals(entry(source,target,0).sourceBytes(),entry(moved,target,1).sourceBytes()));
            check(Arrays.equals(entry(source,target,1).sourceBytes(),entry(moved,target,0).sourceBytes()));
            byte[] restored=RiverMaterialFields.change(PATH,moved,target,RiverMaterialFields.Action.UP,1,null);
            check(Arrays.equals(entry(source,target,0).sourceBytes(),entry(restored,target,0).sourceBytes()));
            byte[] single=RiverMaterialFields.change(PATH,source,target,RiverMaterialFields.Action.REMOVE,1,null);
            check(RiverMaterialFields.entries(palette(single,target)).size()==1);
            rejects(() -> RiverMaterialFields.change(PATH,single,target,RiverMaterialFields.Action.REMOVE,0,null));
            rejects(() -> RiverMaterialFields.change(PATH,source,target,RiverMaterialFields.Action.UP,0,null));
            rejects(() -> RiverMaterialFields.change(PATH,source,target,RiverMaterialFields.Action.DOWN,1,null));
            rejects(() -> RiverMaterialFields.change(PATH,source,target,RiverMaterialFields.Action.DUPLICATE,-1,null));
            check(Arrays.equals(source,copy));
            var readOnly=DimensionFields.readOnlyPaths(new LosslessJson(source));
            String palettePath="hydrology.rivers."+target+(standalone ? "" : ".palette");
            check(readOnly.contains(palettePath+".style") && readOnly.contains(palettePath+".palette[0].tileData") && readOnly.contains(palettePath+".palette[0].debug"));
            for (String list : List.of("null","{}","\"snippet\"","[{} , 1]","[null]","[\"stone\"]")) {
                String badPalette="{\"palette\":"+list+"}";
                byte[] bad=withTarget(target,standalone ? badPalette : "{\"palette\":"+badPalette+"}");
                rejects(() -> RiverMaterialFields.change(PATH,bad,target,RiverMaterialFields.Action.ADD,-1,"stone"));
                rejects(() -> RiverMaterialFields.enableEntries(PATH,bad,target));
                rejects(() -> RiverMaterialFields.setBlock(PATH,bad,target,0,"stone"));
            }
            byte[] missing=withTarget(target,standalone ? "{\"future\":5}" : "{\"palette\":{\"future\":5}}");
            byte[] enabled=RiverMaterialFields.enableEntries(PATH,missing,target);
            check(palette(enabled,target).token("future").equals("5") && entry(enabled,target,0).stringToken("block").equals("STONE"));
            rejects(() -> RiverMaterialFields.enableEntries(PATH,enabled,target));
            byte[] empty=withTarget(target,standalone ? "{\"palette\":[]}" : "{\"palette\":{\"palette\":[]}}");
            check(RiverMaterialFields.entries(palette(RiverMaterialFields.change(PATH,empty,target,RiverMaterialFields.Action.ADD,-1,"clay"),target)).size()==1);
            byte[] absentBlock=withTarget(target,standalone ? "{\"palette\":[{}]}" : "{\"palette\":{\"palette\":[{}]}}");
            byte[] assigned=RiverMaterialFields.setBlock(PATH,absentBlock,target,0,"stone");
            check(entry(assigned,target,0).fields().equals(Set.of("block")));
            byte[] weighted=RiverMaterialFields.enableWeight(PATH,assigned,target,0);
            check(entry(weighted,target,0).token("weight").equals("1"));
            rejects(() -> RiverMaterialFields.enableWeight(PATH,weighted,target,0));
            rejects(() -> RiverMaterialFields.setWeight(PATH,assigned,target,0,"2"));
            for (String wrong : List.of("null","[]","{}","true","1")) {
                String badEntry="{\"palette\":[{\"block\":"+wrong+",\"weight\":"+wrong+"}]}";
                byte[] bad=withTarget(target,standalone ? badEntry : "{\"palette\":"+badEntry+"}");
                rejects(() -> RiverMaterialFields.setBlock(PATH,bad,target,0,"stone"));
                if (!wrong.equals("1")) rejects(() -> RiverMaterialFields.setWeight(PATH,bad,target,0,"2"));
            }
            for (String path : List.of("regions/a.json","biomes/a.json","generators/a.json","dimensions/nested/a.json","a.json")) {
                rejects(() -> RiverMaterialFields.createPalette(path,parent,target));
                rejects(() -> RiverMaterialFields.setBlock(path,source,target,0,"stone"));
                rejects(() -> RiverMaterialFields.change(path,source,target,RiverMaterialFields.Action.REMOVE,0,null));
            }
            for (String noParent : List.of("{}","{\"hydrology\":{}}","{\"hydrology\":{\"rivers\":{}}}","{\"hydrology\":{\"rivers\":{\"surface\":null,\"underground\":null}}}")) {
                rejects(() -> RiverMaterialFields.createPalette(PATH,bytes(noParent),target));
                if (!standalone) rejects(() -> RiverMaterialFields.createMaterial(PATH,bytes(noParent),target));
            }
            var drafts=new InspectorDrafts(); String listPrefix=RiverMaterialFields.listDraftPrefix(target);
            for (String key : List.of(listPrefix+"0.block",listPrefix+"0.weight",RiverMaterialFields.draftPrefix(target)+"depth",RiverMaterialFields.listDraftPrefix(target.equals(padding) ? targets.get(0) : padding)+"0.block")) drafts.update(key,"a","b");
            check(!drafts.transitionUnder(listPrefix,() -> false,() -> { throw new AssertionError(); }) && drafts.count()==4);
            try { drafts.transitionUnder(listPrefix,() -> true,() -> { throw new IOException("failed write"); }); throw new AssertionError(); } catch (IOException expected) { check(drafts.count()==4); }
            check(drafts.transitionUnder(listPrefix,() -> true,() -> {}) && drafts.count()==2);
        }
        rejects(() -> RiverMaterialFields.createPalette(PATH,parent,"surface.sources.palette"));
        check(FieldCoverage.fields("riverMaterial").size()==3 && FieldCoverage.fields("riverMaterial").stream().allMatch(FieldCoverage.Field::implemented));
        check(FieldCoverage.fields("materialPalette").size()==3 && FieldCoverage.fields("blockData").size()==6);
        byte[] all=paddingDefault;
        for (String target : RiverMaterialFields.MATERIAL_TARGETS) all=RiverMaterialFields.createMaterial(PATH,all,target);
        var workspace=PackWorkspace.newPack(Map.of(PATH,all,"dimensions/raw.json",withTarget(padding,"{\"palette\":[{\"block\":\"unknown:existing\",\"future\":true}]}")));
        workspace.save(temp.resolve("river-materials")); workspace.exportZip(temp.resolve("river-materials.zip"));
        var reopened=PackWorkspace.open(temp.resolve("river-materials")); var imported=PackWorkspace.importZip(temp.resolve("river-materials.zip"));
        for (String path : workspace.paths()) {
            check(Arrays.equals(workspace.read(path),reopened.read(path)) && Arrays.equals(workspace.read(path),imported.read(path)));
        }
        System.out.println("PASS: river material contextual/class defaults, depth/weight native bounds, namespace/fluid checks, list operations, draft isolation and lossless Save/ZIP contracts");
    }
}
