package dev.irisvisualizer.editor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class Terrain3DTests {
    private static final String PATH="biomes/nested/test.json";
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static String text(byte[] bytes) { return new String(bytes,StandardCharsets.UTF_8); }
    private static void check(boolean value) { if(!value) throw new AssertionError("Terrain profile contract failed"); }
    private static void rejects(Runnable action) {
        try {action.run();} catch(IllegalArgumentException expected){return;}
        throw new AssertionError("Expected rejected terrain profile operation");
    }
    static void run(Path temp) throws Exception {
        for(String input:List.of("{\r\n \"unknown\":\"雪\"\r\n}\r\n","{\r\n \"terrain3D\":null,\"unknown\":null\r\n}\r\n")) {
            byte[] original=bytes(input),copy=original.clone();
            byte[] enabled=Terrain3DFields.enableProfile(PATH,original);
            check(Terrain3DFields.object(new LosslessJson(enabled)).fields().isEmpty());
            check(new LosslessJson(enabled).token("unknown").equals(new LosslessJson(original).token("unknown")));
            if(input.contains("\"terrain3D\":null")) check(text(enabled).equals(input.replace("\"terrain3D\":null","\"terrain3D\":{}")));
            rejects(() -> Terrain3DFields.enableProfile(PATH,enabled));
            rejects(() -> Terrain3DFields.enable(PATH,original,"seed"));
            rejects(() -> Terrain3DFields.editBoolean(PATH,original,"enabled",false));
            check(Arrays.equals(original,copy));
        }
        check(Terrain3DFields.defaultToken("enabled").equals("true"));
        byte[] empty=bytes("{\"terrain3D\":{},\"unknown\":true}");
        byte[] disabled=Terrain3DFields.editBoolean(PATH,empty,"enabled",false);
        check(Terrain3DFields.object(new LosslessJson(disabled)).fields().equals(Set.of("enabled")));
        check(Terrain3DFields.object(new LosslessJson(disabled)).token("enabled").equals("false"));
        for(String field:Terrain3DFields.EDITABLE) {
            byte[] enabled=Terrain3DFields.enable(PATH,empty,field);
            var profile=Terrain3DFields.object(new LosslessJson(enabled));
            check(profile.fields().equals(Set.of(field)) && profile.token(field).equals(Terrain3DFields.defaultToken(field)));
            rejects(() -> Terrain3DFields.enable(PATH,enabled,field));
        }
        String original="{\r\n \"terrain3D\":{\"enabled\":true,\"seed\":9007199254740993,\"amplitude\":32,\"horizontalScale\":96,\"verticalScale\":24,\"crackDepth\":0,\"crackWidth\":4,\"crackScale\":96,\"minimumSlope\":0.15,\"slopeFade\":0.35,\"fluidClearance\":8,\"fluidFade\":24,\"densityStyle\":\"unresolved-snippet\",\"crackStyle\":{\"style\":\"FUTURE\",\"zoom\":1e0},\"unknown\":\"雪\\u0061\"},\"unknown\":null\r\n}\r\n";
        byte[] source=bytes(original),copy=source.clone();
        byte[] changed=Terrain3DFields.editText(PATH,source,"amplitude","64.5");
        check(text(changed).equals(original.replace("\"amplitude\":32","\"amplitude\":64.5")));
        byte[] exact=Terrain3DFields.editText(PATH,source,"seed","-9223372036854775808");
        check(text(exact).equals(original.replace("9007199254740993","-9223372036854775808")));
        check(Terrain3DFields.object(new LosslessJson(Terrain3DFields.editText(PATH,exact,"seed","9223372036854775807"))).token("seed").equals("9223372036854775807"));
        check(text(Terrain3DFields.editBoolean(PATH,source,"enabled",false)).equals(original.replace("\"enabled\":true","\"enabled\":false")));
        Map<String,double[]> bounds=Map.of("amplitude",new double[]{0,128},"horizontalScale",new double[]{8,4096},"verticalScale",new double[]{8,4096},"crackDepth",new double[]{0,128},"crackWidth",new double[]{0.25,64},"crackScale",new double[]{8,4096},"minimumSlope",new double[]{0,16},"slopeFade",new double[]{0.001,16},"fluidClearance",new double[]{0,128},"fluidFade",new double[]{1,128});
        for(var entry:bounds.entrySet()) {
            String field=entry.getKey(); double min=entry.getValue()[0],max=entry.getValue()[1];
            Terrain3DFields.editText(PATH,source,field,Double.toString(min));
            Terrain3DFields.editText(PATH,source,field,Double.toString(max));
            for(String invalid:List.of(Double.toString(min-1),Double.toString(max+1),"NaN","Infinity"))
                rejects(() -> Terrain3DFields.editText(PATH,source,field,invalid));
        }
        for(String invalid:List.of("9223372036854775808","-9223372036854775809","1.5","NaN"))
            rejects(() -> Terrain3DFields.editText(PATH,source,"seed",invalid));
        for(String unsupported:List.of("densityStyle","crackStyle","future")) {
            rejects(() -> Terrain3DFields.enable(PATH,source,unsupported));
            rejects(() -> Terrain3DFields.editText(PATH,source,unsupported,"1"));
        }
        for(String bad:List.of("[]","1","false","\"snippet\"")) {
            byte[] wrong=bytes("{\"terrain3D\":"+bad+"}");
            rejects(() -> Terrain3DFields.enableProfile(PATH,wrong));
            rejects(() -> Terrain3DFields.enable(PATH,wrong,"amplitude"));
            rejects(() -> Terrain3DFields.editText(PATH,wrong,"amplitude","32"));
        }
        rejects(() -> Terrain3DFields.editText(PATH,bytes("{\"terrain3D\":{\"seed\":\"1\"}}"),"seed","2"));
        rejects(() -> Terrain3DFields.editBoolean(PATH,bytes("{\"terrain3D\":{\"enabled\":null}}"),"enabled",true));
        rejects(() -> Terrain3DFields.editBoolean(PATH,source,"seed",true));
        rejects(() -> Terrain3DFields.editText(PATH,source,"enabled","1"));
        rejects(() -> Terrain3DFields.enableProfile("generators/test.json",bytes("{}")));
        check(Arrays.equals(source,copy));
        var drafts=new InspectorDrafts();
        drafts.update("terrain3D#seed","0","1"); drafts.update("terrain3D#amplitude","32","64"); drafts.update("children#new","","parent");
        drafts.applied("terrain3D#amplitude");
        drafts.transitionUnder("children#",()->true,()->{});
        check(drafts.count()==1 && drafts.contains("terrain3D#seed"));
        var workspace=StarterPack.create("terrain_profile");
        workspace.replace("biomes/starter.json",exact);
        workspace.save(temp.resolve("terrain-profile")); workspace.exportZip(temp.resolve("terrain-profile.zip"));
        check(Arrays.equals(PackWorkspace.open(temp.resolve("terrain-profile")).read("biomes/starter.json"),exact));
        check(Arrays.equals(PackWorkspace.importZip(temp.resolve("terrain-profile.zip")).read("biomes/starter.json"),exact));
        workspace.replace("biomes/starter.json",bytes("{\"terrain3D\":\"snippet\"}"));
        workspace.save(temp.resolve("terrain-profile"));
        check(new LosslessJson(PackWorkspace.open(temp.resolve("terrain-profile")).read("biomes/starter.json")).token("terrain3D").equals("\"snippet\""));
        check(FieldCoverage.fields("terrain3D").size()==14 && FieldCoverage.fields("terrain3D").stream().filter(FieldCoverage.Field::implemented).count()==14);
    }
}
