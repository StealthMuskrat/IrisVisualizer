package dev.irisvisualizer.renderer;

import java.util.*;

/** Shared known-payload admission. Reservations cover retained payload within one exclusive account. */
public final class RenderMemory implements AutoCloseable {
    public record Limits(long bytes,int accounts) {
        public static final Limits DEFAULT=new Limits(1024L*1024*1024,64);
        public Limits { if(bytes<0||bytes>1024L*1024*1024||accounts<1||accounts>256)throw new IllegalArgumentException("Shared renderer memory limits exceed their bounds"); }
    }
    public record Owner(String label,long retainedBytes,long reservedBytes,boolean retiring) {
        public long chargedBytes() { return Math.max(retainedBytes,reservedBytes); }
    }
    public record Stats(boolean closed,long chargedBytes,long retainedBytes,long reservedBytes,int accounts,long limitBytes) {}
    public static final class LimitException extends IllegalStateException {
        private LimitException(String message) { super(message); }
    }
    /** One subsystem owns retained accounting and its covering reservation. No source graph is retained. */
    public final class Account implements AutoCloseable {
        private final String label;private long retained,reserved;private boolean retiring,released;
        private Account(String label) { this.label=label; }
        public void retained(long bytes) { synchronized(RenderMemory.this) { update(this,bytes,reserved); } }
        public void reserve(long bytes) { synchronized(RenderMemory.this) { update(this,retained,bytes); } }
        /** Atomically changes both values; rejection preserves this account and every other account. */
        public void set(long retainedBytes,long reservedBytes) { synchronized(RenderMemory.this) { update(this,retainedBytes,reservedBytes); } }
        public Owner stats() { synchronized(RenderMemory.this) { return new Owner(label,retained,reserved,retiring); } }
        /** Seal growth beyond existing charge; live retained/reserved bytes remain charged until released. */
        @Override public void close() { synchronized(RenderMemory.this) { if(released)return;retiring=true;releaseEmpty(this); } }
    }
    private final Limits limits;private final Set<Account> owners=Collections.newSetFromMap(new IdentityHashMap<>());private long charged;private boolean closed;
    public RenderMemory() { this(Limits.DEFAULT); }
    public RenderMemory(Limits limits) { this.limits=Objects.requireNonNull(limits); }
    public Limits limits() { return limits; }
    public synchronized Account open(String label) {
        Objects.requireNonNull(label);if(label.isBlank()||label.length()>128)throw new IllegalArgumentException("A bounded renderer memory owner label is required");
        if(closed)throw new IllegalStateException("Shared renderer admission is closed");if(owners.size()>=limits.accounts())throw new LimitException("Shared renderer memory account limit reached");
        var owner=new Account(label);owners.add(owner);return owner;
    }
    private void update(Account owner,long retained,long reserved) {
        if(retained<0||reserved<0||retained>limits.bytes()||reserved>limits.bytes())throw new LimitException("Shared renderer memory demand exceeds its bound");
        if(owner.released) { if(retained==0&&reserved==0)return;throw new IllegalStateException("Renderer memory account is released"); }
        long before=Math.max(owner.retained,owner.reserved),after=Math.max(retained,reserved),growth=after-before;
        if(growth>0&&(closed||owner.retiring))throw new IllegalStateException("Renderer memory admission is retiring");
        if(growth>limits.bytes()-charged)throw new LimitException("Combined renderer CPU/GPU reservations exceed the shared allowance");
        charged+=growth;owner.retained=retained;owner.reserved=reserved;releaseEmpty(owner);
    }
    private void releaseEmpty(Account owner) { if(owner.retiring&&owner.retained==0&&owner.reserved==0) { owners.remove(owner);owner.released=true; } }
    public synchronized Stats stats() { long retained=0,reserved=0;for(var owner:owners) { retained+=owner.retained;reserved+=owner.reserved; }return new Stats(closed,charged,retained,reserved,owners.size(),limits.bytes()); }
    public synchronized List<Owner> owners() { var result=new ArrayList<Owner>(owners.size());for(var owner:owners)result.add(new Owner(owner.label,owner.retained,owner.reserved,owner.retiring));result.sort(Comparator.comparing(Owner::label));return List.copyOf(result); }
    /** Stop new admission without pretending live work or failed cleanup has released its memory. */
    @Override public synchronized void close() { if(closed)return;closed=true;var iterator=owners.iterator();while(iterator.hasNext()) { var owner=iterator.next();owner.retiring=true;if(owner.retained==0&&owner.reserved==0) { owner.released=true;iterator.remove(); } } }
}
