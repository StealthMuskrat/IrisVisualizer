package dev.irisvisualizer.editor;

import java.io.IOException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Opaque staged copies at existing asset paths. No decoding, reference rewriting or native loading. */
public final class AssetReplacement {
    public static final class Target {
        private final PackWorkspace owner;
        private final String path;
        private final long revision,budget;
        private final int beforeBytes;
        private Target(PackWorkspace owner,String path,long revision,int beforeBytes,long budget) { this.owner=owner;this.path=path;this.revision=revision;this.beforeBytes=beforeBytes;this.budget=budget; }
        public String path() { return path; }
        public long revision() { return revision; }
        public int beforeBytes() { return beforeBytes; }
        public long budget() { return budget; }
    }
    public record Receipt(Path source,String target,int bytes,String sha256) {}
    public static final class Prepared {
        private final byte[] data;
        private final Receipt receipt;
        // Only prepare() supplies an owned bounded read buffer; no caller array is retained.
        private Prepared(byte[] data,Receipt receipt) { this.data=data;this.receipt=receipt; }
        public Receipt receipt() { return receipt; }
        byte[] copyBytes() { return data.clone(); }
    }
    public static boolean supports(String path) { return java.util.Set.of("images","objects").contains(Objects.toString(ResourceTypes.fileFamily(path),"")); }
    public static Target target(PackWorkspace current,String path) throws IOException { return target(current,path,PackWorkspace.MAX_BYTES); }
    static Target target(PackWorkspace current,String path,long maximumBytes) throws IOException {
        Objects.requireNonNull(current);long budget=current.assetReplacementBudget(path,maximumBytes);
        return new Target(current,path,current.contentRevision(path),current.size(path),budget);
    }
    public static boolean current(PackWorkspace current,Target target) {
        return current!=null && target!=null && target.owner==current && current.paths().contains(target.path) && current.contentRevision(target.path)==target.revision && current.size(target.path)==target.beforeBytes;
    }
    private static boolean jpeg(String extension) { return extension.equals(".jpg") || extension.equals(".jpeg"); }
    static Prepared prepare(Path source,String target,long budget,BooleanSupplier cancelled) throws IOException {
        Objects.requireNonNull(cancelled);if (cancelled.getAsBoolean()) throw new CancellationException("Asset capture cancelled");
        if (!supports(target)) throw new IOException("Replacement target must be an images/ PNG/JPEG or objects/ .iob asset");
        if (budget<0 || budget>AssetImports.MAX_BYTES) throw new IllegalArgumentException("Invalid asset capture budget");
        var described=AssetImports.describe(source);String extension=ResourceTypes.extension(target).toLowerCase(Locale.ROOT);
        if (!described.family().equals(ResourceTypes.fileFamily(target)) || !extension.equals(described.extension()) && !(jpeg(extension) && jpeg(described.extension()))) throw new IOException("Choose the same asset extension as the target (JPG/JPEG suffixes are interchangeable)");
        byte[] data=AssetImports.read(described,budget,cancelled);String digest;
        try { digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 is unavailable",impossible); }
        if (cancelled.getAsBoolean()) throw new CancellationException("Asset capture cancelled");
        return new Prepared(data,new Receipt(described.path(),target,data.length,digest));
    }
    private static void validate(PackWorkspace current,Target target,Prepared prepared) throws IOException {
        if (!current(current,target)) throw new IOException("Replacement target bytes or workspace changed; prepare the asset again");
        if (prepared==null || !target.path.equals(prepared.receipt.target())) throw new IOException("Captured replacement belongs to another target");
    }
    public static boolean unchanged(PackWorkspace current,Target target,Prepared prepared) throws IOException { validate(current,target,prepared);return current.contentEquals(target.path,prepared.data); }
    public static boolean fitsHistory(Target target,Receipt receipt) { return target!=null && receipt!=null && (long)target.beforeBytes+receipt.bytes()<=AppliedEditHistory.MAX_BYTES; }
    public static boolean apply(PackWorkspace current,Target target,Prepared prepared,boolean allowUnrecorded) throws IOException { return apply(current,target,prepared,allowUnrecorded,PackWorkspace.MAX_BYTES); }
    static boolean apply(PackWorkspace current,Target target,Prepared prepared,boolean allowUnrecorded,long maximumBytes) throws IOException {
        validate(current,target,prepared);
        if (current.contentEquals(target.path,prepared.data)) return false;
        if (prepared.data.length>current.assetReplacementBudget(target.path,maximumBytes)) throw new IOException("Replacement no longer fits the workspace byte budget");
        if (!fitsHistory(target,prepared.receipt) && !allowUnrecorded) throw new IOException("This replacement exceeds retained Undo history; explicitly acknowledge clearing history before applying");
        current.replace(target.path,prepared.data);return true;
    }
}
