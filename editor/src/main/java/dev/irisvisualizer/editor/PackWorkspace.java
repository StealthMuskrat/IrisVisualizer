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
    public static final int MAX_LOAD_PATH_CHARACTERS=16_000_000;
    record LoadLimits(long bytes,int entries,int depth,int pathCharacters) {
        LoadLimits(long bytes,int entries,int depth) { this(bytes,entries,depth,MAX_LOAD_PATH_CHARACTERS); }
        LoadLimits {
            if (bytes<0 || bytes>MAX_BYTES || entries<1 || entries>MAX_FILES || depth<1 || depth>MAX_PATH_SEGMENTS || pathCharacters<0 || pathCharacters>MAX_LOAD_PATH_CHARACTERS) throw new IllegalArgumentException("Invalid workspace load limits");
        }
    }
    private static final LoadLimits LOAD_LIMITS=new LoadLimits(MAX_BYTES,MAX_FILES,MAX_PATH_SEGMENTS);
    private static final class LoadBudget {
        final LoadLimits limits;final PackLoads.Monitor monitor;long bytes;int characters;final Set<String> nodes=new HashSet<>();
        LoadBudget(LoadLimits limits,PackLoads.Monitor monitor) { this.limits=Objects.requireNonNull(limits);this.monitor=Objects.requireNonNull(monitor); }
        String entry(String key) throws IOException {
            monitor.check();key=safe(key);String[] parts=key.split("/");
            if(parts.length>limits.depth())throw new IOException("Pack path exceeds nesting limit");String current="";
            for(String part:parts) {
                monitor.check();current+=(current.isEmpty() ? "" : "/")+part;
                if(nodes.add(current.toLowerCase(Locale.ROOT))) {
                    if(nodes.size()>limits.entries())throw new IOException("Too many pack entries, including folders");
                    if(current.length()>limits.pathCharacters()-characters)throw new IOException("Pack paths exceed the 16 million character metadata budget");characters+=current.length();
                }
            }
            return key;
        }
        long remaining() { return limits.bytes()-bytes; }
        void progress(PackWorkspace result,long partial) { monitor.update(result.files.size(),result.directories.size(),bytes+partial,nodes.size(),characters); }
        byte[] read(InputStream input,PackWorkspace result,long limit) throws IOException { return readBounded(input,limit,monitor,n->progress(result,n)); }
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
    /** Immutable-by-ownership array capture for the read-only worker; no full payload copy or digest on the UI thread. */
    ByteInspection.Source captureByteInspection(String path) throws IOException {
        if(path==null || !files.containsKey(path))throw new IOException("Choose an existing resource to inspect");byte[] data=files.get(path);return new ByteInspection.Source(path,data);
    }
    /** Selected JSON bytes are immutable by ownership; only path metadata is copied on the UI thread. */
    ReferenceInspection.Source captureReferenceInspection(String path) throws IOException {
        if(path==null || !files.containsKey(path) || !ReferenceDiagnostics.supports(path))throw new IOException("Choose a supported existing JSON resource to inspect links");
        return new ReferenceInspection.Source(path,files.get(path),paths());
    }
    /** Private immutable applied bytes and a tiny baseline fingerprint; worker never receives the live workspace. */
    FileComparison.Source captureFileComparison(String path) throws IOException {
        if(path==null || !files.containsKey(path))throw new IOException("Choose an existing resource to compare");
        return new FileComparison.Source(directory,path,files.get(path),savedHashes.get(path),created.contains(path));
    }
    private record AssetPlan(AssetImports.Source source,String target,long budget) {}
    private AssetPlan planAssetImport(Path source,String key,long maximumBytes) throws IOException {
        if (key==null) throw new IOException("Choose a resource key");
        var asset=AssetImports.describe(source);String target=safe(asset.family()+"/"+key+asset.extension());
        return new AssetPlan(asset,target,assetImportBudget(target,asset.bytes(),maximumBytes));
    }
    /** Source-independent destination preflight, also used immediately before a captured import is installed. */
    long assetImportBudget(String target,long bytes,long maximumBytes) throws IOException {
        if (maximumBytes<0 || maximumBytes>MAX_BYTES) throw new IllegalArgumentException("Invalid workspace asset-import budget");
        if (bytes<0 || bytes>AssetImports.MAX_BYTES) throw new IOException("Asset import exceeds the 64 MiB file limit");
        checkAssetImportPath(target);
        if (files.size()>=MAX_FILES) throw new IOException("Too many pack files");
        validatePaths(target);
        long available=maximumBytes-files.values().stream().mapToLong(data -> data.length).sum();
        if (available<0 || bytes>available) throw new IOException("Asset exceeds the remaining workspace byte budget");
        return Math.min(AssetImports.MAX_BYTES,available);
    }
    private void checkAssetImportPath(String target) throws IOException {
        target=safe(target);
        for (String component : target.split("/")) if (component.length()>255) throw new IOException("Imported asset path components must be at most 255 characters, including the file extension");
        if (!AssetReplacement.supports(target)) throw new IOException("Import only images/ PNG/JPEG or objects/ .iob assets");
        String family=ResourceTypes.fileFamily(target),extension=ResourceTypes.extension(target);
        String key=target.substring(family.length()+1,target.length()-extension.length());
        if (!extension.equals(extension.toLowerCase(Locale.ROOT)) || !ResourceTypes.target(target,key).equals(target)) throw new IOException("Use a lowercase asset extension and resource key");
        if (foldedFiles.contains(target.toLowerCase(Locale.ROOT))) throw new IOException("Conflicting resource path: "+target);
    }
    /** Joint graph/capacity preflight, without source reads or mutation. */
    long assetImportBatchBudget(Map<String,Long> sizes,long maximumBytes) throws IOException {
        if (maximumBytes<0 || maximumBytes>MAX_BYTES) throw new IllegalArgumentException("Invalid batch workspace byte budget");
        if (sizes.isEmpty() || sizes.size()>StagedAssetBatchImport.MAX_FILES) throw new IOException("Choose 1 to 32 asset files per batch");
        long total=0;var folded=new HashSet<String>();
        for (var entry : sizes.entrySet()) {
            checkAssetImportPath(entry.getKey());
            if (!folded.add(entry.getKey().toLowerCase(Locale.ROOT))) throw new IOException("Conflicting batch destination: "+entry.getKey());
            if (entry.getValue()==null || entry.getValue()<0 || entry.getValue()>AssetImports.MAX_BYTES) throw new IOException("Each batch source must fit the 64 MiB file limit");
            total+=entry.getValue();
        }
        if (total>StagedAssetBatchImport.MAX_BYTES) throw new IOException("Asset batch exceeds the 128 MiB captured-byte limit");
        if (files.size()+sizes.size()>MAX_FILES) throw new IOException("Too many pack files");
        validatePaths(List.copyOf(sizes.keySet()));
        long available=maximumBytes-files.values().stream().mapToLong(data -> data.length).sum();
        if (available<0 || total>available) throw new IOException("Asset batch exceeds the remaining workspace byte budget");
        return Math.min(StagedAssetBatchImport.MAX_BYTES,available);
    }
    private record CapturedInstall(String path,byte[] data,byte[] hash) {}
    /** All joint preflight, defensive copies and hashing precede installation. One structural operation clears history once. */
    List<String> installCapturedAssetBatch(List<AssetReplacement.Prepared> captures,long maximumBytes) throws IOException {
        var sizes=new LinkedHashMap<String,Long>();
        for (var captured : captures) if (sizes.putIfAbsent(captured.receipt().target(),(long)captured.receipt().bytes())!=null) throw new IOException("Duplicate captured destination");
        assetImportBatchBudget(sizes,maximumBytes);
        var installs=new ArrayList<CapturedInstall>();
        for (var captured : captures) { byte[] owned=captured.copyBytes();installs.add(new CapturedInstall(captured.receipt().target(),owned,hash(owned))); }
        // Workspace is UI-owned. All path, entry and capacity guards have passed for the whole proposed graph.
        for (var install : installs) { String path=install.path();files.put(path,install.data());foldedFiles.add(path.toLowerCase(Locale.ROOT));savedHashes.put(path,install.hash());contentRevisions.put(path,++contentSequence);changed.add(path);created.add(path); }
        resourceRevision++;history.clear("Asset batch imported; earlier applied-edit history was cleared.");return List.copyOf(sizes.keySet());
    }
    /** Installs private captured bytes after complete preflight; no source I/O or baseline adoption. */
    String installCapturedAsset(String target,AssetReplacement.Prepared prepared,long maximumBytes) throws IOException {
        assetImportBudget(target,prepared.receipt().bytes(),maximumBytes);
        add(target,prepared.copyBytes());changed.add(target);created.add(target);resourceRevision++;
        history.clear("Asset imported; earlier applied-edit history was cleared.");return target;
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
    private String reviewedCreationTarget(String parent,String name,String dependency) throws IOException {
        String base=ResourceCreation.base(parent);
        if(name==null || name.length()>250 || !name.matches("[a-z0-9_-]+"))throw new IOException("Use 1-250 lowercase letters, digits, _ or -; omit .json");
        if(!Set.of("image-maps","blocks","expressions","structures","jigsaw-pools","jigsaw-pieces").contains(base) || !parent.equals(base)) {
            if(!directories.contains(parent) && files.keySet().stream().noneMatch(path->path.startsWith(parent+"/")))throw new IOException("Parent folder is not in this workspace");
        }
        if(ResourceCreation.dependencyType(parent)!=null && !ResourceCreation.choices(this,parent).contains(dependency))throw new IOException("Choose an exact existing "+ResourceCreation.dependencyType(parent)+" key");
        String key=(parent.equals(base) ? "" : parent.substring(base.length()+1)+"/")+name;
        return safe(ResourceTypes.target(base+"/template.json",key));
    }
    private void reviewedAdditionBudget(String path,int bytes,long maximumBytes) throws IOException {
        if(maximumBytes<0 || maximumBytes>MAX_BYTES)throw new IllegalArgumentException("Invalid reviewed resource budget");safe(path);
        for(String component:path.split("/"))if(component.length()>255)throw new IOException("Resource path components must fit 255 characters, including the extension");
        if(ResourceTypes.fileFamily(path)==null)throw new IOException("Unsupported resource destination");
        if(bytes<0 || files.values().stream().mapToLong(data->data.length).sum()+bytes>maximumBytes)throw new IOException("Reviewed resource exceeds remaining workspace byte capacity");
        if(files.size()>=MAX_FILES || foldedFiles.contains(path.toLowerCase(Locale.ROOT)))throw new IOException("Resource destination is already occupied or file limit reached");validatePaths(List.of(path),true);
    }
    ReviewedResourceAddition.Target reviewResourceCreation(String parent,String name,String dependency,long maximumBytes) throws IOException {
        String path=reviewedCreationTarget(parent,name,dependency);byte[] data=ResourceCreation.bytes(this,parent,name,dependency);reviewedAdditionBudget(path,data.length,maximumBytes);
        var plan=new ReviewedResourceAddition.Plan(ReviewedResourceAddition.Mode.CREATE_JSON,parent,name,dependency,null,path,data.length);
        return new ReviewedResourceAddition.Target(this,new ReviewedResourceAddition.Source(plan,data),-1);
    }
    ReviewedResourceAddition.Target reviewResourceDuplication(String source,String key,long maximumBytes) throws IOException {
        if(!files.containsKey(source) || ResourceTypes.fileFamily(source)==null)throw new IOException("Choose a supported JSON, image or .iob resource to duplicate");
        if(key==null || key.length()>16384)throw new IOException("Use a relative resource key of at most 16,384 characters");
        String proposed=safe(ResourceTypes.fileFamily(source)+"/"+key+ResourceTypes.extension(source));
        for(String component:proposed.split("/"))if(component.length()>255)throw new IOException("Resource path components must fit 255 characters");
        String path=safe(ResourceTypes.target(source,key));byte[] data=files.get(source);reviewedAdditionBudget(path,data.length,maximumBytes);
        var plan=new ReviewedResourceAddition.Plan(ReviewedResourceAddition.Mode.DUPLICATE,null,key,null,source,path,data.length);
        return new ReviewedResourceAddition.Target(this,new ReviewedResourceAddition.Source(plan,data),contentRevision(source));
    }
    boolean reviewedAdditionSourceCurrent(ReviewedResourceAddition.Target target) {
        return target!=null && target.owner==this && (target.source.plan.mode()!=ReviewedResourceAddition.Mode.DUPLICATE || files.containsKey(target.source.plan.source()) && contentRevision(target.source.plan.source())==target.stamp);
    }
    void checkReviewedAddition(ReviewedResourceAddition.Target target,long maximumBytes) throws IOException {
        if(!reviewedAdditionSourceCurrent(target))throw new IOException("Source resource bytes or workspace changed; prepare the resource again");var plan=target.source.plan;
        if(plan.mode()==ReviewedResourceAddition.Mode.CREATE_JSON && !reviewedCreationTarget(plan.parent(),plan.name(),plan.dependency()).equals(plan.target()))throw new IOException("Creation destination changed");
        reviewedAdditionBudget(plan.target(),plan.bytes(),maximumBytes);
    }
    String installReviewedAddition(ReviewedResourceAddition.Target target,ReviewedResourceAddition.Prepared prepared,long maximumBytes) throws IOException {
        checkReviewedAddition(target,maximumBytes);if(prepared==null || prepared.origin!=target.source || !prepared.available())throw new IOException("Prepared resource belongs to another request or was already applied");
        byte[] fingerprint=prepared.hashCopy();byte[] data=prepared.consume();String path=target.source.plan.target();
        files.put(path,data);foldedFiles.add(path.toLowerCase(Locale.ROOT));savedHashes.put(path,fingerprint);contentRevisions.put(path,++contentSequence);changed.add(path);created.add(path);resourceRevision++;history.clear("Reviewed resource added; earlier applied-edit history was cleared.");return path;
    }
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
    /** Pure proposed-folder graph preflight for the reviewed UI; legacy synchronous APIs remain compatible. */
    ReviewedFolderOperation.Plan reviewFolderOperation(ReviewedFolderOperation.Mode mode,String path,String value) throws IOException {
        Objects.requireNonNull(mode);if(path==null || path.length()>16384)throw new IOException("Choose a resource folder of at most 16,384 characters");safe(path);
        String target=null;
        if(mode==ReviewedFolderOperation.Mode.CREATE || mode==ReviewedFolderOperation.Mode.RENAME) {
            if(value==null || value.length()>255 || !value.matches("[a-z0-9_-]+"))throw new IOException("Use 1-255 lowercase letters, digits, _ or - for the folder name");
        }
        if(mode==ReviewedFolderOperation.Mode.CREATE) {
            if(!ResourceTypes.folderFamily(path))throw new IOException("Choose a supported resource folder; dimensions must stay flat");
            if(!directories.contains(path) && files.keySet().stream().noneMatch(p->p.startsWith(path+"/")))throw new IOException("Parent folder is not in this workspace");
            target=path+"/"+value;
        }else {
            emptyFolder(path);
            if(mode==ReviewedFolderOperation.Mode.RENAME)target=path.substring(0,path.lastIndexOf('/'))+"/"+value;
            else if(mode==ReviewedFolderOperation.Mode.MOVE) {
                if(value==null || value.length()>16384)throw new IOException("Choose an existing destination folder of at most 16,384 characters");safe(value);
                if(!ResourceTypes.folderFamily(value) || !path.substring(0,path.indexOf('/')).equals(value.split("/",-1)[0]))throw new IOException("Move folders only within their existing resource family");
                if(!directories.contains(value) && files.keySet().stream().noneMatch(p->p.startsWith(value+"/")))throw new IOException("Choose an existing destination folder");
                if(value.equals(path) || value.startsWith(path+"/"))throw new IOException("A folder cannot be moved into itself");target=value+path.substring(path.lastIndexOf('/'));
            }
        }
        var proposed=new TreeSet<>(directories);if(mode!=ReviewedFolderOperation.Mode.CREATE)proposed.remove(path);
        if(target!=null) {
            safe(target);for(String component:target.split("/"))if(component.length()>255)throw new IOException("Folder path components must fit 255 characters");
            if(mode==ReviewedFolderOperation.Mode.CREATE || !target.equals(path)) {
                if(target.equalsIgnoreCase(path))throw new IOException("Case-only folder renames are not supported on Windows");final String destination=target;
                if(directories.contains(target) || files.containsKey(target) || files.keySet().stream().anyMatch(p->p.startsWith(destination+"/")))throw new IOException("Destination folder is already occupied: "+target);
            }
            for(String ancestor=target.substring(0,target.lastIndexOf('/'));!ancestor.isEmpty();) {
                if(removedDirectories.contains(ancestor))throw new IOException("Restore the pending parent-folder removal first: "+ancestor);
                int slash=ancestor.lastIndexOf('/');ancestor=slash<0 ? "" : ancestor.substring(0,slash);
            }
            proposed.add(target);
        }
        validatePaths(List.of(),proposed,true);return new ReviewedFolderOperation.Plan(mode,path,value,target);
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
    public static PackWorkspace open(Path root) throws IOException { return open(root,LOAD_LIMITS); }
    static PackWorkspace open(Path root,LoadLimits limits) throws IOException { return open(root,limits,PackLoads.monitor()); }
    static PackWorkspace open(Path root,LoadLimits limits,PackLoads.Monitor monitor) throws IOException {
        monitor.check();rejectLinks(root.toAbsolutePath().normalize());Path base=root.toRealPath();
        if(!Files.isDirectory(base,LinkOption.NOFOLLOW_LINKS))throw new IOException("Select a pack directory");
        PackWorkspace result=empty();result.directory=base;LoadBudget budget=new LoadBudget(limits,monitor);
        Files.walkFileTree(base,new SimpleFileVisitor<>() {
            final Map<Path,java.nio.file.attribute.BasicFileAttributes> parents=new HashMap<>();
            private String key(Path path) throws IOException { return budget.entry(base.relativize(path).toString().replace('\\','/')); }
            @Override public FileVisitResult preVisitDirectory(Path dir,java.nio.file.attribute.BasicFileAttributes attributes) throws IOException {
                monitor.check();rejectLinks(dir);var before=PackLoads.attributes(dir);
                if(!before.isDirectory())throw new IOException("Pack folder changed before loading: "+dir);
                parents.put(dir,before);if(!dir.equals(base))result.directories.add(key(dir));budget.progress(result,0);return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path path,java.nio.file.attribute.BasicFileAttributes attributes) throws IOException {
                monitor.check();String key=key(path);checkedTarget(base,key);
                // File-tree enumeration may carry cached Windows attributes. Use matching explicit reads at both ends.
                attributes=PackLoads.attributes(path);
                if(attributes.isSymbolicLink() || !attributes.isRegularFile())throw new IOException("Unsupported or linked pack entry: "+key);
                if(attributes.size()>budget.remaining())throw new IOException("Pack exceeds workspace byte limit");byte[] data;
                try(var input=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)) { data=budget.read(input,result,budget.remaining()); }
                PackLoads.unchanged(path,attributes);budget.bytes+=data.length;result.add(key,data,monitor);budget.progress(result,0);return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path dir,IOException failure) throws IOException {
                monitor.check();if(failure!=null)throw failure;PackLoads.unchanged(dir,parents.remove(dir));return FileVisitResult.CONTINUE;
            }
        });
        monitor.check();result.validatePaths();monitor.check();result.savedDirectories.addAll(result.directories);return result;
    }
    public static PackWorkspace importZip(Path archive) throws IOException { return importZip(archive,LOAD_LIMITS); }
    static PackWorkspace importZip(Path archive,LoadLimits limits) throws IOException { return importZip(archive,limits,PackLoads.monitor()); }
    static PackWorkspace importZip(Path archive,LoadLimits limits,PackLoads.Monitor monitor) throws IOException {
        monitor.check();Path path=archive.toAbsolutePath().normalize();rejectLinks(path);var before=PackLoads.attributes(path);
        if(!before.isRegularFile())throw new IOException("Select a regular ZIP archive");
        PackWorkspace result=empty();LoadBudget budget=new LoadBudget(limits,monitor);var expected=new LinkedHashMap<String,ZipEntry>();var names=new HashSet<String>();
        // Validate the central directory before streaming. Invalid/truncated archives cannot silently become empty/partial packs.
        try(var index=new ZipFile(path.toFile())) {
            if(index.size()>limits.entries())throw new IOException("Too many ZIP entries");var entries=index.entries();
            while(entries.hasMoreElements()) {
                monitor.check();ZipEntry entry=entries.nextElement();String key=budget.entry(entry.getName().replaceAll("/$",""));
                if(!names.add(key.toLowerCase(Locale.ROOT)))throw new IOException("Duplicate ZIP entry: "+key);
                if(entry.getSize()<0 || entry.getCrc()<0 || entry.getSize()>limits.bytes() || (entry.isDirectory() && entry.getSize()!=0))throw new IOException("Invalid or oversized ZIP entry: "+key);
                expected.put(entry.getName(),entry);budget.progress(result,0);
            }
        }
        PackLoads.unchanged(path,before);
        try(var zip=new ZipInputStream(Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS))) {
            while(true) {
                monitor.check();ZipEntry entry=zip.getNextEntry();if(entry==null)break;
                ZipEntry indexed=expected.remove(entry.getName());
                if(indexed==null || indexed.isDirectory()!=entry.isDirectory() || indexed.getMethod()!=entry.getMethod())throw new IOException("ZIP local entry differs from its central directory: "+entry.getName());
                String key=budget.entry(entry.getName().replaceAll("/$",""));
                if(entry.isDirectory()) { budget.read(zip,result,0);result.directories.add(key); }
                else {
                    if(entry.getSize()>budget.remaining())throw new IOException("Expanded ZIP exceeds workspace byte limit");
                    byte[] data=budget.read(zip,result,budget.remaining());budget.bytes+=data.length;result.add(key,data,monitor);
                }
                if(entry.getSize()!=indexed.getSize() || entry.getCrc()!=indexed.getCrc())throw new IOException("ZIP payload differs from its central directory: "+entry.getName());budget.progress(result,0);
            }
        }
        if(!expected.isEmpty())throw new IOException("ZIP is missing entries from its central directory");
        monitor.check();PackLoads.unchanged(path,before);result.validatePaths();monitor.check();result.savedDirectories.addAll(result.directories);return result;
    }
    static byte[] readBounded(InputStream input,long limit) throws IOException { return readBounded(input,limit,PackLoads.monitor(),n->{}); }
    static byte[] readBounded(InputStream input,long limit,PackLoads.Monitor monitor,java.util.function.LongConsumer progress) throws IOException {
        if(limit<0 || limit>MAX_BYTES)throw new IllegalArgumentException("Invalid read budget");
        ByteArrayOutputStream data=new ByteArrayOutputStream();byte[] buffer=new byte[8192];long total=0;
        while(true) {
            monitor.check();int count=input.read(buffer,0,(int)Math.min(buffer.length,limit-total+1));
            if(count<0) { monitor.check();return data.toByteArray(); }
            if(count==0) { monitor.check();int value=input.read();if(value<0) { monitor.check();return data.toByteArray(); }buffer[0]=(byte)value;count=1; }
            total+=count;progress.accept(total);monitor.check();if(total>limit)throw new IOException("Pack exceeds workspace byte limit");data.write(buffer,0,count);
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
    /** UI metadata capture only: no file payloads, hashing or disk I/O. */
    PendingDiskReview.Source capturePendingDiskReview(WorkspaceChanges.Entry selected) throws IOException {
        var entries=pendingChanges();
        if (selected!=null) {
            if (!entries.contains(selected)) throw new IOException("This pending destination is no longer available");
            entries=List.of(selected);
        }
        return new PendingDiskReview.Source(directory,entries,savedHashes,removedDirectories,relocatedDirectoryTargets);
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
        save(target,FileSaves::write);
    }
    /** Injectable publication boundary for filesystem failure contracts; normal callers use the same staged writer. */
    void save(Path target,FileSaves.Writer writer) throws IOException {
        Objects.requireNonNull(writer);
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
            FileSaves.preflight(target.resolve(key),same && !created.contains(key) ? savedHashes.get(key) : null);
        }
        if (same) for (String key : removedDirectories) preflightDirectoryRemoval(target,key);
        Files.createDirectories(target);
        for (String key : directories) {
            rejectLinks(target);checkedTarget(target,key);Files.createDirectories(target.resolve(key));
            if (same && changedDirectories.remove(key)) { savedDirectories.add(key);relocatedDirectoryTargets.remove(key);saveRevision++; }
        }
        for (var entry : files.entrySet()) {
            if (same && !changed.contains(entry.getKey())) continue;
            rejectLinks(target);Path file=checkedTarget(target,entry.getKey());Files.createDirectories(file.getParent());
            var receipt=Objects.requireNonNull(writer.write(target,entry.getKey(),entry.getValue(),same && !created.contains(entry.getKey()) ? savedHashes.get(entry.getKey()) : null),"Save writer returned no receipt");
            created.remove(entry.getKey());
            savedHashes.put(entry.getKey(),receipt.savedHash());
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
        // Published-file receipts already installed exact captured-byte hashes; untouched baselines stay independent.
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
    /** UI-only baseline adoption after a guarded, successfully published immutable Save As snapshot. */
    void adoptCopiedBaseline(Path target,ZipExportSource.Baseline baseline) throws IOException {
        if (baseline==null || baseline.summary().revision()!=revision() || !baseline.matches(files.keySet(),directories)) throw new IOException("Applied workspace differs from the published Save As snapshot");
        Path destination=target.toAbsolutePath().normalize();var hashes=baseline.copyHashes();
        directory=destination;savedHashes.clear();savedHashes.putAll(hashes);
        changed.clear();created.clear();changedDirectories.clear();removedDirectories.clear();relocatedDirectoryTargets.clear();savedDirectories.clear();savedDirectories.addAll(directories);saveRevision++;
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
    /** Full applied export snapshot; private immutable arrays are shared without another complete pack allocation. */
    public ZipExportSource captureZipExport() throws IOException {
        var source=new ZipExportSource(files,directories,revision(),directory);validatePaths();return source;
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
    private void add(String key,byte[] bytes) throws IOException { add(key,bytes,null); }
    private void add(String key,byte[] bytes,PackLoads.Monitor monitor) throws IOException {
        if(files.size()>=MAX_FILES)throw new IOException("Too many pack files");
        if(!foldedFiles.add(key.toLowerCase(Locale.ROOT)))throw new IOException("Conflicting resource paths: "+key);
        byte[] fingerprint;
        if(monitor==null)fingerprint=hash(bytes);
        else {
            try {
                var digest=java.security.MessageDigest.getInstance("SHA-256");
                for(int offset=0;offset<bytes.length;offset+=8192) { monitor.check();digest.update(bytes,offset,Math.min(8192,bytes.length-offset)); }
                monitor.check();fingerprint=digest.digest();
            } catch(java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
        }
        files.put(key,bytes);savedHashes.put(key,fingerprint);contentRevisions.put(key,++contentSequence);
    }
    private void validatePaths() throws IOException { validatePaths(List.of()); }
    private void validatePaths(String additionalFile) throws IOException { validatePaths(List.of(additionalFile)); }
    private void validatePaths(List<String> additionalFiles) throws IOException { validatePaths(additionalFiles,false); }
    private void validatePaths(List<String> additionalFiles,boolean boundCharacters) throws IOException {
        validatePaths(additionalFiles,directories,boundCharacters);
    }
    private void validatePaths(List<String> additionalFiles,Set<String> proposedDirectories,boolean boundCharacters) throws IOException {
        long pathCharacters=0;
        Map<String, String> spellings = new HashMap<>(); Set<String> folders = new HashSet<>();
        List<String> plannedFiles=new ArrayList<>(files.keySet());plannedFiles.addAll(additionalFiles);
        for (String file : plannedFiles) for (String current=file;!current.isEmpty();) {
            if (removedDirectories.contains(current)) throw new IOException("Restore the pending folder removal before adding resources inside it: "+current);
            int slash=current.lastIndexOf('/');current=slash<0 ? "" : current.substring(0,slash);
        }
        List<String> all = new ArrayList<>(plannedFiles); all.addAll(proposedDirectories);
        for (String key : all) {
            String[] parts = key.split("/"); String current = "";
            for (int i=0;i<parts.length;i++) {
                current += (i == 0 ? "" : "/") + parts[i];
                String folded = current.toLowerCase(Locale.ROOT), prior = spellings.putIfAbsent(folded, current);
                if(prior==null) { pathCharacters+=current.length();if(boundCharacters && pathCharacters>MAX_LOAD_PATH_CHARACTERS)throw new IOException("Reviewed resource exceeds the 16 million path-character metadata budget"); }
                if (spellings.size() > MAX_FILES) throw new IOException("Too many pack entries, including folders");
                if (prior != null && !prior.equals(current)) throw new IOException("Conflicting Windows path spelling: " + current);
                if (i < parts.length-1 || proposedDirectories.contains(key)) folders.add(folded);
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
    static void rejectLinks(Path path) throws IOException {
        for (Path part = path; part != null; part = part.getParent())
            if (Files.isSymbolicLink(part)) throw new IOException("Destination contains a symbolic link: " + part);
    }
    static Path checkedTarget(Path root, String key) throws IOException {
        Path current = root;
        for (String part : key.split("/")) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) throw new IOException("Destination contains a symbolic link: " + current);
            if (!current.equals(root.resolve(key)) && Files.exists(current) && !Files.isDirectory(current))
                throw new IOException("Destination path component is not a directory: " + current);
        }
        return current;
    }
    static String safe(String key) throws IOException {
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
