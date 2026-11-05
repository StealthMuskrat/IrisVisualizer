package dev.irisvisualizer.renderer;

import java.util.*;
import java.util.function.BooleanSupplier;

/** Bounded native/atlas epochs. Old leased scenes remain charged while a replacement is admitted. */
public final class WorldSectionCache implements AutoCloseable {
    public record Limits(int sections,int faces,int cellRecords,int markers,long meshBytes,long workingBytes) {
        public static final Limits DEFAULT=new Limits(8192,2097152,2097152,1048576,256L*1024*1024,512L*1024*1024);
        public Limits { if(sections<1||sections>65536||faces<0||faces>2097152||cellRecords<0||cellRecords>4194304||markers<0||markers>1048576||meshBytes<0||meshBytes>256L*1024*1024||workingBytes<0||workingBytes>512L*1024*1024)throw new IllegalArgumentException("Section cache limits exceed renderer bounds"); }
    }
    public record Stats(boolean closed,int sections,int leasedSections,int reservations,long leases,long sourcePayloadBytes,long meshBytes,int faces,int cellRecords,int markers,int epochs) {
        public long knownPayloadBytes() { return sourcePayloadBytes+meshBytes; }
    }
    private static final int MAX_EPOCHS=64;
    private static final class Epoch {
        final WorldModels models;final WorldVisibility visibility;final long sourceBytes;int slots;
        Epoch(WorldScenePlan plan) { models=plan.models();visibility=plan.visibility();sourceBytes=plan.usage().orElseThrow().sourcePayloadBytes(); }
    }
    private record Key(Epoch epoch,WorldSnapshot.Extent extent,WorldMesher.Limits limits) {}
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
    private final Limits limits;private final RenderMemory.Account admission;private final LinkedHashMap<Key,Slot> entries=new LinkedHashMap<>(16,0.75f,true);
    private List<Epoch> epochs=new ArrayList<>();private Epoch prepared;private Set<Key> preparedKeys=Set.of();private long sourceBytes,meshBytes,leases,preparedBytes;private int faces,cellRecords,markers;private boolean closed;
    public WorldSectionCache() { this(Limits.DEFAULT); }
    public WorldSectionCache(Limits limits) { this(limits,(RenderMemory.Account)null); }
    /** The cache exclusively owns retained-byte accounting; its host may set a covering job reservation. */
    public WorldSectionCache(Limits limits,RenderMemory.Account admission) { this.limits=Objects.requireNonNull(limits);this.admission=admission; }
    private void admit(long bytes) {
        if(admission==null)return;
        try { admission.retained(bytes); }
        catch(RenderMemory.LimitException failure) { throw WorldProblem.fail(WorldProblem.Code.CACHE_BUSY,failure.getMessage()); }
    }
    private void updateAdmission() { admit(sourceBytes+meshBytes+preparedBytes); }
    public Limits limits() { return limits; }
    /** Preflight complete replacement plus all pinned old data before changing cache admissions. */
    public synchronized void prepare(WorldScenePlan plan) { prepare(plan,()->false); }
    public synchronized void prepare(WorldScenePlan plan,BooleanSupplier cancelled) {
        Objects.requireNonNull(plan);Objects.requireNonNull(cancelled);WorldProblem.check(cancelled);if(closed)throw new IllegalStateException("Section cache is closed");if(plan.requiresPlaceholder())throw new WorldProblem.Failure(plan.problems().getFirst());var usage=plan.usage().orElseThrow();
        if(usage.sections()>limits.sections()||usage.faces()>limits.faces()||usage.cellRecords()>limits.cellRecords()||usage.markers()>limits.markers()||usage.meshBytes()>limits.meshBytes()||usage.knownPayloadBytes()>Math.min(limits.workingBytes(),plan.limits().workingBytes()))throw WorldProblem.fail(WorldProblem.Code.LIMIT,"The complete scene cannot fit the section cache; no partial scene was admitted");
        Epoch target=null;for(var epoch:epochs)if(epoch.models==plan.models()&&epoch.visibility==plan.visibility()) { target=epoch;break; }
        if(target==null)target=new Epoch(plan);else if(target.sourceBytes!=usage.sourcePayloadBytes())throw WorldProblem.fail(WorldProblem.Code.ASSIGNMENT,"Shared scene source accounting changed inside one epoch");
        var wanted=new HashSet<Key>();for(var section:plan.sections()) { WorldProblem.check(cancelled);if(!wanted.add(new Key(target,section.extent(),section.limits())))throw WorldProblem.fail(WorldProblem.Code.ASSIGNMENT,"Duplicate section in complete cache admission"); }
        int pinned=0,pinnedFaces=0,pinnedCells=0,pinnedMarkers=0,matchedPinned=0,resident=0,residentFaces=0,residentCells=0,residentMarkers=0;long pinnedBytes=0,matchedBytes=0,residentBytes=0;int matchedFaces=0,matchedCells=0,matchedMarkers=0;
        var protectedEpochs=Collections.newSetFromMap(new IdentityHashMap<Epoch,Boolean>());protectedEpochs.add(target);
        for(var slot:entries.values()) {
            WorldProblem.check(cancelled);var value=slot.plan.usage().orElseThrow();boolean match=wanted.contains(slot.key);
            if(match) { if(slot.mesh==null)throw WorldProblem.fail(WorldProblem.Code.CACHE_BUSY,"A requested section already has an active output reservation");resident++;residentBytes+=value.outputBytes();residentFaces+=value.faces();residentCells+=value.nonEmptyCells();residentMarkers+=value.markers(); }
            if(slot.pins==0)continue;pinned++;pinnedBytes+=value.outputBytes();pinnedFaces+=value.faces();pinnedCells+=value.nonEmptyCells();pinnedMarkers+=value.markers();protectedEpochs.add(slot.key.epoch());
            if(match) { matchedPinned++;matchedBytes+=value.outputBytes();matchedFaces+=value.faces();matchedCells+=value.nonEmptyCells();matchedMarkers+=value.markers(); }
        }
        long protectedSource=0;for(var epoch:protectedEpochs)protectedSource+=epoch.sourceBytes;long working=Math.min(limits.workingBytes(),plan.limits().workingBytes());
        if(protectedEpochs.size()>MAX_EPOCHS||pinned+usage.sections()-matchedPinned>limits.sections()||pinnedBytes+usage.meshBytes()-matchedBytes>limits.meshBytes()||pinnedFaces+usage.faces()-matchedFaces>limits.faces()||pinnedCells+usage.cellRecords()-matchedCells>limits.cellRecords()||pinnedMarkers+usage.markers()-matchedMarkers>limits.markers()||protectedSource+pinnedBytes+usage.meshBytes()-matchedBytes>working)
            throw WorldProblem.fail(WorldProblem.Code.CACHE_BUSY,"Complete replacement plus pinned prior scenes exceeds the shared cache budget; no old scene was evicted");
        if(leases+usage.sections()>65536)throw WorldProblem.fail(WorldProblem.Code.LIMIT,"Complete replacement would exceed the active section ticket limit");
        // Allocate all new metadata before committing admission or evicting unleased data.
        var nextEpochs=new ArrayList<Epoch>(epochs.size()+1);for(var epoch:epochs)if(epoch.slots>0||epoch==target)nextEpochs.add(epoch);if(!nextEpochs.contains(target))nextEpochs.add(target);
        var iterator=entries.values().iterator();WorldProblem.check(cancelled);
        int missing=usage.sections()-resident,missingFaces=usage.faces()-residentFaces,missingCells=usage.cellRecords()-residentCells,missingMarkers=usage.markers()-residentMarkers;long missingBytes=usage.meshBytes()-residentBytes;
        long nextSource=0;for(var epoch:nextEpochs)nextSource+=epoch.sourceBytes;
        // Shared admission precedes every cache mutation and the first section output allocation.
        // Existing unleased payload is conservatively included until eviction actually releases it.
        admit(nextSource+meshBytes+missingBytes);
        prepared=target;preparedKeys=wanted;epochs=nextEpochs;sourceBytes=nextSource;preparedBytes=missingBytes;
        while(!futureFits(missing,missingBytes,missingFaces,missingCells,missingMarkers,working)) {
            boolean removed=false;while(iterator.hasNext()) { var slot=iterator.next();if(slot.pins==0&&!wanted.contains(slot.key)) { subtract(slot);iterator.remove();removed=true;break; } }
            if(!removed)throw new IllegalStateException("Preflight and complete cache replacement admission disagree");
        }
    }
    /** Admission precedes output allocation. Cached sections and incomplete reservations use one key. */
    public synchronized Ticket reserve(WorldMeshPlan plan,long sceneWorkingBytes) {
        Objects.requireNonNull(plan);if(closed)throw new IllegalStateException("Section cache is closed");if(plan.requiresPlaceholder())throw new WorldProblem.Failure(plan.problems().getFirst());
        if(sceneWorkingBytes<0||sceneWorkingBytes>512L*1024*1024)throw new IllegalArgumentException("Invalid scene working budget");
        if(prepared==null||prepared.models!=plan.models()||prepared.visibility!=plan.visibility())throw WorldProblem.fail(WorldProblem.Code.CACHE_BUSY,"Section reservation does not belong to the prepared native scene epoch");
        if(leases>=65536)throw WorldProblem.fail(WorldProblem.Code.LIMIT,"Active section ticket count budget reached");
        var usage=plan.usage().orElseThrow();long working=Math.min(sceneWorkingBytes,limits.workingBytes());var key=new Key(prepared,plan.extent(),plan.limits());if(!preparedKeys.contains(key))throw WorldProblem.fail(WorldProblem.Code.ASSIGNMENT,"Section was not included in complete scene admission");var cached=entries.get(key);
        if(cached!=null) {
            if(cached.mesh==null)throw WorldProblem.fail(WorldProblem.Code.CACHE_BUSY,"This section already has an active output reservation");
            trim(null,working,cached);var ticket=new Ticket(this,cached);cached.pins++;leases++;return ticket;
        }
        trim(usage,working,null);var slot=new Slot(key,plan);var ticket=new Ticket(this,slot);
        try { entries.put(key,slot); }
        catch(RuntimeException|Error failure) { try { entries.remove(key); }catch(RuntimeException|Error rollback) { if(rollback!=failure)failure.addSuppressed(rollback); }throw failure; }
        prepared.slots++;preparedBytes-=usage.outputBytes();meshBytes+=usage.outputBytes();faces+=usage.faces();cellRecords+=usage.nonEmptyCells();markers+=usage.markers();leases++;updateAdmission();return ticket;
    }
    private boolean futureFits(int sections,long bytes,int faces,int cells,int markers,long working) { return epochs.size()<=MAX_EPOCHS&&entries.size()+sections<=limits.sections()&&meshBytes+bytes<=limits.meshBytes()&&this.faces+faces<=limits.faces()&&cellRecords+cells<=limits.cellRecords()&&this.markers+markers<=limits.markers()&&sourceBytes+meshBytes+bytes<=working; }
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
    private void subtract(Slot slot) {
        var usage=slot.plan.usage().orElseThrow();meshBytes-=usage.outputBytes();faces-=usage.faces();cellRecords-=usage.nonEmptyCells();markers-=usage.markers();var epoch=slot.key.epoch();
        if(epoch==prepared&&preparedKeys.contains(slot.key))preparedBytes+=usage.outputBytes();
        if(--epoch.slots==0&&epoch!=prepared) { epochs.remove(epoch);sourceBytes-=epoch.sourceBytes; }updateAdmission();
    }
    private void remove(Slot slot) { entries.remove(slot.key);subtract(slot); }
    private void release(Slot slot) {
        if(slot.pins<1||leases<1)throw new IllegalStateException("Invalid section lease accounting");slot.pins--;leases--;if(slot.pins==0&&(slot.mesh==null||closed))remove(slot);
        if(closed&&leases==0)clearEntries();
    }
    private void clearEntries() { entries.clear();epochs.clear();prepared=null;preparedKeys=Set.of();sourceBytes=0;meshBytes=0;preparedBytes=0;faces=0;cellRecords=0;markers=0;updateAdmission();if(closed&&admission!=null)admission.close(); }
    /** Returns false while any output reservation or scene lease is still live. */
    public synchronized boolean clear() { if(leases!=0)return false;clearEntries();return true; }
    /** Worker-owned epoch transition: discard prior admission and only unleased data before decoding. */
    synchronized void retireUnleased() {
        if(closed)throw new IllegalStateException("Section cache is closed");prepared=null;preparedKeys=Set.of();preparedBytes=0;var iterator=entries.values().iterator();
        while(iterator.hasNext()) { var slot=iterator.next();if(slot.pins==0) { subtract(slot);iterator.remove(); } }
        var unused=epochs.iterator();while(unused.hasNext()) { var epoch=unused.next();if(epoch.slots==0) { sourceBytes-=epoch.sourceBytes;unused.remove(); } }updateAdmission();
    }
    /** Retires admissions immediately; still-leased entries stay charged until their last release. */
    @Override public synchronized void close() {
        closed=true;prepared=null;preparedKeys=Set.of();preparedBytes=0;var iterator=entries.values().iterator();while(iterator.hasNext()) { var slot=iterator.next();if(slot.pins==0) { subtract(slot);iterator.remove(); } }
        var epochIterator=epochs.iterator();while(epochIterator.hasNext()) { var epoch=epochIterator.next();if(epoch.slots==0) { sourceBytes-=epoch.sourceBytes;epochIterator.remove(); } }
        if(leases==0)clearEntries();else { updateAdmission();if(admission!=null)admission.close(); }
    }
    public synchronized Stats stats() {
        int pinned=0,reserved=0;for(var slot:entries.values()) { if(slot.pins>0)pinned++;if(slot.mesh==null)reserved++; }
        return new Stats(closed,entries.size(),pinned,reserved,leases,sourceBytes,meshBytes,faces,cellRecords,markers,epochs.size());
    }
}
