package dev.irisvisualizer.editor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

final class NativeGenerationFilterTests {
    private static final String PATH="dimensions/native.json";
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static String text(byte[] value) { return new String(value,StandardCharsets.UTF_8); }
    private static byte[] document(String list,String token) { return bytes("{\""+(list.equals("frequencyOverrides") ? "importedStructures" : "importedFeatures")+"\":{\""+list+"\":"+token+",\"futurePolicy\":9007199254740993},\"futureRoot\":\"雪\"}"); }
    private static LosslessJson entry(byte[] raw,String list,int index) { return NativeGenerationFilters.entries(new LosslessJson(raw),list).get(index); }
    private static void check(boolean value) { if (!value) throw new AssertionError("Native generation filter contract failed"); }
    private static void rejects(Runnable action) { try { action.run(); } catch (IllegalArgumentException expected) { return; }throw new AssertionError("Expected rejected native generation filter operation"); }
    static void run(Path temp) throws Exception {
        byte[] absent=bytes("{\r\n \"future\":\"雪\\u0061\", \"large\":9007199254740993\r\n}\r\n");
        check(NativeGenerationFilters.steps().size()==11 && NativeGenerationFilters.steps().containsAll(List.of("RAW_GENERATION","UNDERGROUND_ORES","VEGETAL_DECORATION","TOP_LAYER_MODIFICATION")));
        byte[] rule=NativeGenerationFilters.change(PATH,absent,"biomeRules",RiverProfiles.Action.ADD,-1,null);check(entry(rule,"biomeRules",0).fields().isEmpty() && new LosslessJson(rule).tokenPath("importedFeatures.enabled")==null);
        for (String field : List.of("steps","disabledSteps")) {
            byte[] current=absent;
            for (String step : NativeGenerationFilters.steps()) { current=NativeGenerationFilters.changeString(PATH,current,field,-1,RiverProfiles.Action.ADD,-1,step);check(!NativeGenerationFilters.stepHelp(step).isEmpty()); }
            check(new LosslessJson(current).stringArrayPath("importedFeatures."+field).equals(NativeGenerationFilters.steps()));
            byte[] changed=NativeGenerationFilters.setString(PATH,current,field,-1,0,"LAKES");check(new LosslessJson(changed).stringArrayPath("importedFeatures."+field).get(0).equals("LAKES"));
            rejects(() -> NativeGenerationFilters.changeString(PATH,absent,field,-1,RiverProfiles.Action.ADD,-1,"lakes"));rejects(() -> NativeGenerationFilters.setString(PATH,changed,field,-1,0,"FUTURE"));
            rejects(() -> NativeGenerationFilters.changeString(PATH,changed,field,-1,RiverProfiles.Action.UP,0,null));rejects(() -> NativeGenerationFilters.changeString(PATH,changed,field,-1,RiverProfiles.Action.DOWN,10,null));
            byte[] one=NativeGenerationFilters.changeString(PATH,absent,field,-1,RiverProfiles.Action.ADD,-1,"LAKES");check(new LosslessJson(NativeGenerationFilters.changeString(PATH,one,field,-1,RiverProfiles.Action.REMOVE,0,null)).tokenPath("importedFeatures."+field)==null);
        }
        for (String field : NativeGenerationFilters.EDITABLE) {
            List<String> valid=field.equals("biomes") ? List.of("minecraft:forest","mod:","#minecraft:is_forest") : field.equals("disabled") ? List.of("minecraft:ore","mod:","mod:tree/") : List.of("minecraft:ore_iron","mod:tree/path");
            byte[] current=rule;for (String key : valid) current=NativeGenerationFilters.changeString(PATH,current,field,0,RiverProfiles.Action.ADD,-1,key);check(NativeGenerationFilters.strings(entry(current,"biomeRules",0),field,false).equals(valid));
            String key=valid.get(0);byte[] stable=current;check(Arrays.equals(stable,NativeGenerationFilters.setString(PATH,stable,field,0,0,key)));
            for (String invalid : List.of("", "Minecraft:forest", "forest", "mod:bad key", "#mod:", "x:y\u0000")) rejects(() -> NativeGenerationFilters.setString(PATH,stable,field,0,0,invalid));
            if (!field.equals("biomes")) rejects(() -> NativeGenerationFilters.setString(PATH,stable,field,0,0,"#minecraft:forest"));if (field.equals("disabledExact")) rejects(() -> NativeGenerationFilters.setString(PATH,stable,field,0,0,"mod:"));
            byte[] one=NativeGenerationFilters.changeString(PATH,rule,field,0,RiverProfiles.Action.ADD,-1,key);check(entry(NativeGenerationFilters.changeString(PATH,one,field,0,RiverProfiles.Action.REMOVE,0,null),"biomeRules",0).token(field)==null);
        }
        byte[] rawRule=document("biomeRules","[{ \"biomes\":[\"minecraft:for\\u0065st\",\"#mod:tag\"],\"disabled\":[\"mod:\"],\"future\":{\"n\":9007199254740993}}]");
        check(Arrays.equals(rawRule,NativeGenerationFilters.setString(PATH,rawRule,"biomes",0,0,"minecraft:forest")));
        byte[] duplicated=NativeGenerationFilters.changeString(PATH,rawRule,"biomes",0,RiverProfiles.Action.DUPLICATE,0,null);check(text(duplicated).split("minecraft:for\\\\u0065st",-1).length==3);
        byte[] moved=NativeGenerationFilters.changeString(PATH,rawRule,"biomes",0,RiverProfiles.Action.DOWN,0,null);check(NativeGenerationFilters.strings(entry(moved,"biomeRules",0),"biomes",false).equals(List.of("#mod:tag","minecraft:forest")) && text(moved).contains("minecraft:for\\u0065st"));
        for (String list : List.of("biomeRules","frequencyOverrides")) {
            byte[] raw=list.equals("biomeRules") ? rawRule : document(list,"[{\"structureSet\":\"mod:set\\u005fone\",\"multiplier\":1e0,\"future\":9007199254740993}]");
            byte[] copy=NativeGenerationFilters.change(PATH,raw,list,RiverProfiles.Action.DUPLICATE,0,null);check(Arrays.equals(entry(copy,list,0).sourceBytes(),entry(copy,list,1).sourceBytes()));byte[] reordered=NativeGenerationFilters.change(PATH,copy,list,RiverProfiles.Action.DOWN,0,null);check(entry(reordered,list,0).token("future")!=null);check(Arrays.equals(raw,NativeGenerationFilters.change(PATH,copy,list,RiverProfiles.Action.REMOVE,1,null)));
            check(new LosslessJson(NativeGenerationFilters.change(PATH,raw,list,RiverProfiles.Action.REMOVE,0,null)).tokenPath((list.equals("biomeRules") ? "importedFeatures" : "importedStructures")+"."+list)==null);
            byte[] oversized=document(list,"["+String.join(",",Collections.nCopies(65,"{}"))+"]");rejects(() -> NativeGenerationFilters.change(PATH,oversized,list,RiverProfiles.Action.REMOVE,0,null));
            for (String token : List.of("null","\"snippet\"","[null]","[{},3]","{}")) { byte[] wrong=document(list,token);rejects(() -> NativeGenerationFilters.change(PATH,wrong,list,RiverProfiles.Action.ADD,-1,"mod:set"));rejects(() -> NativeGenerationFilters.change(PATH,wrong,list,RiverProfiles.Action.REMOVE,0,null)); }
        }
        byte[] frequency=NativeGenerationFilters.change(PATH,absent,"frequencyOverrides",RiverProfiles.Action.ADD,-1,"mod:structures/set");check(entry(frequency,"frequencyOverrides",0).fields().equals(java.util.Set.of("structureSet")) && NativeGenerationFilters.multiplier(entry(frequency,"frequencyOverrides",0)).equals("1"));
        for (String value : List.of("0.01","1","16","1e0","3.125")) check(entry(NativeGenerationFilters.setMultiplier(PATH,frequency,0,value),"frequencyOverrides",0).token("multiplier")!=null);
        for (String value : List.of("0","0.009","16.0001","-1","1e1000","NaN","Infinity","null","")) rejects(() -> NativeGenerationFilters.setMultiplier(PATH,frequency,0,value));rejects(() -> NativeGenerationFilters.setMultiplier(PATH,frequency,0,null));
        byte[] precise=document("frequencyOverrides","[{\"structureSet\":\"mod:set\\u005fone\",\"multiplier\":1e0,\"future\":9007199254740993},{\"structureSet\":\" MOD:SET_ONE \",\"multiplier\":2}]");
        check(Arrays.equals(precise,NativeGenerationFilters.setStructureSet(PATH,precise,0,"mod:set_one")) && Arrays.equals(precise,NativeGenerationFilters.setMultiplier(PATH,precise,0,"1.000")));
        byte[] changed=NativeGenerationFilters.setStructureSet(PATH,precise,0,"other:set");check(text(changed).contains("\"multiplier\":1e0") && text(changed).contains("\"future\":9007199254740993"));check(NativeGenerationFilters.multiplier(entry(NativeGenerationFilters.resetMultiplier(PATH,precise,0),"frequencyOverrides",0)).equals("1"));
        for (String value : List.of("", "minecraft:", "fortress", "Minecraft:set", "#mod:set")) { rejects(() -> NativeGenerationFilters.setStructureSet(PATH,precise,0,value));rejects(() -> NativeGenerationFilters.change(PATH,absent,"frequencyOverrides",RiverProfiles.Action.ADD,-1,value)); }
        for (String token : List.of("null","\"1\"","[]","1e1000")) { byte[] wrong=document("frequencyOverrides","[{\"multiplier\":"+token+"}]");rejects(() -> NativeGenerationFilters.setMultiplier(PATH,wrong,0,"1"));rejects(() -> NativeGenerationFilters.resetMultiplier(PATH,wrong,0)); }
        for (String token : List.of("null","[3]","[\"mod:a\",null]","{}")) { byte[] wrong=document("biomeRules","[{\"biomes\":"+token+"}]");rejects(() -> NativeGenerationFilters.changeString(PATH,wrong,"biomes",0,RiverProfiles.Action.ADD,-1,"mod:a"));rejects(() -> NativeGenerationFilters.setString(PATH,wrong,"biomes",0,0,"mod:a")); }
        for (String field : List.of("steps","disabledSteps")) for (String token : List.of("null","[3]","\"snippet\"")) { byte[] wrong=document(field,token);rejects(() -> NativeGenerationFilters.changeString(PATH,wrong,field,-1,RiverProfiles.Action.ADD,-1,"LAKES")); }
        byte[] largeStrings=document("steps","["+String.join(",",Collections.nCopies(65,"\"LAKES\""))+"]");rejects(() -> NativeGenerationFilters.setString(PATH,largeStrings,"steps",-1,0,"LAKES"));rejects(() -> NativeGenerationFilters.changeString(PATH,largeStrings,"steps",-1,RiverProfiles.Action.REMOVE,0,null));
        for (String policy : List.of("importedFeatures","importedStructures")) for (String token : List.of("null","\"snippet\"","[]")) { byte[] wrong=bytes("{\""+policy+"\":"+token+"}");String list=policy.equals("importedFeatures") ? "biomeRules" : "frequencyOverrides";rejects(() -> NativeGenerationFilters.change(PATH,wrong,list,RiverProfiles.Action.ADD,-1,"mod:set"));check(DimensionFields.readOnlyPaths(new LosslessJson(wrong)).contains(policy)); }
        for (String path : List.of("biomes/native.json","regions/native.json","dimensions/nested/native.json","native.json")) { rejects(() -> NativeGenerationFilters.change(path,rule,"biomeRules",RiverProfiles.Action.REMOVE,0,null));rejects(() -> NativeGenerationFilters.setMultiplier(path,frequency,0,"2"));rejects(() -> NativeGenerationFilters.changeString(path,absent,"steps",-1,RiverProfiles.Action.ADD,-1,"LAKES")); }
        check(DimensionFields.readOnlyPaths(new LosslessJson(rawRule)).contains("importedFeatures.biomeRules[0].future"));check(FieldCoverage.groups(PATH).containsAll(List.of("importedFeatureBiomeRule","structureSetFrequency")) && FieldCoverage.fields("importedFeatures").stream().allMatch(FieldCoverage.Field::implemented) && FieldCoverage.fields("importedFeatureBiomeRule").size()==3 && FieldCoverage.fields("structureSetFrequency").size()==2);
        var drafts=new InspectorDrafts();String scope=NativeGenerationFilters.prefix("biomeRules"),inside=scope+"0.biomes.0",outside=NativeGenerationFilters.prefix("frequencyOverrides")+"0.multiplier";drafts.update(inside,"a","b");drafts.update(outside,"1","2");check(!drafts.transitionUnder(scope,() -> false,() -> {}) && drafts.count()==2);try { drafts.transitionUnder(scope,() -> true,() -> { throw new IOException("failed"); });throw new AssertionError(); } catch (IOException expected) { check(drafts.count()==2); }check(drafts.transitionUnder(scope,() -> true,() -> {}) && drafts.count()==1 && drafts.contains(outside));
        var workspace=PackWorkspace.newPack(Map.of(PATH,rawRule,"objects/opaque.iob",new byte[]{0,1,2},"biomes/opaque.json",bytes("opaque")));workspace.replace(PATH,duplicated);workspace.save(temp.resolve("native-generation-filters"));workspace.exportZip(temp.resolve("native-generation-filters.zip"));var reopened=PackWorkspace.open(temp.resolve("native-generation-filters"));var imported=PackWorkspace.importZip(temp.resolve("native-generation-filters.zip"));for (String path : workspace.paths()) check(Arrays.equals(workspace.read(path),reopened.read(path)) && Arrays.equals(workspace.read(path),imported.read(path)));
        System.out.println("PASS: eleven native decoration steps, biome registry/tag/family/exact rules, ordered structure-set frequency overrides, finite native bounds/default resets, raw no-ops/list operations, malformed/oversized/path guards, coverage, scoped drafts and lossless Save/ZIP without native generation");
    }
}
