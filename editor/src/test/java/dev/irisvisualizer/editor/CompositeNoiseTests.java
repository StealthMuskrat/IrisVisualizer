package dev.irisvisualizer.editor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

final class CompositeNoiseTests {
    private static final String PATH = "generators/nested/test.json";
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static String text(byte[] bytes) { return new String(bytes, StandardCharsets.UTF_8); }
    private static void check(boolean value) { if (!value) throw new AssertionError("Composite noise contract failed"); }
    private static void rejects(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Expected rejected noise list operation");
    }
    static void run(Path temp) throws Exception {
        String first = "{\"seed\":9007199254740993, \"style\":{\"style\":\"FUTURE\",\"unknown\":[true,null]},\"fracture\":[{\"seed\":7}],\"text\":\"雪\\u0061\"}";
        String second = "{ \"zoom\":1e+2,\"enabled\":false }";
        String third = "{}";
        String prefix = "{\r\n \"unknown\":{\"雪\":true},\"composite\": [\r\n  ";
        String gap = ",\r\n  ", suffix = "\r\n ],\"seed\":-9223372036854775808\r\n}\r\n";
        byte[] original = bytes(prefix + first + gap + second + gap + third + suffix);
        byte[] copy = original.clone();
        byte[] duplicate = CompositeNoise.change(PATH, original, CompositeNoise.Action.DUPLICATE, 0);
        check(text(duplicate).equals(prefix + first + gap + first + ", " + second + gap + third + suffix));
        check(Arrays.equals(CompositeNoise.change(PATH, duplicate, CompositeNoise.Action.REMOVE, 1), original));
        byte[] moved = CompositeNoise.change(PATH, original, CompositeNoise.Action.DOWN, 0);
        check(text(moved).equals(prefix + second + gap + first + gap + third + suffix));
        check(Arrays.equals(CompositeNoise.change(PATH, moved, CompositeNoise.Action.UP, 1), original));
        check(text(CompositeNoise.change(PATH, original, CompositeNoise.Action.REMOVE, 0)).equals(prefix + second + gap + third + suffix));
        check(text(CompositeNoise.change(PATH, original, CompositeNoise.Action.REMOVE, 1)).equals(prefix + first + gap + third + suffix));
        check(text(CompositeNoise.change(PATH, original, CompositeNoise.Action.REMOVE, 2)).equals(prefix + first + gap + second + suffix));
        byte[] added = CompositeNoise.change(PATH, original, CompositeNoise.Action.ADD, -1);
        check(text(added).equals(prefix + first + gap + second + gap + third + ", {}" + suffix));
        check(Arrays.equals(original, copy));
        for (String input : List.of("{}", "{\"composite\":[]}", "{\"composite\":[ \r\n ]}")) {
            byte[] created = CompositeNoise.change(PATH, bytes(input), CompositeNoise.Action.ADD, -1);
            check(new LosslessJson(created).objectArray("composite").size() == 1);
            check(new LosslessJson(created).objectArray("composite").getFirst().fields().isEmpty());
        }
        check(CompositeNoise.minimumEntries() == 1);
        rejects(() -> CompositeNoise.change(PATH, bytes("{\"composite\":[{}]}"), CompositeNoise.Action.REMOVE, 0));
        rejects(() -> CompositeNoise.change(PATH, original, CompositeNoise.Action.UP, 0));
        rejects(() -> CompositeNoise.change(PATH, original, CompositeNoise.Action.DOWN, 2));
        rejects(() -> CompositeNoise.change(PATH, original, CompositeNoise.Action.DUPLICATE, 3));
        rejects(() -> CompositeNoise.change(PATH, original, CompositeNoise.Action.REMOVE, -1));
        rejects(() -> CompositeNoise.change("biomes/test.json", original, CompositeNoise.Action.ADD, -1));
        for (String bad : List.of("null", "{}", "\"snippet\"", "[{},\"snippet\"]", "[{},null]", "[1]"))
            for (var action : CompositeNoise.Action.values())
                rejects(() -> CompositeNoise.change(PATH, bytes("{\"composite\":" + bad + "}"), action, 0));
        rejects(() -> CompositeNoise.change(PATH, bytes("{\"composite\":[{\"a\":1,\"a\":2}]}"), CompositeNoise.Action.ADD, -1));
        rejects(() -> CompositeNoise.change(PATH, new byte[]{(byte)0xff}, CompositeNoise.Action.ADD, -1));
        rejects(() -> CompositeNoise.change(PATH, bytes("{}"), CompositeNoise.Action.DUPLICATE, 0));
        var document = new LosslessJson(original);
        rejects(() -> document.objectArrayInsert("composite", 0, bytes("\"snippet\"")));
        rejects(() -> document.objectArrayInsert("composite", 4, bytes("{}")));
        check(Arrays.equals(document.objectArrayMove("composite", 0, 0), original));
        check(new LosslessJson(document.objectArrayMove("composite", 0, 2)).objectArray("composite").get(2).token("seed").equals("9007199254740993"));

        // Only drafts whose indices could have changed are cleared, and only after successful writes.
        var drafts = new InspectorDrafts();
        drafts.update("composite#0.seed", "1", "2"); drafts.update("noise#seed", "1", "3");
        check(!drafts.transitionUnder("composite#", () -> false, () -> { throw new AssertionError("Cancelled mutation ran"); }));
        check(drafts.count() == 2);
        try { drafts.transitionUnder("composite#", () -> true, () -> { throw new java.io.IOException("Write failed"); }); }
        catch (java.io.IOException expected) { check(drafts.count() == 2); }
        check(drafts.transitionUnder("composite#", () -> true, () -> {}));
        check(drafts.count() == 1 && drafts.contains("noise#seed"));

        var workspace = StarterPack.create("noise_structure");
        workspace.replace("generators/flat.json", original);
        workspace.save(temp.resolve("noise-structure"));
        workspace.replace("generators/flat.json", CompositeNoise.change("generators/flat.json", workspace.read("generators/flat.json"), CompositeNoise.Action.DOWN, 0));
        check(workspace.dirty());
        workspace.save(temp.resolve("noise-structure"));
        check(!workspace.dirty());
        check(Arrays.equals(PackWorkspace.open(temp.resolve("noise-structure")).read("generators/flat.json"), moved));
        workspace.exportZip(temp.resolve("noise-structure.zip"));
        check(Arrays.equals(PackWorkspace.importZip(temp.resolve("noise-structure.zip")).read("generators/flat.json"), moved));
    }
}
