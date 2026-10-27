package dev.irisvisualizer.editor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;
import java.util.TreeMap;

/** Literal fallback authoring and advisory source overlap checks; no registry resolution runs. */
public final class BlockFallbacks {
    public static final List<String> OBJECT_FIELDS = List.of("blockFallbacks");
    private static final Properties DATA=new Properties();
    static {
        try (var input=BlockFallbacks.class.getResourceAsStream("/block-fallback-fields.properties")) {
            if (input==null) throw new IOException("Missing pinned block fallback definitions");DATA.load(input);
            if (!"packTopLevelDimensionMaps".equals(DATA.getProperty("scope.policy")) || !"registryRenameSingleFallback".equals(DATA.getProperty("resolution.policy")) || !"trimLowerBaseDefaultNamespace".equals(DATA.getProperty("source.policy")) || !"unspecifiedIteration".equals(DATA.getProperty("winner.policy"))) throw new IOException("Unsupported block fallback policy");
        } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    public static String prefix() { return "blockFallbacks#."; }
    public static String help() { return DATA.getProperty("description",""); }
    private static void authorize(String path) { if (!DimensionFields.dimension(path)) throw new IllegalArgumentException("Block fallbacks belong to top-level dimension resources"); }
    static LosslessJson object(LosslessJson document) { String token=document.token("blockFallbacks");return token==null ? null : new LosslessJson(token.getBytes(StandardCharsets.UTF_8)); }
    static List<LosslessJson.StringProperty> entries(LosslessJson document) { var map=object(document);return map==null ? null : map.stringProperties(); }
    private static void source(String value) { if (value==null || !value.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) throw new IllegalArgumentException("Enter a lowercase namespaced base block id without properties; registry existence is unchecked"); }
    private static void target(String value) { if (value==null || value.isBlank()) throw new IllegalArgumentException("Enter a nonblank fallback state; registry and state validity are unchecked"); }
    public static byte[] enable(String path,byte[] original) {
        authorize(path);var root=new LosslessJson(original);if (root.token("blockFallbacks")!=null) throw new IllegalArgumentException("Map already exists or has an unsupported type");return root.enableObject("blockFallbacks");
    }
    public static byte[] add(String path,byte[] original,String source,String target) {
        authorize(path);source(source);target(target);return new LosslessJson(original).objectEdit("blockFallbacks",m -> m.stringMapAdd(source,target),false);
    }
    public static byte[] set(String path,byte[] original,String previousSource,String nextSource,String target) {
        authorize(path);if (previousSource==null || nextSource==null) throw new IllegalArgumentException("Select a source key");if (!previousSource.equals(nextSource)) source(nextSource);target(target);
        return new LosslessJson(original).objectEdit("blockFallbacks",m -> {
            m.stringProperties();String old=m.stringToken(previousSource);if (old==null) throw new IllegalArgumentException("Source key is absent");
            byte[] renamed=m.stringMapRename(previousSource,nextSource);return old.equals(target) ? renamed : new LosslessJson(renamed).stringValue(nextSource,target);
        },false);
    }
    public static byte[] remove(String path,byte[] original,String source) { authorize(path);return new LosslessJson(original).objectEdit("blockFallbacks",m -> m.stringMapRemove(source),false); }
    /** Source identity only, matching ContentGate.normalizeState/baseKey; targets are never resolved. */
    static String normalizedSource(String source) {
        if (source==null) return null;String trimmed=source.trim();if (trimmed.isEmpty()) return null;
        int properties=trimmed.indexOf('[');String base=(properties<0 ? trimmed : trimmed.substring(0,properties)).toLowerCase(Locale.ROOT);
        return base.indexOf(':')<0 ? DATA.getProperty("defaultNamespace")+":"+base : base;
    }
    public record Definition(String path,String source,String target) {}
    public record Overlap(String normalizedSource,List<Definition> definitions) {
        public Overlap { definitions=List.copyOf(definitions); }
    }
    public record Notice(String path,String message) {}
    public record Audit(int inspectedDimensions,int definitions,List<Overlap> overlaps,List<Notice> notices) {
        public Audit { overlaps=List.copyOf(overlaps);notices=List.copyOf(notices); }
    }
    public static Audit inspect(PackWorkspace workspace) {
        Objects.requireNonNull(workspace);var grouped=new TreeMap<String,List<Definition>>();var notices=new ArrayList<Notice>();int dimensions=0,count=0;
        for (String path : workspace.paths()) {
            if (!DimensionFields.dimension(path)) continue;
            if (workspace.size(path)>ReferenceDiagnostics.MAX_JSON_BYTES) { notices.add(new Notice(path,"Above the 1 MB inspection limit; overlap check is incomplete for this resource."));continue; }
            try {
                var rows=entries(new LosslessJson(workspace.read(path)));dimensions++;
                if (rows==null) continue;
                for (var row : rows) {
                    String normalized=normalizedSource(row.key());
                    if (normalized==null || row.value().isBlank()) { notices.add(new Notice(path,"Blank source/target entry is ignored by the native fallback reader or normalizer: "+row.key()));continue; }
                    grouped.computeIfAbsent(normalized,ignored -> new ArrayList<>()).add(new Definition(path,row.key(),row.value()));count++;
                }
            } catch (IllegalArgumentException unsupported) { notices.add(new Notice(path,"Unsupported JSON or fallback map; overlap check is incomplete for this resource. Stored data is preserved.")); }
        }
        var overlaps=new ArrayList<Overlap>();for (var group : grouped.entrySet()) if (group.getValue().size()>1) overlaps.add(new Overlap(group.getKey(),group.getValue()));
        return new Audit(dimensions,count,overlaps,notices);
    }
    public static final class AuditCache {
        private PackWorkspace workspace;
        private long revision=-1;
        private Audit audit;
        public Audit inspect(PackWorkspace current) {
            Objects.requireNonNull(current);if (workspace!=current || revision!=current.revision()) { audit=BlockFallbacks.inspect(current);workspace=current;revision=current.revision(); }return audit;
        }
    }
}
