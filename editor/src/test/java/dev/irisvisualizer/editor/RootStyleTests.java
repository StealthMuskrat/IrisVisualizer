package dev.irisvisualizer.editor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class RootStyleTests {
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static String text(byte[] bytes) { return new String(bytes,StandardCharsets.UTF_8); }
    private static void check(boolean value) { if (!value) throw new AssertionError("Root style contract failed"); }
    private static void rejects(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Expected rejected root style operation");
    }
    static void run(Path temp) throws Exception {
        var targets = new ArrayList<NoiseStyleFields.Target>();
        for (String field : NoiseStyleFields.DIMENSION_FIELDS) targets.add(NoiseStyleFields.Target.dimension(field));
        targets.add(NoiseStyleFields.Target.biome("childStyle"));
        var prefixes = new HashSet<String>();
        for (var target : targets) {
            String path = target.resourceBase().equals("dimensions") ? "dimensions/test.json" : "biomes/nested/test.json";
            String field = target.objectField();
            check(NoiseStyleFields.contextDefault(target).equals("CELLULAR_IRIS_DOUBLE"));
            check(!NoiseStyleFields.contextDescription(target).isBlank());
            check(prefixes.add(target.draftPrefix()));
            String style = "{\"style\":\"FUTURE\\u005fSTYLE\",\"zoom\":1e0,\"exponent\":1,\"expression\":\"unresolved\",\"fracture\":{\"style\":\"UNKNOWN\",\"zoom\":9e0},\"unknown\":\"雪\"}";
            String prefix = "{\r\n \""+field+"\": ", suffix = ",\"otherStyle\":{\"style\":\"IRIS\"},\"unknown\":9007199254740993\r\n}\r\n";
            byte[] original = bytes(prefix+style+suffix), copy = original.clone();
            for (String choice : List.of("IRIS","CELLULAR_IRIS_DOUBLE","PERLIN"))
                check(text(NoiseStyleFields.editStyle(path,original,target,choice)).equals(prefix+style.replace("FUTURE\\u005fSTYLE",choice)+suffix));
            check(text(NoiseStyleFields.editText(path,original,target,"zoom","2.5")).equals(prefix+style.replace("\"zoom\":1e0","\"zoom\":2.5")+suffix));
            check(text(NoiseStyleFields.editText(path,original,target,"exponent","2")).equals(prefix+style.replace("\"exponent\":1","\"exponent\":2")+suffix));
            rejects(() -> NoiseStyleFields.editText(path,original,target,"zoom","0"));
            rejects(() -> NoiseStyleFields.editText(path,original,target,"zoom","NaN"));
            rejects(() -> NoiseStyleFields.editText(path,original,target,"exponent","64.01"));
            rejects(() -> NoiseStyleFields.editStyle(path,original,target,"UNKNOWN"));
            rejects(() -> NoiseStyleFields.enable(path,original,target,"expression"));
            check(Arrays.equals(original,copy));
            for (String child : NoiseStyleFields.EDITABLE) {
                byte[] enabled = NoiseStyleFields.enable(path,bytes("{\"unknown\":\"雪\"}"),target,child);
                var root = new LosslessJson(enabled);
                check(root.fields().equals(Set.of(field,"unknown")) && root.token("unknown").equals("\"雪\""));
                var object = NoiseStyleFields.object(root,target);
                check(object.stringToken("style").equals("CELLULAR_IRIS_DOUBLE"));
                check(object.fields().equals(child.equals("style") ? Set.of("style") : Set.of("style",child)));
                rejects(() -> NoiseStyleFields.enable(path,enabled,target,child));
            }
            byte[] empty = bytes("{\""+field+"\":{}}");
            var emptyScalar = NoiseStyleFields.object(new LosslessJson(NoiseStyleFields.enable(path,empty,target,"zoom")),target);
            check(emptyScalar.fields().equals(Set.of("zoom")) && emptyScalar.token("style")==null);
            check(NoiseStyleFields.object(new LosslessJson(NoiseStyleFields.enable(path,empty,target,"style")),target).stringToken("style").equals("FLAT"));
            rejects(() -> NoiseStyleFields.editStyle(path,empty,target,"IRIS"));
            rejects(() -> NoiseStyleFields.editStyle(path,bytes("{}"),target,"IRIS"));
            for (String bad : List.of("null","[]","1","false","\"snippet\"")) {
                byte[] wrong = bytes("{\""+field+"\":"+bad+"}");
                rejects(() -> NoiseStyleFields.enable(path,wrong,target,"style"));
                rejects(() -> NoiseStyleFields.editStyle(path,wrong,target,"IRIS"));
                rejects(() -> NoiseStyleFields.editText(path,wrong,target,"zoom","2"));
            }
            rejects(() -> NoiseStyleFields.editStyle("generators/test.json",original,target,"IRIS"));
            rejects(() -> NoiseStyleFields.editStyle("regions/test.json",original,target,"IRIS"));
        }
        rejects(() -> NoiseStyleFields.Target.dimension("regionStyle.zoom"));
        rejects(() -> NoiseStyleFields.Target.biome("biomeStyle"));
        rejects(() -> new NoiseStyleFields.Target("generators","style",null));
        rejects(() -> new NoiseStyleFields.Target("dimensions","regionStyle",NoiseFields.Target.composite(0)));
        rejects(() -> NoiseStyleFields.enable("dimensions/nested/test.json",bytes("{}"),targets.getFirst(),"style"));
        check(NoiseStyleFields.Target.noise(NoiseFields.Target.composite(0)).draftPrefix().equals("composite#0.style."));
        check(NoiseStyleFields.Target.noise(NoiseFields.Target.cliffHeight()).draftPrefix().equals("cliffHeightGenerator#style."));
        var paths = DimensionFields.readOnlyPaths(new LosslessJson(bytes("{\"regionStyle\":{\"style\":\"IRIS\",\"zoom\":1,\"exponent\":1,\"fracture\":\"snippet\",\"future\":true},\"futureRoot\":true}")));
        check(paths.equals(List.of("regionStyle.fracture","regionStyle.future","futureRoot")));
        check(DimensionFields.readOnlyPaths(new LosslessJson(bytes("{\"regionStyle\":\"snippet\"}"))).equals(List.of("regionStyle")));
        var drafts = new InspectorDrafts();
        for (var target : targets) drafts.update(target.draftPrefix()+"zoom","1","2");
        drafts.update("composite#0.style.zoom","1","3");
        drafts.transitionUnder("composite#",()->true,()->{});
        check(drafts.count()==7);
        drafts.transitionUnder(targets.getFirst().draftPrefix(),()->true,()->{});
        check(drafts.count()==6 && !drafts.contains(targets.getFirst().draftPrefix()+"zoom"));
        var workspace = StarterPack.create("root_styles");
        workspace.replace("dimensions/root_styles.json",NoiseStyleFields.enable("dimensions/root_styles.json",workspace.read("dimensions/root_styles.json"),targets.getFirst(),"style"));
        workspace.replace("biomes/starter.json",NoiseStyleFields.enable("biomes/starter.json",workspace.read("biomes/starter.json"),NoiseStyleFields.Target.biome("childStyle"),"style"));
        workspace.save(temp.resolve("root-style-settings")); workspace.exportZip(temp.resolve("root-style-settings.zip"));
        var reopened = PackWorkspace.open(temp.resolve("root-style-settings"));
        var imported = PackWorkspace.importZip(temp.resolve("root-style-settings.zip"));
        for (String path : workspace.paths()) {
            check(Arrays.equals(workspace.read(path),reopened.read(path)));
            check(Arrays.equals(workspace.read(path),imported.read(path)));
        }
    }
}
