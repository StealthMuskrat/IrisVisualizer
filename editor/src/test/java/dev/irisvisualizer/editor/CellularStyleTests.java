package dev.irisvisualizer.editor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

final class CellularStyleTests {
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static String text(byte[] bytes) { return new String(bytes,StandardCharsets.UTF_8); }
    private static void check(boolean value) { if (!value) throw new AssertionError("Cellular style contract failed"); }
    private static void rejects(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Expected rejected cellular style operation");
    }
    private static byte[] wrap(NoiseStyleFields.Target target, String style) {
        if (target.noise()==null) return bytes("{\r\n \""+target.objectField()+"\":"+style+",\"unknown\":\"雪\"\r\n}\r\n");
        String noise="{\"seed\":9007199254740993,\"style\":"+style+",\"fracture\":[{\"seed\":7}]}";
        return bytes(target.noise().index()>=0 ? "{\"composite\":["+noise+",{\"unknown\":true}]}" : "{\"cliffHeightGenerator\":"+noise+",\"unknown\":true}");
    }
    private static LosslessJson object(byte[] data, NoiseStyleFields.Target target) {
        var root=new LosslessJson(data);
        if(target.noise()==null) return NoiseStyleFields.object(root,target);
        var noise=target.noise().index()>=0 ? root.objectArray("composite").getFirst() : new LosslessJson(bytes(root.token("cliffHeightGenerator")));
        return NoiseStyleFields.object(noise,target);
    }
    static void run(Path temp) throws Exception {
        var targets=new ArrayList<NoiseStyleFields.Target>();
        targets.add(NoiseStyleFields.Target.noise(NoiseFields.Target.composite(0)));
        targets.add(NoiseStyleFields.Target.noise(NoiseFields.Target.cliffHeight()));
        for(String field:NoiseStyleFields.DIMENSION_FIELDS) targets.add(NoiseStyleFields.Target.dimension(field));
        targets.add(NoiseStyleFields.Target.biome("childStyle"));
        check(NoiseStyleFields.defaultToken("cellularFrequency").equals("0") && NoiseStyleFields.defaultToken("cellularZoom").equals("1"));
        for(var target:targets) {
            String path=target.resourceBase()+"/test.json";
            String style="{\"style\":\"FUTURE\",\"cellularFrequency\":0,\"cellularZoom\":-2e0,\"zoom\":1,\"expression\":\"unknown-expression\",\"fracture\":{\"cellularZoom\":-9},\"unknown\":\"雪\"}";
            byte[] original=wrap(target,style),copy=original.clone();
            for(String value:List.of("0","-1","-1000000")) NoiseStyleFields.editText(path,original,target,"cellularZoom",value);
            rejects(() -> NoiseStyleFields.editText(path,original,target,"cellularFrequency","0.5"));
            byte[] zoom=NoiseStyleFields.editText(path,original,target,"cellularZoom","2.5");
            check(text(zoom).equals(text(original).replace("\"cellularZoom\":-2e0","\"cellularZoom\":2.5")));
            byte[] active=NoiseStyleFields.editText(path,zoom,target,"cellularFrequency","0.5");
            check(text(active).equals(text(zoom).replace("\"cellularFrequency\":0","\"cellularFrequency\":0.5")));
            for(String invalid:List.of("0","-1","4.9e-324","NaN","Infinity"))
                rejects(() -> NoiseStyleFields.editText(path,active,target,"cellularZoom",invalid));
            for(String invalid:List.of("-0.1","NaN","Infinity"))
                rejects(() -> NoiseStyleFields.editText(path,active,target,"cellularFrequency",invalid));
            NoiseStyleFields.editText(path,active,target,"cellularZoom",Double.toString(Double.MIN_NORMAL));
            NoiseStyleFields.editText(path,active,target,"cellularFrequency","4.9e-324");
            NoiseStyleFields.editText(path,active,target,"cellularFrequency","1000000");
            // An invalid active sibling can be disabled without silently replacing it.
            byte[] invalidPair=wrap(target,"{\"cellularFrequency\":1,\"cellularZoom\":0,\"zoom\":1}");
            byte[] disabled=NoiseStyleFields.editText(path,invalidPair,target,"cellularFrequency","0");
            check(object(disabled,target).token("cellularZoom").equals("0"));
            byte[] unrelated=NoiseStyleFields.editText(path,invalidPair,target,"zoom","2");
            check(object(unrelated,target).token("cellularFrequency").equals("1") && object(unrelated,target).token("cellularZoom").equals("0"));
            byte[] wrongSibling=wrap(target,"{\"cellularFrequency\":1,\"cellularZoom\":\"bad\"}");
            rejects(() -> NoiseStyleFields.editText(path,wrongSibling,target,"cellularFrequency","2"));
            check(object(NoiseStyleFields.editText(path,wrongSibling,target,"cellularFrequency","0"),target).token("cellularZoom").equals("\"bad\""));
            byte[] missingZoom=wrap(target,"{\"cellularFrequency\":1}");
            byte[] enabledZoom=NoiseStyleFields.enable(path,missingZoom,target,"cellularZoom");
            check(object(enabledZoom,target).token("cellularZoom").equals("1"));
            byte[] absentPair=wrap(target,"{}");
            for(String field:List.of("cellularFrequency","cellularZoom")) {
                byte[] enabled=NoiseStyleFields.enable(path,absentPair,target,field);
                check(object(enabled,target).fields().equals(Set.of(field)));
                check(object(enabled,target).token(field).equals(NoiseStyleFields.defaultToken(field)));
                rejects(() -> NoiseStyleFields.enable(path,enabled,target,field));
            }
            byte[] implicitZoom=wrap(target,"{\"cellularFrequency\":0}");
            check(object(NoiseStyleFields.editText(path,implicitZoom,target,"cellularFrequency","2"),target).token("cellularZoom")==null);
            byte[] invalidFrequency=wrap(target,"{\"cellularFrequency\":-1,\"cellularZoom\":1}");
            rejects(() -> NoiseStyleFields.editText(path,invalidFrequency,target,"cellularZoom","2"));
            check(Arrays.equals(original,copy));
        }
        var target=NoiseStyleFields.Target.dimension("regionStyle");
        byte[] original=wrap(target,"{\"cellularFrequency\":1,\"cellularZoom\":0,\"unknown\":\"雪\"}");
        var workspace=StarterPack.create("cellular_styles");
        workspace.replace("dimensions/cellular_styles.json",NoiseStyleFields.editText("dimensions/cellular_styles.json",original,target,"cellularZoom","2"));
        workspace.save(temp.resolve("cellular-styles")); workspace.exportZip(temp.resolve("cellular-styles.zip"));
        check(Arrays.equals(PackWorkspace.open(temp.resolve("cellular-styles")).read("dimensions/cellular_styles.json"),workspace.read("dimensions/cellular_styles.json")));
        check(Arrays.equals(PackWorkspace.importZip(temp.resolve("cellular-styles.zip")).read("dimensions/cellular_styles.json"),workspace.read("dimensions/cellular_styles.json")));
        var drafts=new InspectorDrafts();
        for(var context:targets) drafts.update(context.draftPrefix()+"cellularFrequency","0","1");
        drafts.transitionUnder("composite#",()->true,()->{});
        check(drafts.count()==targets.size()-1);
    }
}
