package dev.irisvisualizer.editor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

final class CustomBiomeTests {
    private static final String PATH="biomes/nested/custom.json";
    private static final CustomBiomes.Context ROOT=CustomBiomes.Context.biome(0),SPAWN=ROOT.spawn(0),PARTICLE=ROOT.particleTarget();
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static String text(byte[] value) { return new String(value,StandardCharsets.UTF_8); }
    private static byte[] document(String list) { return bytes("{\"customDerivitives\":"+list+",\"futureRoot\":9007199254740993}"); }
    private static byte[] single(String entry) { return document("["+entry+"]"); }
    private static LosslessJson entry(byte[] raw,int index) { return CustomBiomes.entries(new LosslessJson(raw)).get(index); }
    private static LosslessJson spawn(byte[] raw,int index) { return CustomBiomes.spawns(entry(raw,0)).get(index); }
    private static LosslessJson particle(byte[] raw) { return CustomBiomes.particle(entry(raw,0)); }
    private static void check(boolean value) { if (!value) throw new AssertionError("Custom-biome contract failed"); }
    private static void rejects(Runnable action) { try { action.run(); } catch (IllegalArgumentException expected) { return; }throw new AssertionError("Expected rejected custom-biome operation"); }
    static void run(Path temp) throws Exception {
        check(CustomBiomes.EDITABLE.size()==15 && CustomBiomes.SPAWN_FIELDS.size()==5 && CustomBiomes.PARTICLE_FIELDS.size()==2 && CustomBiomes.MAXIMUM_ENTRIES==64);
        byte[] absent=bytes("{\r\n \"derivative\":\"minecraft:plains\",\"futureRoot\":\"雪\\u0061\"\r\n}"),created=CustomBiomes.change(PATH,absent,RiverProfiles.Action.ADD,-1,"rose");
        check(entry(created,0).fields().size()==1 && entry(created,0).stringToken("id").equals("rose"));check(Arrays.equals(absent,CustomBiomes.change(PATH,created,RiverProfiles.Action.REMOVE,0,null)));
        check(entry(CustomBiomes.change(PATH,document("null"),RiverProfiles.Action.ADD,-1,"rose"),0).stringToken("id").equals("rose"));check(CustomBiomes.entries(new LosslessJson(CustomBiomes.change(PATH,document("[]"),RiverProfiles.Action.ADD,-1,"rose"))).size()==1);
        for (String id : List.of("","Rose","two words","namespace:rose","a/b","a.b")) rejects(() -> CustomBiomes.change(PATH,absent,RiverProfiles.Action.ADD,-1,id));
        rejects(() -> CustomBiomes.change(PATH,created,RiverProfiles.Action.ADD,-1,"rose"));rejects(() -> CustomBiomes.change(PATH,single("{\"id\":\"ROSE\"}"),RiverProfiles.Action.ADD,-1,"rose"));
        byte[] more=CustomBiomes.change(PATH,created,RiverProfiles.Action.ADD,-1,"blue");rejects(() -> CustomBiomes.setText(PATH,more,ROOT,"id","blue"));check(entry(CustomBiomes.setText(PATH,created,ROOT,"id","pink"),0).stringToken("id").equals("pink"));
        for (var context : List.of(ROOT,SPAWN,PARTICLE)) {
            byte[] base=context==ROOT ? created : context==SPAWN ? CustomBiomes.changeSpawn(PATH,created,ROOT,RiverProfiles.Action.ADD,-1,"minecraft:cow") : CustomBiomes.enableParticle(PATH,created,ROOT);
            for (String field : context==ROOT ? List.of("temperature","humidity","spawnRarity") : context==SPAWN ? List.of("weight") : List.of("rarity")) {
                byte[] enabled=CustomBiomes.enableScalar(PATH,base,context,field);rejects(() -> CustomBiomes.enableScalar(PATH,enabled,context,field));rejects(() -> CustomBiomes.setScalar(PATH,base,context,field,"1"));
                String min=field.equals("temperature") || field.equals("humidity") ? "-3" : field.equals("spawnRarity") ? "0" : "1",max=field.equals("temperature") || field.equals("humidity") ? "3" : field.equals("spawnRarity") ? "20" : field.equals("weight") ? "1000" : "10000";
                CustomBiomes.setScalar(PATH,enabled,context,field,min);CustomBiomes.setScalar(PATH,enabled,context,field,max);
                for (String invalid : List.of("-4","10001","NaN","Infinity","1e999","")) rejects(() -> CustomBiomes.setScalar(PATH,enabled,context,field,invalid));
                if (!field.equals("temperature") && !field.equals("humidity")) rejects(() -> CustomBiomes.setScalar(PATH,enabled,context,field,"1.000000000000000001"));
                for (String token : List.of("null","true","[]","{}","\"1\"")) {
                    byte[] wrong=context==ROOT ? single("{\""+field+"\":"+token+"}") : context==SPAWN ? single("{\"spawns\":[{\""+field+"\":"+token+"}]}") : single("{\"ambientParticle\":{\""+field+"\":"+token+"}}");rejects(() -> CustomBiomes.setScalar(PATH,wrong,context,field,"1"));check(!CustomBiomes.readOnlyPaths(new LosslessJson(wrong)).isEmpty());
                }
            }
        }
        for (String field : List.of("category","downfallType")) {
            check(CustomBiomes.choices(ROOT,field).size()==(field.equals("category") ? 17 : 3));
            for (String choice : CustomBiomes.choices(ROOT,field)) check(entry(CustomBiomes.setEnum(PATH,created,ROOT,field,choice),0).stringToken(field).equals(choice));
            rejects(() -> CustomBiomes.setEnum(PATH,created,ROOT,field,"UNKNOWN"));rejects(() -> CustomBiomes.setEnum(PATH,single("{\""+field+"\":null}"),ROOT,field,CustomBiomes.choices(ROOT,field).getFirst()));
        }
        for (String field : CustomBiomes.COLORS) {
            check(entry(CustomBiomes.setText(PATH,created,ROOT,field,"#12aBef"),0).stringToken(field).equals("#12aBef"));
            for (String invalid : List.of("red","123456","#12345","#1234567","#xyzxyz")) rejects(() -> CustomBiomes.setText(PATH,created,ROOT,field,invalid));
            if (List.of("grassColor","foliageColor").contains(field)) check(entry(CustomBiomes.setText(PATH,created,ROOT,field,""),0).stringToken(field).isEmpty());else rejects(() -> CustomBiomes.setText(PATH,created,ROOT,field,""));
            rejects(() -> CustomBiomes.setText(PATH,single("{\""+field+"\":null}"),ROOT,field,"#112233"));
        }
        byte[] withSpawn=CustomBiomes.changeSpawn(PATH,created,ROOT,RiverProfiles.Action.ADD,-1,"mod:beetle");check(spawn(withSpawn,0).fields().size()==1 && spawn(withSpawn,0).stringToken("type").equals("mod:beetle"));
        check(Arrays.equals(created,CustomBiomes.changeSpawn(PATH,withSpawn,ROOT,RiverProfiles.Action.REMOVE,0,null)));
        for (String group : CustomBiomes.choices(SPAWN,"group")) check(spawn(CustomBiomes.setEnum(PATH,withSpawn,SPAWN,"group",group),0).stringToken("group").equals(group));check(CustomBiomes.choices(SPAWN,"group").size()==8);
        check(spawn(CustomBiomes.setEnum(PATH,single("{\"spawns\":[{\"group\":null}]}"),SPAWN,"group","MISC"),0).stringToken("group").equals("MISC"));
        byte[] counted=CustomBiomes.setCounts(PATH,withSpawn,SPAWN,"1","2147483647");check(spawn(counted,0).token("minCount").equals("1") && spawn(counted,0).token("maxCount").equals("2147483647"));
        CustomBiomes.setCounts(PATH,counted,SPAWN,"2e0","5.0");
        for (String[] pair : new String[][]{{"0","1"},{"2","1"},{"1","2147483648"},{"1.000000000000000001","5"},{"1","NaN"}}) rejects(() -> CustomBiomes.setCounts(PATH,counted,SPAWN,pair[0],pair[1]));
        for (String field : List.of("minCount","maxCount")) for (String token : List.of("null","true","1.5","2147483648","\"1\"")) {
            byte[] wrong=single("{\"spawns\":[{\""+field+"\":"+token+"}]}");rejects(() -> CustomBiomes.setCounts(PATH,wrong,SPAWN,"1","5"));check(CustomBiomes.readOnlyPaths(new LosslessJson(wrong)).contains("customDerivitives[0].spawns[0]."+field));
        }
        rejects(() -> CustomBiomes.enableScalar(PATH,withSpawn,SPAWN,"minCount"));rejects(() -> CustomBiomes.setScalar(PATH,counted,SPAWN,"maxCount","5"));
        byte[] withParticle=CustomBiomes.enableParticle(PATH,created,ROOT);check(particle(withParticle).fields().isEmpty() && CustomBiomes.defaultToken(PARTICLE,"particle").equals("minecraft:flash") && CustomBiomes.defaultToken(PARTICLE,"rarity").equals("35"));
        rejects(() -> CustomBiomes.enableParticle(PATH,withParticle,ROOT));byte[] disabled=CustomBiomes.disableParticle(PATH,withParticle,ROOT);check(entry(disabled,0).token("ambientParticle").equals("null") && particle(CustomBiomes.enableParticle(PATH,disabled,ROOT)).fields().isEmpty());check(Arrays.equals(created,CustomBiomes.disableParticle(PATH,created,ROOT)));
        check(particle(CustomBiomes.setText(PATH,withParticle,PARTICLE,"particle","mod:mist")).stringToken("particle").equals("mod:mist"));
        for (String key : List.of("cow","Minecraft:cow","#minecraft:tag","mod:../bad","mod:/bad","mod:a//b","mod:a/","")) {
            rejects(() -> CustomBiomes.changeSpawn(PATH,created,ROOT,RiverProfiles.Action.ADD,-1,key));rejects(() -> CustomBiomes.setText(PATH,withParticle,PARTICLE,"particle",key));rejects(() -> CustomBiomes.changeTag(PATH,created,ROOT,RiverProfiles.Action.ADD,-1,key));
        }
        byte[] tagged=CustomBiomes.changeTag(PATH,created,ROOT,RiverProfiles.Action.ADD,-1,"minecraft:is_overworld");check(CustomBiomes.tags(entry(tagged,0)).equals(List.of("minecraft:is_overworld")));
        check(Arrays.equals(created,CustomBiomes.changeTag(PATH,tagged,ROOT,RiverProfiles.Action.REMOVE,0,null)));tagged=CustomBiomes.changeTag(PATH,tagged,ROOT,RiverProfiles.Action.DUPLICATE,0,null);check(CustomBiomes.tags(entry(tagged,0)).size()==2);
        tagged=CustomBiomes.setTag(PATH,tagged,ROOT,1,"mod:forest");tagged=CustomBiomes.changeTag(PATH,tagged,ROOT,RiverProfiles.Action.DOWN,0,null);check(CustomBiomes.tags(entry(tagged,0)).equals(List.of("mod:forest","minecraft:is_overworld")));
        String raw="{\r\n \"customDerivitives\":[{\"id\":\"rose\",\"temperature\":1e0,\"skyColor\":\"legacy color\",\"tags\":[\"mod:old\\u005ftag\"],\"spawns\":[{\"type\":\" Cow \",\"minCount\":2e0,\"maxCount\":5,\"weight\":1,\"group\":null,\"futureSpawn\":9007199254740993}],\"ambientParticle\":{\"particle\":\"flash\",\"rarity\":35,\"futureParticle\":\"雪\\u0061\"},\"futureCustom\":9007199254740993}],\"derivative\":\"mod:vanilla\",\"futureRoot\":9223372036854775807\r\n}\r\n";
        byte[] source=bytes(raw),copy=source.clone();check(text(CustomBiomes.setText(PATH,source,ROOT,"skyColor","#112233")).equals(raw.replace("\"legacy color\"","\"#112233\"")));check(text(CustomBiomes.setScalar(PATH,source,ROOT,"temperature","0.5")).equals(raw.replace("\"temperature\":1e0","\"temperature\":0.5")));
        byte[] duplicated=CustomBiomes.change(PATH,source,RiverProfiles.Action.DUPLICATE,0,"blue");check(text(entry(duplicated,1).sourceBytes()).equals(text(entry(source,0).sourceBytes()).replace("\"id\":\"rose\"","\"id\":\"blue\"")) && text(entry(duplicated,0).sourceBytes()).equals(text(entry(source,0).sourceBytes())));
        duplicated=CustomBiomes.change(PATH,duplicated,RiverProfiles.Action.DOWN,0,null);check(entry(duplicated,0).stringToken("id").equals("blue"));duplicated=CustomBiomes.change(PATH,duplicated,RiverProfiles.Action.UP,1,null);check(entry(duplicated,0).stringToken("id").equals("rose"));
        byte[] spawned=CustomBiomes.changeSpawn(PATH,source,ROOT,RiverProfiles.Action.DUPLICATE,0,null);check(text(spawn(spawned,0).sourceBytes()).equals(text(spawn(spawned,1).sourceBytes())));spawned=CustomBiomes.setText(PATH,spawned,ROOT.spawn(1),"type","mod:other");spawned=CustomBiomes.changeSpawn(PATH,spawned,ROOT,RiverProfiles.Action.DOWN,0,null);check(spawn(spawned,0).stringToken("type").equals("mod:other"));
        check(CustomBiomes.readOnlyPaths(new LosslessJson(source)).containsAll(List.of("customDerivitives[0].futureCustom","customDerivitives[0].spawns[0].futureSpawn","customDerivitives[0].ambientParticle.futureParticle")) && !CustomBiomes.readOnlyPaths(new LosslessJson(source)).contains("customDerivitives[0].spawns[0].group"));
        for (String malformed : List.of("true","{}","\"snippet/custom\"","[null]","[{},true]")) {
            byte[] wrong=document(malformed);rejects(() -> CustomBiomes.change(PATH,wrong,RiverProfiles.Action.ADD,-1,"new"));rejects(() -> CustomBiomes.setText(PATH,wrong,ROOT,"id","new"));check(CustomBiomes.readOnlyPaths(new LosslessJson(wrong)).contains("customDerivitives"));
        }
        for (String malformed : List.of("null","true","{}","\"snippet/spawn\"","[{},1]")) {
            byte[] wrong=single("{\"spawns\":"+malformed+"}");rejects(() -> CustomBiomes.changeSpawn(PATH,wrong,ROOT,RiverProfiles.Action.ADD,-1,"minecraft:cow"));rejects(() -> CustomBiomes.setText(PATH,wrong,SPAWN,"type","mod:other"));check(CustomBiomes.readOnlyPaths(new LosslessJson(wrong)).contains("customDerivitives[0].spawns"));
            byte[] wrongTags=single("{\"tags\":"+malformed+"}");rejects(() -> CustomBiomes.changeTag(PATH,wrongTags,ROOT,RiverProfiles.Action.ADD,-1,"mod:tag"));check(CustomBiomes.readOnlyPaths(new LosslessJson(wrongTags)).contains("customDerivitives[0].tags"));
        }
        for (String malformed : List.of("true","[]","1","\"snippet/particle\"")) { byte[] wrong=single("{\"ambientParticle\":"+malformed+"}");rejects(() -> CustomBiomes.enableParticle(PATH,wrong,ROOT));rejects(() -> CustomBiomes.disableParticle(PATH,wrong,ROOT));check(CustomBiomes.readOnlyPaths(new LosslessJson(wrong)).contains("customDerivitives[0].ambientParticle")); }
        byte[] oversized=document("["+"{},".repeat(64)+"{}]");rejects(() -> CustomBiomes.change(PATH,oversized,RiverProfiles.Action.REMOVE,0,null));rejects(() -> CustomBiomes.setText(PATH,oversized,ROOT,"id","new"));
        byte[] oversizedSpawns=single("{\"spawns\":["+"{},".repeat(64)+"{}]}");rejects(() -> CustomBiomes.changeSpawn(PATH,oversizedSpawns,ROOT,RiverProfiles.Action.REMOVE,0,null));rejects(() -> CustomBiomes.setCounts(PATH,oversizedSpawns,SPAWN,"1","5"));
        byte[] oversizedTags=single("{\"tags\":["+"\"mod:tag\",".repeat(64)+"\"mod:tag\"]}");rejects(() -> CustomBiomes.changeTag(PATH,oversizedTags,ROOT,RiverProfiles.Action.ADD,-1,"mod:next"));rejects(() -> CustomBiomes.setTag(PATH,oversizedTags,ROOT,0,"mod:next"));
        check(entry(CustomBiomes.setText(PATH,oversizedTags,ROOT,"id","new"),0).token("tags").equals(entry(oversizedTags,0).token("tags")));
        for (String path : List.of("dimensions/custom.json","regions/custom.json","generators/custom.json","other.json")) {
            rejects(() -> CustomBiomes.change(path,absent,RiverProfiles.Action.ADD,-1,"new"));rejects(() -> CustomBiomes.setText(path,source,ROOT,"id","new"));rejects(() -> CustomBiomes.setScalar(path,source,ROOT,"temperature","0"));rejects(() -> CustomBiomes.setEnum(path,source,ROOT,"category","plains"));rejects(() -> CustomBiomes.setCounts(path,source,SPAWN,"1","5"));rejects(() -> CustomBiomes.enableParticle(path,source,ROOT));rejects(() -> CustomBiomes.changeTag(path,source,ROOT,RiverProfiles.Action.ADD,-1,"mod:tag"));
        }
        rejects(() -> CustomBiomes.setText(PATH,absent,ROOT,"id","new"));rejects(() -> CustomBiomes.setCounts(PATH,created,SPAWN,"1","5"));rejects(() -> CustomBiomes.setText(PATH,created,PARTICLE,"particle","mod:mist"));
        rejects(() -> CustomBiomes.setText(PATH,source,SPAWN,"id","new"));rejects(() -> CustomBiomes.setEnum(PATH,source,ROOT,"id","plains"));rejects(() -> new CustomBiomes.Context(-1,-1,false));rejects(() -> new CustomBiomes.Context(0,0,true));
        check(Arrays.equals(source,copy));var drafts=new InspectorDrafts();String parent=ROOT.prefix()+"temperature",spawnMin=SPAWN.prefix()+"minCount",spawnMax=SPAWN.prefix()+"maxCount",particleKey=PARTICLE.prefix()+"particle",tagKey=CustomBiomes.listPrefix(ROOT,"tags")+"0",other="outside-custom#id";
        for (String key : List.of(parent,spawnMin,spawnMax,particleKey,tagKey,other)) drafts.update(key,"1","2");rejects(() -> CustomBiomes.setCounts(PATH,source,SPAWN,"8","2"));check(drafts.count()==6);CustomBiomes.setCounts(PATH,source,SPAWN,"2","8");drafts.applied(spawnMin);drafts.applied(spawnMax);check(drafts.count()==4);
        check(!drafts.transitionUnder(PARTICLE.prefix(),() -> false,() -> { throw new AssertionError(); }) && drafts.count()==4);try { drafts.transitionUnder(PARTICLE.prefix(),() -> true,() -> { throw new IOException("failed write"); });throw new AssertionError(); } catch (IOException expected) { check(drafts.count()==4); }
        check(drafts.transitionUnder(PARTICLE.prefix(),() -> true,() -> {}) && drafts.count()==3 && drafts.contains(parent) && drafts.contains(tagKey));check(drafts.transitionUnder(CustomBiomes.prefix(),() -> true,() -> {}) && drafts.count()==1 && drafts.contains(other));
        var workspace=PackWorkspace.newPack(Map.of(PATH,source,"biomes/unsupported.json",document("[\"snippet/custom\"]"),"objects/opaque.iob",new byte[]{0,127,-1,42}));workspace.replace(PATH,CustomBiomes.setText(PATH,workspace.read(PATH),ROOT,"skyColor","#112233"));workspace.replace(PATH,CustomBiomes.setCounts(PATH,workspace.read(PATH),SPAWN,"1","8"));workspace.replace(PATH,CustomBiomes.disableParticle(PATH,workspace.read(PATH),ROOT));
        workspace.save(temp.resolve("custom-biomes"));workspace.exportZip(temp.resolve("custom-biomes.zip"));var reopened=PackWorkspace.open(temp.resolve("custom-biomes"));var imported=PackWorkspace.importZip(temp.resolve("custom-biomes.zip"));for (String path : workspace.paths()) check(Arrays.equals(workspace.read(path),reopened.read(path)) && Arrays.equals(workspace.read(path),imported.read(path)));
        check(FieldCoverage.fields("customBiome").size()==15 && FieldCoverage.fields("customBiome").stream().allMatch(FieldCoverage.Field::implemented) && FieldCoverage.fields("customBiomeSpawn").size()==5 && FieldCoverage.fields("customBiomeSpawn").stream().allMatch(FieldCoverage.Field::implemented) && FieldCoverage.fields("customBiomeParticle").size()==2 && FieldCoverage.fields("customBiomeParticle").stream().allMatch(FieldCoverage.Field::implemented));
        System.out.println("PASS: all custom biome/spawn/particle fields, list/null/default lifecycles, local IDs/colors/enums/key syntax, exact scalar/paired counts, raw duplication/order, type/size/owner guards, scoped drafts and lossless Save/ZIP");
    }
}
