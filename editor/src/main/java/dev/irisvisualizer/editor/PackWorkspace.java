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
    private long resourceRevision, editRevision;
    private final Set<String> changed = new HashSet<>();
    private final Set<String> directories = new TreeSet<>();
    private final Map<String, byte[]> savedHashes = new HashMap<>();
    private final Set<String> foldedFiles = new HashSet<>();
    private final AppliedEditHistory history = new AppliedEditHistory();
    private Path directory;
    public static final long MAX_BYTES = 512L * 1024 * 1024;
    public static final int MAX_FILES = 100_000;
    public static final int MAX_PATH_SEGMENTS = 64;
    record LoadLimits(long bytes, int entries, int depth) {
        LoadLimits {
            if (bytes < 0 || bytes > MAX_BYTES || entries < 1 || entries > MAX_FILES || depth < 1 || depth > MAX_PATH_SEGMENTS)
                throw new IllegalArgumentException("Invalid workspace load limits");
        }
    }
    private static final LoadLimits LOAD_LIMITS = new LoadLimits(MAX_BYTES, MAX_FILES, MAX_PATH_SEGMENTS);
    private static final class LoadBudget {
        final LoadLimits limits; long bytes; final Set<String> nodes = new HashSet<>();
        LoadBudget(LoadLimits limits) { this.limits = limits; }
        String entry(String key) throws IOException {
            key = safe(key); String[] parts = key.split("/");
            if (parts.length > limits.depth()) throw new IOException("Pack path exceeds nesting limit: " + key);
            String current = "";
            for (String part : parts) {
                current += (current.isEmpty() ? "" : "/") + part;
                nodes.add(current.toLowerCase(Locale.ROOT));
                if (nodes.size() > limits.entries()) throw new IOException("Too many pack entries, including folders");
            }
            return key;
        }
        long remaining() { return limits.bytes() - bytes; }
    }

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
    public long revision() { return resourceRevision + editRevision; }
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
        changed.add(target); created.add(target); resourceRevision++; history.clear("Resource duplicated; earlier applied-edit history was cleared."); return target;
    }
    public String createResource(String parent, String name, String dependency) throws IOException {
        String base = ResourceCreation.base(parent);
        if (name == null || !name.matches("[a-z0-9_-]+")) throw new IOException("Use lowercase letters, digits, _ or - for the resource name; omit .json");
        if (!((base.equals("image-maps") || base.equals("blocks") || base.equals("expressions")) && parent.equals(base)) && !directories.contains(parent) && files.keySet().stream().noneMatch(path -> path.startsWith(parent + "/")))
            throw new IOException("Parent folder is not in this workspace");
        String key = (parent.equals(base) ? "" : parent.substring(base.length() + 1) + "/") + name;
        String target = safe(ResourceTypes.target(base + "/template.json", key));
        byte[] bytes = ResourceCreation.bytes(this, parent, name, dependency);
        if (files.values().stream().mapToLong(value -> value.length).sum() + bytes.length > MAX_BYTES) throw new IOException("Workspace exceeds byte limit");
        add(target, bytes);
        try { validatePaths(); }
        catch (IOException failure) {
            files.remove(target); foldedFiles.remove(target.toLowerCase(Locale.ROOT)); savedHashes.remove(target); throw failure;
        }
        changed.add(target); created.add(target); resourceRevision++; history.clear("Resource created; earlier applied-edit history was cleared."); return target;
    }
    public String createSubfolder(String parent, String name) throws IOException {
        if (!ResourceTypes.folderFamily(parent)) throw new IOException("Subfolders are supported only inside regions, biomes, generators, image-maps, blocks, expressions, objects and images; dimensions must stay flat");
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
        changedDirectories.add(target); resourceRevision++; history.clear("Folder created; earlier applied-edit history was cleared."); return target;
    }
    public static PackWorkspace open(Path root) throws IOException { return open(root, LOAD_LIMITS); }
    static PackWorkspace open(Path root, LoadLimits limits) throws IOException {
        Path base = root.toRealPath();
        if (!Files.isDirectory(base)) throw new IOException("Select a pack directory");
        PackWorkspace result = empty(); result.directory = base; LoadBudget budget = new LoadBudget(limits);
        Files.walkFileTree(base, new SimpleFileVisitor<>() {
            private String key(Path path) throws IOException { return budget.entry(base.relativize(path).toString().replace('\\', '/')); }
            @Override public FileVisitResult preVisitDirectory(Path dir, java.nio.file.attribute.BasicFileAttributes attributes) throws IOException {
                if (!dir.equals(base)) result.directories.add(key(dir));
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path path, java.nio.file.attribute.BasicFileAttributes attributes) throws IOException {
                String key = key(path);
                if (attributes.isSymbolicLink() || !attributes.isRegularFile()) throw new IOException("Unsupported or linked pack entry: " + key);
                if (attributes.size() > budget.remaining()) throw new IOException("Pack exceeds workspace byte limit");
                byte[] data;
                try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) { data = readBounded(input, budget.remaining()); }
                budget.bytes += data.length; result.add(key, data); return FileVisitResult.CONTINUE;
            }
        });
        result.validatePaths(); return result;
    }
    public static PackWorkspace importZip(Path archive) throws IOException { return importZip(archive, LOAD_LIMITS); }
    static PackWorkspace importZip(Path archive, LoadLimits limits) throws IOException {
        PackWorkspace result = empty(); LoadBudget budget = new LoadBudget(limits);
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
            ZipEntry entry; Set<String> names = new HashSet<>();
            while ((entry = zip.getNextEntry()) != null) {
                String key = budget.entry(entry.getName().replaceAll("/$", ""));
                if (!names.add(key.toLowerCase(Locale.ROOT))) throw new IOException("Duplicate ZIP entry: " + key);
                if (entry.isDirectory()) {
                    // Do not silently drain an unbounded payload disguised as a directory.
                    readBounded(zip, 0); result.directories.add(key);
                } else {
                    if (entry.getSize() > budget.remaining()) throw new IOException("Expanded ZIP exceeds workspace byte limit");
                    byte[] data = readBounded(zip, budget.remaining());
                    budget.bytes += data.length; result.add(key, data);
                }
            }
        }
        result.validatePaths(); return result;
    }
    static byte[] readBounded(InputStream input, long limit) throws IOException {
        if (limit < 0 || limit > MAX_BYTES) throw new IllegalArgumentException("Invalid read budget");
        ByteArrayOutputStream data = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; long total = 0;
        while (true) {
            int count = input.read(buffer, 0, (int)Math.min(buffer.length, limit - total + 1));
            if (count < 0) return data.toByteArray();
            if (count == 0) { int value = input.read(); if (value < 0) return data.toByteArray(); buffer[0] = (byte)value; count = 1; }
            total += count;
            if (total > limit) throw new IOException("Pack exceeds workspace byte limit");
            data.write(buffer, 0, count);
        }
    }
    public Set<String> paths() { return Collections.unmodifiableSet(files.navigableKeySet()); }
    public Set<String> directories() { return Collections.unmodifiableSet(directories); }
    public boolean changed(String path) { return changed.contains(path); }
    public int size(String path) { return Objects.requireNonNull(files.get(path), "Unknown resource").length; }
    public byte[] read(String path) { return Objects.requireNonNull(files.get(path), "Unknown resource").clone(); }
    public boolean dirty() { return !changed.isEmpty() || !changedDirectories.isEmpty(); }
    public Path directory() { return directory; }
    public int undoCount() { return history.undoCount(); }
    public int redoCount() { return history.redoCount(); }
    public long historyBytes() { return history.bytes(); }
    public String historyNote() { return history.note(); }
    public String undoPath() { var edit=history.next(false);return edit==null ? null : edit.path; }
    public String redoPath() { var edit=history.next(true);return edit==null ? null : edit.path; }
    public List<AppliedEditReview.Step> historySteps(boolean forward) { return history.steps(forward); }
    public boolean hasHistoryEdit(long id) { return history.find(id)!=null; }
    public AppliedEditReview.Comparison reviewHistoryEdit(long id) { var edit=history.find(id);return edit==null ? null : edit.review(); }
    public void clearHistory() { history.clear("Applied-edit history cleared."); }
    /** Restores exact bytes in memory. Save remains an explicit, conflict-checked operation. */
    public String undo() throws IOException { return restoreHistory(false); }
    public String redo() throws IOException { return restoreHistory(true); }
    public String undoThrough(long id) throws IOException { return restoreHistoryThrough(id,false); }
    public String redoThrough(long id) throws IOException { return restoreHistoryThrough(id,true); }
    private String restoreHistory(boolean forward) throws IOException {
        var edit=history.next(forward);
        if (edit==null) throw new IOException(forward ? "No applied edit to redo" : "No applied edit to undo");
        return restoreHistoryThrough(edit.id,forward);
    }
    private String restoreHistoryThrough(long id,boolean forward) throws IOException {
        var plan=history.plan(id,forward,files,MAX_BYTES);
        for (var entry : plan.restored().entrySet()) { files.put(entry.getKey(),entry.getValue());updateChanged(entry.getKey(),entry.getValue()); }
        editRevision++;for (int i=0;i<plan.steps();i++) history.moved(forward);return plan.target();
    }
    private void checkReplacementSize(String path,byte[] data) throws IOException {
        long total=files.values().stream().mapToLong(value -> value.length).sum()-files.get(path).length+data.length;
        if (total>MAX_BYTES) throw new IOException("Workspace exceeds 512 MiB limit");
    }
    private void installReplacement(String path,byte[] data) {
        files.put(path,data);
        updateChanged(path,data);editRevision++;
    }
    private void updateChanged(String path,byte[] data) {
        if (created.contains(path) || !Arrays.equals(savedHashes.get(path),hash(data))) changed.add(path);else changed.remove(path);
    }
    /** The schema/policy editor must authorize changes before using this internal byte-storage API. */
    public void replace(String path, byte[] data) throws IOException {
        path = safe(path);
        if (!files.containsKey(path)) throw new IOException("Unknown resource");
        if (Arrays.equals(files.get(path), data)) return;
        checkReplacementSize(path,data);byte[] before=files.get(path),after=data.clone();
        installReplacement(path,after);history.record(path,before,after);
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
                if (spellings.size() > MAX_FILES) throw new IOException("Too many pack entries, including folders");
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
        String[] parts = key.split("/", -1);
        if (parts.length > MAX_PATH_SEGMENTS) throw new IOException("Pack path exceeds 64 segments");
        for (String part : parts) {
            String stem = part.split("\\.", 2)[0].toUpperCase(Locale.ROOT);
            if (part.isEmpty() || part.equals(".") || part.equals("..") || part.endsWith(".") || part.endsWith(" ")
                    || part.chars().anyMatch(c -> c < 32 || "<>\"|?*".indexOf(c) >= 0)
                    || stem.matches("CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]")) throw new IOException("Unsafe Windows pack path: " + key);
        }
        return key;
    }
}
