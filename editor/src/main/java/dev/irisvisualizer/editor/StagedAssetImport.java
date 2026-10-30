package dev.irisvisualizer.editor;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/** Reviewed opaque bytes for a new asset path. The target token stays on the UI thread. */
public final class StagedAssetImport {
    public static final class Target {
        private final PackWorkspace owner;
        private final Path source;
        private final String path;
        private final long budget;
        private Target(PackWorkspace owner,Path source,String path,long budget) { this.owner=owner;this.source=source;this.path=path;this.budget=budget; }
        public Path source() { return source; }
        public String path() { return path; }
        public long budget() { return budget; }
    }
    public static Target target(PackWorkspace current,Path source,String key) throws IOException { return target(current,source,key,PackWorkspace.MAX_BYTES); }
    static Target target(PackWorkspace current,Path source,String key,long maximumBytes) throws IOException {
        Objects.requireNonNull(current);if (key==null) throw new IOException("Choose a resource key");var description=AssetImports.describe(source);
        // Workspace preflight bounds path depth/components before validating the pasted resource-key regex.
        String path=description.family()+"/"+key+description.extension();
        return new Target(current,description.path(),path,current.assetImportBudget(path,description.bytes(),maximumBytes));
    }
    public static boolean belongsTo(PackWorkspace current,Target target) { return target!=null && target.owner==current; }
    public static boolean available(PackWorkspace current,Target target) {
        if (!belongsTo(current,target)) return false;
        try { current.assetImportBudget(target.path,0,PackWorkspace.MAX_BYTES);return true; } catch (IOException unavailable) { return false; }
    }
    public static String apply(PackWorkspace current,Target target,AssetReplacement.Prepared prepared,boolean acknowledgeHistoryClear) throws IOException { return apply(current,target,prepared,acknowledgeHistoryClear,PackWorkspace.MAX_BYTES); }
    static String apply(PackWorkspace current,Target target,AssetReplacement.Prepared prepared,boolean acknowledgeHistoryClear,long maximumBytes) throws IOException {
        if (!belongsTo(current,target)) throw new IOException("Import belongs to another workspace; capture again");
        if (prepared==null || !target.path.equals(prepared.receipt().target())) throw new IOException("Captured asset belongs to another destination");
        current.assetImportBudget(target.path,prepared.receipt().bytes(),maximumBytes);
        if (!acknowledgeHistoryClear) throw new IOException("Explicitly acknowledge clearing applied-edit history before importing a new asset");
        return current.installCapturedAsset(target.path,prepared,maximumBytes);
    }
}
