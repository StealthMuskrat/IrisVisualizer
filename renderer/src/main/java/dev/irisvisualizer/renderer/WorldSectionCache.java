package dev.irisvisualizer.renderer;

import java.util.*;

/** One native/atlas epoch at a time. Reservations and leased sections remain charged until released. */
public final class WorldSectionCache implements AutoCloseable {
    public record Limits(int sections,int faces,int cellRecords,int markers,long meshBytes,long workingBytes) {
        public static final Limits DEFAULT=new Limits(8192,2097152,2097152,1048576,256L*1024*1024,512L*1024*1024);
        public Limits { if(sections<1||sections>65536||faces<0||faces>2097152||cellRecords<0||cellRecords>4194304||markers<0||markers>1048576||meshBytes<0||meshBytes>256L*1024*1024||workingBytes<0||workingBytes>512L*1024*1024)throw new IllegalArgumentException("Section cache limits exceed renderer bounds"); }
    }
    public record Stats(boolean closed,int sections,int leasedSections,int reservations,long leases,long sourcePayloadBytes,long meshBytes,int faces,int cellRecords,int markers) {
        public long knownPayloadBytes() { return sourcePayloadBytes+meshBytes; }
    }
    private record Key(WorldSnapshot.Extent extent,WorldMesher.Limits limits) {}
    private static final class Slot {
        final Key key;final WorldMeshPlan plan;WorldMesh mesh;int pins=1;
        Slot(Key key,WorldMeshPlan plan) { this.key=key;this.plan=plan; }
    }
    public static final class Ticket implements AutoCloseable {
        private final WorldSectionCache owner;private Slot slot;
        private Ticket(WorldSectionCache owner,Slot slot) { this.owner=owner;this.slot=slot; }
        private Slot open() { if(slot==null)throw new IllegalStateException("Section cache ticket is closed");return slot; }
        public boolean cached() { synchronized(owner) { return open().mesh!=null; } }
        public WorldMesh mesh() { synchronized(owner) { var value=open().mesh;if(value==null)throw new IllegalStateException("Section reservation is not complete");return value; } }
        public void complete(WorldMesh mesh) {
            Objects.requireNonNull(mesh);synchronized(owner) {
                var entry=open();if(entry.mesh!=null)throw new IllegalStateException("Section reservation is already complete");
                if(mesh.requiresPlaceholder())throw new WorldProblem.Failure(mesh.problems().getFirst());
                if(mesh.models()!=entry.plan.models()||mesh.visibility()!=entry.plan.visibility()||!mesh.extent().equals(entry.plan.extent())||!mesh.usage().equals(entry.plan.usage()))throw WorldProblem.fail(WorldProblem.Code.ASSIGNMENT,"Completed section does not match its admitted plan");
                entry.mesh=mesh;
            }
        }
        @Override public void close() { synchronized(owner) { if(slot==null)return;var entry=slot;slot=null;owner.release(entry); } }
    }
    private final Limits limits;private final LinkedHashMap<Key,Slot> entries=new LinkedHashMap<>(16,0.75f,true);
    private WorldModels models;private WorldVisibility visibility;private long sourceBytes,meshBytes,leases;private int faces,cellRecords,markers;private boolean closed;
    public WorldSectionCache() { this(Limits.DEFAULT); }
    public WorldSectionCache(Limits limits) { this.limits=Objects.requireNonNull(limits); }
    public Limits limits() { return limits; }
    /** Check the complete scene before evicting anything. An old leased epoch cannot be replaced. */
    public synchronized void prepare(WorldScenePlan plan) {
        Objects.requireNonNull(plan);if(closed)throw new IllegalStateException("Section cache is closed");if(plan.requiresPlaceholder())throw new WorldProblem.Failure(plan.problems().getFirst());var usage=plan.usage().orElseThrow();
        if(usage.sections()>limits.sections()||usage.faces()>limits.faces()||usage.cellRecords()>limits.cellRecords()||usage.markers()>limits.markers()||usage.meshBytes()>limits.meshBytes()||usage.knownPayloadBytes()>Math.min(limits.workingBytes(),plan.limits().workingBytes()))throw WorldProblem.fail(WorldProblem.Code.LIMIT,"The complete scene cannot fit the section cache; no partial scene was admitted");
        if(models!=plan.models()||visibility!=plan.visibility()) {
            if(leases!=0)throw WorldProblem.fail(WorldProblem.Code.CACHE_BUSY,"Release prior scene leases before replacing the native snapshot or atlas epoch");
            clearEntries();models=plan.models();visibility=plan.visibility();sourceBytes=usage.sourcePayloadBytes();
        } else if(sourceBytes!=usage.sourcePayloadBytes())throw WorldProblem.fail(WorldProblem.Code.ASSIGNMENT,"Shared scene source accounting changed inside one epoch");
    }
    /** Admission precedes output allocation. Cached sections and incomplete reservations use one key. */
    public synchronized Ticket reserve(WorldMeshPlan plan,long sceneWorkingBytes) {
        Objects.requireNonNull(plan);if(closed)throw new IllegalStateException("Section cache is closed");if(plan.requiresPlaceholder())throw new WorldProblem.Failure(plan.problems().getFirst());
        if(sceneWorkingBytes<0||sceneWorkingBytes>512L*1024*1024)throw new IllegalArgumentException("Invalid scene working budget");
        if(models!=plan.models()||visibility!=plan.visibility())throw WorldProblem.fail(WorldProblem.Code.CACHE_BUSY,"Section reservation does not belong to the prepared native scene epoch");
        if(leases>=65536)throw WorldProblem.fail(WorldProblem.Code.LIMIT,"Active section ticket count budget reached");
        var usage=plan.usage().orElseThrow();long working=Math.min(sceneWorkingBytes,limits.workingBytes());var key=new Key(plan.extent(),plan.limits());var cached=entries.get(key);
        if(cached!=null) {
            if(cached.mesh==null)throw WorldProblem.fail(WorldProblem.Code.CACHE_BUSY,"This section already has an active output reservation");
            trim(null,working,cached);var ticket=new Ticket(this,cached);cached.pins++;leases++;return ticket;
        }
        trim(usage,working,null);var slot=new Slot(key,plan);var ticket=new Ticket(this,slot);
        try { entries.put(key,slot); }
        catch(OutOfMemoryError exhausted) { entries.remove(key);throw exhausted; }
        meshBytes+=usage.outputBytes();faces+=usage.faces();cellRecords+=usage.nonEmptyCells();markers+=usage.markers();leases++;return ticket;
    }
    private boolean fits(WorldMesh.Usage addition,long working) {
        return entries.size()+(addition==null?0:1)<=limits.sections()&&meshBytes+(addition==null?0:addition.outputBytes())<=limits.meshBytes()
            &&faces+(addition==null?0:addition.faces())<=limits.faces()&&cellRecords+(addition==null?0:addition.nonEmptyCells())<=limits.cellRecords()&&markers+(addition==null?0:addition.markers())<=limits.markers()
            &&sourceBytes+meshBytes+(addition==null?0:addition.outputBytes())<=working;
    }
    private void trim(WorldMesh.Usage addition,long working,Slot protectedSlot) {
        var iterator=entries.values().iterator();
        while(!fits(addition,working)) {
            boolean removed=false;
            while(iterator.hasNext()) { var slot=iterator.next();if(slot!=protectedSlot&&slot.pins==0) { subtract(slot);iterator.remove();removed=true;break; } }
            if(!removed)throw WorldProblem.fail(WorldProblem.Code.CACHE_BUSY,"Section cache is pinned or reserved at its limit; release scene leases before requesting more output");
        }
    }
    private void subtract(Slot slot) { var usage=slot.plan.usage().orElseThrow();meshBytes-=usage.outputBytes();faces-=usage.faces();cellRecords-=usage.nonEmptyCells();markers-=usage.markers(); }
    private void remove(Slot slot) { entries.remove(slot.key);subtract(slot); }
    private void release(Slot slot) {
        if(slot.pins<1||leases<1)throw new IllegalStateException("Invalid section lease accounting");slot.pins--;leases--;if(slot.pins==0&&(slot.mesh==null||closed))remove(slot);
        if(closed&&leases==0) { clearEntries();models=null;visibility=null;sourceBytes=0; }
    }
    private void clearEntries() { entries.clear();meshBytes=0;faces=0;cellRecords=0;markers=0; }
    /** Returns false while any output reservation or scene lease is still live. */
    public synchronized boolean clear() { if(leases!=0)return false;clearEntries();models=null;visibility=null;sourceBytes=0;return true; }
    /** Retires admissions immediately; still-leased entries stay charged until their last release. */
    @Override public synchronized void close() {
        closed=true;var iterator=entries.values().iterator();while(iterator.hasNext()) { var slot=iterator.next();if(slot.pins==0) { subtract(slot);iterator.remove(); } }
        if(leases==0) { clearEntries();models=null;visibility=null;sourceBytes=0; }
    }
    public synchronized Stats stats() {
        int pinned=0,reserved=0;for(var slot:entries.values()) { if(slot.pins>0)pinned++;if(slot.mesh==null)reserved++; }
        return new Stats(closed,entries.size(),pinned,reserved,leases,sourceBytes,meshBytes,faces,cellRecords,markers);
    }
}
