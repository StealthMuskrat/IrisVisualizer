package dev.irisvisualizer.editor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

final class TerrainStyleTests {
    private static final String PATH="biomes/nested/test.json";
    private static byte[] bytes(String text) {return text.getBytes(StandardCharsets.UTF_8);}
    private static String text(byte[] bytes) {return new String(bytes,StandardCharsets.UTF_8);}
    private static void check(boolean value) {if(!value) throw new AssertionError("Terrain style contract failed");}
    private static void rejects(Runnable action) {
        try {action.run();} catch(IllegalArgumentException expected){return;}
        throw new AssertionError("Expected rejected terrain style operation");
    }
    private static LosslessJson style(byte[] data,NoiseStyleFields.Target target) {
        return NoiseStyleFields.object(Terrain3DFields.object(new LosslessJson(data)),target);
    }
    static void run(Path temp) throws Exception {
        String leaf="{\"style\":\"FUTURE\\u005fSTYLE\",\"zoom\":1e0,\"exponent\":1,\"cellularFrequency\":0,\"cellularZoom\":1,\"expression\":\"unresolved-expression\",\"fracture\":{\"style\":\"UNKNOWN\",\"zoom\":9e0},\"unknown\":\"雪\"}";
        String prefix="{\r\n \"terrain3D\":{\"densityStyle\":",gap=",\"crackStyle\":",suffix=",\"seed\":9007199254740993,\"unknown\":true},\"unknown\":\"雪\"\r\n}\r\n";
        byte[] original=bytes(prefix+leaf+gap+leaf+suffix),copy=original.clone();
        for(String field:NoiseStyleFields.TERRAIN_FIELDS) {
            var target=NoiseStyleFields.Target.terrain(field);
            check(target.terrain() && target.member().equals(field));
            check(NoiseStyleFields.contextDefault(target).equals("SIMPLEX") && !NoiseStyleFields.contextDescription(target).isBlank());
            for(String choice:NoiseStyleFields.choices()) {
                String changedLeaf=leaf.replace("FUTURE\\u005fSTYLE",choice);
                String expected=prefix+(field.equals("densityStyle")?changedLeaf:leaf)+gap+(field.equals("crackStyle")?changedLeaf:leaf)+suffix;
                check(text(NoiseStyleFields.editStyle(PATH,original,target,choice)).equals(expected));
            }
            byte[] zoom=NoiseStyleFields.editText(PATH,original,target,"zoom","2.5");
            String changedLeaf=leaf.replace("\"zoom\":1e0","\"zoom\":2.5");
            check(text(zoom).equals(prefix+(field.equals("densityStyle")?changedLeaf:leaf)+gap+(field.equals("crackStyle")?changedLeaf:leaf)+suffix));
            // Terrain requires this lower bound even while cellular frequency is zero.
            for(String invalid:List.of("0","-1","0.000001","4.9e-324","NaN","Infinity"))
                rejects(() -> NoiseStyleFields.editText(PATH,original,target,"cellularZoom",invalid));
            for(String valid:List.of("0.00001","1",Double.toString(Double.MAX_VALUE)))
                NoiseStyleFields.editText(PATH,original,target,"cellularZoom",valid);
            NoiseStyleFields.editText(PATH,original,target,"zoom","0.00001");
            NoiseStyleFields.editText(PATH,original,target,"exponent","0.01562");
            NoiseStyleFields.editText(PATH,original,target,"exponent","64");
            rejects(() -> NoiseStyleFields.editText(PATH,original,target,"exponent","64.01"));
            rejects(() -> NoiseStyleFields.editText(PATH,original,target,"cellularFrequency","-1"));
            rejects(() -> NoiseStyleFields.editStyle(PATH,original,target,"not-known"));
            rejects(() -> NoiseStyleFields.enable(PATH,original,target,"fracture"));
            rejects(() -> NoiseStyleFields.editStyle("generators/test.json",original,target,"IRIS"));
            byte[] active=bytes("{\"terrain3D\":{\""+field+"\":{\"cellularFrequency\":0,\"cellularZoom\":0.000001}}}");
            rejects(() -> NoiseStyleFields.editText(PATH,active,target,"cellularFrequency","1"));
            check(style(NoiseStyleFields.editText(PATH,active,target,"cellularFrequency","0"),target).token("cellularZoom").equals("0.000001"));
            for(String child:NoiseStyleFields.EDITABLE) {
                byte[] enabled=NoiseStyleFields.enable(PATH,bytes("{\"terrain3D\":{\"unknown\":true}}"),target,child);
                var object=style(enabled,target);
                check(object.stringToken("style").equals("SIMPLEX"));
                check(object.fields().equals(child.equals("style")?Set.of("style"):Set.of("style",child)));
                check(new LosslessJson(enabled).tokenPath("terrain3D.unknown").equals("true"));
                check(new LosslessJson(enabled).tokenPath("terrain3D."+(field.equals("densityStyle")?"crackStyle":"densityStyle"))==null);
                rejects(() -> NoiseStyleFields.enable(PATH,enabled,target,child));
            }
            byte[] empty=bytes("{\"terrain3D\":{\""+field+"\":{}}}");
            check(style(NoiseStyleFields.enable(PATH,empty,target,"style"),target).stringToken("style").equals("FLAT"));
            check(style(NoiseStyleFields.enable(PATH,empty,target,"cellularZoom"),target).fields().equals(Set.of("cellularZoom")));
            for(String bad:List.of("null","[]","1","\"snippet\"")) {
                byte[] wrong=bytes("{\"terrain3D\":{\""+field+"\":"+bad+"}}");
                rejects(() -> NoiseStyleFields.enable(PATH,wrong,target,"style"));
                rejects(() -> NoiseStyleFields.editStyle(PATH,wrong,target,"IRIS"));
            }
            for(String absent:List.of("{}","{\"terrain3D\":null}","{\"terrain3D\":\"snippet\"}"))
                rejects(() -> NoiseStyleFields.enable(PATH,bytes(absent),target,"style"));
        }
        check(Arrays.equals(original,copy));
        rejects(() -> NoiseStyleFields.Target.terrain("densityStyle.fracture"));
        rejects(() -> NoiseStyleFields.Target.dimension("terrain3D.densityStyle"));
        rejects(() -> NoiseStyleFields.Target.terrain("unknown"));
        var general=NoiseStyleFields.Target.biome("childStyle");
        byte[] generalData=bytes("{\"childStyle\":{\"cellularFrequency\":0,\"cellularZoom\":1}}");
        NoiseStyleFields.editText(PATH,generalData,general,"cellularZoom","-1");
        var drafts=new InspectorDrafts();
        drafts.update("terrain3D#densityStyle.zoom","1","2"); drafts.update("terrain3D#crackStyle.zoom","1","3"); drafts.update("terrain3D#seed","0","4");
        drafts.transitionUnder(NoiseStyleFields.Target.terrain("densityStyle").draftPrefix(),()->true,()->{});
        check(drafts.count()==2 && drafts.contains("terrain3D#crackStyle.zoom") && drafts.contains("terrain3D#seed"));
        var workspace=StarterPack.create("terrain_styles");
        workspace.replace("biomes/starter.json",NoiseStyleFields.editStyle(PATH,original,NoiseStyleFields.Target.terrain("densityStyle"),"PERLIN"));
        workspace.save(temp.resolve("terrain-styles")); workspace.exportZip(temp.resolve("terrain-styles.zip"));
        check(Arrays.equals(PackWorkspace.open(temp.resolve("terrain-styles")).read("biomes/starter.json"),workspace.read("biomes/starter.json")));
        check(Arrays.equals(PackWorkspace.importZip(temp.resolve("terrain-styles.zip")).read("biomes/starter.json"),workspace.read("biomes/starter.json")));
    }
}
