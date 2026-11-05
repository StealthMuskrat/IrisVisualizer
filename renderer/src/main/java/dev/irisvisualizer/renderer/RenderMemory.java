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
    private static final class Holding {
        final RenderMemory memory;final Account account;int references=1;
        Holding(RenderMemory memory,Account account) { this.memory=memory;this.account=account; }
    }
    /** Independent owner of one shared admission. Retain before sharing ownership; close after actual release. */
    public static final class Hold implements AutoCloseable {
        private static final Hold NONE=new Hold(null);private final Holding holding;private boolean closed;
        private Hold(Holding holding) { this.holding=holding; }
        public static Hold none() { return NONE; }
        public boolean tracked() { return holding!=null; }
        /** Untracked compatibility handles may be used with any coordinator. */
        public boolean belongsTo(RenderMemory memory) { return holding==null||holding.memory==memory; }
        public long bytes() { if(holding==null)return 0;synchronized(holding.memory) { return closed?0:Math.max(holding.account.retained,holding.account.reserved); } }
        public Hold retain() {
            if(holding==null)return NONE;
            synchronized(holding.memory) {
                if(closed)throw new IllegalStateException("Renderer memory hold is closed");
                if(holding.references>=65536)throw new LimitException("Renderer memory hold owner count reached");
                var next=new Hold(holding);holding.references++;return next;
            }
        }
        /** Call only after temporary work has unwound; cannot acquire additional bytes. */
        public void shrinkTo(long bytes) {
            if(bytes<0)throw new IllegalArgumentException("Negative retained renderer payload");if(holding==null)return;
            synchronized(holding.memory) {
                if(closed)return;long before=Math.max(holding.account.retained,holding.account.reserved);
                if(bytes>before)throw new IllegalArgumentException("A renderer memory hold cannot grow");
                holding.memory.update(holding.account,bytes,0);
            }
        }
        @Override public void close() {
            if(holding==null)return;
            synchronized(holding.memory) {
                if(closed)return;closed=true;
                if(--holding.references==0) { holding.memory.update(holding.account,0,0);holding.account.close(); }
            }
        }
    }
    /** Reserve before starting work. A failed reservation leaves no empty account behind. */
    public synchronized Hold hold(String label,long bytes) {
        var account=open(label);
        try { var hold=new Hold(new Holding(this,account));update(account,0,bytes);return hold; }
        catch(RuntimeException|Error failure) { try { account.close(); }catch(RuntimeException|Error rollback) { if(rollback!=failure)failure.addSuppressed(rollback); }throw failure; }
    }
    /** Atomically replaces idle, caller-owned holds; active/shared owners never supply release credit. */
    public synchronized Hold holdReplacing(String label,long bytes,Hold... replaced) {
        Objects.requireNonNull(label);Objects.requireNonNull(replaced);
        if(label.isBlank()||label.length()>128||replaced.length>256)throw new IllegalArgumentException("Unbounded renderer hold replacement metadata");
        if(closed)throw new IllegalStateException("Shared renderer admission is closed");
        if(bytes<0||bytes>limits.bytes())throw new LimitException("Shared renderer memory demand exceeds its bound");
        var handles=Collections.newSetFromMap(new IdentityHashMap<Hold,Boolean>());var references=new IdentityHashMap<Holding,Integer>();
        for(var hold:replaced) {
            Objects.requireNonNull(hold);if(hold.holding==null)continue;
            if(hold.holding.memory!=this)throw new IllegalArgumentException("Cannot replace a hold from another renderer coordinator");
            if(!hold.closed&&handles.add(hold))references.merge(hold.holding,1,Integer::sum);
        }
        long credit=0;int released=0;
        for(var entry:references.entrySet())if(entry.getValue()==entry.getKey().references) {
            var account=entry.getKey().account;credit+=Math.max(account.retained,account.reserved);released++;
        }
        if(bytes-credit>limits.bytes()-charged||owners.size()+1-released>limits.accounts())throw new LimitException("Replacement renderer hold exceeds shared bytes or account allowance");
        var account=new Account(label);var result=new Hold(new Holding(this,account));var iterator=handles.iterator();
        try { owners.add(account); }catch(RuntimeException|Error failure) { try { owners.remove(account); }catch(RuntimeException|Error rollback) { if(rollback!=failure)failure.addSuppressed(rollback); }throw failure; }
        // All allocations and rejection checks precede consuming the idle handles.
        while(iterator.hasNext())iterator.next().close();account.reserved=bytes;charged+=bytes;return result;
    }
    private final Limits limits;private final Set<Account> owners=Collections.newSetFromMap(new IdentityHashMap<>());private long charged;private boolean closed;
    public RenderMemory() { this(Limits.DEFAULT); }
    public RenderMemory(Limits limits) { this.limits=Objects.requireNonNull(limits); }
    public Limits limits() { return limits; }
    public synchronized Account open(String label) {
        Objects.requireNonNull(label);if(label.isBlank()||label.length()>128)throw new IllegalArgumentException("A bounded renderer memory owner label is required");
        if(closed)throw new IllegalStateException("Shared renderer admission is closed");if(owners.size()>=limits.accounts())throw new LimitException("Shared renderer memory account limit reached");
        var owner=new Account(label);try { owners.add(owner); }catch(RuntimeException|Error failure) { try { owners.remove(owner); }catch(RuntimeException|Error rollback) { if(rollback!=failure)failure.addSuppressed(rollback); }throw failure; }return owner;
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
