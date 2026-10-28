package dev.irisvisualizer.editor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Locale;

/** Exact opaque file copies for permitted image/object families. No decoding, rewriting or native loading. */
public final class AssetImports {
    public static final long MAX_BYTES=64L*1024*1024;
    public record Source(Path path,String family,String extension,String suggestedKey,long bytes) {
        public String target(String key) throws IOException { return ResourceTypes.target(family+"/asset"+extension,key); }
    }
    static String suggestedKey(String fileName) {
        int dot=fileName.lastIndexOf('.');String base=(dot<0 ? fileName : fileName.substring(0,dot)).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+","_").replaceAll("^_+|_+$","");
        if (base.length()>64) base=base.substring(0,64);
        if (base.isEmpty()) base="imported_asset";
        if (base.matches("con|prn|aux|nul|com[1-9]|lpt[1-9]")) base+="_asset";
        return base;
    }
    private static void rejectLinks(Path path) throws IOException {
        for (Path part=path;part!=null;part=part.getParent()) if (Files.isSymbolicLink(part)) throw new IOException("Asset source contains a symbolic link: "+part);
    }
    public static Source describe(Path source) throws IOException {
        if (source==null) throw new IOException("Choose a PNG/JPEG image or Iris .iob object file");
        Path path=source.toAbsolutePath().normalize();rejectLinks(path);
        if (path.getFileName()==null) throw new IOException("Choose an asset file");
        String name=path.getFileName().toString(),lower=name.toLowerCase(Locale.ROOT);
        String family=lower.endsWith(".iob") ? "objects" : AssetImage.supportedPath(name) ? "images" : null;
        if (family==null) throw new IOException("Asset import supports .png, .jpg, .jpeg and .iob files only");
        var attributes=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile()) throw new IOException("Choose a regular asset file, not a folder or special file");
        if (attributes.size()>MAX_BYTES) throw new IOException("Asset import exceeds the 64 MiB file limit");
        // Existing native-key pickers require lowercase .png/.iob suffixes. This renames only the new destination.
        return new Source(path,family,ResourceTypes.extension(name).toLowerCase(Locale.ROOT),suggestedKey(name),attributes.size());
    }
    static byte[] read(Source source,long budget) throws IOException {
        if (budget<0 || budget>MAX_BYTES) throw new IllegalArgumentException("Invalid asset copy budget");
        if (source.bytes()>budget) throw new IOException("Asset exceeds the remaining workspace/import byte budget");
        rejectLinks(source.path());byte[] data;
        try (var input=Files.newInputStream(source.path(),LinkOption.NOFOLLOW_LINKS)) { data=PackWorkspace.readBounded(input,budget); }
        if (data.length!=source.bytes()) throw new IOException("Asset size changed while importing; choose the file again");
        return data;
    }
}
