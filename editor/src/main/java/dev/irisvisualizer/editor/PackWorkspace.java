package dev.irisvisualizer.editor;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** UI-owned, byte-preserving pack data. Preview validation has no authority over file operations. */
public final class PackWorkspace {
    private final NavigableMap<String, byte[]> files = new TreeMap<>();
    private final Set<String> changed = new HashSet<>();
    private final Set<String> directories = new TreeSet<>();
    private Path directory;
    public static final long MAX_BYTES = 512L * 1024 * 1024;
    public static final int MAX_FILES = 100_000;

    public static PackWorkspace empty() { return new PackWorkspace(); }
    public static PackWorkspace open(Path root) throws IOException {
        root = root.toRealPath();
        if (!Files.isDirectory(root)) throw new IOException("Select a pack directory");
        PackWorkspace result = empty(); result.directory = root;
        long bytes = 0;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.toList()) {
                if (Files.isSymbolicLink(path)) throw new IOException("Pack contains a symbolic link: " + path);
                String key = root.relativize(path).toString().replace('\\', '/');
                if (key.isEmpty()) continue;
                key = safe(key);
                if (Files.isDirectory(path)) result.directories.add(key);
                else if (Files.isRegularFile(path)) {
                    long size = Files.size(path);
                    if (size > MAX_BYTES - bytes) throw new IOException("Pack exceeds 512 MiB workspace limit");
                    byte[] data = Files.readAllBytes(path); bytes += data.length;
                    result.add(key, data);
                } else throw new IOException("Unsupported pack entry: " + path);
            }
        }
        return result;
    }
    public static PackWorkspace importZip(Path archive) throws IOException {
        PackWorkspace result = empty(); long total = 0;
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
            ZipEntry entry; Set<String> names = new HashSet<>();
            while ((entry = zip.getNextEntry()) != null) {
                String key = safe(entry.getName().replaceAll("/$", ""));
                if (!names.add(key.toLowerCase(Locale.ROOT))) throw new IOException("Duplicate ZIP entry: " + key);
                if (entry.isDirectory()) result.directories.add(key);
                else {
                    ByteArrayOutputStream data = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int read;
                    while ((read = zip.read(buffer)) != -1) {
                        total += read;
                        if (total > MAX_BYTES) throw new IOException("Expanded ZIP exceeds 512 MiB workspace limit");
                        data.write(buffer, 0, read);
                    }
                    result.add(key, data.toByteArray());
                }
                if (names.size() > MAX_FILES) throw new IOException("Too many pack entries");
            }
        }
        result.validatePaths(); return result;
    }
    public Set<String> paths() { return Collections.unmodifiableSet(files.navigableKeySet()); }
    public Set<String> directories() { return Collections.unmodifiableSet(directories); }
    public boolean changed(String path) { return changed.contains(path); }
    public byte[] read(String path) { return Objects.requireNonNull(files.get(path), "Unknown resource").clone(); }
    public boolean dirty() { return !changed.isEmpty(); }
    public Path directory() { return directory; }
    /** The schema/policy editor must authorize changes before using this internal byte-storage API. */
    public void replace(String path, byte[] data) throws IOException {
        path = safe(path);
        if (!files.containsKey(path)) throw new IOException("Unknown resource");
        if (Arrays.equals(files.get(path), data)) return;
        long total = files.values().stream().mapToLong(value -> value.length).sum() - files.get(path).length + data.length;
        if (total > MAX_BYTES) throw new IOException("Workspace exceeds 512 MiB limit");
        files.put(path, data.clone()); changed.add(path);
    }
    public void save(Path target) throws IOException {
        target = target.toAbsolutePath().normalize();
        Files.createDirectories(target);
        if (Files.isSymbolicLink(target)) throw new IOException("Destination is a symbolic link");
        boolean same = directory != null && target.toRealPath().equals(directory);
        validatePaths();
        for (String key : directories) checkedTarget(target, key);
        for (String key : files.keySet()) checkedTarget(target, key);
        for (String key : directories) Files.createDirectories(target.resolve(key));
        for (var entry : files.entrySet()) {
            if (same && !changed.contains(entry.getKey())) continue;
            Path file = target.resolve(entry.getKey()); Files.createDirectories(file.getParent());
            Files.write(file, entry.getValue());
        }
        directory = target.toRealPath(); changed.clear();
    }
    public void exportZip(Path archive) throws IOException {
        Path destination = archive.toAbsolutePath().normalize();
        if (directory != null && destination.startsWith(directory)) throw new IOException("Export ZIP outside the open pack directory");
        validatePaths();
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(destination))) {
            for (String key : directories) { zip.putNextEntry(new ZipEntry(key + "/")); zip.closeEntry(); }
            for (var entry : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey())); zip.write(entry.getValue()); zip.closeEntry();
            }
        }
    }
    private void add(String key, byte[] bytes) throws IOException {
        if (files.size() >= MAX_FILES) throw new IOException("Too many pack files");
        if (files.keySet().stream().anyMatch(name -> name.equalsIgnoreCase(key))) throw new IOException("Conflicting resource paths: " + key);
        files.put(key, bytes);
    }
    private void validatePaths() throws IOException {
        Set<String> folded = new HashSet<>();
        for (String key : files.keySet()) folded.add(key.toLowerCase(Locale.ROOT));
        for (String key : files.keySet()) {
            for (int slash = key.indexOf('/'); slash >= 0; slash = key.indexOf('/', slash + 1))
                if (folded.contains(key.substring(0, slash).toLowerCase(Locale.ROOT))) throw new IOException("File/directory path collision: " + key);
        }
        for (String key : directories) if (folded.contains(key.toLowerCase(Locale.ROOT))) throw new IOException("File/directory path collision: " + key);
    }
    private static Path checkedTarget(Path root, String key) throws IOException {
        Path current = root;
        for (String part : key.split("/")) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) throw new IOException("Destination contains a symbolic link: " + current);
        }
        return current;
    }
    private static String safe(String key) throws IOException {
        if (key.isBlank() || key.startsWith("/") || key.contains("\\") || key.contains(":")) throw new IOException("Unsafe pack path: " + key);
        for (String part : key.split("/", -1)) {
            String stem = part.split("\\.", 2)[0].toUpperCase(Locale.ROOT);
            if (part.isEmpty() || part.equals(".") || part.equals("..") || part.endsWith(".") || part.endsWith(" ")
                    || part.chars().anyMatch(c -> c < 32 || "<>\"|?*".indexOf(c) >= 0)
                    || stem.matches("CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]")) throw new IOException("Unsafe Windows pack path: " + key);
        }
        return key;
    }
}
