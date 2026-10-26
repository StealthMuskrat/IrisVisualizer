package dev.irisvisualizer.editor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class CliffNoiseTests {
    private static final String PATH = "generators/nested/test.json";
    private static final NoiseFields.Target CLIFF = NoiseFields.Target.cliffHeight();
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static String text(byte[] value) { return new String(value, StandardCharsets.UTF_8); }
    private static void check(boolean value) { if (!value) throw new AssertionError("Cliff noise contract failed"); }
    private static void rejects(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Expected rejected cliff noise operation");
    }
    static void run(Path temp) throws Exception {
        String original = "{\r\n \"cliffHeightMin\":0,\"cliffHeightMax\":32,\"cliffHeightGenerator\": {\"seed\":9007199254740993,\"zoom\":1e+2,\"octaves\":1,\"enabled\":true,\"style\":{\"style\":\"FUTURE\",\"unknown\":true},\"fracture\":[{\"seed\":7}],\"text\":\"雪\\u0061\"},\"composite\":[\"unresolved-snippet\"],\"unknown\":true\r\n}\r\n";
        byte[] source = bytes(original), copy = source.clone();
        byte[] seed = NoiseFields.editText(PATH, source, CLIFF, "seed", "-9223372036854775808");
        check(text(seed).equals(original.replace("\"seed\":9007199254740993", "\"seed\":-9223372036854775808")));
        byte[] zoom = NoiseFields.editText(PATH, source, CLIFF, "zoom", "2.5");
        check(text(zoom).equals(original.replace("\"zoom\":1e+2", "\"zoom\":2.5")));
        byte[] toggle = NoiseFields.editBoolean(PATH, source, CLIFF, "enabled", false);
        check(text(toggle).equals(original.replace("\"enabled\":true", "\"enabled\":false")));
        byte[] height = GeneratorFields.editText(PATH, source, "cliffHeightMin", "128.5");
        check(text(height).equals(original.replace("\"cliffHeightMin\":0", "\"cliffHeightMin\":128.5")));
        // The official scalar annotations impose bounds, not a minimum <= maximum relationship.
        check(new LosslessJson(height).token("cliffHeightMax").equals("32"));
        for (String field : List.of("cliffHeightMin", "cliffHeightMax")) {
            check(GeneratorFields.defaultToken(field).equals("0"));
            check(new LosslessJson(GeneratorFields.enable(PATH, bytes("{}"), field)).fields().equals(Set.of(field)));
            for (String valid : List.of("0", "8192", "0.5")) GeneratorFields.editText(PATH, source, field, valid);
            for (String bad : List.of("-0.01", "8192.01", "NaN", "Infinity"))
                rejects(() -> GeneratorFields.editText(PATH, source, field, bad));
        }
        Map<String,String> numbers = Map.of("zoom","2.5", "opacity","0.25", "offsetX","-13.5", "offsetY","7", "offsetZ","-2", "seed","9223372036854775807", "exponent","2", "octaves","4");
        for (String field : NoiseFields.EDITABLE) {
            // Explicit enablement creates just the requested field; no style, sibling or composite defaults.
            byte[] enabled = NoiseFields.enable(PATH, bytes("{\r\n \"unknown\":\"雪\"\r\n}"), CLIFF, field);
            var document = new LosslessJson(enabled);
            check(document.fields().equals(Set.of("unknown", "cliffHeightGenerator")));
            check(document.token("unknown").equals("\"雪\""));
            var child = new LosslessJson(bytes(document.token("cliffHeightGenerator")));
            check(child.fields().equals(Set.of(field)) && child.token(field).equals(NoiseFields.defaultToken(field)));
            byte[] edited = NoiseFields.booleanField(field)
                    ? NoiseFields.editBoolean(PATH, enabled, CLIFF, field, !child.token(field).equals("true"))
                    : NoiseFields.editText(PATH, enabled, CLIFF, field, numbers.get(field));
            check(!Arrays.equals(enabled, edited));
            rejects(() -> NoiseFields.enable(PATH, enabled, CLIFF, field));
        }
        for (String invalid : List.of("null", "[]", "\"snippet\"", "false", "7")) {
            byte[] wrongParent = bytes("{\"cliffHeightGenerator\":" + invalid + "}");
            rejects(() -> NoiseFields.enable(PATH, wrongParent, CLIFF, "zoom"));
            rejects(() -> NoiseFields.editText(PATH, wrongParent, CLIFF, "seed", "2"));
            rejects(() -> NoiseFields.editBoolean(PATH, wrongParent, CLIFF, "enabled", true));
        }
        rejects(() -> NoiseFields.editText(PATH, bytes("{}"), CLIFF, "seed", "2"));
        rejects(() -> NoiseFields.editText(PATH, bytes("{\"cliffHeightGenerator\":{}}"), CLIFF, "seed", "2"));
        rejects(() -> NoiseFields.editText(PATH, source, CLIFF, "seed", "9223372036854775808"));
        rejects(() -> NoiseFields.editText(PATH, source, CLIFF, "octaves", "1.5"));
        rejects(() -> NoiseFields.editText(PATH, source, CLIFF, "octaves", "17"));
        rejects(() -> NoiseFields.editText(PATH, source, CLIFF, "zoom", "0"));
        rejects(() -> NoiseFields.editText(PATH, source, CLIFF, "zoom", "NaN"));
        rejects(() -> NoiseFields.editBoolean(PATH, source, CLIFF, "zoom", true));
        rejects(() -> NoiseFields.editText(PATH, source, CLIFF, "enabled", "1"));
        rejects(() -> NoiseFields.enable(PATH, source, CLIFF, "style"));
        rejects(() -> NoiseFields.enable("biomes/test.json", source, CLIFF, "zoom"));
        rejects(() -> NoiseFields.enable(PATH, source, -1, "zoom"));
        rejects(() -> new NoiseFields.Target("style", -1));
        rejects(() -> new NoiseFields.Target("cliffHeightGenerator", 0));
        rejects(() -> new NoiseFields.Target("composite", -1));
        rejects(() -> new LosslessJson(source).objectEdit("cliffHeightGenerator", object -> bytes("[]"), false));
        check(Arrays.equals(source, copy));
        var drafts = new InspectorDrafts();
        drafts.update(CLIFF.draftPrefix()+"seed", "1", "2");
        drafts.update(NoiseFields.Target.composite(0).draftPrefix()+"seed", "1", "3");
        drafts.transitionUnder("composite#", () -> true, () -> {});
        check(drafts.count()==1 && drafts.contains("cliffHeightGenerator#seed"));

        var workspace = StarterPack.create("cliff_settings");
        workspace.replace("generators/flat.json", seed);
        workspace.save(temp.resolve("cliff-settings"));
        check(Arrays.equals(PackWorkspace.open(temp.resolve("cliff-settings")).read("generators/flat.json"), seed));
        workspace.exportZip(temp.resolve("cliff-settings.zip"));
        check(Arrays.equals(PackWorkspace.importZip(temp.resolve("cliff-settings.zip")).read("generators/flat.json"), seed));
        check(FieldCoverage.fields("generator").stream().filter(field -> Set.of("cliffHeightMin","cliffHeightMax","cliffHeightGenerator").contains(field.name())).allMatch(FieldCoverage.Field::implemented));
    }
}
