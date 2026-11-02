package dev.irisvisualizer.renderer;

import java.util.Objects;

/** Exact Minecraft asset identity. Filesystem spelling is never normalized into another resource. */
public record AssetId(String namespace,String path) implements Comparable<AssetId> {
    public AssetId {
        Objects.requireNonNull(namespace);Objects.requireNonNull(path);
        if(namespace.isEmpty() || namespace.length()>64 || !namespace.matches("[a-z0-9_.-]+"))
            throw new IllegalArgumentException("Use an exact lowercase Minecraft asset namespace");
        if(path.isEmpty() || path.length()>1024 || !path.matches("[a-z0-9_./-]+"))
            throw new IllegalArgumentException("Use an exact lowercase Minecraft asset path");
        for(String part:path.split("/",-1))if(part.isEmpty() || part.equals(".") || part.equals(".."))
            throw new IllegalArgumentException("Asset paths must not contain empty, dot or parent segments");
    }
    public static AssetId parse(String text) {
        Objects.requireNonNull(text);int colon=text.indexOf(':');
        if(colon<1 || colon!=text.lastIndexOf(':'))throw new IllegalArgumentException("Use an explicit namespace:path asset ID");
        return new AssetId(text.substring(0,colon),text.substring(colon+1));
    }
    public static AssetId minecraft(String path) { return new AssetId("minecraft",path); }
    @Override public String toString() { return namespace+":"+path; }
    @Override public int compareTo(AssetId other) { int order=namespace.compareTo(other.namespace);return order==0?path.compareTo(other.path):order; }
}
