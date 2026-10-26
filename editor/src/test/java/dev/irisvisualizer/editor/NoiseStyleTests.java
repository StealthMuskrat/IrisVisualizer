package dev.irisvisualizer.editor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

final class NoiseStyleTests {
    private static final String PATH = "generators/nested/test.json";
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static String text(byte[] bytes) { return new String(bytes, StandardCharsets.UTF_8); }
    private static void check(boolean value) { if (!value) throw new AssertionError("Noise style contract failed"); }
    private static void rejects(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Expected rejected noise style operation");
    }
    private static LosslessJson noise(byte[] original, NoiseFields.Target target) {
        var root = new LosslessJson(original);
        return target.index() >= 0 ? root.objectArray("composite").get(target.index()) : new LosslessJson(bytes(root.token("cliffHeightGenerator")));
    }
    static void run(Path temp) throws Exception {
        String style = "{\"style\":\"FUTURE\\u005fSTYLE\",\"zoom\":1e0,\"exponent\":1,\"expression\":\"unknown-expression\",\"imageMap\":\"unknown-image\",\"fracture\":{\"style\":\"UNKNOWN\",\"zoom\":9e0},\"unknown\":\"雪\"}";
        String entry = "{\"seed\":9007199254740993,\"style\":"+style+",\"fracture\":[{\"seed\":7}]}";
        String prefix = "{\r\n \"composite\":[", middle = ",{\"unknown\":true}],\"cliffHeightGenerator\":", suffix = ",\"unknown\":\"雪\"\r\n}\r\n";
        byte[] original = bytes(prefix+entry+middle+entry+suffix), copy = original.clone();
        check(NoiseStyleFields.choices().size()==186 && NoiseStyleFields.noiseDefault().equals("IRIS") && NoiseStyleFields.objectDefault().equals("FLAT"));
        for (var target : List.of(NoiseFields.Target.composite(0), NoiseFields.Target.cliffHeight())) {
            for (String choice : NoiseStyleFields.choices()) {
                byte[] changed = NoiseStyleFields.editStyle(PATH, original, target, choice);
                String changedEntry = entry.replace("FUTURE\\u005fSTYLE",choice);
                check(text(changed).equals(prefix+(target.index()>=0?changedEntry:entry)+middle+(target.index()<0?changedEntry:entry)+suffix));
                check(!NoiseStyleFields.description(choice).isBlank());
            }
            byte[] zoom = NoiseStyleFields.editText(PATH, original, target, "zoom", "2.5");
            String changedEntry = entry.replace("\"zoom\":1e0", "\"zoom\":2.5");
            check(text(zoom).equals(prefix+(target.index()>=0?changedEntry:entry)+middle+(target.index()<0?changedEntry:entry)+suffix));
            for (String valid : List.of("0.01562","64")) NoiseStyleFields.editText(PATH,original,target,"exponent",valid);
            NoiseStyleFields.editText(PATH,original,target,"zoom","1000000");
            for (String invalid : List.of("0", "-1", "0.000001", "NaN", "Infinity"))
                rejects(() -> NoiseStyleFields.editText(PATH,original,target,"zoom",invalid));
            for (String invalid : List.of("0.01561", "64.01", "NaN"))
                rejects(() -> NoiseStyleFields.editText(PATH,original,target,"exponent",invalid));
            for (String field : List.of("expression","imageMap","fracture","cacheSize","cellularZoom","cellularFrequency","multiplier"))
                rejects(() -> NoiseStyleFields.enable(PATH,original,target,field));
            rejects(() -> NoiseStyleFields.editStyle(PATH,original,target,"not-a-style"));
            rejects(() -> NoiseStyleFields.editText(PATH,original,target,"style","2"));
            rejects(() -> NoiseStyleFields.enable(PATH,original,target,"style"));
        }
        check(Arrays.equals(original,copy));

        // A missing parent is IRIS; an existing empty style object is FLAT. Keep both semantics.
        for (var target : List.of(NoiseFields.Target.composite(0), NoiseFields.Target.cliffHeight())) {
            byte[] absent = bytes("{\"composite\":[{}]}");
            byte[] enabled = NoiseStyleFields.enable(PATH,absent,target,"style");
            var created = noise(enabled,target);
            check(created.fields().equals(Set.of("style")));
            check(NoiseStyleFields.object(created).stringToken("style").equals("IRIS"));
            for (String field : List.of("zoom","exponent")) {
                byte[] scalar = NoiseStyleFields.enable(PATH,absent,target,field);
                var child = NoiseStyleFields.object(noise(scalar,target));
                check(child.fields().equals(Set.of("style",field)) && child.stringToken("style").equals("IRIS"));
                check(child.token(field).equals("1"));
                rejects(() -> NoiseStyleFields.enable(PATH,scalar,target,field));
            }
        }
        var target = NoiseFields.Target.composite(0);
        byte[] empty = bytes("{\"composite\":[{\"style\":{}}]}");
        var existing = NoiseStyleFields.object(noise(NoiseStyleFields.enable(PATH,empty,target,"zoom"),target));
        check(existing.fields().equals(Set.of("zoom")) && existing.token("style")==null);
        check(NoiseStyleFields.object(noise(NoiseStyleFields.enable(PATH,empty,target,"style"),target)).stringToken("style").equals("FLAT"));
        rejects(() -> NoiseStyleFields.editStyle(PATH,empty,target,"IRIS"));
        rejects(() -> NoiseStyleFields.editText(PATH,empty,target,"zoom","2"));
        for (String bad : List.of("null", "3", "false", "[]", "\"snippet\"")) {
            byte[] wrong = bytes("{\"composite\":[{\"style\":"+bad+"}]}");
            rejects(() -> NoiseStyleFields.enable(PATH,wrong,target,"zoom"));
            rejects(() -> NoiseStyleFields.editStyle(PATH,wrong,target,"IRIS"));
            rejects(() -> NoiseStyleFields.editText(PATH,wrong,target,"zoom","2"));
        }
        rejects(() -> NoiseStyleFields.enable(PATH,bytes("{\"composite\":[\"snippet\"]}"),target,"style"));
        rejects(() -> NoiseStyleFields.enable(PATH,bytes("{\"cliffHeightGenerator\":\"snippet\"}"),NoiseFields.Target.cliffHeight(),"style"));
        rejects(() -> NoiseStyleFields.enable("biomes/test.json",empty,target,"style"));
        rejects(() -> NoiseStyleFields.editStyle(PATH,bytes("{\"composite\":[{\"style\":{\"style\":null}}]}"),target,"IRIS"));
        rejects(() -> NoiseStyleFields.editText(PATH,bytes("{\"composite\":[{\"style\":{\"zoom\":\"1\"}}]}"),target,"zoom","2"));

        var drafts = new InspectorDrafts();
        drafts.update("composite#0.style.zoom","1","2"); drafts.update("cliffHeightGenerator#style.zoom","1","3");
        drafts.transitionUnder("composite#",()->true,()->{});
        check(drafts.count()==1 && drafts.contains("cliffHeightGenerator#style.zoom"));
        var workspace = StarterPack.create("style_settings");
        workspace.replace("generators/flat.json",NoiseStyleFields.editStyle(PATH,original,target,"CLOVER"));
        workspace.save(temp.resolve("style-settings"));
        workspace.exportZip(temp.resolve("style-settings.zip"));
        check(Arrays.equals(PackWorkspace.open(temp.resolve("style-settings")).read("generators/flat.json"),workspace.read("generators/flat.json")));
        check(Arrays.equals(PackWorkspace.importZip(temp.resolve("style-settings.zip")).read("generators/flat.json"),workspace.read("generators/flat.json")));
    }
}
