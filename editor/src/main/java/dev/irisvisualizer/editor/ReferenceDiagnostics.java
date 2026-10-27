package dev.irisvisualizer.editor;

import java.util.*;

/** Partial, exact-path reference inspection. Never modifies data or emulates Iris loader aliases. */
public final class ReferenceDiagnostics {
    public static final int MAX_JSON_BYTES = 1_000_000;
    public enum State { EXACT_FILE, NO_EXACT_FILE, ABSENT_REQUIRED, EMPTY_REQUIRED, UNSUPPORTED, INVALID_KEY }
    public record Link(String field, String key, String target, State state) {}
    public static List<Link> inspect(PackWorkspace workspace, String path) {
        String base = ResourceTypes.base(path);
        if (base == null || !Set.of("dimensions", "regions", "biomes").contains(base)) return List.of();
        if (workspace.size(path) > MAX_JSON_BYTES) return List.of(new Link("JSON", "Above 1 MB inspection limit", null, State.UNSUPPORTED));
        List<Link> links = new ArrayList<>();
        try {
            LosslessJson document = new LosslessJson(workspace.read(path));
            switch (base) {
                case "dimensions" -> list(workspace, document, "regions", "regions", true, links);
                case "regions" -> {
                    for (String field : List.of("landBiomes", "seaBiomes", "shoreBiomes", "caveBiomes"))
                        list(workspace, document, field, "biomes", field.equals("landBiomes"), links);
                }
                case "biomes" -> {
                    biomeGenerators(workspace,document,links);
                    list(workspace,document,"children","biomes",BiomeChildren.required(),links);
                }
                default -> { }
            }
            riverBiomes(workspace,path,document,links);
        } catch (IllegalArgumentException invalid) { links.add(new Link("JSON", "Invalid/ambiguous JSON", null, State.UNSUPPORTED)); }
        return List.copyOf(links);
    }
    private static void riverBiomes(PackWorkspace workspace, String path, LosslessJson document, List<Link> links) {
        LosslessJson policy;
        try { policy = RiverPolicyFields.object(path,document); }
        catch (IllegalArgumentException mismatch) { links.add(new Link("riverPolicy", "Unsupported policy object", null, State.UNSUPPORTED)); return; }
        if (policy == null) return;
        for (String field : RiverBiomeLists.EDITABLE) {
            String token = policy.token(field);
            if (token == null || token.equals("null")) continue; // Inherited references are not resolved.
            list(workspace,policy,field,"biomes",false,"riverPolicy.",links);
        }
    }
    private static void biomeGenerators(PackWorkspace workspace, LosslessJson document, List<Link> links) {
        if (document.token("generators") == null) {
            links.add(new Link("generators", "Implicit defaults not inspected", null, State.UNSUPPORTED)); return;
        }
        try {
            List<LosslessJson> entries = document.objectArray("generators");
            for (int index=0;index<entries.size();index++) {
                String field = "generators[" + index + "].generator";
                try {
                    String key = entries.get(index).stringToken("generator");
                    if (key == null) links.add(new Link(field, "Implicit default not inspected", null, State.UNSUPPORTED));
                    else links.add(link(workspace, field, key, "generators"));
                } catch (IllegalArgumentException mismatch) { links.add(new Link(field, "Non-string reference", null, State.UNSUPPORTED)); }
            }
        } catch (IllegalArgumentException mismatch) { links.add(new Link("generators", "Snippet/mixed/non-object list", null, State.UNSUPPORTED)); }
    }
    private static void list(PackWorkspace workspace, LosslessJson document, String field, String type, boolean required, List<Link> links) {
        list(workspace,document,field,type,required,"",links);
    }
    private static void list(PackWorkspace workspace, LosslessJson document, String field, String type, boolean required, String prefix, List<Link> links) {
        String displayed = prefix+field;
        if (document.token(field) == null) {
            if (required) links.add(new Link(displayed, "Absent reference list", null, State.ABSENT_REQUIRED));
            return;
        }
        try {
            List<String> keys = document.stringArrayPath(field);
            if (keys.isEmpty() && required) links.add(new Link(displayed, "Empty reference list", null, State.EMPTY_REQUIRED));
            for (int index=0;index<keys.size();index++) links.add(link(workspace, displayed + "[" + index + "]", keys.get(index), type));
        } catch (IllegalArgumentException mismatch) { links.add(new Link(displayed, "Non-string or unsupported list", null, State.UNSUPPORTED)); }
    }
    private static Link link(PackWorkspace workspace, String field, String key, String type) {
        if (!ResourceTypes.referenceKey(key))
            return new Link(field, key, null, State.INVALID_KEY);
        String target = type + "/" + key + ".json";
        return new Link(field, key, target, workspace.paths().contains(target) ? State.EXACT_FILE : State.NO_EXACT_FILE);
    }
}
