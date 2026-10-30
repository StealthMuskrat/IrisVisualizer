package dev.irisvisualizer.editor;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** UI-owned, byte-preserving pack data. Preview validation has no authority over file operations. */
public final class PackWorkspace implements PackResourceView {
    private final NavigableMap<String, byte[]> files = new TreeMap<>();
    private final Set<String> changedDirectories = new HashSet<>();
    private final Set<String> savedDirectories = new HashSet<>();
    private final Set<String> removedDirectories = new TreeSet<>();
    private final Set<String> relocatedDirectoryTargets = new HashSet<>();
    private final Set<String> created = new HashSet<>();
    private long resourceRevision, editRevision;
    private long saveRevision;
    private final Set<String> changed = new HashSet<>();
    private final Set<String> directories = new TreeSet<>();
    private final Map<String, byte[]> savedHashes = new HashMap<>();
    private final Map<String, Long> contentRevisions = new HashMap<>();
    private long contentSequence;
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
    /** In-memory byte identity only. Save, folder changes and edits to other files do not advance it. */
    public long contentRevision(String path) {
        Long revision=contentRevisions.get(path);if (revision==null) throw new IllegalArgumentException("Unknown resource");return revision;
    }
    private record AssetPlan(AssetImports.Source source,String target,long budget) {}
    private AssetPlan planAssetImport(Path source,String key,long maximumBytes) throws IOException {
        if (maximumBytes<0 || maximumBytes>MAX_BYTES) throw new IllegalArgumentException("Invalid workspace asset-import budget");
        var asset=AssetImports.describe(source);String target=safe(asset.family()+"/"+key+asset.extension());
        for (String component : target.split("/")) if (component.length()>255) throw new IOException("Imported asset path components must be at most 255 characters, including the file extension");
        // Bound path depth/components before the resource-key regex processes untrusted pasted input.
        asset.target(key);
        if (files.size()>=MAX_FILES) throw new IOException("Too many pack files");
        if (foldedFiles.contains(target.toLowerCase(Locale.ROOT))) throw new IOException("Conflicting resource path: "+target);
        validatePaths(target);
        long available=maximumBytes-files.values().stream().mapToLong(bytes -> bytes.length).sum();
        if (available<0 || asset.bytes()>available) throw new IOException("Asset exceeds the remaining workspace byte budget");
        return new AssetPlan(asset,target,Math.min(AssetImports.MAX_BYTES,available));
    }
    /** Metadata/path/capacity checks only. The source is checked again when the user commits the import. */
    public String checkAssetImport(Path source,String key) throws IOException { return planAssetImport(source,key,MAX_BYTES).target(); }
    public String importAsset(Path source,String key) throws IOException { return importAsset(source,key,MAX_BYTES); }
    String importAsset(Path source,String key,long maximumBytes) throws IOException {
        var plan=planAssetImport(source,key,maximumBytes);byte[] data=AssetImports.read(plan.source(),plan.budget());
        // All fallible I/O/preflight precedes installation; failed imports leave workspace/history state intact.
        add(plan.target(),data);changed.add(plan.target());created.add(plan.target());resourceRevision++;
        history.clear("Asset imported; earlier applied-edit history was cleared.");return plan.target();
    }
    /** Capacity only; existing asset paths stay fixed and replacement does not add a resource. */
    long assetReplacementBudget(String path) throws IOException { return assetReplacementBudget(path,MAX_BYTES); }
    long assetReplacementBudget(String path,long maximumBytes) throws IOException {
        if (maximumBytes<0 || maximumBytes>MAX_BYTES) throw new IllegalArgumentException("Invalid replacement workspace budget");
        if (!AssetReplacement.supports(path) || !files.containsKey(path)) throw new IOException("Choose an existing images/ PNG/JPEG or objects/ .iob asset");
        long available=maximumBytes-files.values().stream().mapToLong(data -> data.length).sum()+files.get(path).length;
        if (available<0) throw new IOException("Asset replacement exceeds the remaining workspace byte budget");
        return Math.min(AssetImports.MAX_BYTES,available);
    }
    boolean contentEquals(String path,byte[] data) { return Arrays.equals(files.get(path),data); }
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
            files.remove(target); foldedFiles.remove(target.toLowerCase(Locale.ROOT)); savedHashes.remove(target);contentRevisions.remove(target); throw failure;
        }
        changed.add(target); created.add(target); resourceRevision++; history.clear("Resource duplicated; earlier applied-edit history was cleared."); return target;
    }
    public String createResource(String parent, String name, String dependency) throws IOException {
        String base = ResourceCreation.base(parent);
        if (name == null || !name.matches("[a-z0-9_-]+")) throw new IOException("Use lowercase letters, digits, _ or - for the resource name; omit .json");
        if (!((base.equals("image-maps") || base.equals("blocks") || base.equals("expressions") || base.equals("structures") || base.equals("jigsaw-pools") || base.equals("jigsaw-pieces")) && parent.equals(base)) && !directories.contains(parent) && files.keySet().stream().noneMatch(path -> path.startsWith(parent + "/")))
            throw new IOException("Parent folder is not in this workspace");
        String key = (parent.equals(base) ? "" : parent.substring(base.length() + 1) + "/") + name;
        String target = safe(ResourceTypes.target(base + "/template.json", key));
        byte[] bytes = ResourceCreation.bytes(this, parent, name, dependency);
        if (files.values().stream().mapToLong(value -> value.length).sum() + bytes.length > MAX_BYTES) throw new IOException("Workspace exceeds byte limit");
        add(target, bytes);
        try { validatePaths(); }
        catch (IOException failure) {
            files.remove(target); foldedFiles.remove(target.toLowerCase(Locale.ROOT)); savedHashes.remove(target);contentRevisions.remove(target); throw failure;
        }
        changed.add(target); created.add(target); resourceRevision++; history.clear("Resource created; earlier applied-edit history was cleared."); return target;
    }
    public String createSubfolder(String parent, String name) throws IOException {
        if (!ResourceTypes.folderFamily(parent)) throw new IOException("Subfolders are supported only inside regions, biomes, generators, image-maps, blocks, expressions, structures, jigsaw-pools, jigsaw-pieces, objects and images; dimensions must stay flat");
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
        installDirectory(target); resourceRevision++; history.clear("Folder created; earlier applied-edit history was cleared."); return target;
    }
    private void installDirectory(String path) {
        removedDirectories.remove(path);
        if (!savedDirectories.contains(path)) changedDirectories.add(path);
    }
    private void removeDirectory(String path) {
        directories.remove(path);changedDirectories.remove(path);relocatedDirectoryTargets.remove(path);
        if (savedDirectories.contains(path)) removedDirectories.add(path);
    }
    /** Exact leaf folders only: no files, nested folders, base folders or read-only resource families. */
    public boolean editableEmptyFolder(String path) {
        return path!=null && path.contains("/") && ResourceTypes.folderFamily(path) && directories.contains(path)
                && files.keySet().stream().noneMatch(p -> p.startsWith(path+"/"))
                && directories.stream().noneMatch(p -> p.startsWith(path+"/"));
    }
    private void emptyFolder(String path) throws IOException {
        if (!editableEmptyFolder(path)) throw new IOException("Choose an empty leaf subfolder in a supported resource family; base folders and folders containing files or subfolders cannot be changed");
    }
    public String renameEmptyFolder(String source,String name) throws IOException {
        if (name==null || name.length()>255 || !name.matches("[a-z0-9_-]+")) throw new IOException("Use up to 255 lowercase letters, digits, _ or - for the new folder name");
        emptyFolder(source);return relocateEmptyFolder(source,source.substring(0,source.lastIndexOf('/'))+"/"+name);
    }
    public String moveEmptyFolder(String source,String parent) throws IOException {
        emptyFolder(source);
        if (parent==null || !ResourceTypes.folderFamily(parent) || !source.substring(0,source.indexOf('/')).equals(parent.split("/",-1)[0])) throw new IOException("Move folders only within their existing base resource family");
        if (!directories.contains(parent) && files.keySet().stream().noneMatch(p -> p.startsWith(parent+"/"))) throw new IOException("Choose an existing destination folder");
        if (parent.equals(source) || parent.startsWith(source+"/")) throw new IOException("A folder cannot be moved into itself");
        return relocateEmptyFolder(source,parent+source.substring(source.lastIndexOf('/')));
    }
    private String relocateEmptyFolder(String source,String target) throws IOException {
        target=safe(target);if (source.equals(target)) return source;
        if (source.equalsIgnoreCase(target)) throw new IOException("Case-only folder renames are not supported on Windows");
        final String destination=target;
        if (directories.contains(target) || files.containsKey(target) || files.keySet().stream().anyMatch(p -> p.startsWith(destination+"/"))) throw new IOException("Destination folder is already occupied: "+target);
        // Preflight the complete proposed path graph before installing pending changes or clearing history.
        directories.remove(source);directories.add(target);
        try { validatePaths(); }
        catch (IOException failure) { directories.remove(target);directories.add(source);throw failure; }
        removeDirectory(source);installDirectory(target);if (!savedDirectories.contains(target)) relocatedDirectoryTargets.add(target);resourceRevision++;
        history.clear("Empty folder relocated; earlier applied-edit history was cleared.");return target;
    }
    public void deleteEmptyFolder(String path) throws IOException {
        emptyFolder(path);removeDirectory(path);resourceRevision++;history.clear("Empty folder removed; earlier applied-edit history was cleared.");
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
        result.validatePaths(); result.savedDirectories.addAll(result.directories);return result;
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
        result.validatePaths(); result.savedDirectories.addAll(result.directories);return result;
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
    public boolean dirty() { return !changed.isEmpty() || !changedDirectories.isEmpty() || !removedDirectories.isEmpty(); }
    public Path directory() { return directory; }
    // Save may advance per-file baselines before a later write fails; review caches must notice those advances.
    long saveRevision() { return saveRevision; }
    List<WorkspaceChanges.Entry> pendingChanges() {
        var entries=new ArrayList<WorkspaceChanges.Entry>();
        for (String path : changed) entries.add(new WorkspaceChanges.Entry(path,created.contains(path) ? WorkspaceChanges.Kind.NEW_FILE : WorkspaceChanges.Kind.MODIFIED_FILE,files.get(path).length));
        for (String path : changedDirectories) entries.add(new WorkspaceChanges.Entry(path,WorkspaceChanges.Kind.NEW_FOLDER,0));
        for (String path : removedDirectories) entries.add(new WorkspaceChanges.Entry(path,WorkspaceChanges.Kind.REMOVED_FOLDER,0));
        entries.sort(Comparator.comparing(WorkspaceChanges.Entry::path));return List.copyOf(entries);
    }
    WorkspaceChanges.Check checkPendingChange(WorkspaceChanges.Entry entry,long byteLimit) {
        if (byteLimit<0 || byteLimit>WorkspaceChanges.MAX_CHECK_BYTES) throw new IllegalArgumentException("Invalid disk-check byte limit");
        if (directory==null) return diskCheck(entry,WorkspaceChanges.Status.NO_ACTIVE_FOLDER,-1,"No active folder. Save or Save As first chooses a destination; imported ZIPs have no active folder.");
        try {
            rejectLinks(directory);
            if (!Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS)) return diskCheck(entry,WorkspaceChanges.Status.CONFLICT,-1,"The active pack folder is missing or is no longer a directory.");
            Path file=checkedTarget(directory,safe(entry.path()));
            java.nio.file.attribute.BasicFileAttributes attributes;
            try { attributes=Files.readAttributes(file,java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS); }
            catch (NoSuchFileException missing) {
                return diskCheck(entry,entry.kind()==WorkspaceChanges.Kind.MODIFIED_FILE ? WorkspaceChanges.Status.CONFLICT : WorkspaceChanges.Status.AVAILABLE,-1,
                        entry.kind()==WorkspaceChanges.Kind.MODIFIED_FILE ? "The resource was removed outside the editor." : entry.kind()==WorkspaceChanges.Kind.REMOVED_FOLDER ? "The folder is already absent. Save will not recreate it; permissions and future races are unchecked." : "The destination does not currently exist. Permissions and future races are not checked.");
            }
            if (entry.kind()==WorkspaceChanges.Kind.NEW_FOLDER && relocatedDirectoryTargets.contains(entry.path())) return diskCheck(entry,WorkspaceChanges.Status.CONFLICT,-1,"The relocated-folder destination became occupied outside the workspace. Save refuses the collision.");
            if (entry.kind()==WorkspaceChanges.Kind.NEW_FOLDER) return diskCheck(entry,attributes.isDirectory() ? WorkspaceChanges.Status.FOLDER_EXISTS : WorkspaceChanges.Status.CONFLICT,-1,
                    attributes.isDirectory() ? "The destination is already a directory. Its contents are not inspected." : "The folder destination is occupied by a non-directory.");
            if (entry.kind()==WorkspaceChanges.Kind.REMOVED_FOLDER) {
                try { preflightDirectoryRemoval(directory,entry.path()); }
                catch (IOException conflict) { return diskCheck(entry,WorkspaceChanges.Status.CONFLICT,-1,conflict.getMessage()); }
                return diskCheck(entry,WorkspaceChanges.Status.REMOVAL_READY,-1,"The folder contains only pending folder removals, or is empty. Save repeats this check and removes empty folders without recursive file deletion.");
            }
            if (entry.kind()==WorkspaceChanges.Kind.NEW_FILE) return diskCheck(entry,WorkspaceChanges.Status.CONFLICT,attributes.size(),"A new-file destination is already occupied. Save will not overwrite it, even if its bytes are identical.");
            if (!attributes.isRegularFile()) return diskCheck(entry,WorkspaceChanges.Status.CONFLICT,attributes.size(),"The resource destination is no longer a regular file.");
            if (attributes.size()>byteLimit) return diskCheck(entry,WorkspaceChanges.Status.TOO_LARGE,attributes.size(),"Disk file exceeds the bounded review limit. Save still performs its own full conflict check.");
            var digest=java.security.MessageDigest.getInstance("SHA-256");long total=0;
            try (var input=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)) {
                byte[] buffer=new byte[8192];int count;
                while ((count=input.read(buffer,0,(int)Math.min(buffer.length,byteLimit-total+1)))!=-1) {
                    total+=count;
                    if (total>byteLimit) return diskCheck(entry,WorkspaceChanges.Status.TOO_LARGE,total,"Disk file grew beyond the bounded review limit while reading.");
                    digest.update(buffer,0,count);
                }
            }
            boolean matches=Arrays.equals(savedHashes.get(entry.path()),digest.digest());
            return diskCheck(entry,matches ? WorkspaceChanges.Status.BASELINE_MATCH : WorkspaceChanges.Status.CONFLICT,total,
                    matches ? "Disk bytes match the saved baseline at this check. This is not a guarantee that a later Save will succeed." : "Disk bytes differ from the saved baseline. Review disk changes by reopening, or save the applied workspace to a fresh folder with Save As/Save Copy.");
        } catch (IOException | SecurityException failure) { return diskCheck(entry,WorkspaceChanges.Status.IO_ERROR,-1,"Disk check could not complete: "+failure.getMessage()); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private WorkspaceChanges.Check diskCheck(WorkspaceChanges.Entry entry,WorkspaceChanges.Status status,long bytes,String detail) { return new WorkspaceChanges.Check(entry,directory,status,bytes,detail); }
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
        for (var entry : plan.restored().entrySet()) {
            if (!Arrays.equals(files.get(entry.getKey()),entry.getValue())) contentRevisions.put(entry.getKey(),++contentSequence);
            files.put(entry.getKey(),entry.getValue());updateChanged(entry.getKey(),entry.getValue());
        }
        editRevision++;for (int i=0;i<plan.steps();i++) history.moved(forward);return plan.target();
    }
    private void checkReplacementSize(String path,byte[] data) throws IOException {
        long total=files.values().stream().mapToLong(value -> value.length).sum()-files.get(path).length+data.length;
        if (total>MAX_BYTES) throw new IOException("Workspace exceeds 512 MiB limit");
    }
    private void installReplacement(String path,byte[] data) {
        files.put(path,data);contentRevisions.put(path,++contentSequence);
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
        for (String key : relocatedDirectoryTargets) if (Files.exists(target.resolve(key),LinkOption.NOFOLLOW_LINKS)) throw new IOException("Relocated folder destination became occupied outside the workspace: "+key);
        for (String key : files.keySet()) {
            if (same && !changed.contains(key)) continue;
            Path file = target.resolve(key);
            if (Files.exists(file)) {
                if (!same || created.contains(key)) throw new IOException("Destination already contains resource: " + key + ". Choose an empty folder.");
                if (!Files.isRegularFile(file) || !Arrays.equals(savedHashes.get(key), hashFile(file)))
                    throw new IOException("Resource changed outside the editor: " + key + ". Reopen the pack to review disk changes before saving.");
            } else if (same && !created.contains(key)) throw new IOException("Resource was removed outside the editor: " + key);
        }
        if (same) for (String key : removedDirectories) preflightDirectoryRemoval(target,key);
        Files.createDirectories(target);
        for (String key : directories) {
            Files.createDirectories(target.resolve(key));
            if (same && changedDirectories.remove(key)) { savedDirectories.add(key);relocatedDirectoryTargets.remove(key);saveRevision++; }
        }
        for (var entry : files.entrySet()) {
            if (same && !changed.contains(entry.getKey())) continue;
            Path file = target.resolve(entry.getKey()); Files.createDirectories(file.getParent());
            if (created.contains(entry.getKey())) Files.write(file, entry.getValue(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            else Files.write(file, entry.getValue(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            created.remove(entry.getKey());
            savedHashes.put(entry.getKey(), hash(entry.getValue()));
            saveRevision++;
        }
        if (same) {
            var removals=removedDirectories.stream().sorted(Comparator.comparingInt((String p) -> p.split("/").length).reversed().thenComparing(Comparator.naturalOrder())).toList();
            for (String key : removals) {
                Path folder=checkedTarget(target,key);
                try {
                    var attributes=Files.readAttributes(folder,java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
                    if (!attributes.isDirectory()) throw new IOException("Folder removal destination is no longer a directory: "+key);
                    Files.delete(folder); // Nonrecursive: a new child causes failure rather than deleting its contents.
                } catch (NoSuchFileException alreadyAbsent) { /* Desired empty folder is already absent. */ }
                removedDirectories.remove(key);savedDirectories.remove(key);saveRevision++;
            }
        }
        directory = target.toRealPath();
        for (var entry : files.entrySet()) savedHashes.put(entry.getKey(), hash(entry.getValue()));
        changed.clear(); created.clear(); changedDirectories.clear();removedDirectories.clear();relocatedDirectoryTargets.clear();savedDirectories.clear();savedDirectories.addAll(directories);
        saveRevision++;
    }
    /** Full in-memory snapshot to a fresh folder, then adopt its save baseline without clearing edit history. */
    public void saveAs(Path target) throws IOException {
        Path destination=freshDestination(target);var snapshot=freshSnapshot();snapshot.save(destination);
        directory=snapshot.directory;savedHashes.clear();savedHashes.putAll(snapshot.savedHashes);
        changed.clear();created.clear();changedDirectories.clear();removedDirectories.clear();relocatedDirectoryTargets.clear();savedDirectories.clear();savedDirectories.addAll(directories);
        saveRevision++;
    }
    /** Full in-memory snapshot without changing this workspace's folder, baseline, revisions or history. */
    public void saveCopy(Path target) throws IOException { Path destination=freshDestination(target);var snapshot=freshSnapshot();snapshot.save(destination); }
    private PackWorkspace freshSnapshot() throws IOException {
        validatePaths();var snapshot=empty();
        // Internal byte arrays are never modified in place. Sharing them avoids a second complete pack allocation.
        for (var entry : files.entrySet()) { snapshot.add(entry.getKey(),entry.getValue());snapshot.created.add(entry.getKey());snapshot.changed.add(entry.getKey()); }
        snapshot.directories.addAll(directories);snapshot.changedDirectories.addAll(directories);return snapshot;
    }
    private Path freshDestination(Path target) throws IOException {
        if (target==null) throw new IOException("Choose a new or empty destination folder");
        target=target.toAbsolutePath().normalize();rejectLinks(target);
        Path existing=target;var tail=new ArrayDeque<Path>();
        while (!Files.exists(existing,LinkOption.NOFOLLOW_LINKS)) {
            if (existing.getFileName()!=null) tail.addFirst(existing.getFileName());existing=existing.getParent();
            if (existing==null) throw new IOException("No existing destination ancestor");
        }
        for (Path part=existing;part!=null;part=part.getParent()) if (Files.exists(part,LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(part,LinkOption.NOFOLLOW_LINKS)) throw new IOException("Destination path component is not a folder: "+part);
        Path resolved=existing.toRealPath();for (Path part : tail) resolved=resolved.resolve(part);
        if (directory!=null && (resolved.startsWith(directory) || directory.startsWith(resolved))) throw new IOException("Choose a folder outside the active pack; ancestor, nested and same-folder destinations are not supported");
        if (Files.exists(target,LinkOption.NOFOLLOW_LINKS)) {
            try (var entries=Files.list(target)) { if (entries.findAny().isPresent()) throw new IOException("Destination must be new or empty: "+target); }
        }
        return target;
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
        files.put(key, bytes); savedHashes.put(key, hash(bytes));contentRevisions.put(key,++contentSequence);
    }
    private void validatePaths() throws IOException { validatePaths(null); }
    private void validatePaths(String additionalFile) throws IOException {
        Map<String, String> spellings = new HashMap<>(); Set<String> folders = new HashSet<>();
        List<String> plannedFiles=new ArrayList<>(files.keySet());if (additionalFile!=null) plannedFiles.add(additionalFile);
        for (String file : plannedFiles) for (String current=file;!current.isEmpty();) {
            if (removedDirectories.contains(current)) throw new IOException("Restore the pending folder removal before adding resources inside it: "+current);
            int slash=current.lastIndexOf('/');current=slash<0 ? "" : current.substring(0,slash);
        }
        List<String> all = new ArrayList<>(plannedFiles); all.addAll(directories);
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
        for (String file : plannedFiles) if (folders.contains(file.toLowerCase(Locale.ROOT)))
            throw new IOException("File/directory path collision: " + file);
        // Pending old spellings constrain Windows collisions without consuming the final pack's entry budget.
        for (String removed : removedDirectories) {
            String current="";for (String part : removed.split("/")) {
                current+=(current.isEmpty() ? "" : "/")+part;String prior=spellings.get(current.toLowerCase(Locale.ROOT));
                if (prior!=null && !prior.equals(current)) throw new IOException("Conflicting pending Windows folder spelling: "+current);
            }
        }
    }
    private void preflightDirectoryRemoval(Path root,String key) throws IOException {
        Path folder=checkedTarget(root,key);java.nio.file.attribute.BasicFileAttributes attributes;
        try { attributes=Files.readAttributes(folder,java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS); }
        catch (NoSuchFileException absent) { return; }
        if (!attributes.isDirectory()) throw new IOException("Folder removal destination is no longer a directory: "+key);
        try (var children=Files.newDirectoryStream(folder)) {
            for (Path child : children) {
                String relative=root.relativize(child).toString().replace('\\','/');
                if (!removedDirectories.contains(relative) || !Files.isDirectory(child,LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(child)) throw new IOException("Folder changed outside the editor; unexpected contents block removal: "+key);
            }
        }
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
