package dev.irisvisualizer.editor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class CavePlacementTests {
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static String text(byte[] bytes) { return new String(bytes, StandardCharsets.UTF_8); }
    private static void check(boolean condition) { if (!condition) throw new AssertionError("Cave bounds/placement contract failed"); }
    private static void rejects(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Expected rejected cave bounds/placement operation");
    }
    private static LosslessJson profile(byte[] bytes) { return CaveProfileFields.object(new LosslessJson(bytes)); }
    private static LosslessJson range(byte[] bytes) { return CavePlacementFields.range(profile(bytes)); }
    static void run(Path temp) throws Exception {
        check(CavePlacementFields.choices("defaultObjectAnchor").size() == 5);
        check(CavePlacementFields.choices("defaultObjectPlaceMode").size() == 21);
        check(CavePlacementFields.defaultValue("defaultObjectAnchor").equals("FLOOR"));
        check(!CavePlacementFields.nullable("defaultObjectAnchor") && CavePlacementFields.nullable("defaultObjectPlaceMode"));
        check(CavePlacementFields.rangeDefault("min", true).equals("0") && CavePlacementFields.rangeDefault("max", true).equals("384"));
        check(CavePlacementFields.rangeDefault("min", false).equals("16") && CavePlacementFields.rangeDefault("max", false).equals("32"));
        var prefixes = new HashSet<String>();
        String original = "{\r\n \"caveProfile\":{\"verticalRange\":{\"min\":-1e1,\"max\":384.00,\"future\":\"雪\\u0061\"},\"defaultObjectAnchor\":\"FUTURE\\u005fANCHOR\",\"defaultObjectPlaceMode\":\"FUTURE\\u005fPLACE\",\"baseDensityStyle\":{\"style\":\"FUTURE\",\"zoom\":1e0},\"modules\":[\"unresolved-module\"],\"enabled\":false},\"unknown\":9007199254740993\r\n}\r\n";
        byte[] source = bytes(original), copy = source.clone();
        for (String context : CaveProfileFields.CONTEXTS) {
            String path = context + "/test.json";
            check(prefixes.add(CavePlacementFields.draftPrefix(context)));
            byte[] enabled = CavePlacementFields.enableRange(path, bytes("{\"caveProfile\":{\"future\":true}}"));
            check(range(enabled).fields().equals(Set.of("min", "max")));
            check(range(enabled).token("min").equals("0") && range(enabled).token("max").equals("384"));
            check(profile(enabled).token("enabled") == null && profile(enabled).token("future").equals("true"));
            rejects(() -> CavePlacementFields.enableRange(path, enabled));
            rejects(() -> CavePlacementFields.editRange(path, bytes("{\"caveProfile\":{}}"), "0", "384"));
            // An empty explicit object inherits IrisRange defaults, not cave constructor arguments.
            byte[] empty = bytes("{\"caveProfile\":{\"verticalRange\":{}}}");
            byte[] filled = CavePlacementFields.editRange(path, empty, "16", "32");
            check(range(filled).token("min").equals("16") && range(filled).token("max").equals("32"));
            check(text(CavePlacementFields.editRange(path, source, "-10", "400")).equals(original.replace("\"max\":384.00", "\"max\":400")));
            check(text(CavePlacementFields.editRange(path, source, "-10.5", "384")).equals(original.replace("\"min\":-1e1", "\"min\":-10.5")));
            check(Arrays.equals(CavePlacementFields.editRange(path, source, "-10", "384"), source));
            CavePlacementFields.editRange(path, source, "-0.25", "-0.25");
            CavePlacementFields.editRange(path, source, Double.toString(-Double.MAX_VALUE), Double.toString(Double.MAX_VALUE));
            byte[] partial = bytes("{\"caveProfile\":{\"verticalRange\":{\"min\":1e0,\"future\":true}}}");
            byte[] repaired = CavePlacementFields.editRange(path, partial, "1", "32");
            check(range(repaired).token("min").equals("1e0") && range(repaired).token("max").equals("32") && range(repaired).token("future").equals("true"));
            byte[] overflow = bytes("{\"caveProfile\":{\"verticalRange\":{\"min\":-1e999,\"max\":1e999}}}");
            check(range(CavePlacementFields.editRange(path, overflow, "0", "384")).token("max").equals("384"));
            for (String invalid : List.of("NaN", "Infinity", "-Infinity", "1e999", "\"1\"", "")) {
                rejects(() -> CavePlacementFields.editRange(path, source, invalid, "384"));
                rejects(() -> CavePlacementFields.editRange(path, source, "0", invalid));
            }
            rejects(() -> CavePlacementFields.editRange(path, source, "385", "384"));
            for (String bad : List.of("null", "[]", "1", "true", "\"snippet\"")) {
                byte[] wrong = bytes("{\"caveProfile\":{\"verticalRange\":" + bad + "}}");
                rejects(() -> CavePlacementFields.enableRange(path, wrong));
                rejects(() -> CavePlacementFields.editRange(path, wrong, "0", "384"));
                byte[] wrongMin = bytes("{\"caveProfile\":{\"verticalRange\":{\"min\":" + bad + ",\"max\":384}}}");
                rejects(() -> CavePlacementFields.editRange(path, wrongMin, "0", "384"));
                byte[] wrongMax = bytes("{\"caveProfile\":{\"verticalRange\":{\"min\":0,\"max\":" + bad + "}}}");
                rejects(() -> CavePlacementFields.editRange(path, wrongMax, "0", "384"));
            }
            for (String field : CavePlacementFields.ENUM_FIELDS) {
                check(!CavePlacementFields.description(field).isBlank());
                String old = field.equals("defaultObjectAnchor") ? "FUTURE\\u005fANCHOR" : "FUTURE\\u005fPLACE";
                for (String choice : CavePlacementFields.choices(field)) {
                    check(!CavePlacementFields.choiceDescription(field, choice).isBlank());
                    byte[] changed = CavePlacementFields.assign(path, source, field, choice);
                    check(text(changed).equals(original.replace(old, choice)));
                    byte[] absent = CavePlacementFields.assign(path, bytes("{\"caveProfile\":{}}"), field, choice);
                    check(profile(absent).fields().equals(Set.of(field)) && profile(absent).stringToken(field).equals(choice));
                }
                rejects(() -> CavePlacementFields.assign(path, source, field, "FUTURE"));
                for (String bad : List.of("[]", "1", "false", "{}")) {
                    byte[] wrong = bytes("{\"caveProfile\":{\"" + field + "\":" + bad + "}}");
                    rejects(() -> CavePlacementFields.assign(path, wrong, field, CavePlacementFields.choices(field).getFirst()));
                    if (CavePlacementFields.nullable(field)) rejects(() -> CavePlacementFields.assign(path, wrong, field, null));
                }
            }
            byte[] cleared = CavePlacementFields.assign(path, source, "defaultObjectPlaceMode", null);
            check(text(cleared).equals(original.replace("\"FUTURE\\u005fPLACE\"", "null")));
            check(CavePlacementFields.enumValue(profile(cleared), "defaultObjectPlaceMode") == null);
            byte[] selected = CavePlacementFields.assign(path, cleared, "defaultObjectPlaceMode", "FAST_MIN_STILT");
            check(profile(selected).stringToken("defaultObjectPlaceMode").equals("FAST_MIN_STILT"));
            byte[] absent = bytes("{\"caveProfile\":{\"future\":true}}");
            check(Arrays.equals(CavePlacementFields.assign(path, absent, "defaultObjectPlaceMode", null), absent));
            rejects(() -> CavePlacementFields.assign(path, source, "defaultObjectAnchor", null));
            rejects(() -> CavePlacementFields.assign(path, bytes("{\"caveProfile\":{\"defaultObjectAnchor\":null}}"), "defaultObjectAnchor", "FLOOR"));
            for (String missing : List.of("{}", "{\"caveProfile\":null}", "{\"caveProfile\":\"snippet\"}")) {
                rejects(() -> CavePlacementFields.enableRange(path, bytes(missing)));
                rejects(() -> CavePlacementFields.editRange(path, bytes(missing), "0", "384"));
                rejects(() -> CavePlacementFields.assign(path, bytes(missing), "defaultObjectAnchor", "FLOOR"));
                rejects(() -> CavePlacementFields.assign(path, bytes(missing), "defaultObjectPlaceMode", null));
            }
        }
        check(prefixes.size() == 3 && Arrays.equals(source, copy));
        for (String path : List.of("generators/test.json", "dimensions/nested/test.json", "test.json")) {
            rejects(() -> CavePlacementFields.enableRange(path, source));
            rejects(() -> CavePlacementFields.editRange(path, source, "0", "384"));
            rejects(() -> CavePlacementFields.assign(path, source, "defaultObjectAnchor", "FLOOR"));
        }
        rejects(() -> CavePlacementFields.assign("biomes/test.json", source, "enabled", "FLOOR"));
        rejects(() -> CavePlacementFields.choices("modules"));
        rejects(() -> CavePlacementFields.rangeDefault("future", false));
        var readonly = DimensionFields.readOnlyPaths(new LosslessJson(source));
        check(readonly.equals(List.of("caveProfile.verticalRange.future", "caveProfile.modules", "unknown")));
        check(DimensionFields.readOnlyPaths(new LosslessJson(bytes("{\"caveProfile\":{\"verticalRange\":\"snippet\"}}"))).equals(List.of("caveProfile.verticalRange")));
        var drafts = new InspectorDrafts();
        for (String prefix : prefixes) drafts.update(prefix + "min", "0", "1");
        drafts.update("caveProfile#biomes.baseWeight", "1", "2");
        drafts.update("caveProfile#biomes.surfaceBreakStyle.zoom", "1", "2");
        drafts.applied(CavePlacementFields.draftPrefix("biomes") + "min");
        check(drafts.count() == 4 && drafts.contains("caveProfile#biomes.baseWeight") && drafts.contains("caveProfile#biomes.surfaceBreakStyle.zoom"));
        var workspace = StarterPack.create("cave_placement");
        for (String path : List.of("dimensions/cave_placement.json", "regions/starter.json", "biomes/starter.json")) {
            byte[] changed = CavePlacementFields.editRange(path, source, "-8.5", "320");
            changed = CavePlacementFields.assign(path, changed, "defaultObjectAnchor", "CEILING");
            changed = CavePlacementFields.assign(path, changed, "defaultObjectPlaceMode", null);
            workspace.replace(path, changed);
        }
        workspace.save(temp.resolve("cave-placement")); workspace.exportZip(temp.resolve("cave-placement.zip"));
        var reopened = PackWorkspace.open(temp.resolve("cave-placement")); var imported = PackWorkspace.importZip(temp.resolve("cave-placement.zip"));
        for (String path : workspace.paths()) {
            check(Arrays.equals(workspace.read(path), reopened.read(path)));
            check(Arrays.equals(workspace.read(path), imported.read(path)));
        }
        System.out.println("PASS: cave bounds defaults/preservation, all anchor/placement choices, null overrides, owner/draft guards and Save/ZIP contracts");
    }
}
