package dev.irisvisualizer.editor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

/** Literal dimension-owned IDs only; does not resolve area ownership, aliases or hydrology validity. */
public final class RiverIdLists {
    public static final List<String> EDITABLE = List.of("profiles", "surfacePools");
    private static final Properties DEFINITIONS = new Properties();
    public enum Status { READY, NEED_DIMENSION, UNAVAILABLE }
    public record Catalog(List<String> ids, String dimension, Status status, String detail) {
        public Catalog { ids = List.copyOf(ids); }
    }
    static {
        try (var input = RiverIdLists.class.getResourceAsStream("/river-id-fields.properties")) {
            if (input == null) throw new IOException("Missing pinned river ID definitions");
            DEFINITIONS.load(input);
            if (!"1".equals(DEFINITIONS.getProperty("implicitProfileCount")) || !"0".equals(DEFINITIONS.getProperty("implicitPoolCount"))) throw new IOException("Unsupported implicit catalog defaults");
            if (!"hydrology.rivers.profiles".equals(DEFINITIONS.getProperty("profiles.catalogPath")) || !"hydrology.surfacePools".equals(DEFINITIONS.getProperty("surfacePools.catalogPath"))) throw new IOException("Unsupported river ID catalog paths");
            for (String field : EDITABLE) if (!"null".equals(DEFINITIONS.getProperty(field+".default")) || defaultId(field).isBlank()) throw new IOException("Invalid nullable ID-list defaults");
            if (!"firstConfiguredProfile".equals(DEFINITIONS.getProperty("profiles.emptyMeaning")) || !"clearInheritedSelection".equals(DEFINITIONS.getProperty("surfacePools.emptyMeaning"))) throw new IOException("Unsupported ID-list empty meaning");
        } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    private static String authorize(String path, String field) {
        String context = ResourceTypes.base(path);
        if (context == null || !CaveProfileFields.CONTEXTS.contains(context) || !EDITABLE.contains(field)) throw new IllegalArgumentException("River ID selection is read-only");
        return context;
    }
    public static String description(String field) { return DEFINITIONS.getProperty(field+".description", ""); }
    private static String defaultId(String field) { return DEFINITIONS.getProperty(field+".idDefault", ""); }
    public static String draftPrefix(String context, String field) {
        if (!EDITABLE.contains(field)) throw new IllegalArgumentException("Unsupported river ID selection");
        return RiverPolicyFields.draftPrefix(context)+field+".";
    }
    public static String emptyLabel(String field) {
        if (!EDITABLE.contains(field)) throw new IllegalArgumentException("Unsupported river ID selection");
        return field.equals("profiles") ? "Explicit empty selection: clears inheritance; Iris uses its first configured dimension profile."
                : "Explicit empty selection: clears inherited pool IDs.";
    }
    public static List<String> dimensions(PackWorkspace workspace) {
        return ResourceTypes.referenceKeys(workspace,"dimensions").stream().map(key -> "dimensions/"+key+".json").toList();
    }
    public static Catalog catalog(PackWorkspace workspace, String owner, String chosenDimension, String field) {
        String context = authorize(owner,field);
        String dimension = context.equals("dimensions") ? owner : chosenDimension;
        if (context.equals("dimensions") && chosenDimension != null && !chosenDimension.equals(owner))
            return new Catalog(List.of(),owner,Status.UNAVAILABLE,"A dimension policy uses its own ID catalog.");
        if (dimension == null) return new Catalog(List.of(),null,Status.NEED_DIMENSION,"Choose a dimension to supply the available ID strings.");
        if (!dimensions(workspace).contains(dimension)) return new Catalog(List.of(),dimension,Status.UNAVAILABLE,"The chosen dimension is not an exact dimension resource.");
        if (workspace.size(dimension)>ReferenceDiagnostics.MAX_JSON_BYTES) return new Catalog(List.of(),dimension,Status.UNAVAILABLE,"Dimension is above the 1 MB catalog inspection limit.");
        try {
            var document = new LosslessJson(workspace.read(dimension));
            var hydrology = childOrDefault(document,"hydrology");
            var container = field.equals("profiles") ? childOrDefault(hydrology,"rivers") : hydrology;
            String token = container.token(field);
            if (token == null) return new Catalog(field.equals("profiles") ? List.of(defaultId(field)) : List.of(),dimension,Status.READY,
                    field.equals("profiles") ? "Iris default profile ID; no configuration is inserted." : "No pool IDs: Iris defaults to an empty pool list.");
            var entries = container.objectArray(field); var ids = new LinkedHashSet<String>();
            for (var entry : entries) {
                String id = entry.token("id")==null ? defaultId(field) : entry.stringToken("id");
                if (id!=null && id.length()>4096) return new Catalog(List.of(),dimension,Status.UNAVAILABLE,"A configured ID exceeds the editor's 4096-character catalog limit; configuration is preserved.");
                if (id==null || id.isBlank() || !ids.add(id)) return new Catalog(List.of(),dimension,Status.UNAVAILABLE,"Catalog contains a blank or duplicate literal ID; configuration is preserved.");
            }
            return new Catalog(List.copyOf(ids),dimension,Status.READY,ids.isEmpty() ? "The configured catalog is explicitly empty." : "IDs match the configured text exactly; other hydrology settings are not validated.");
        } catch (IllegalArgumentException mismatch) {
            return new Catalog(List.of(),dimension,Status.UNAVAILABLE,"Catalog has unsupported JSON, containers, entries or ID types; configuration is preserved.");
        }
    }
    private static LosslessJson childOrDefault(LosslessJson parent, String field) {
        String token = parent.token(field);
        return new LosslessJson((token==null ? "{}" : token).getBytes(StandardCharsets.UTF_8));
    }
    public static final class CatalogCache {
        private PackWorkspace workspace;
        private String owner, dimension;
        private long revision = -1;
        private final Map<String,Catalog> catalogs = new HashMap<>();
        public Catalog inspect(PackWorkspace current, String resource, String chosen, String field) {
            if (workspace!=current || revision!=current.revision() || !Objects.equals(owner,resource) || !Objects.equals(dimension,chosen)) {
                catalogs.clear(); workspace=current; revision=current.revision(); owner=resource; dimension=chosen;
            }
            return catalogs.computeIfAbsent(field,ignored -> catalog(current,resource,chosen,field));
        }
    }
    public static byte[] setInherited(String path, byte[] original, String field, boolean inherit) {
        authorize(path,field);
        return new LosslessJson(original).objectEdit("riverPolicy",policy -> policy.nullableStringArray(field,inherit),false);
    }
    public static byte[] edit(String path, byte[] original, String field, int index, String id, PackWorkspace workspace, String chosenDimension) {
        authorize(path,field);
        if (id != null) {
            var catalog = catalog(workspace,path,chosenDimension,field);
            if (catalog.status()!=Status.READY || !catalog.ids().contains(id)) throw new IllegalArgumentException("Choose a unique literal ID available in the selected dimension catalog.");
        }
        return new LosslessJson(original).objectEdit("riverPolicy",policy -> policy.stringArrayPath(field,index,id),false);
    }
    public static byte[] change(String path, byte[] original, String field, int index, RiverBiomeLists.Action action) {
        authorize(path,field);
        if (action==null) throw new IllegalArgumentException("Choose an ID-list action");
        return new LosslessJson(original).objectEdit("riverPolicy",policy -> switch(action) {
            case DUPLICATE -> policy.stringArrayDuplicate(field,index);
            case UP -> policy.stringArrayMove(field,index,index-1);
            case DOWN -> policy.stringArrayMove(field,index,index+1);
        },false);
    }
}
