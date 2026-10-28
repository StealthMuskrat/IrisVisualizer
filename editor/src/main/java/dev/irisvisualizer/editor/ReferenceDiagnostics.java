package dev.irisvisualizer.editor;

import java.util.*;

/** Partial, exact-path reference inspection. Never modifies data or emulates Iris loader aliases. */
public final class ReferenceDiagnostics {
    public static final int MAX_JSON_BYTES = 1_000_000;
    public static final int MAX_LINKS = 1024;
    private static final class Links extends ArrayList<Link> {
        boolean truncated;
        @Override public boolean add(Link link) { if (size() >= MAX_LINKS) { truncated=true;return false; }return super.add(link); }
    }
    private static boolean full(List<Link> links) { if (links.size()<MAX_LINKS) return false;((Links)links).truncated=true;return true; }
    public enum State { EXACT_FILE, NO_EXACT_FILE, ABSENT_REQUIRED, EMPTY_REQUIRED, UNSUPPORTED, INVALID_KEY }
    public record Link(String field, String key, String target, State state) {}
    public static List<Link> inspect(PackWorkspace workspace, String path) {
        String base = ResourceTypes.base(path);
        if (base == null || !Set.of("dimensions", "regions", "biomes").contains(base)) return List.of();
        if (workspace.size(path) > MAX_JSON_BYTES) return List.of(new Link("JSON", "Above 1 MB inspection limit", null, State.UNSUPPORTED));
        Links links = new Links();
        try {
            LosslessJson document = new LosslessJson(workspace.read(path));
            switch (base) {
                case "dimensions" -> {
                    list(workspace,document,"regions","regions",true,links);
                    scalar(workspace,document,"focus","biomes","",false,false,links);
                    scalar(workspace,document,"focusRegion","regions","",false,false,links);
                    scalar(workspace,document,"upperDimension","dimensions","",true,false,links);
                    if (document.token("dimensionStack")!=null && !full(links)) {
                        try { list(workspace,new LosslessJson(document.token("dimensionStack").getBytes(java.nio.charset.StandardCharsets.UTF_8)),"dimensions","dimensions",true,"dimensionStack.",links); }
                        catch (IllegalArgumentException wrong) { links.add(new Link("dimensionStack","Unsupported stack object",null,State.UNSUPPORTED)); }
                    }
                    objectEntries(workspace,document,"staticObjects","",true,links);
                    carvingBiomes(workspace,document,links);
                }
                case "regions" -> {
                    for (String field : List.of("landBiomes", "seaBiomes", "shoreBiomes", "caveBiomes"))
                        list(workspace, document, field, "biomes", field.equals("landBiomes"), links);
                    objectEntries(workspace,document,"objects","",false,links);
                }
                case "biomes" -> {
                    biomeGenerators(workspace,document,links);
                    list(workspace,document,"children","biomes",BiomeChildren.required(),links);
                    scalar(workspace,document,"carvingBiome","biomes","",false,false,links);
                    objectEntries(workspace,document,"objects","",false,links);
                    floating(workspace,document,links);
                }
                default -> { }
            }
            riverBiomes(workspace,path,document,links);
        } catch (IllegalArgumentException invalid) { links.add(new Link("JSON", "Invalid/ambiguous JSON", null, State.UNSUPPORTED)); }
        List<Link> result=new ArrayList<>(links);
        if (links.truncated) result.add(new Link("References","Inspection truncated at "+MAX_LINKS+" links",null,State.UNSUPPORTED));
        return List.copyOf(result);
    }
    private static void carvingBiomes(PackWorkspace workspace,LosslessJson document,List<Link> links) {
        if (full(links) || document.token("carving")==null || document.token("carving").equals("null")) return;
        try {
            var entries=document.objectArray("carving");
            for (int index=0;index<entries.size() && !full(links);index++) scalar(workspace,entries.get(index),"biome","biomes","carving["+index+"].",false,true,links);
        } catch (IllegalArgumentException wrong) { links.add(new Link("carving","Snippet/mixed/non-object list",null,State.UNSUPPORTED)); }
    }
    private static void scalar(PackWorkspace workspace,LosslessJson document,String field,String type,String prefix,boolean upper,boolean selfDefault,List<Link> links) {
        if (full(links)) return;
        String token=document.token(field);if (token==null || (upper || selfDefault) && token.equals("null")) return;
        try {
            String key=document.stringToken(field);
            if (upper ? key.isEmpty() || key.equalsIgnoreCase("none") : key.isBlank()) return;
            links.add(link(workspace,prefix+field,key,type));
        } catch (IllegalArgumentException wrong) { links.add(new Link(prefix+field,"Non-string reference",null,State.UNSUPPORTED)); }
    }
    private static void objectEntries(PackWorkspace workspace,LosslessJson document,String field,String prefix,boolean fixed,List<Link> links) {
        if (full(links) || document.token(field)==null) return;
        try {
            var entries=document.objectArray(field);
            for (int index=0;index<entries.size() && !full(links);index++) {
                var entry=entries.get(index);String displayed=prefix+field+"["+index+"].";
                if (fixed) {
                    try { String key=entry.stringToken("object");links.add(key==null ? new Link(displayed+"object","Absent reference",null,State.ABSENT_REQUIRED) : link(workspace,displayed+"object",key,"objects")); }
                    catch (IllegalArgumentException wrong) { links.add(new Link(displayed+"object","Non-string reference",null,State.UNSUPPORTED)); }
                } else for (String pool : FloatingObjectPlacements.POOLS) list(workspace,entry,pool,"objects",pool.equals("place"),displayed,links);
            }
        } catch (IllegalArgumentException wrong) { links.add(new Link(prefix+field,"Snippet/mixed/non-object list",null,State.UNSUPPORTED)); }
    }
    private static void floating(PackWorkspace workspace,LosslessJson document,List<Link> links) {
        String field="floatingChildBiomes";if (full(links) || document.token(field)==null || document.token(field).equals("null")) return;
        try {
            var entries=document.objectArray(field);
            for (int index=0;index<entries.size() && !full(links);index++) {
                var entry=entries.get(index);String prefix=field+"["+index+"].";
                scalar(workspace,entry,"biome","biomes",prefix,false,true,links);
                for (String collection : FloatingObjectPlacements.LIST_FIELDS) if (!"null".equals(entry.token(collection))) objectEntries(workspace,entry,collection,prefix,false,links);
            }
        } catch (IllegalArgumentException wrong) { links.add(new Link(field,"Snippet/mixed/non-object list",null,State.UNSUPPORTED)); }
    }
    private static void riverBiomes(PackWorkspace workspace, String path, LosslessJson document, List<Link> links) {
        if (full(links)) return;
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
        if (full(links)) return;
        if (document.token("generators") == null) {
            links.add(new Link("generators", "Implicit defaults not inspected", null, State.UNSUPPORTED)); return;
        }
        try {
            List<LosslessJson> entries = document.objectArray("generators");
            for (int index=0;index<entries.size() && !full(links);index++) {
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
        if (full(links)) return;
        String displayed = prefix+field;
        if (document.token(field) == null) {
            if (required) links.add(new Link(displayed, "Absent reference list", null, State.ABSENT_REQUIRED));
            return;
        }
        try {
            List<String> keys = document.stringArrayPath(field);
            if (keys.isEmpty() && required) links.add(new Link(displayed, "Empty reference list", null, State.EMPTY_REQUIRED));
            for (int index=0;index<keys.size() && !full(links);index++) links.add(link(workspace, displayed + "[" + index + "]", keys.get(index), type));
        } catch (IllegalArgumentException mismatch) { links.add(new Link(displayed, "Non-string or unsupported list", null, State.UNSUPPORTED)); }
    }
    private static Link link(PackWorkspace workspace, String field, String key, String type) {
        if (!ResourceTypes.referenceKey(key))
            return new Link(field, key, null, State.INVALID_KEY);
        String target = type + "/" + key + (type.equals("objects") ? ".iob" : ".json");
        if (type.equals("dimensions") && !type.equals(ResourceTypes.base(target))) return new Link(field,key,null,State.INVALID_KEY);
        return new Link(field, key, target, workspace.paths().contains(target) ? State.EXACT_FILE : State.NO_EXACT_FILE);
    }
}
