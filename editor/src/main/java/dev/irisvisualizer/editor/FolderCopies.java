package dev.irisvisualizer.editor;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/** Worker-only full snapshot copies. No source disk reads, live workspace access or baseline adoption. */
public final class FolderCopies {
    public record Receipt(Path directory,ZipExportSource.Summary source) {}
    private record Stamp(Object key,FileTime modified) {}
    private record Plan(Path directory,Path anchor,Stamp expected) {}
    private record Created(Path path,Object key,boolean directory) {}
    @FunctionalInterface interface Mover { void move(Path staging,Path destination) throws IOException; }
    private static void rejectLinks(Path path) throws IOException { for (Path part=path;part!=null;part=part.getParent()) if (Files.isSymbolicLink(part)) throw new IOException("Folder copy path contains a symbolic link: "+part); }
    private static Created created(Path path,boolean directory) throws IOException {
        var attributes=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if (directory ? !attributes.isDirectory() : !attributes.isRegularFile()) throw new IOException("Created copy path changed type: "+path);
        return new Created(path,attributes.fileKey(),directory);
    }
    private static void checkOwned(Created item) throws IOException {
        rejectLinks(item.path());var attributes=Files.readAttributes(item.path(),BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if ((item.directory() ? !attributes.isDirectory() : !attributes.isRegularFile()) || item.key()!=null && !Objects.equals(item.key(),attributes.fileKey())) throw new IOException("Copy staging path changed; left in place: "+item.path());
    }
    /** Delete only paths created by this operation, in reverse order, without recursive traversal. */
    private static IOException cleanup(List<Created> items) {
        IOException first=null;
        for (int i=items.size()-1;i>=0;i--) try { var item=items.get(i);checkOwned(item);Files.delete(item.path()); }
        catch (NoSuchFileException absent) { /* Already absent; never recreate staging paths. */ }
        catch (IOException failure) { if (first==null) first=new IOException("Copy cleanup left a path in place: "+items.get(i).path(),failure); }
        return first;
    }
    static final class Ownership {
        private final Path root;
        private final List<Created> items=new ArrayList<>();
        private final LinkedHashMap<Path,Created> directories=new LinkedHashMap<>();
        Ownership(Path root) throws IOException { this.root=root;var item=created(root,true);items.add(item);directories.put(root,item); }
        private Path resolve(String relative) throws IOException {
            Path path=root.resolve(relative).normalize();if (relative.isEmpty() || relative.contains("\\") || relative.contains(":") || !path.startsWith(root) || path.equals(root)) throw new IOException("Unsafe copied resource path");return path;
        }
        private void directory(Path path) throws IOException {
            var known=directories.get(path);if (known!=null) { checkOwned(known);return; }
            if (!path.startsWith(root) || path.getParent()==null) throw new IOException("Unsafe copied folder");directory(path.getParent());
            Files.createDirectory(path);var item=created(path,true);items.add(item);directories.put(path,item);
        }
        void directory(String relative) throws IOException { directory(resolve(relative)); }
        OutputStream open(String relative) throws IOException {
            Path path=resolve(relative);directory(path.getParent());rejectLinks(path);
            var output=Files.newOutputStream(path,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);
            try { items.add(created(path,false));return output; } catch (IOException failure) { output.close();throw failure; }
        }
        IOException cleanup() { return FolderCopies.cleanup(items); }
    }
    private static Stamp empty(Path directory) throws IOException {
        var attributes=Files.readAttributes(directory,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory()) throw new IOException("Save Copy destination must be a new or empty directory");
        try (var children=Files.list(directory)) { if (children.findAny().isPresent()) throw new IOException("Save Copy destination is not empty: "+directory); }
        return new Stamp(attributes.fileKey(),attributes.lastModifiedTime());
    }
    private static Plan plan(ZipExportSource source,Path target) throws IOException {
        if (target==null) throw new IOException("Choose a new or empty Save Copy directory");target=target.toAbsolutePath().normalize();rejectLinks(target);
        Path ancestor=target;var missing=new java.util.ArrayDeque<Path>();
        while (!Files.exists(ancestor,LinkOption.NOFOLLOW_LINKS)) { if (ancestor.getFileName()!=null) missing.addFirst(ancestor.getFileName());ancestor=ancestor.getParent();if (ancestor==null) throw new IOException("No existing copy destination ancestor"); }
        if (!Files.isDirectory(ancestor,LinkOption.NOFOLLOW_LINKS)) throw new IOException("Copy destination parent is not a directory");Path anchor=ancestor.toRealPath(),resolved=anchor;for (Path part : missing) resolved=resolved.resolve(part);
        Path active=source.summary().activeDirectory();if (active!=null && (resolved.startsWith(active) || active.startsWith(resolved))) throw new IOException("Save Copy outside the captured pack; same, nested and ancestor destinations are not supported");
        if (resolved.getParent()==null) throw new IOException("Choose a new or empty copy directory");
        Stamp expected=Files.exists(resolved,LinkOption.NOFOLLOW_LINKS) ? empty(resolved) : null;
        // Existing empty target directories stage beside themselves, never inside the directory being replaced.
        if (expected!=null) anchor=resolved.getParent().toRealPath();return new Plan(resolved,anchor,expected);
    }
    private static void sameDestination(Plan expected,Plan current) throws IOException { if (!expected.directory().equals(current.directory()) || !Objects.equals(expected.expected(),current.expected())) throw new IOException("Save Copy destination changed after capture started"); }
    private static void parents(Path parent,List<Created> created) throws IOException {
        rejectLinks(parent);var missing=new java.util.ArrayDeque<Path>();Path ancestor=parent;
        while (!Files.exists(ancestor,LinkOption.NOFOLLOW_LINKS)) { missing.addFirst(ancestor);ancestor=ancestor.getParent();if (ancestor==null) throw new IOException("Copy destination parent disappeared"); }
        if (!Files.isDirectory(ancestor,LinkOption.NOFOLLOW_LINKS)) throw new IOException("Copy parent is not a directory");
        for (Path path : missing) { rejectLinks(path);try { Files.createDirectory(path);created.add(created(path,true)); } catch (FileAlreadyExistsException raced) { if (!Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) throw new IOException("Copy parent changed",raced); } }
    }
    // The no-replace directory move fails if moving a nonempty directory would require moving its entries.
    // Staging beside an existing destination ancestor avoids recursive copy fallbacks and mount-table queries.
    public static Receipt copy(ZipExportSource source,Path target,ZipExports.Control control,ZipExportSource.Progress progress) throws IOException { return copy(source,target,control,progress,Files::move); }
    static Receipt copy(ZipExportSource source,Path target,ZipExports.Control control,ZipExportSource.Progress progress,Mover mover) throws IOException {
        Objects.requireNonNull(source);Objects.requireNonNull(control);Objects.requireNonNull(progress);Objects.requireNonNull(mover);control.check();Plan plan=plan(source,target);
        Path staging=null;Ownership ownership=null;var newParents=new ArrayList<Created>();boolean published=false,removedEmpty=false;
        try {
            control.check();staging=Files.createTempDirectory(plan.anchor(),".iris-copy-");ownership=new Ownership(staging);source.writeDirectory(ownership,control::cancelled,progress);control.check();sameDestination(plan,plan(source,plan.directory()));
            control.beginPublication();parents(plan.directory().getParent(),newParents);sameDestination(plan,plan(source,plan.directory()));
            if (plan.expected()!=null) { Files.delete(plan.directory());removedEmpty=true; } // Nonrecursive: a newly added child prevents removal.
            mover.move(staging,plan.directory());published=true;return new Receipt(plan.directory(),source.summary());
        } finally {
            IOException failure=null;
            if (!published) {
                if (removedEmpty && !Files.exists(plan.directory(),LinkOption.NOFOLLOW_LINKS)) try { rejectLinks(plan.directory());Files.createDirectory(plan.directory()); } catch (FileAlreadyExistsException raced) { /* Preserve the competing path. */ } catch (IOException restore) { failure=new IOException("Could not restore empty copy destination: "+plan.directory(),restore); }
                if (ownership!=null) { var cleanup=ownership.cleanup();if (failure==null) failure=cleanup; }
                else if (staging!=null) try { Files.delete(staging); } catch (IOException cleanup) { if (failure==null) failure=cleanup; }
                var cleanup=cleanup(newParents);if (failure==null) failure=cleanup;
            }
            control.finished();if (failure!=null) throw failure;
        }
    }
}
