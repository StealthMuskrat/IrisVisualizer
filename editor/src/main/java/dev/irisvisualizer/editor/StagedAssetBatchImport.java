package dev.irisvisualizer.editor;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Bounded opaque capture and atomic new-path installation. No reference edits, decoding or native loading. */
public final class StagedAssetBatchImport {
    public static final int MAX_FILES=32;
    public static final long MAX_BYTES=128L*1024*1024;
    /** Immutable worker input. Contains neither a workspace nor its UI-owned target token. */
    public record Input(Path source,String target) {}
    public static final class Target {
        private final PackWorkspace owner;
        private final List<Input> inputs;
        private final long budget;
        private Target(PackWorkspace owner,List<Input> inputs,long budget) { this.owner=owner;this.inputs=List.copyOf(inputs);this.budget=budget; }
        public List<Input> inputs() { return inputs; }
        public long budget() { return budget; }
    }
    public static final class Prepared {
        private final List<AssetReplacement.Prepared> captures;
        private final List<AssetReplacement.Receipt> receipts;
        private Prepared(List<AssetReplacement.Prepared> captures) { this.captures=List.copyOf(captures);receipts=captures.stream().map(AssetReplacement.Prepared::receipt).toList(); }
        public List<AssetReplacement.Receipt> receipts() { return receipts; }
        public long bytes() { return receipts.stream().mapToLong(AssetReplacement.Receipt::bytes).sum(); }
    }
    public static Target target(PackWorkspace current,List<Path> sources,String prefix) throws IOException { return target(current,sources,prefix,PackWorkspace.MAX_BYTES); }
    static Target target(PackWorkspace current,List<Path> sources,String prefix,long maximumBytes) throws IOException {
        Objects.requireNonNull(current);Objects.requireNonNull(sources);
        if (sources.isEmpty() || sources.size()>MAX_FILES) throw new IOException("Choose 1 to 32 asset files per batch");
        if (prefix==null || prefix.length()>16_384 || !prefix.isEmpty() && (prefix.split("/",-1).length>62 || java.util.Arrays.stream(prefix.split("/",-1)).anyMatch(p -> p.length()>255 || p.isEmpty()) || !prefix.matches("(?:[a-z0-9_-]+/)*[a-z0-9_-]+"))) throw new IOException("Use an optional relative lowercase folder prefix (letters, digits, _ and -), without a trailing slash");
        var inputs=new ArrayList<Input>();var sizes=new LinkedHashMap<String,Long>();
        for (Path source : sources) {
            var described=AssetImports.describe(source);String key=(prefix.isEmpty() ? "" : prefix+"/")+described.suggestedKey();String path=described.family()+"/"+key+described.extension();
            if (sizes.putIfAbsent(path,described.bytes())!=null) throw new IOException("Source names produce the same batch destination: "+path+". Use separate single-file imports with distinct keys.");
            inputs.add(new Input(described.path(),path));
        }
        return new Target(current,inputs,current.assetImportBatchBudget(sizes,maximumBytes));
    }
    private static void cancelled(BooleanSupplier cancelled) { if (cancelled.getAsBoolean()) throw new CancellationException("Asset batch capture cancelled"); }
    static Prepared prepare(List<Input> inputs,long budget,BooleanSupplier cancelled) throws IOException {
        Objects.requireNonNull(cancelled);cancelled(cancelled);inputs=List.copyOf(inputs);
        if (inputs.isEmpty() || inputs.size()>MAX_FILES) throw new IOException("Choose 1 to 32 asset files per batch");
        if (budget<0 || budget>MAX_BYTES) throw new IllegalArgumentException("Invalid asset batch capture budget");
        var paths=new java.util.HashSet<String>();for (var input : inputs) if (!paths.add(input.target().toLowerCase(java.util.Locale.ROOT))) throw new IOException("Duplicate batch capture target");
        var captures=new ArrayList<AssetReplacement.Prepared>();long bytes=0;
        for (var input : inputs) {
            cancelled(cancelled);var captured=AssetReplacement.prepare(input.source(),input.target(),Math.min(AssetImports.MAX_BYTES,budget-bytes),cancelled);captures.add(captured);bytes+=captured.receipt().bytes();
        }
        cancelled(cancelled);return new Prepared(captures);
    }
    public static boolean belongsTo(PackWorkspace current,Target target) { return current!=null && target!=null && target.owner==current; }
    public static boolean available(PackWorkspace current,Target target) {
        if (!belongsTo(current,target)) return false;
        var sizes=new LinkedHashMap<String,Long>();for (var input : target.inputs) sizes.put(input.target(),0L);
        try { current.assetImportBatchBudget(sizes,PackWorkspace.MAX_BYTES);return true; } catch (IOException unavailable) { return false; }
    }
    public static List<String> apply(PackWorkspace current,Target target,Prepared prepared,boolean acknowledgeHistoryClear) throws IOException { return apply(current,target,prepared,acknowledgeHistoryClear,PackWorkspace.MAX_BYTES); }
    static List<String> apply(PackWorkspace current,Target target,Prepared prepared,boolean acknowledgeHistoryClear,long maximumBytes) throws IOException {
        if (!belongsTo(current,target)) throw new IOException("Asset batch belongs to another workspace; capture again");
        if (prepared==null || prepared.receipts.size()!=target.inputs.size()) throw new IOException("Captured batch belongs to another request");
        for (int i=0;i<target.inputs.size();i++) if (!target.inputs.get(i).target().equals(prepared.receipts.get(i).target()) || !target.inputs.get(i).source().equals(prepared.receipts.get(i).source())) throw new IOException("Captured batch source/destination order differs from the reviewed request");
        var sizes=new LinkedHashMap<String,Long>();for (var receipt : prepared.receipts) sizes.put(receipt.target(),(long)receipt.bytes());current.assetImportBatchBudget(sizes,maximumBytes);
        if (!acknowledgeHistoryClear) throw new IOException("Explicitly acknowledge creating the batch and clearing applied-edit history before Apply");
        return current.installCapturedAssetBatch(prepared.captures,maximumBytes);
    }
}
