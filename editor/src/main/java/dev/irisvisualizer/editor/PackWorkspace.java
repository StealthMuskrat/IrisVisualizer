package dev.irisvisualizer.editor;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** UI-owned, byte-preserving pack data. Preview validation has no authority over file operations. */
public final class PackWorkspace {
    private final NavigableMap<String, byte[]> files = new TreeMap<>();
    private final Set<String> changedDirectories = new HashSet<>();
    private final Set<String> created = new HashSet<>();
    private long resourceRevision;
    private final Set<String> changed = new HashSet<>();
    private final Set<String> directories = new TreeSet<>();
    private final Map<String, byte[]> savedHashes = new HashMap<>();
    private final Set<String> foldedFiles = new HashSet<>();
    private Path directory;
    public static final long MAX_BYTES = 512L * 1024 * 1024;
    public static final int MAX_FILES = 100_000;

    public static PackWorkspace empty() { return new PackWorkspace(); }
    static PackWorkspace newPack(Map<String, byte[]> content) throws IOException {
        PackWorkspace result = empty(); long bytes = 0;
        for (var entry : content.entrySet()) {
            bytes += entry.getValue().length;
            if (bytes > MAX_BYTES) throw new IOException("Pack exceeds workspace limit");
            String path = safe(entry.getKey()); result.add(path, entry.getValue().clone()); result.changed.add(path); result.created.add(path);
        }
        result.validatePaths(); return result;
    }
    public long resourceRevision() { return resourceRevision; }
    /** Duplicate bytes under the same supported type. No references or file contents are rewritten. */
    public String duplicate(String source, String key) throws IOException {
        if (!files.containsKey(source)) throw new IOException("Unknown resource");
        String target = safe(ResourceTypes.target(source, key));
        if (files.values().stream().mapToLong(bytes -> bytes.length).sum() + files.get(source).length > MAX_BYTES)
            throw new IOException("Workspace exceeds byte limit");
        // add() checks case-insensitive file collisions before mutating storage.
        add(target, files.get(source).clone());
        try { validatePaths(); }
        catch (IOException failure) {
            files.remove(target); foldedFiles.remove(target.toLowerCase(Locale.ROOT)); savedHashes.remove(target); throw failure;
        }
        changed.add(target); created.add(target); resourceRevision++; return target;
    }
    public String createSubfolder(String parent, String name) throws IOException {
        if (!ResourceTypes.folderFamily(parent)) throw new IOException("Subfolders are supported only inside regions, biomes, generators, objects and images; dimensions must stay flat");
        if (name == null || !name.matches("[a-z0-9_-]+")) throw new IOException("Use lowercase letters, digits, _ or - for a subfolder name");
        boolean parentExists = directories.contains(parent) || files.keySet().stream().anyMatch(path -> path.startsWith(parent + "/"));
        if (!parentExists) throw new IOException("Parent folder is not in this workspace");
        String target = safe(parent + "/" + name);
        if (directories.contains(target) || files.keySet().stream().anyMatch(path -> path.startsWith(target + "/")))
            throw new IOException("Folder already exists: " + target);
        if (directories.size() + files.size() >= MAX_FILES) throw new IOException("Too many pack entries");
        directories.add(target);
        try { validatePaths(); }
        catch (IOException failure) { directories.remove(target); throw failure; }
        changedDirectories.add(target); resourceRevision++; return target;
    }
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
        result.validatePaths(); return result;
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
    public int size(String path) { return Objects.requireNonNull(files.get(path), "Unknown resource").length; }
    public byte[] read(String path) { return Objects.requireNonNull(files.get(path), "Unknown resource").clone(); }
    public boolean dirty() { return !changed.isEmpty() || !changedDirectories.isEmpty(); }
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
        rejectLinks(target);
        boolean same = directory != null && Files.exists(target) && target.toRealPath().equals(directory);
        validatePaths();
        for (String key : directories) checkedTarget(target, key);
        for (String key : files.keySet()) checkedTarget(target, key);
        // Preflight every planned write before creating directories or changing any files.
        for (String key : directories) if (Files.exists(target.resolve(key)) && !Files.isDirectory(target.resolve(key)))
            throw new IOException("Destination directory is occupied by a file: " + key);
        for (String key : files.keySet()) {
            if (same && !changed.contains(key)) continue;
            Path file = target.resolve(key);
            if (Files.exists(file)) {
                if (!same || created.contains(key)) throw new IOException("Destination already contains resource: " + key + ". Choose an empty folder.");
                if (!Files.isRegularFile(file) || !Arrays.equals(savedHashes.get(key), hashFile(file)))
                    throw new IOException("Resource changed outside the editor: " + key + ". Reopen the pack to review disk changes before saving.");
            } else if (same && !created.contains(key)) throw new IOException("Resource was removed outside the editor: " + key);
        }
        Files.createDirectories(target);
        for (String key : directories) Files.createDirectories(target.resolve(key));
        for (var entry : files.entrySet()) {
            if (same && !changed.contains(entry.getKey())) continue;
            Path file = target.resolve(entry.getKey()); Files.createDirectories(file.getParent());
            if (created.contains(entry.getKey())) Files.write(file, entry.getValue(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            else Files.write(file, entry.getValue(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            created.remove(entry.getKey());
            savedHashes.put(entry.getKey(), hash(entry.getValue()));
        }
        directory = target.toRealPath();
        for (var entry : files.entrySet()) savedHashes.put(entry.getKey(), hash(entry.getValue()));
        changed.clear(); created.clear(); changedDirectories.clear();
    }
    public void exportZip(Path archive) throws IOException { exportZip(archive, false); }
    public void exportZip(Path archive, boolean replaceExisting) throws IOException {
        Path destination = archive.toAbsolutePath().normalize();
        rejectLinks(destination);
        if (directory != null && destination.startsWith(directory)) throw new IOException("Export ZIP outside the open pack directory");
        validatePaths();
        OpenOption[] options = replaceExisting
                ? new OpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS}
                : new OpenOption[]{StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS};
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(destination, options))) {
            for (String key : directories) { zip.putNextEntry(new ZipEntry(key + "/")); zip.closeEntry(); }
            for (var entry : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey())); zip.write(entry.getValue()); zip.closeEntry();
            }
        }
    }
    private void add(String key, byte[] bytes) throws IOException {
        if (files.size() >= MAX_FILES) throw new IOException("Too many pack files");
        if (!foldedFiles.add(key.toLowerCase(Locale.ROOT))) throw new IOException("Conflicting resource paths: " + key);
        files.put(key, bytes); savedHashes.put(key, hash(bytes));
    }
    private void validatePaths() throws IOException {
        Map<String, String> spellings = new HashMap<>(); Set<String> folders = new HashSet<>();
        List<String> all = new ArrayList<>(files.keySet()); all.addAll(directories);
        for (String key : all) {
            String[] parts = key.split("/"); String current = "";
            for (int i=0;i<parts.length;i++) {
                current += (i == 0 ? "" : "/") + parts[i];
                String folded = current.toLowerCase(Locale.ROOT), prior = spellings.putIfAbsent(folded, current);
                if (prior != null && !prior.equals(current)) throw new IOException("Conflicting Windows path spelling: " + current);
                if (i < parts.length-1 || directories.contains(key)) folders.add(folded);
            }
        }
        for (String file : files.keySet()) if (folders.contains(file.toLowerCase(Locale.ROOT)))
            throw new IOException("File/directory path collision: " + file);
    }
    private static byte[] hash(byte[] bytes) {
        try { return java.security.MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static byte[] hashFile(Path path) throws IOException {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
                byte[] buffer = new byte[8192]; int count;
                while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
            }
            return digest.digest();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static void rejectLinks(Path path) throws IOException {
        for (Path part = path; part != null; part = part.getParent())
            if (Files.isSymbolicLink(part)) throw new IOException("Destination contains a symbolic link: " + part);
    }
    private static Path checkedTarget(Path root, String key) throws IOException {
        Path current = root;
        for (String part : key.split("/")) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) throw new IOException("Destination contains a symbolic link: " + current);
            if (!current.equals(root.resolve(key)) && Files.exists(current) && !Files.isDirectory(current))
                throw new IOException("Destination path component is not a directory: " + current);
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
