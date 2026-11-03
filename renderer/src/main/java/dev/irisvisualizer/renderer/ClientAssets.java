package dev.irisvisualizer.renderer;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.zip.*;

/** Worker-side, read-only capture of a user-selected, version-matched client JAR/ZIP. No downloads. */
public final class ClientAssets {
    private ClientAssets() {}
    public record Limits(long archiveBytes,long capturedBytes,int assetBytes,int entries,int pathCharacters) {
        public static final Limits DEFAULT=new Limits(256L*1024*1024,256L*1024*1024,16*1024*1024,250_000,16*1024*1024);
        public Limits {
            if(archiveBytes<1 || archiveBytes>256L*1024*1024 || capturedBytes<1 || capturedBytes>256L*1024*1024 || assetBytes<1 || assetBytes>16*1024*1024 || entries<1 || entries>250_000 || pathCharacters<1 || pathCharacters>16*1024*1024)
                throw new IllegalArgumentException("Client-asset capture limits exceed renderer bounds");
        }
    }
    /** Known primitive capture peak; archive-copy, directory preflight and catalog loading are sequential phases. */
    public static long workingBytes(Limits limits) {
        Objects.requireNonNull(limits);return Math.max(65536,Math.max(ZipDirectory.workingBytes(limits.archiveBytes()),limits.capturedBytes()+65536));
    }
    private record Stamp(long size,FileTime modified,Object key) {
        static Stamp file(Path path)throws IOException {
            var value=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            if(!value.isRegularFile() || value.isSymbolicLink())throw new IOException("Choose a regular client JAR/ZIP file");
            return new Stamp(value.size(),value.lastModifiedTime(),value.fileKey());
        }
    }
    private record Capture(Path source,Path temporary,Stamp temporaryStamp,String sha256,long bytes) {}
    private static void check(BooleanSupplier cancelled) { if(cancelled.getAsBoolean())throw new CancellationException("Client asset capture cancelled"); }
    private static MessageDigest digest() { try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);} }
    private static String hash(byte[] bytes) { return HexFormat.of().formatHex(digest().digest(bytes)); }
    private static void remove(Path path,Stamp expected)throws IOException {
        if(!Files.exists(path,LinkOption.NOFOLLOW_LINKS))return;
        Stamp actual=Stamp.file(path);
        if(expected.key()!=null ? !Objects.equals(expected.key(),actual.key()) : !expected.equals(actual))
            throw new IOException("Private client-asset capture changed; left in place: "+path);
        Files.delete(path);
    }
    private static Capture capture(Path selected,Limits limits,BooleanSupplier cancelled)throws IOException {
        check(cancelled);Path source=selected.toRealPath();Stamp before=Stamp.file(source);
        if(before.size()>limits.archiveBytes())throw new IOException("Client archive exceeds its capture byte limit");
        Path temporary=Files.createTempFile("iris-renderer-client-",".jar");Stamp created=Stamp.file(temporary);boolean retained=false;Throwable failure=null;
        try {
            long total=0;var digest=digest();byte[] buffer=new byte[64*1024];
            try(var input=Files.newInputStream(source,LinkOption.NOFOLLOW_LINKS);var output=Files.newOutputStream(temporary,StandardOpenOption.WRITE,StandardOpenOption.TRUNCATE_EXISTING)) {
                for(int n;(n=input.read(buffer))!=-1;) {
                    check(cancelled);if(n==0)continue;
                    if(n>limits.archiveBytes()-total)throw new IOException("Client archive grew beyond its capture byte limit");
                    output.write(buffer,0,n);digest.update(buffer,0,n);total+=n;
                }
            }
            check(cancelled);if(total!=before.size() || !before.equals(Stamp.file(source)))throw new IOException("Client archive changed while being captured; select it again");
            var result=new Capture(source,temporary,Stamp.file(temporary),HexFormat.of().formatHex(digest.digest()),total);retained=true;return result;
        }catch(IOException|RuntimeException|Error error){failure=error;throw error;}
        finally {
            if(!retained)try {
                // The file can be partially written; a non-null creation identity remains authoritative.
                Stamp expected=created.key()!=null?created:Stamp.file(temporary);remove(temporary,expected);
            }catch(IOException cleanup){if(failure!=null)failure.addSuppressed(cleanup);else throw cleanup;}
        }
    }
    private static void path(String name)throws IOException {
        if(name.isEmpty() || name.length()>4096 || name.startsWith("/") || name.indexOf('\\')>=0 || name.indexOf(':')>=0 || name.chars().anyMatch(c->c<32 || c==127))
            throw new IOException("Client archive contains an unsupported entry path");
        String plain=name.endsWith("/")?name.substring(0,name.length()-1):name;
        for(String part:plain.split("/",-1))if(part.isEmpty() || part.equals(".") || part.equals(".."))throw new IOException("Client archive contains a noncanonical entry path");
    }
    private static ClientAssetSnapshot.Key key(String name)throws IOException {
        String prefix="assets/minecraft/";if(!name.startsWith(prefix))return null;
        String relative=name.substring(prefix.length());ClientAssetSnapshot.Kind kind;String id;
        if(relative.startsWith("blockstates/") && relative.endsWith(".json")){kind=ClientAssetSnapshot.Kind.BLOCKSTATE;id=relative.substring(12,relative.length()-5);}
        else if(relative.startsWith("models/") && relative.endsWith(".json")){kind=ClientAssetSnapshot.Kind.MODEL;id=relative.substring(7,relative.length()-5);}
        else if(relative.startsWith("textures/") && relative.endsWith(".png.mcmeta")){kind=ClientAssetSnapshot.Kind.TEXTURE_METADATA;id=relative.substring(9,relative.length()-11);}
        else if(relative.startsWith("textures/") && relative.endsWith(".png")){kind=ClientAssetSnapshot.Kind.TEXTURE;id=relative.substring(9,relative.length()-4);}
        else return null;
        try{return new ClientAssetSnapshot.Key(kind,AssetId.minecraft(id));}catch(IllegalArgumentException unsupported){throw new IOException("Noncanonical vanilla asset entry",unsupported);}
    }
    private static byte[] read(ZipFile zip,ZipEntry entry,int maximum,BooleanSupplier cancelled)throws IOException {
        long claimed=entry.getSize();if(claimed<0 || claimed>maximum || entry.getCrc()<0)throw new IOException("Client asset lacks bounded size/CRC metadata: "+entry.getName());
        byte[] bytes=new byte[(int)claimed];var crc=new CRC32();int offset=0;
        try(var input=zip.getInputStream(entry)) {
            while(offset<bytes.length) {
                check(cancelled);int n=input.read(bytes,offset,Math.min(64*1024,bytes.length-offset));
                if(n<0)throw new IOException("Client asset ended before its declared size: "+entry.getName());if(n==0)continue;
                crc.update(bytes,offset,n);offset+=n;
            }
            check(cancelled);if(input.read()!=-1 || crc.getValue()!=entry.getCrc())throw new IOException("Client asset size/CRC mismatch: "+entry.getName());
        }
        return bytes;
    }
    private static String version(byte[] source,String expected,BooleanSupplier cancelled)throws IOException {
        var value=AssetJson.parse(source,new AssetJson.Limits(64*1024,4096,32,16*1024),cancelled);
        if(!(value instanceof AssetJson.ObjectValue object) || !(object.fields().get("id") instanceof AssetJson.Text id))
            throw new IOException("Client version.json must contain a string id; version inference from filenames is disabled");
        if(!id.value().matches("[A-Za-z0-9][A-Za-z0-9._+-]{0,127}"))throw new IOException("Client version.json id has an unsupported spelling");
        if(!expected.equals(id.value()))throw new IOException("Client assets target "+id.value()+"; the pinned Minecraft target is "+expected);
        return id.value();
    }
    public static ClientAssetSnapshot load(Path selected,String expectedVersion,BooleanSupplier cancelled)throws IOException {
        return load(selected,expectedVersion,Limits.DEFAULT,cancelled);
    }
    /** Invoke on a worker. Captures selected bytes, validates ZIP/CRC/version, then deletes only private scratch. */
    public static ClientAssetSnapshot load(Path selected,String expectedVersion,Limits limits,BooleanSupplier cancelled)throws IOException {
        Objects.requireNonNull(selected);Objects.requireNonNull(expectedVersion);Objects.requireNonNull(limits);Objects.requireNonNull(cancelled);
        if(!expectedVersion.matches("[A-Za-z0-9][A-Za-z0-9._+-]{0,127}"))throw new IllegalArgumentException("Use the exact pinned Minecraft version ID");
        var capture=capture(selected,limits,cancelled);Throwable failure=null;
        try {
            ZipDirectory.verify(capture.temporary(),capture.bytes(),limits,cancelled);
            try(var zip=new ZipFile(capture.temporary().toFile(),StandardCharsets.UTF_8)) {
                var versionEntry=zip.getEntry("version.json");
                if(versionEntry==null)throw new IOException("Client version.json is missing; choose a version-matched client archive");
                String actual=version(read(zip,versionEntry,64*1024,cancelled),expectedVersion,cancelled);
                var assets=new TreeMap<ClientAssetSnapshot.Key,ClientAssetSnapshot.Asset>();boolean versionSeen=false;int count=0,ignored=0;long total=0,names=0;
                for(var entries=zip.entries();entries.hasMoreElements();) {
                    check(cancelled);var entry=entries.nextElement();if(++count>limits.entries())throw new IOException("Client archive exceeds its entry limit");
                    String name=entry.getName();path(name);names+=name.length();if(names>limits.pathCharacters())throw new IOException("Client archive exceeds its entry-name budget");
                    if(entry.isDirectory()){ignored++;continue;}
                    if(name.equals("version.json")) {
                        if(versionSeen)throw new IOException("Duplicate client version.json entry");
                        versionSeen=true;continue;
                    }
                    var key=key(name);if(key==null){ignored++;continue;}
                    if(assets.containsKey(key))throw new IOException("Duplicate vanilla asset identity: "+key.id());
                    long size=entry.getSize();if(size<0 || size>limits.capturedBytes()-total)throw new IOException("Client assets exceed the captured-byte budget");
                    byte[] bytes=read(zip,entry,limits.assetBytes(),cancelled);total+=bytes.length;
                    assets.put(key,ClientAssetSnapshot.Asset.owned(name,bytes,hash(bytes)));
                }
                if(!versionSeen)throw new IOException("Client version.json was not present in its directory enumeration");
                for(var kind:List.of(ClientAssetSnapshot.Kind.BLOCKSTATE,ClientAssetSnapshot.Kind.MODEL,ClientAssetSnapshot.Kind.TEXTURE))
                    if(assets.keySet().stream().noneMatch(key->key.kind()==kind))throw new IOException("Client archive has no vanilla "+kind+" assets; server JARs and incomplete archives are unsupported");
                check(cancelled);var result=new ClientAssetSnapshot(new ClientAssetSnapshot.Receipt(capture.source(),capture.sha256(),capture.bytes(),expectedVersion,actual,count,ignored,total),assets);check(cancelled);return result;
            }
        }catch(IOException|RuntimeException|Error error){failure=error;throw error;}
        finally { try{remove(capture.temporary(),capture.temporaryStamp());}catch(IOException cleanup){if(failure!=null)failure.addSuppressed(cleanup);else throw cleanup;} }
    }
}
