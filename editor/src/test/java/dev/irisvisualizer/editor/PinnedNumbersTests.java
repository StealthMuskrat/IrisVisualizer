package dev.irisvisualizer.editor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/** Deferred exact-draft, native-width, source-boundary and persistence contracts. */
final class PinnedNumbersTests {
    private static byte[] bytes(String text){return text.getBytes(StandardCharsets.UTF_8);}
    private static void check(boolean value,String message){if(!value)throw new AssertionError("Pinned numeric drafts: "+message);}
    private static void reject(Runnable action){try{action.run();}catch(IllegalArgumentException expected){return;}throw new AssertionError("Expected rejected numeric draft");}
    private static String input(String type,String value){return PinnedNumbers.input(type,null,null,null,value);}
    private static void token(byte[] source,String field,String expected){check(new LosslessJson(source).tokenPath(field).equals(expected),"exact token at "+field);}
    static void run(Path temp)throws Exception{
        for(String type:List.of("int","long","double")){
            for(String invalid:List.of("+1","01","-01","0x1.0p0","1f","1D","NaN","Infinity","-Infinity"," 1","1 ",".5","1.","1e","１","null","true","\"1\"","","1".repeat(PinnedNumbers.MAX_CHARACTERS+1)))reject(()->input(type,invalid));
            reject(()->input(type,null));
            for(String valid:List.of("0","-0","3","3.0","3e0","-12E+0"))check(input(type,valid).equals(valid),"equivalent numeric spelling is retained for native "+type);
        }
        for(String invalid:List.of("1.000000000000000000000001","2147483648","-2147483649","1e1000"))reject(()->input("int",invalid));
        for(String valid:List.of("2147483647","-2147483648","2.147483647e9"))input("int",valid);
        for(String invalid:List.of("9223372036854775808","-9223372036854775809","9007199254740993.0000000001"))reject(()->input("long",invalid));
        for(String valid:List.of("9007199254740993","9223372036854775807","-9223372036854775808","9.007199254740993e15"))input("long",valid);
        for(String invalid:List.of("1e309","-1e309","1e-10000","-1e-10000"))reject(()->input("double",invalid));
        for(String valid:List.of("-0.0","0e-10000","4.9e-324","1.7976931348623157e308","0.10000000000000001"))input("double",valid);
        reject(()->input("float","1"));reject(()->input(null,"1"));
        reject(()->PinnedNumbers.input("double","0","1",null,"1.00000000000000000001"));
        reject(()->PinnedNumbers.input("double","0","1",null,"-1e-100"));
        reject(()->PinnedNumbers.input("int","0","8","1,2,4,8","4.00000000000000000001"));
        check(PinnedNumbers.input("int","0","8","1,2,4,8","4e0").equals("4e0"),"discrete options compare exact values without rewriting accepted spelling");
        var definitions=new Properties();definitions.setProperty("x.type","int");definitions.setProperty("x.minimum","0");definitions.setProperty("x.maximum","8");
        check(PinnedNumbers.supported(definitions,"x","-99") && !PinnedNumbers.supported(definitions,"x","1.0000000000000001") && !PinnedNumbers.supported(definitions,"x","2147483648"),"stored range errors stay correctable; wrong native integer shape/width does not");
        definitions.setProperty("x.type","double");check(PinnedNumbers.supported(definitions,"x","1e-10000") && !PinnedNumbers.supported(definitions,"x","1e10000"),"stored native double underflow remains inspectable without inventing a value; overflow stays read-only");
        rootEditors();nestedEditors();pairsAndStorage(temp);
        System.out.println("PASS: exact pinned numeric draft grammar and token splicing, int32/int64 limits beyond double precision, decimal bounds/discrete choices, finite double and underflow handling, source-bound read-only types and atomic paired generator heights, nested/fractured/noise/terrain scopes, legacy double API compatibility and exact Save/Copy/As/ZIP preservation without native generation");
    }
    private static void rootEditors(){
        byte[] dimension=bytes("{\r\n \"fluidHeight\":62,\"landChance\":1e0,\"terrainSamplingStep\":4,\"importedStructures\":{\"undergroundYShift\":0,\"future\":9007199254740993},\"unknown\":\"雪\\u0023\"\r\n}");
        token(DimensionFields.editText("dimensions/a.json",dimension,"fluidHeight","63.0"),"fluidHeight","63.0");
        token(DimensionFields.editText("dimensions/a.json",dimension,"terrainSamplingStep","8e0"),"terrainSamplingStep","8e0");
        byte[] shifted=DimensionFields.editText("dimensions/a.json",dimension,"importedStructures.undergroundYShift","-12e0");
        check(new String(shifted,StandardCharsets.UTF_8).equals(new String(dimension,StandardCharsets.UTF_8).replace("\"undergroundYShift\":0","\"undergroundYShift\":-12e0")),"nested numeric edit preserves every unrelated byte and unknown property");
        reject(()->DimensionFields.editText("dimensions/a.json",dimension,"fluidHeight","62.000000000000000001"));
        reject(()->DimensionFields.editText("dimensions/a.json",dimension,"landChance","1.00000000000000001"));
        reject(()->DimensionFields.editText("dimensions/a.json",dimension,"terrainSamplingStep","5"));
        reject(()->DimensionFields.editText("dimensions/nested/a.json",dimension,"fluidHeight","1"));
        reject(()->DimensionFields.editText("mods/a.json",dimension,"fluidHeight","1"));
        reject(()->DimensionFields.editText("dimensions/a.json",dimension,"bedrock","1"));
        reject(()->DimensionFields.editText("dimensions/a.json",bytes("{}"),"fluidHeight","1"));
        reject(()->DimensionFields.editText("dimensions/a.json",bytes("{\"importedStructures\":null}"),"importedStructures.undergroundYShift","1"));
        token(DimensionFields.edit("dimensions/a.json",dimension,"fluidHeight",63),"fluidHeight","63");
        byte[] region=bytes("{\"rarity\":1,\"shoreHeightZoom\":3.14,\"future\":\"retained\"}");
        token(RegionFields.editText("regions/nested/a.json",region,"rarity","1e0"),"rarity","1e0");
        reject(()->RegionFields.editText("regions/a.json",region,"rarity","1.00000000000000001"));
        reject(()->RegionFields.editText("regions/a.json",region,"shoreHeightZoom","0x1.0p0"));
        byte[] biome=bytes("{\"rarity\":1,\"lockLayersMax\":5,\"oreDepositSizeMultiplier\":1}");
        token(BiomeFields.editText("biomes/a.json",biome,"rarity","2.0"),"rarity","2.0");
        reject(()->BiomeFields.editText("biomes/a.json",biome,"lockLayersMax","2147483648"));
        reject(()->BiomeFields.editText("biomes/a.json",biome,"oreDepositSizeMultiplier","1e-10000"));
        byte[] generator=bytes("{\"seed\":9007199254740993,\"offsetX\":0,\"interpolator\":{\"horizontalScale\":7,\"future\":true}}");
        token(GeneratorFields.editText("generators/nested/a.json",generator,"seed","9223372036854775807"),"seed","9223372036854775807");
        token(GeneratorFields.editText("generators/a.json",generator,"offsetX","-0.0"),"offsetX","-0.0");
        token(GeneratorFields.editText("generators/a.json",generator,"interpolator.horizontalScale","1.20e1"),"interpolator.horizontalScale","1.20e1");
        reject(()->GeneratorFields.editText("generators/a.json",generator,"offsetX","1f"));
        reject(()->GeneratorFields.editText("generators/a.json",generator,"seed","9007199254740993.00000001"));
        for(String unsupported:List.of("null","true","\"1\"","{}","[]","1.000000000000001","2147483648")){
            check(!BiomeFields.numericSupported("rarity",unsupported),"wrong native type/width is read-only in the root UI");
            byte[] source=bytes("{\"rarity\":"+unsupported+"}");reject(()->BiomeFields.editText("biomes/a.json",source,"rarity","2"));
        }
        token(DimensionFields.editText("dimensions/a.json",bytes("{\"landChance\":2}"),"landChance","0.5"),"landChance","0.5");
        check(Arrays.equals(dimension,DimensionFields.editText("dimensions/a.json",dimension,"landChance","1e0")),"applying the exact existing token is byte-preserving");
    }
    private static void nestedEditors(){
        byte[] noise=bytes("{\"cliffHeightGenerator\":{\"octaves\":1,\"seed\":9007199254740993,\"opacity\":1,\"style\":{\"zoom\":1,\"cellularFrequency\":0,\"cellularZoom\":-1},\"fracture\":[{\"octaves\":2}],\"future\":true}}");
        var cliff=NoiseFields.Target.cliffHeight();
        token(new LosslessJson(NoiseFields.editText("generators/a.json",noise,cliff,"seed","-9223372036854775808")).token("cliffHeightGenerator").getBytes(StandardCharsets.UTF_8),"seed","-9223372036854775808");
        reject(()->NoiseFields.editText("generators/a.json",noise,cliff,"octaves","1.00000000000000001"));
        reject(()->NoiseFields.editText("generators/a.json",noise,cliff,"opacity","1.00000000000000001"));
        byte[] child=NoiseFields.editText("generators/a.json",noise,cliff.child(0),"octaves","3e0");check(new String(child,StandardCharsets.UTF_8).equals(new String(noise,StandardCharsets.UTF_8).replace("\"octaves\":2","\"octaves\":3e0")),"fracture path retains exact numeric spelling and sibling bytes");
        var style=NoiseStyleFields.Target.noise(cliff);reject(()->NoiseStyleFields.editText("generators/a.json",noise,style,"zoom","1f"));reject(()->NoiseStyleFields.editText("generators/a.json",noise,style,"zoom","1e-10000"));
        token(NoiseStyleFields.editText("generators/a.json",noise,style,"cellularZoom","-2e0"),"cliffHeightGenerator.style.cellularZoom","-2e0");
        reject(()->NoiseStyleFields.editText("generators/a.json",noise,style,"cellularFrequency","1"));
        byte[] terrain=bytes("{\"terrain3D\":{\"seed\":1,\"amplitude\":64,\"future\":\"exact\"}}");
        token(Terrain3DFields.editText("biomes/a.json",terrain,"seed","9.007199254740993e15"),"terrain3D.seed","9.007199254740993e15");
        token(Terrain3DFields.editText("biomes/a.json",terrain,"amplitude","6.45e1"),"terrain3D.amplitude","6.45e1");
        reject(()->Terrain3DFields.editText("biomes/a.json",terrain,"amplitude","64f"));
        reject(()->Terrain3DFields.editText("regions/a.json",terrain,"amplitude","64"));
    }
    private static void pairsAndStorage(Path temp)throws Exception{
        String path="biomes/number.json";byte[] original=bytes("{\r\n\"rarity\":1,\"generators\":[{\"generator\":\"native\",\"min\":0,\"max\":32,\"unknown\":9007199254740993}],\"future\":\"雪\"\r\n}");
        var files=new LinkedHashMap<String,byte[]>();files.put(path,original);files.put("generators/native.json",bytes("{\"seed\":9007199254740993,\"offsetX\":0}"));files.put("objects/opaque.iob",new byte[]{0,-1,7});files.put("images/opaque.png",new byte[]{1,-1});files.put("unknown/future.json",bytes("malformed future data"));
        var pack=PackWorkspace.newPack(files);var drafts=new InspectorDrafts();drafts.update("unrelated","stored","unfinished");long revision=pack.revision();
        for(String bad:List.of("1.0000000000000001","2147483648","1f"))reject(()->BiomeGeneratorLinks.rangeText(path,pack.read(path),0,bad,"32"));
        reject(()->BiomeGeneratorLinks.rangeText(path,pack.read(path),0,"33","32"));
        reject(()->BiomeGeneratorLinks.addText(path,pack.read(path),pack,"native","1","2.0000000000000001"));
        check(pack.revision()==revision && pack.undoCount()==0 && drafts.any() && Arrays.equals(pack.read(path),original),"rejected paired/add drafts leave workspace/history/drafts/bytes unchanged");
        byte[] ranged=BiomeGeneratorLinks.rangeText(path,original,0,"1e1","2.0e1");var row=new LosslessJson(ranged).objectArray("generators").getFirst();check(row.token("min").equals("1e1") && row.token("max").equals("2.0e1") && row.token("unknown").equals("9007199254740993"),"paired exact values retain unknowns atomically");
        byte[] missing=bytes("{\"generators\":[{\"generator\":\"native\",\"future\":true}]}");var enabled=new LosslessJson(BiomeGeneratorLinks.rangeText(path,missing,0,"0e0","32.0")).objectArray("generators").getFirst();check(enabled.token("min").equals("0e0") && enabled.token("max").equals("32.0") && enabled.token("future").equals("true"),"missing pair members become explicit only on paired Apply");
        byte[] unsupported=bytes("{\"generators\":[{\"min\":0,\"max\":\"32\"}]}");reject(()->BiomeGeneratorLinks.rangeText(path,unsupported,0,"1","32"));
        pack.replace(path,BiomeFields.editText(path,ranged,"rarity","2e0"));pack.replace("generators/native.json",GeneratorFields.editText("generators/native.json",pack.read("generators/native.json"),"offsetX","-0.0"));
        var expected=new LinkedHashMap<String,byte[]>();for(String file:pack.paths())expected.put(file,pack.read(file));int undo=pack.undoCount();
        Path save=temp.resolve("pinned-numbers-save"),copy=temp.resolve("pinned-numbers-copy"),as=temp.resolve("pinned-numbers-as"),zip=temp.resolve("pinned-numbers.zip");pack.save(save);pack.saveCopy(copy);pack.exportZip(zip);pack.saveAs(as);
        for(var reopened:List.of(PackWorkspace.open(save),PackWorkspace.open(copy),PackWorkspace.open(as),PackWorkspace.importZip(zip)))for(var entry:expected.entrySet())check(Arrays.equals(entry.getValue(),reopened.read(entry.getKey())),"numeric tokens/unknown/opaque bytes survive Save/Copy/As/ZIP");
        check(pack.undoCount()==undo && drafts.any(),"storage and exact numeric splices retain unrelated drafts/history");
    }
}
