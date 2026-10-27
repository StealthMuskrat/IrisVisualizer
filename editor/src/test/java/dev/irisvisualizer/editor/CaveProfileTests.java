package dev.irisvisualizer.editor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class CaveProfileTests {
    private static byte[] bytes(String text){return text.getBytes(StandardCharsets.UTF_8);}
    private static String text(byte[] bytes){return new String(bytes,StandardCharsets.UTF_8);}
    private static void check(boolean value){if(!value)throw new AssertionError("Cave profile contract failed");}
    private static void rejects(Runnable action){
        try{action.run();}catch(IllegalArgumentException expected){return;}
        throw new AssertionError("Expected rejected cave profile operation");
    }
    static void run(Path temp)throws Exception{
        Set<String> integers=Set.of("verticalEdgeFade","sampleStep","adaptiveSampleStep","surfaceClearance","surfaceBreakDepth","objectMinDepthBelowSurface","anchorScanStep","anchorSearchAttempts","fluidMinDepthBelowSurface");
        Map<String,double[]> bounds=Map.ofEntries(
            Map.entry("verticalEdgeFade",new double[]{0,128}),Map.entry("verticalEdgeFadeStrength",new double[]{0,1}),
            Map.entry("baseWeight",new double[]{0,Double.POSITIVE_INFINITY}),Map.entry("detailWeight",new double[]{0,Double.POSITIVE_INFINITY}),Map.entry("warpStrength",new double[]{0,Double.POSITIVE_INFINITY}),
            Map.entry("thresholdBias",new double[]{0,1}),Map.entry("sampleStep",new double[]{1,8}),Map.entry("adaptiveSampleStep",new double[]{2,4}),Map.entry("adaptiveThresholdMargin",new double[]{0,1}),
            Map.entry("surfaceClearance",new double[]{0,64}),Map.entry("surfaceBreakNoiseThreshold",new double[]{-1,1}),Map.entry("surfaceBreakDepth",new double[]{0,64}),Map.entry("surfaceBreakThresholdBoost",new double[]{0,1}),
            Map.entry("objectMinDepthBelowSurface",new double[]{0,64}),Map.entry("anchorScanStep",new double[]{1,8}),Map.entry("anchorSearchAttempts",new double[]{1,64}),Map.entry("fluidMinDepthBelowSurface",new double[]{0,64}));
        check(CaveProfileFields.EDITABLE.size()==23 && CaveProfileFields.defaultToken("enabled").equals("false"));
        String original="{\r\n \"caveProfile\":{\"enabled\":true,\"sampleStep\":1e0,\"verticalEdgeFadeStrength\":0.18,\"baseWeight\":1,\"baseDensityStyle\":{\"style\":\"FUTURE\",\"zoom\":1e0},\"modules\":[\"unresolved-module\"],\"verticalRange\":{\"min\":0,\"max\":384},\"densityThreshold\":{\"future\":true},\"future\":\"雪\\u0061\"},\"unknown\":9007199254740993\r\n}\r\n";
        byte[] source=bytes(original),copy=source.clone();
        for(String path:List.of("dimensions/test.json","regions/nested/test.json","biomes/nested/test.json")){
            check(!CaveProfileFields.description(path).isBlank());
            byte[] enabled=CaveProfileFields.enableProfile(path,bytes("{\"unknown\":\"雪\"}"));
            check(CaveProfileFields.object(new LosslessJson(enabled)).fields().isEmpty());
            check(new LosslessJson(enabled).token("unknown").equals("\"雪\""));
            rejects(()->CaveProfileFields.enableProfile(path,enabled));
            rejects(()->CaveProfileFields.enable(path,bytes("{}"),"baseWeight"));
            rejects(()->CaveProfileFields.editBoolean(path,bytes("{}"),"enabled",true));
            for(String field:CaveProfileFields.EDITABLE){
                byte[] present=CaveProfileFields.enable(path,enabled,field);
                var profile=CaveProfileFields.object(new LosslessJson(present));
                check(profile.fields().equals(Set.of(field)) && profile.token(field).equals(CaveProfileFields.defaultToken(field)));
                check(!CaveProfileFields.help(field).isBlank());
                rejects(()->CaveProfileFields.enable(path,present,field));
                if(CaveProfileFields.booleanField(field)){
                    boolean value=!CaveProfileFields.defaultToken(field).equals("true");
                    byte[] toggled=CaveProfileFields.editBoolean(path,enabled,field,value);
                    check(CaveProfileFields.object(new LosslessJson(toggled)).fields().equals(Set.of(field)));
                    check(CaveProfileFields.object(new LosslessJson(toggled)).token(field).equals(Boolean.toString(value)));
                    rejects(()->CaveProfileFields.editText(path,present,field,"1"));
                }else{
                    double min=bounds.get(field)[0],max=bounds.get(field)[1];
                    CaveProfileFields.editText(path,present,field,Double.toString(min));
                    CaveProfileFields.editText(path,present,field,Double.toString(Double.isFinite(max)?max:1000000));
                    rejects(()->CaveProfileFields.editText(path,present,field,Double.toString(min-1)));
                    if(Double.isFinite(max))rejects(()->CaveProfileFields.editText(path,present,field,Double.toString(max+1)));
                    rejects(()->CaveProfileFields.editText(path,present,field,"NaN"));
                    rejects(()->CaveProfileFields.editText(path,present,field,"Infinity"));
                    if(integers.contains(field))rejects(()->CaveProfileFields.editText(path,present,field,"2.5"));
                }
            }
            check(text(CaveProfileFields.editText(path,source,"sampleStep","3")).equals(original.replace("\"sampleStep\":1e0","\"sampleStep\":3")));
            check(text(CaveProfileFields.editBoolean(path,source,"enabled",false)).equals(original.replace("\"enabled\":true","\"enabled\":false")));
            check(text(CaveProfileFields.editText(path,source,"verticalEdgeFadeStrength","0.5")).equals(original.replace("\"verticalEdgeFadeStrength\":0.18","\"verticalEdgeFadeStrength\":0.5")));
            for(String bad:List.of("null","[]","1","false","\"snippet\"")){
                byte[] wrong=bytes("{\"caveProfile\":"+bad+"}");
                rejects(()->CaveProfileFields.enableProfile(path,wrong));
                rejects(()->CaveProfileFields.enable(path,wrong,"sampleStep"));
                rejects(()->CaveProfileFields.editBoolean(path,wrong,"enabled",true));
            }
            for(String field:List.of("verticalRange","densityThreshold","baseDensityStyle","modules","defaultObjectAnchor"))
                rejects(()->CaveProfileFields.enable(path,source,field));
        }
        check(Arrays.equals(source,copy));
        rejects(()->CaveProfileFields.enableProfile("generators/test.json",bytes("{}")));
        rejects(()->CaveProfileFields.enableProfile("dimensions/nested/test.json",bytes("{}")));
        rejects(()->CaveProfileFields.editText("biomes/test.json",bytes("{\"caveProfile\":{\"sampleStep\":\"1\"}}"),"sampleStep","2"));
        rejects(()->CaveProfileFields.editBoolean("regions/test.json",bytes("{\"caveProfile\":{\"enabled\":null}}"),"enabled",true));
        var readOnly=DimensionFields.readOnlyPaths(new LosslessJson(source));
        check(readOnly.equals(List.of("caveProfile.modules","caveProfile.verticalRange","caveProfile.densityThreshold","caveProfile.future","unknown")));
        check(DimensionFields.readOnlyPaths(new LosslessJson(bytes("{\"caveProfile\":\"snippet\"}"))).equals(List.of("caveProfile")));
        var drafts=new InspectorDrafts();
        drafts.update("caveProfile#biomes.sampleStep","1","2");drafts.update("caveProfile#biomes.baseWeight","1","3");drafts.update("children#new","","parent");
        drafts.applied("caveProfile#biomes.sampleStep");drafts.transitionUnder("children#",()->true,()->{});
        check(drafts.count()==1 && drafts.contains("caveProfile#biomes.baseWeight"));
        var workspace=StarterPack.create("cave_controls");
        for(String path:List.of("dimensions/cave_controls.json","regions/starter.json","biomes/starter.json"))workspace.replace(path,CaveProfileFields.editText(path,source,"baseWeight","2.5"));
        workspace.save(temp.resolve("cave-controls"));workspace.exportZip(temp.resolve("cave-controls.zip"));
        var reopened=PackWorkspace.open(temp.resolve("cave-controls"));var imported=PackWorkspace.importZip(temp.resolve("cave-controls.zip"));
        for(String path:workspace.paths()){
            check(Arrays.equals(workspace.read(path),reopened.read(path)));
            check(Arrays.equals(workspace.read(path),imported.read(path)));
        }
        workspace.replace("biomes/starter.json",bytes("{\"caveProfile\":\"unresolved-snippet\"}"));workspace.save(temp.resolve("cave-controls"));
        check(new LosslessJson(PackWorkspace.open(temp.resolve("cave-controls")).read("biomes/starter.json")).token("caveProfile").equals("\"unresolved-snippet\""));
        check(FieldCoverage.fields("caveProfile").size()==32 && FieldCoverage.fields("caveProfile").stream().filter(FieldCoverage.Field::implemented).count()==27);
    }
}
