package dev.irisvisualizer.editor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Predicate;

/** Deferred supported-scope/default-omission contracts; no model loading or generation. */
final class ScalarDefaultTests {
    @FunctionalInterface private interface Change {byte[] apply(String path,byte[] source,String field);}
    private static byte[] bytes(String text){return text.getBytes(StandardCharsets.UTF_8);}
    private static void check(boolean value,String message){if(!value)throw new AssertionError("Scalar defaults: "+message);}
    private static void reject(Runnable action){try{action.run();}catch(IllegalArgumentException expected){return;}throw new AssertionError("Expected unsupported scalar reset");}
    private static void model(String path,List<String> fields,Predicate<String> scalar,Change enable,Change reset){
        byte[] original=bytes("{\"future\":{\"exact\":9007199254740993,\"escaped\":\"\\u96ea\"}}");
        for(String field:fields){
            if(!scalar.test(field)){reject(()->reset.apply(path,original,field));continue;}
            check(Arrays.equals(original,reset.apply(path,original,field)),"absent reset does not insert a value or ancestor: "+path+" "+field);
            byte[] enabled=enable.apply(path,original,field);check(new LosslessJson(enabled).tokenPath(field)!=null,"explicit enable stores its pinned default");
            byte[] omitted=reset.apply(path,enabled,field);var document=new LosslessJson(omitted);
            check(document.tokenPath(field)==null && document.token("future").equals(new LosslessJson(original).token("future")),"reset removes only the selected scalar and preserves unknown data: "+path+" "+field);
            check(Arrays.equals(omitted,reset.apply(path,omitted,field)),"repeated absent reset is byte-preserving");
        }
    }
    static void run(Path temp)throws Exception{
        model("dimensions/a.json",DimensionFields.EDITABLE,f->!DimensionFields.arrayField(f),DimensionFields::enable,DimensionFields::reset);
        model("regions/nested/a.json",RegionFields.EDITABLE,f->!RegionFields.arrayField(f),RegionFields::enable,RegionFields::reset);
        model("biomes/nested/a.json",BiomeFields.EDITABLE,f->true,BiomeFields::enable,BiomeFields::reset);
        model("generators/nested/a.json",GeneratorFields.EDITABLE,f->true,GeneratorFields::enable,GeneratorFields::reset);
        String original="{ \"future\":9007199254740993, \"fluidHeight\" : 63, \"bedrock\" : false, \"tail\":\"雪\\u0023\" }";
        check(new String(DimensionFields.reset("dimensions/a.json",bytes(original),"fluidHeight"),StandardCharsets.UTF_8).equals(original.replace("\"fluidHeight\" : 63, ","")),"middle-member reset preserves every unrelated byte");
        byte[] nested=bytes("{\"importedStructures\":{\"undergroundYShift\":12},\"future\":true}");
        check(new String(DimensionFields.reset("dimensions/a.json",nested,"importedStructures.undergroundYShift"),StandardCharsets.UTF_8).equals("{\"importedStructures\":{},\"future\":true}"),"empty existing parent remains explicit instead of being pruned");
        for(String wrong:List.of("null","\"snippet/foreign\"","[]"))reject(()->DimensionFields.reset("dimensions/a.json",bytes("{\"importedStructures\":"+wrong+"}"),"importedStructures.undergroundYShift"));
        for(String wrong:List.of("null","true","\"62\"","[]","{}","62.00000000000001","2147483648"))reject(()->DimensionFields.reset("dimensions/a.json",bytes("{\"fluidHeight\":"+wrong+"}"),"fluidHeight"));
        for(String wrong:List.of("null","1","\"false\"","{}"))reject(()->BiomeFields.reset("biomes/a.json",bytes("{\"lockLayers\":"+wrong+"}"),"lockLayers"));
        check(new LosslessJson(DimensionFields.reset("dimensions/a.json",bytes("{\"fluidHeight\":-1}"),"fluidHeight")).fields().isEmpty(),"type-valid out-of-bound data can be explicitly reset");
        reject(()->DimensionFields.reset("dimensions/nested/a.json",bytes("{}"),"fluidHeight"));reject(()->DimensionFields.reset("mods/a.json",bytes("{}"),"fluidHeight"));
        reject(()->BiomeFields.reset("biomes/a.json",bytes("{\"name\":\"keep\"}"),"name"));reject(()->GeneratorFields.reset("generators/a.json",bytes("{}"),"unknown"));
        nestedNoiseAndTerrain();persistenceAndPair(temp);
        System.out.println("PASS: supported scalar default omission across all original resource fields, absent/default distinction and idempotence, exact unknown bytes and retained parents, read-only type/width/array/resource guards, nested and recursive noise/style/terrain scopes with cellular constraints, atomic paired heights and exact draft keys, Save/reopen/Copy/As/ZIP and existing Undo/Redo without materialized defaults or generation");
    }
    private static void nestedNoiseAndTerrain(){
        String original="{\"cliffHeightGenerator\":{\"enabled\":false,\"seed\":9007199254740993,\"fracture\":[{\"octaves\":2,\"future\":true}],\"style\":{\"zoom\":3,\"cellularFrequency\":1,\"cellularZoom\":2,\"future\":\"\\u96ea\"}}}";
        byte[] source=bytes(original);var cliff=NoiseFields.Target.cliffHeight();
        check(new String(NoiseFields.reset("generators/a.json",source,cliff,"seed"),StandardCharsets.UTF_8).equals(original.replace("\"seed\":9007199254740993,","")),"exact signed64 seed omission never converts through double");
        check(new String(NoiseFields.reset("generators/a.json",source,cliff.child(0),"octaves"),StandardCharsets.UTF_8).equals(original.replace("\"octaves\":2,","")),"recursive noise child reset retains its parent/list and siblings");
        byte[] changed=NoiseFields.reset("generators/a.json",source,cliff,"enabled");check(new LosslessJson(changed).tokenPath("cliffHeightGenerator.enabled")==null,"noise toggle restores absence");
        reject(()->NoiseFields.reset("regions/a.json",source,cliff,"seed"));reject(()->NoiseFields.reset("generators/a.json",source,cliff,"style"));
        var style=NoiseStyleFields.Target.noise(cliff);changed=NoiseStyleFields.reset("generators/a.json",source,style,"cellularZoom");check(new LosslessJson(changed).tokenPath("cliffHeightGenerator.style.cellularZoom")==null && new LosslessJson(changed).tokenPath("cliffHeightGenerator.style.cellularFrequency").equals("1"),"style reset validates the default with the existing cellular frequency");
        byte[] invalid=bytes(original.replace("\"cellularFrequency\":1","\"cellularFrequency\":-1"));reject(()->NoiseStyleFields.reset("generators/a.json",invalid,style,"cellularZoom"));
        reject(()->NoiseStyleFields.reset("generators/a.json",source,style,"style"));
        byte[] terrain=bytes("{\"terrain3D\":{\"enabled\":false,\"seed\":9223372036854775807,\"amplitude\":64,\"unknown\":true}}");
        changed=Terrain3DFields.reset("biomes/a.json",terrain,"enabled");check(new LosslessJson(changed).tokenPath("terrain3D.enabled")==null && new LosslessJson(changed).tokenPath("terrain3D.seed").equals("9223372036854775807"),"profile flag reset preserves its active parent and exact seed");
        reject(()->Terrain3DFields.reset("regions/a.json",terrain,"enabled"));reject(()->Terrain3DFields.reset("biomes/a.json",bytes("{\"terrain3D\":null}"),"enabled"));
    }
    private static void persistenceAndPair(Path temp)throws Exception{
        String biome="biomes/defaults.json",dimension="dimensions/defaults.json",generator="generators/native.json";
        var files=new LinkedHashMap<String,byte[]>();files.put(biome,bytes("{\"lockLayers\":false,\"generators\":[{\"generator\":\"native\",\"min\":1,\"max\":32,\"unknown\":9007199254740993}],\"future\":\"雪\"}"));
        files.put(dimension,bytes("{\"fluidHeight\":63,\"importedStructures\":{\"undergroundYShift\":12,\"future\":true}}"));files.put(generator,bytes("{\"seed\":9007199254740993,\"future\":true}"));files.put("unknown/future.json",bytes("malformed future data"));files.put("objects/opaque.iob",new byte[]{0,1,-1});files.put("images/opaque.png",new byte[]{1,-1,7});
        var pack=PackWorkspace.newPack(files);Path root=temp.resolve("scalar-defaults-save");pack.save(root);var drafts=new InspectorDrafts();
        drafts.update("generator#0.min","1","unfinished");drafts.update("generator#0.max","32","unfinished");drafts.update("generator#10.min","1","keep");drafts.update("unrelated","old","keep");
        byte[] wrong=bytes("{\"generators\":[{\"min\":1,\"max\":\"32\"}]}");reject(()->BiomeFields.resetGeneratorRange(biome,wrong,0));
        check(pack.undoCount()==0 && drafts.count()==4 && Arrays.equals(pack.read(biome),files.get(biome)),"rejected pair reset leaves applied data/history and every draft intact");
        pack.replace(biome,BiomeFields.resetGeneratorRange(biome,pack.read(biome),0));drafts.applied("generator#0.min");drafts.applied("generator#0.max");
        var row=new LosslessJson(pack.read(biome)).objectArray("generators").getFirst();check(row.token("min")==null && row.token("max")==null && row.stringToken("generator").equals("native") && row.token("unknown").equals("9007199254740993"),"paired reset omits both members while retaining references/unknowns");
        check(drafts.count()==2 && drafts.contains("generator#10.min") && drafts.contains("unrelated"),"exact pair draft keys do not discard similarly prefixed other rows");
        byte[] omitted=pack.read(biome);pack.undo();check(new LosslessJson(pack.read(biome)).objectArray("generators").getFirst().token("max").equals("32"),"existing Undo restores the previous explicit pair");pack.redo();check(Arrays.equals(pack.read(biome),omitted),"existing Redo restores exact omitted pair bytes");
        pack.replace(biome,BiomeFields.reset(biome,pack.read(biome),"lockLayers"));pack.replace(dimension,DimensionFields.reset(dimension,pack.read(dimension),"fluidHeight"));pack.replace(dimension,DimensionFields.reset(dimension,pack.read(dimension),"importedStructures.undergroundYShift"));pack.replace(generator,GeneratorFields.reset(generator,pack.read(generator),"seed"));
        var expected=new LinkedHashMap<String,byte[]>();for(String path:pack.paths())expected.put(path,pack.read(path));int history=pack.undoCount();
        Path copy=temp.resolve("scalar-defaults-copy"),as=temp.resolve("scalar-defaults-as"),zip=temp.resolve("scalar-defaults.zip");pack.save(root);pack.saveCopy(copy);pack.exportZip(zip);pack.saveAs(as);
        for(var reopened:List.of(PackWorkspace.open(root),PackWorkspace.open(copy),PackWorkspace.open(as),PackWorkspace.importZip(zip))){
            for(var entry:expected.entrySet())check(Arrays.equals(reopened.read(entry.getKey()),entry.getValue()),"exact reset/unknown/opaque bytes across Save/reopen/Copy/As/ZIP");
            check(new LosslessJson(reopened.read(dimension)).token("fluidHeight")==null && new LosslessJson(reopened.read(dimension)).tokenPath("importedStructures.undergroundYShift")==null && new LosslessJson(reopened.read(generator)).token("seed")==null,"storage does not materialize omitted class defaults");
        }
        check(pack.undoCount()==history && drafts.count()==2,"Save modes preserve existing applied history and unrelated drafts");
    }
}
