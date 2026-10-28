package dev.irisvisualizer.editor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class CaveThresholdTests {
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static String text(byte[] bytes) { return new String(bytes, StandardCharsets.UTF_8); }
    private static void check(boolean condition) { if (!condition) throw new AssertionError("Cave threshold contract failed"); }
    private static void rejects(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Expected rejected cave threshold operation");
    }
    private static LosslessJson threshold(byte[] source) { return CaveThresholdFields.object(CaveProfileFields.object(new LosslessJson(source))); }
    private static LosslessJson style(byte[] source, NoiseStyleFields.Target target) { return NoiseStyleFields.object(threshold(source), target); }
    static void run(Path temp) throws Exception {
        check(!CaveThresholdFields.description().isBlank() && !CaveThresholdFields.styleDescription().isBlank());
        check(CaveThresholdFields.defaultBound("min", true).equals("-0.2") && CaveThresholdFields.defaultBound("max", true).equals("0.2"));
        check(CaveThresholdFields.defaultBound("min", false).equals("16") && CaveThresholdFields.defaultBound("max", false).equals("32"));
        check(CaveThresholdFields.defaultStyle(true).equals("CELLULAR_IRIS_DOUBLE") && CaveThresholdFields.defaultStyle(false).equals("STATIC"));
        String leaf = "{\"style\":\"FUTURE\\u005fSTYLE\",\"zoom\":1e0,\"exponent\":1,\"cellularFrequency\":0,\"cellularZoom\":1,\"expression\":\"unresolved\",\"fracture\":{\"style\":\"FUTURE\"},\"future\":\"雪\"}";
        String prefix = "{\r\n \"caveProfile\":{\"densityThreshold\":{\"min\":-2e-1,\"max\":0.200,\"style\":";
        String suffix = ",\"future\":true},\"baseDensityStyle\":{\"style\":\"FUTURE\",\"zoom\":8e0},\"verticalRange\":{\"min\":0,\"max\":384},\"modules\":[\"unknown-module\"],\"enabled\":false},\"unknown\":9007199254740993\r\n}\r\n";
        String original = prefix + leaf + suffix;
        byte[] source = bytes(original), copy = source.clone();
        var prefixes = new HashSet<String>();
        for (String context : CaveProfileFields.CONTEXTS) {
            String path = context + "/test.json";
            var target = NoiseStyleFields.Target.threshold(context);
            check(target.threshold() && target.cave() && !target.terrain() && target.member().equals("style"));
            check(NoiseStyleFields.contextDefault(target).equals("STATIC") && !NoiseStyleFields.contextDescription(target).isBlank());
            check(prefixes.add(CaveThresholdFields.draftPrefix(context)) && prefixes.add(target.draftPrefix()));
            byte[] enabled = CaveThresholdFields.enable(path, bytes("{\"caveProfile\":{\"future\":true}}"));
            check(threshold(enabled).fields().equals(Set.of("min", "max", "style")));
            check(threshold(enabled).token("min").equals("-0.2") && threshold(enabled).token("max").equals("0.2"));
            check(style(enabled, target).fields().equals(Set.of("style")) && style(enabled, target).stringToken("style").equals("CELLULAR_IRIS_DOUBLE"));
            check(new LosslessJson(enabled).tokenPath("caveProfile.enabled") == null && new LosslessJson(enabled).tokenPath("caveProfile.future").equals("true"));
            byte[] withZoom = NoiseStyleFields.enable(path, enabled, target, "zoom");
            check(style(withZoom, target).stringToken("style").equals("CELLULAR_IRIS_DOUBLE") && style(withZoom, target).token("zoom").equals("1"));
            check(threshold(withZoom).token("min").equals("-0.2") && threshold(withZoom).token("max").equals("0.2"));
            rejects(() -> CaveThresholdFields.enable(path, enabled));
            for (String choice : NoiseStyleFields.choices())
                check(text(NoiseStyleFields.editStyle(path, source, target, choice)).equals(prefix + leaf.replace("FUTURE\\u005fSTYLE", choice) + suffix));
            check(text(NoiseStyleFields.editText(path, source, target, "zoom", "2.5")).equals(prefix + leaf.replace("\"zoom\":1e0", "\"zoom\":2.5") + suffix));
            NoiseStyleFields.editText(path, source, target, "cellularZoom", "-1"); // General inactive rules.
            rejects(() -> NoiseStyleFields.editText(path, source, target, "zoom", "0"));
            rejects(() -> NoiseStyleFields.editText(path, source, target, "exponent", "65"));
            rejects(() -> NoiseStyleFields.editText(path, source, target, "cellularFrequency", "-1"));
            rejects(() -> NoiseStyleFields.editStyle(path, source, target, "unknown"));
            rejects(() -> NoiseStyleFields.enable(path, source, target, "fracture"));
            check(text(CaveThresholdFields.editRange(path, source, "-0.2", "0.3")).equals(original.replace("\"max\":0.200", "\"max\":0.3")));
            check(text(CaveThresholdFields.editRange(path, source, "-0.3", "0.2")).equals(original.replace("\"min\":-2e-1", "\"min\":-0.3")));
            check(Arrays.equals(CaveThresholdFields.editRange(path, source, "-0.2", "0.2"), source));
            CaveThresholdFields.editRange(path, source, "-1000.5", "1000.5"); // No invented -1..1 bounds.
            CaveThresholdFields.editRange(path, source, "0.25", "0.25");
            CaveThresholdFields.editRange(path, source, Double.toString(-Double.MAX_VALUE), Double.toString(Double.MAX_VALUE));
            rejects(() -> CaveThresholdFields.editRange(path, source, "0.3", "0.2"));
            for (String invalid : List.of("NaN", "Infinity", "1e999", "", "\"0\"")) {
                rejects(() -> CaveThresholdFields.editRange(path, source, invalid, "1"));
                rejects(() -> CaveThresholdFields.editRange(path, source, "0", invalid));
            }
            byte[] empty = bytes("{\"caveProfile\":{\"densityThreshold\":{}}}");
            byte[] filled = CaveThresholdFields.editRange(path, empty, "16", "32");
            check(threshold(filled).fields().equals(Set.of("min", "max")) && threshold(filled).token("max").equals("32"));
            for (String field : NoiseStyleFields.EDITABLE) {
                byte[] styleEnabled = NoiseStyleFields.enable(path, empty, target, field);
                var expected = new HashSet<String>(); expected.add("style"); expected.add(field);
                check(threshold(styleEnabled).fields().equals(Set.of("style")) && style(styleEnabled, target).fields().equals(expected));
                check(style(styleEnabled, target).stringToken("style").equals("STATIC"));
                rejects(() -> NoiseStyleFields.enable(path, styleEnabled, target, field));
            }
            byte[] emptyStyle = bytes("{\"caveProfile\":{\"densityThreshold\":{\"style\":{}}}}");
            check(style(NoiseStyleFields.enable(path, emptyStyle, target, "style"), target).stringToken("style").equals("FLAT"));
            check(style(NoiseStyleFields.enable(path, emptyStyle, target, "zoom"), target).token("zoom").equals("1"));
            byte[] partial = bytes("{\"caveProfile\":{\"densityThreshold\":{\"min\":1e0,\"future\":true}}}");
            byte[] repaired = CaveThresholdFields.editRange(path, partial, "1", "32");
            check(threshold(repaired).token("min").equals("1e0") && threshold(repaired).token("max").equals("32") && threshold(repaired).token("future").equals("true"));
            byte[] unsupportedStyle = bytes("{\"caveProfile\":{\"densityThreshold\":{\"min\":0,\"max\":1,\"style\":\"snippet\"}}}");
            check(threshold(CaveThresholdFields.editRange(path, unsupportedStyle, "0", "2")).token("style").equals("\"snippet\""));
            byte[] invalidBounds = bytes("{\"caveProfile\":{\"densityThreshold\":{\"min\":\"wrong-type\",\"max\":1,\"style\":{\"style\":\"IRIS\"}}}}");
            check(style(NoiseStyleFields.editStyle(path, invalidBounds, target, "SIMPLEX"), target).stringToken("style").equals("SIMPLEX"));
            for (String bad : List.of("null", "[]", "1", "true", "\"snippet\"")) {
                byte[] wrong = bytes("{\"caveProfile\":{\"densityThreshold\":" + bad + "}}");
                rejects(() -> CaveThresholdFields.enable(path, wrong));
                rejects(() -> CaveThresholdFields.editRange(path, wrong, "0", "1"));
                rejects(() -> NoiseStyleFields.enable(path, wrong, target, "style"));
                byte[] wrongStyle = bytes("{\"caveProfile\":{\"densityThreshold\":{\"style\":" + bad + "}}}");
                rejects(() -> NoiseStyleFields.enable(path, wrongStyle, target, "style"));
                rejects(() -> NoiseStyleFields.editStyle(path, wrongStyle, target, "IRIS"));
            }
            for (String bad : List.of("null", "[]", "true", "\"0\"", "{}")) {
                byte[] wrongMin = bytes("{\"caveProfile\":{\"densityThreshold\":{\"min\":" + bad + ",\"max\":1}}}");
                byte[] wrongMax = bytes("{\"caveProfile\":{\"densityThreshold\":{\"min\":0,\"max\":" + bad + "}}}");
                rejects(() -> CaveThresholdFields.editRange(path, wrongMin, "0", "1"));
                rejects(() -> CaveThresholdFields.editRange(path, wrongMax, "0", "1"));
            }
            for (String missing : List.of("{}", "{\"caveProfile\":null}", "{\"caveProfile\":\"snippet\"}")) {
                rejects(() -> CaveThresholdFields.enable(path, bytes(missing)));
                rejects(() -> CaveThresholdFields.editRange(path, bytes(missing), "0", "1"));
                rejects(() -> NoiseStyleFields.enable(path, bytes(missing), target, "style"));
            }
            byte[] absentThreshold = bytes("{\"caveProfile\":{}} ");
            rejects(() -> CaveThresholdFields.editRange(path, absentThreshold, "0", "1"));
            rejects(() -> NoiseStyleFields.enable(path, absentThreshold, target, "zoom"));
            rejects(() -> NoiseStyleFields.editStyle("generators/test.json", source, target, "IRIS"));
        }
        check(prefixes.size() == 6 && Arrays.equals(source, copy));
        for (String path : List.of("generators/test.json", "dimensions/nested/test.json", "test.json")) {
            rejects(() -> CaveThresholdFields.enable(path, source));
            rejects(() -> CaveThresholdFields.editRange(path, source, "0", "1"));
        }
        rejects(() -> NoiseStyleFields.Target.threshold("generators"));
        rejects(() -> NoiseStyleFields.Target.cave("biomes", "densityThreshold.style"));
        rejects(() -> new NoiseStyleFields.Target("biomes", "caveProfile.densityThreshold.style.fracture", null));
        rejects(() -> CaveThresholdFields.defaultBound("style", false));
        check(FieldCoverage.fields("styledRange").size() == 3 && FieldCoverage.fields("styledRange").stream().allMatch(FieldCoverage.Field::implemented));
        var readOnly = DimensionFields.readOnlyPaths(new LosslessJson(source));
        check(readOnly.equals(List.of( "caveProfile.densityThreshold.style.future", "caveProfile.densityThreshold.future", "caveProfile.modules", "unknown")));
        var drafts = new InspectorDrafts();
        for (String context : CaveProfileFields.CONTEXTS) {
            drafts.update(CaveThresholdFields.draftPrefix(context) + "min", "0", "1");
            drafts.update(NoiseStyleFields.Target.threshold(context).draftPrefix() + "zoom", "1", "2");
        }
        drafts.update(CavePlacementFields.draftPrefix("biomes") + "min", "0", "1");
        drafts.update("caveProfile#biomes.baseWeight", "1", "2");
        drafts.applied(CaveThresholdFields.draftPrefix("biomes") + "min");
        check(drafts.count() == 7 && drafts.contains(NoiseStyleFields.Target.threshold("biomes").draftPrefix() + "zoom") && drafts.contains(CavePlacementFields.draftPrefix("biomes") + "min"));
        var workspace = StarterPack.create("cave_threshold");
        for (String path : List.of("dimensions/cave_threshold.json", "regions/starter.json", "biomes/starter.json")) {
            var target = NoiseStyleFields.Target.threshold(ResourceTypes.base(path));
            byte[] changed = CaveThresholdFields.editRange(path, source, "-0.5", "0.5");
            changed = NoiseStyleFields.editStyle(path, changed, target, "SIMPLEX");
            workspace.replace(path, changed);
        }
        workspace.save(temp.resolve("cave-threshold")); workspace.exportZip(temp.resolve("cave-threshold.zip"));
        var reopened = PackWorkspace.open(temp.resolve("cave-threshold")); var imported = PackWorkspace.importZip(temp.resolve("cave-threshold.zip"));
        for (String path : workspace.paths()) {
            check(Arrays.equals(workspace.read(path), reopened.read(path)));
            check(Arrays.equals(workspace.read(path), imported.read(path)));
        }
        System.out.println("PASS: cave threshold contextual/class/style defaults, bounds, all style choices, owner/parent/draft guards and Save/ZIP contracts");
    }
}
