package dev.irisvisualizer.editor;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/** Exact focus/reachability references only; loader compatibility and native selection are not evaluated. */
public final class ResourceSelectors {
    public static final List<String> DIMENSION_FIELDS = List.of("focus", "focusRegion");
    public static final List<String> BIOME_FIELDS = List.of("carvingBiome");
    private static final Properties DATA=new Properties();
    static {
        try (var input=ResourceSelectors.class.getResourceAsStream("/resource-selector-fields.properties")) {
            if (input==null) throw new IOException("Missing pinned resource-selector definitions");DATA.load(input);
            if (!"usableBiomeBeforeRegion".equals(DATA.getProperty("focus.policy")) || !"reachabilityOnly".equals(DATA.getProperty("carvingBiome.policy"))) throw new IOException("Unsupported selector policy");
        } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    public static List<String> fields(String path) { return "dimensions".equals(ResourceTypes.base(path)) ? DIMENSION_FIELDS : "biomes".equals(ResourceTypes.base(path)) ? BIOME_FIELDS : List.of(); }
    private static void field(String value) { if (value==null || !DIMENSION_FIELDS.contains(value) && !BIOME_FIELDS.contains(value)) throw new IllegalArgumentException("Unsupported resource selector"); }
    public static String family(String field) { field(field);return DATA.getProperty(field+".family"); }
    public static String help(String field) { field(field);return DATA.getProperty(field+".description"); }
    public static List<String> choices(PackWorkspace workspace,String field) { return ResourceTypes.referenceKeys(workspace,family(field)); }
    public static String prefix(String path,String field) { field(field);return "resourceSelector#"+ResourceTypes.base(path)+"."+field; }
    static String stored(LosslessJson document,String field) { field(field);return document.token(field)==null ? "" : document.stringToken(field); }
    static boolean supported(LosslessJson document,String field) { try { stored(document,field);return true; } catch (IllegalArgumentException invalid) { return false; } }
    public static byte[] set(String path,byte[] original,String field,String key,PackWorkspace workspace) {
        field(field);if (!fields(path).contains(field)) throw new IllegalArgumentException("Wrong resource-selector owner");var document=new LosslessJson(original);stored(document,field);
        if (key==null) return document.token(field)==null ? original.clone() : document.stringValue(field,"");
        if (!choices(workspace,field).contains(key)) throw new IllegalArgumentException("Choose an exact in-pack key; loader aliases and target contents are unvalidated");
        return document.token(field)==null ? document.enableStringPath(field,key) : document.stringValue(field,key);
    }
    public static final class CatalogCache {
        private PackWorkspace workspace;private long revision=Long.MIN_VALUE;private final Map<String,ReferenceChoices.Catalog> families=new HashMap<>();
        public void clear(){workspace=null;revision=Long.MIN_VALUE;families.clear();}
        public ReferenceChoices.Catalog catalog(PackWorkspace current,String field){
            java.util.Objects.requireNonNull(current);String family=family(field);if(current!=workspace || current.resourceRevision()!=revision){clear();workspace=current;revision=current.resourceRevision();}
            return families.computeIfAbsent(family,ignored->new ReferenceChoices.Catalog(ResourceTypes.referenceKeys(current,family)));
        }
        public List<String> choices(PackWorkspace current,String field){return catalog(current,field).keys();}
        public ReviewPagination.Page<String> page(PackWorkspace current,String field,String query,int requested){ReferenceChoices.query(query);return catalog(current,field).page(query,requested);}
    }
}
