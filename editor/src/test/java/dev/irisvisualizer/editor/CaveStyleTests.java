package dev.irisvisualizer.editor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class CaveStyleTests {
    private static byte[] bytes(String text){return text.getBytes(StandardCharsets.UTF_8);}
    private static String text(byte[] bytes){return new String(bytes,StandardCharsets.UTF_8);}
    private static void check(boolean value){if(!value)throw new AssertionError("Cave style contract failed");}
    private static void rejects(Runnable action){
        try{action.run();}catch(IllegalArgumentException expected){return;}
        throw new AssertionError("Expected rejected cave style operation");
    }
    private static LosslessJson style(byte[] bytes,NoiseStyleFields.Target target){return NoiseStyleFields.object(CaveProfileFields.object(new LosslessJson(bytes)),target);}
    static void run(Path temp)throws Exception{
        Map<String,String> defaults=Map.of("baseDensityStyle","CELLULAR_IRIS_DOUBLE","detailDensityStyle","SIMPLEX","warpStyle","FLAT","surfaceBreakStyle","SIMPLEX");
        String leaf="{\"style\":\"FUTURE\\u005fSTYLE\",\"zoom\":1e0,\"exponent\":1,\"cellularFrequency\":0,\"cellularZoom\":1,\"expression\":\"unresolved-expression\",\"fracture\":{\"style\":\"UNKNOWN\",\"zoom\":9e0},\"future\":\"雪\"}";
        var prefixes=new HashSet<String>();var drafts=new InspectorDrafts();
        for(String context:CaveProfileFields.CONTEXTS){
            String path=context+"/test.json";
            for(String field:NoiseStyleFields.CAVE_FIELDS){
                var target=NoiseStyleFields.Target.cave(context,field);
                check(target.cave() && !target.terrain() && target.member().equals(field));
                check(NoiseStyleFields.contextDefault(target).equals(defaults.get(field)) && !NoiseStyleFields.contextDescription(target).isBlank());
                check(prefixes.add(target.draftPrefix()));drafts.update(target.draftPrefix()+"zoom","1","2");
                String sibling=field.equals("baseDensityStyle")?"detailDensityStyle":"baseDensityStyle";
                String prefix="{\r\n \"caveProfile\":{\""+field+"\":";
                String suffix=",\""+sibling+"\":{\"style\":\"IRIS\",\"zoom\":7e0},\"modules\":[\"unknown-module\"],\"enabled\":false},\"unknown\":9007199254740993\r\n}\r\n";
                byte[] original=bytes(prefix+leaf+suffix),copy=original.clone();
                for(String choice:NoiseStyleFields.choices())
                    check(text(NoiseStyleFields.editStyle(path,original,target,choice)).equals(prefix+leaf.replace("FUTURE\\u005fSTYLE",choice)+suffix));
                check(text(NoiseStyleFields.editText(path,original,target,"zoom","2.5")).equals(prefix+leaf.replace("\"zoom\":1e0","\"zoom\":2.5")+suffix));
                // Cave styles retain general inactive cellular rules, unlike terrain3D styles.
                NoiseStyleFields.editText(path,original,target,"cellularZoom","-1");
                rejects(()->NoiseStyleFields.editText(path,original,target,"zoom","0"));
                rejects(()->NoiseStyleFields.editText(path,original,target,"exponent","64.01"));
                rejects(()->NoiseStyleFields.editText(path,original,target,"cellularFrequency","-1"));
                rejects(()->NoiseStyleFields.editStyle(path,original,target,"unknown"));
                rejects(()->NoiseStyleFields.enable(path,original,target,"fracture"));
                check(Arrays.equals(original,copy));
                for(String child:NoiseStyleFields.EDITABLE){
                    byte[] enabled=NoiseStyleFields.enable(path,bytes("{\"caveProfile\":{\"unknown\":true}}"),target,child);
                    var object=style(enabled,target);var expected=new HashSet<String>();expected.add("style");expected.add(child);
                    if(field.equals("surfaceBreakStyle"))expected.add("zoom");
                    check(object.fields().equals(expected) && object.stringToken("style").equals(defaults.get(field)));
                    if(field.equals("surfaceBreakStyle"))check(object.token("zoom").equals("0.08"));
                    check(new LosslessJson(enabled).tokenPath("caveProfile.enabled")==null);
                    check(new LosslessJson(enabled).tokenPath("caveProfile.unknown").equals("true"));
                    check(new LosslessJson(enabled).tokenPath("caveProfile."+sibling)==null);
                    rejects(()->NoiseStyleFields.enable(path,enabled,target,child));
                }
                byte[] empty=bytes("{\"caveProfile\":{\""+field+"\":{}}}");
                check(style(NoiseStyleFields.enable(path,empty,target,"style"),target).stringToken("style").equals("FLAT"));
                var explicitZoom=style(NoiseStyleFields.enable(path,empty,target,"zoom"),target);
                check(explicitZoom.fields().equals(Set.of("zoom")) && explicitZoom.token("zoom").equals("1"));
                byte[] explicitBase=bytes("{\"caveProfile\":{\""+field+"\":{\"style\":\"SIMPLEX\"}}}");
                check(style(NoiseStyleFields.enable(path,explicitBase,target,"zoom"),target).token("zoom").equals("1"));
                for(String bad:List.of("null","[]","1","\"snippet\"")){
                    byte[] wrong=bytes("{\"caveProfile\":{\""+field+"\":"+bad+"}}");
                    rejects(()->NoiseStyleFields.enable(path,wrong,target,"style"));
                    rejects(()->NoiseStyleFields.editStyle(path,wrong,target,"IRIS"));
                }
                for(String missing:List.of("{}","{\"caveProfile\":null}","{\"caveProfile\":\"snippet\"}"))
                    rejects(()->NoiseStyleFields.enable(path,bytes(missing),target,"style"));
                rejects(()->NoiseStyleFields.editStyle("generators/test.json",original,target,"IRIS"));
            }
        }
        check(prefixes.size()==12);
        drafts.update("caveProfile#biomes.baseWeight","1","3");
        drafts.transitionUnder(NoiseStyleFields.Target.cave("biomes","surfaceBreakStyle").draftPrefix(),()->true,()->{});
        check(drafts.count()==12 && drafts.contains("caveProfile#biomes.baseWeight"));
        rejects(()->NoiseStyleFields.Target.cave("generators","warpStyle"));
        rejects(()->NoiseStyleFields.Target.cave("dimensions","surfaceBreakStyle.fracture"));
        rejects(()->NoiseStyleFields.Target.cave("regions","unknown"));
        var target=NoiseStyleFields.Target.cave("biomes","surfaceBreakStyle");
        check(NoiseStyleFields.contextDefaultLabel(target).contains("0.08"));
        var readonly=DimensionFields.readOnlyPaths(new LosslessJson(bytes("{\"caveProfile\":{\"baseDensityStyle\":{\"style\":\"IRIS\",\"zoom\":1,\"fracture\":\"unknown\",\"future\":true},\"surfaceBreakStyle\":\"snippet\"}}")));
        check(readonly.equals(List.of("caveProfile.baseDensityStyle.fracture","caveProfile.baseDensityStyle.future","caveProfile.surfaceBreakStyle")));
        var workspace=StarterPack.create("cave_styles");
        for(String path:List.of("dimensions/cave_styles.json","regions/starter.json","biomes/starter.json")){
            byte[] profile=CaveProfileFields.enableProfile(path,workspace.read(path));
            workspace.replace(path,NoiseStyleFields.enable(path,profile,NoiseStyleFields.Target.cave(ResourceTypes.base(path),"surfaceBreakStyle"),"style"));
        }
        workspace.save(temp.resolve("cave-styles"));workspace.exportZip(temp.resolve("cave-styles.zip"));
        var reopened=PackWorkspace.open(temp.resolve("cave-styles"));var imported=PackWorkspace.importZip(temp.resolve("cave-styles.zip"));
        for(String path:workspace.paths()){
            check(Arrays.equals(workspace.read(path),reopened.read(path)));
            check(Arrays.equals(workspace.read(path),imported.read(path)));
        }
    }
}
