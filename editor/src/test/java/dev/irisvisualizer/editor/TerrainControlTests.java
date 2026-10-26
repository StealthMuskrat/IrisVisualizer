package dev.irisvisualizer.editor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

final class TerrainControlTests {
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static String text(byte[] value) { return new String(value, StandardCharsets.UTF_8); }
    private static void check(boolean value) { if (!value) throw new AssertionError("Terrain control contract failed"); }
    private static void rejects(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Expected rejected terrain control operation");
    }
    static void run(Path temp) throws Exception {
        String path = "generators/nested/test.json";
        String original = "{\r\n \"interpolator\": {\"function\":\"FUTURE\\u005fMETHOD\",\"horizontalScale\":7e0,\"unknown\":\"雪\"},\"seed\":9007199254740993,\"composite\":[{\"style\":{\"style\":\"FUTURE\"}}]\r\n}\r\n";
        byte[] source = bytes(original), copy = source.clone();
        check(InterpolationFields.choices().size() == 27);
        check(InterpolationFields.defaultValue().equals("BILINEAR_STARCAST_6"));
        check(new LosslessJson(source).stringPath("interpolator.function").equals("FUTURE_METHOD"));
        for (String choice : InterpolationFields.choices()) {
            byte[] next = InterpolationFields.edit(path, source, choice);
            check(text(next).equals(original.replace("FUTURE\\u005fMETHOD", choice)));
            check(!InterpolationFields.description(choice).isBlank());
        }
        for (String input : List.of("{}", "{\"interpolator\":{}}", "{\"interpolator\":{\"unknown\":true}}")) {
            byte[] enabled = InterpolationFields.enable(path, bytes(input));
            var object = new LosslessJson(bytes(new LosslessJson(enabled).token("interpolator")));
            check(object.stringToken("function").equals(InterpolationFields.defaultValue()));
            check(object.token("horizontalScale") == null);
            rejects(() -> InterpolationFields.enable(path, enabled));
        }
        rejects(() -> InterpolationFields.edit(path, bytes("{}"), "NONE"));
        rejects(() -> InterpolationFields.edit(path, bytes("{\"interpolator\":{}}"), "NONE"));
        for (String value : List.of("FUTURE_METHOD", "none", " NONE ", "", "NONE\"}"))
            rejects(() -> InterpolationFields.edit(path, source, value));
        rejects(() -> InterpolationFields.edit("biomes/test.json", source, "NONE"));
        rejects(() -> InterpolationFields.enable("regions/test.json", bytes("{}")));
        for (String bad : List.of("null", "3", "false", "[]", "\"snippet\"")) {
            byte[] wrongParent = bytes("{\"interpolator\":" + bad + "}");
            rejects(() -> InterpolationFields.enable(path, wrongParent));
            rejects(() -> InterpolationFields.edit(path, wrongParent, "NONE"));
            if (!bad.startsWith("\"")) {
                byte[] wrongField = bytes("{\"interpolator\":{\"function\":" + bad + "}}");
                rejects(() -> InterpolationFields.enable(path, wrongField));
                rejects(() -> InterpolationFields.edit(path, wrongField, "NONE"));
            }
        }
        rejects(() -> InterpolationFields.edit(path, bytes("{\"interpolator\":{\"function\":\"NONE\",\"function\":\"BILINEAR\"}}"), "NONE"));
        check(Arrays.equals(source, copy));
        String escaped = "quote\" slash\\ newline\n tab\t zero\0 雪";
        byte[] string = new LosslessJson(bytes("{\"unknown\":1e3}")).enableStringPath("parent.field", escaped);
        check(new LosslessJson(string).stringPath("parent.field").equals(escaped));
        check(new LosslessJson(string).token("unknown").equals("1e3"));

        String biome = "{\r\n \"lockLayersMax\":7,\"childShrinkFactor\":1.5,\"children\":[\"unknown\"],\"unknown\":\"雪\"\r\n}";
        byte[] biomeBytes = bytes(biome);
        check(text(BiomeFields.edit("biomes/test.json", biomeBytes, "lockLayersMax", Integer.MAX_VALUE)).equals(biome.replace("\"lockLayersMax\":7", "\"lockLayersMax\":2147483647")));
        BiomeFields.edit("biomes/test.json", biomeBytes, "lockLayersMax", Integer.MIN_VALUE);
        for (double invalid : new double[]{2147483648d, -2147483649d, 1.5, Double.NaN, Double.POSITIVE_INFINITY})
            rejects(() -> BiomeFields.edit("biomes/test.json", biomeBytes, "lockLayersMax", invalid));
        check(text(BiomeFields.edit("biomes/test.json", biomeBytes, "childShrinkFactor", -2.5)).equals(biome.replace("\"childShrinkFactor\":1.5", "\"childShrinkFactor\":-2.5")));
        rejects(() -> BiomeFields.edit("biomes/test.json", biomeBytes, "childShrinkFactor", Double.NaN));
        check(BiomeFields.defaultToken("lockLayersMax").equals("7"));
        check(BiomeFields.defaultToken("childShrinkFactor").equals("1.5"));
        check(!BiomeFields.constraints("childShrinkFactor").contains("minimum") && !BiomeFields.constraints("childShrinkFactor").contains("maximum"));
        for (String field : List.of("lockLayersMax", "childShrinkFactor"))
            check(new LosslessJson(BiomeFields.enable("biomes/test.json", bytes("{}"), field)).fields().equals(Set.of(field)));

        String region = "{\"shoreHeightMin\":1.2,\"shoreHeightMax\":3.2,\"landBiomes\":[\"unknown\"],\"unknown\":\"雪\"}";
        byte[] regionBytes = bytes(region);
        check(text(RegionFields.edit("regions/test.json", regionBytes, "shoreHeightMin", 8.5)).equals(region.replace("\"shoreHeightMin\":1.2", "\"shoreHeightMin\":8.5")));
        for (String field : List.of("shoreHeightMin", "shoreHeightMax")) {
            RegionFields.edit("regions/test.json", regionBytes, field, 0);
            RegionFields.edit("regions/test.json", regionBytes, field, 1000000);
            rejects(() -> RegionFields.edit("regions/test.json", regionBytes, field, -0.1));
            rejects(() -> RegionFields.edit("regions/test.json", regionBytes, field, Double.POSITIVE_INFINITY));
            check(new LosslessJson(RegionFields.enable("regions/test.json", bytes("{}"), field)).fields().equals(Set.of(field)));
        }
        check(RegionFields.defaultToken("shoreHeightMin").equals("1.2") && RegionFields.defaultToken("shoreHeightMax").equals("3.2"));
        var workspace = StarterPack.create("terrain_controls");
        workspace.replace("generators/flat.json", InterpolationFields.edit(path, source, "HERMITE"));
        workspace.replace("biomes/starter.json", BiomeFields.edit("biomes/starter.json", biomeBytes, "lockLayersMax", 12));
        workspace.replace("regions/starter.json", RegionFields.edit("regions/starter.json", regionBytes, "shoreHeightMax", 5.5));
        workspace.save(temp.resolve("terrain-controls"));
        workspace.exportZip(temp.resolve("terrain-controls.zip"));
        var reopened = PackWorkspace.open(temp.resolve("terrain-controls"));
        var imported = PackWorkspace.importZip(temp.resolve("terrain-controls.zip"));
        for (String resource : workspace.paths()) {
            check(Arrays.equals(workspace.read(resource), reopened.read(resource)));
            check(Arrays.equals(workspace.read(resource), imported.read(resource)));
        }
    }
}
