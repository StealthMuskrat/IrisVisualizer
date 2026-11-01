package dev.irisvisualizer.editor;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** UI-owned operation authority with one background typed-reference plan and latest-pending replacement. */
final class ReviewedResourceOperation implements AutoCloseable {
    enum Phase {IDLE,PREPARING,READY,BLOCKED,FAILED,STALE,APPLIED,CLOSED}
    record Snapshot(long request,Phase phase,ResourceOperation.Report report,String detail){}
    @FunctionalInterface interface Preparer {ResourceOperation.Prepared prepare(ResourceOperation.Source source,BooleanSupplier cancelled)throws Exception;}
    private record Job(long request,ResourceOperation.Source source,AtomicBoolean cancelled){}
    static final class Acknowledgement {private final ReviewedResourceOperation authority;private final PackWorkspace owner;private final long request,revision;private Acknowledgement(ReviewedResourceOperation authority,PackWorkspace owner,long request){this.authority=authority;this.owner=owner;this.request=request;revision=owner.revision();}}
    private final Object lock=new Object();private final Preparer preparer;private final Thread worker;private PackWorkspace owner;private long revision,saved,generation;private boolean closed;private Job active,pending;private ResourceOperation.Prepared prepared;private Snapshot snapshot=new Snapshot(0,Phase.IDLE,null,null);
    ReviewedResourceOperation(){this(ResourceOperation::prepare,r->new Thread(r,"iris-resource-operation"));}
    ReviewedResourceOperation(Preparer preparer,ThreadFactory threads){this.preparer=Objects.requireNonNull(preparer);worker=Objects.requireNonNull(threads).newThread(this::run);if(worker==null)throw new IllegalArgumentException("Resource operation worker factory returned no thread");worker.setDaemon(true);worker.start();}
    private void invalidate(){generation++;if(active!=null)active.cancelled().set(true);if(pending!=null)pending.cancelled().set(true);pending=null;prepared=null;}
    Snapshot start(PackWorkspace current,ResourceOperation.Mode mode,String path,String value)throws IOException{
        Objects.requireNonNull(current);synchronized(lock){if(closed)throw new IllegalStateException("Resource operation review is closed");}ResourceOperation.Source source;try{source=current.captureResourceOperation(mode,path,value);}catch(OutOfMemoryError exhausted){throw new IOException("Not enough memory to capture resource reference metadata; clear and retry explicitly.");}
        synchronized(lock){if(closed)throw new IllegalStateException("Resource operation review is closed");invalidate();owner=current;revision=current.revision();saved=current.saveRevision();pending=new Job(generation,source,new AtomicBoolean());snapshot=new Snapshot(generation,Phase.PREPARING,null,null);lock.notifyAll();return snapshot;}
    }
    Snapshot poll(PackWorkspace current){Objects.requireNonNull(current);synchronized(lock){if(!closed && owner!=null && (owner!=current || revision!=current.revision() || saved!=current.saveRevision())){invalidate();owner=null;snapshot=new Snapshot(generation,Phase.STALE,null,"Workspace bytes, paths or saved baseline changed; prepare the resource operation again.");}return snapshot;}}
    Acknowledgement acknowledge(PackWorkspace current,long request)throws IOException{var state=poll(current);if(state.request()!=request || state.phase()!=Phase.READY)throw new IOException("Review the current permitted operation first");return new Acknowledgement(this,current,request);}
    boolean acknowledged(PackWorkspace current,long request,Acknowledgement value){return value!=null && value.authority==this && value.owner==current && value.request==request && value.revision==current.revision();}
    String apply(PackWorkspace current,long request,Acknowledgement acknowledgement)throws IOException{
        var state=poll(current);if(state.phase()!=Phase.READY || state.request()!=request || prepared==null || !prepared.available() || !prepared.report.permitted())throw new IOException("No current reference-safe resource operation is ready");if(prepared.report.plan().changes() && current.undoCount()+current.redoCount()>0 && !acknowledged(current,request,acknowledgement))throw new IOException("Acknowledge clearing current applied-edit history first");
        String target=current.installResourceOperation(prepared);var report=prepared.report;synchronized(lock){invalidate();owner=null;snapshot=new Snapshot(generation,Phase.APPLIED,report,"Resource operation applied in memory. Save remains explicit.");}return target;
    }
    void clear(){synchronized(lock){if(closed)return;invalidate();owner=null;snapshot=new Snapshot(generation,Phase.IDLE,null,null);}worker.interrupt();}
    private void run(){while(true){Job job;synchronized(lock){Thread.interrupted();while(!closed && pending==null)try{lock.wait();}catch(InterruptedException interrupted){if(closed)return;}if(closed)return;active=job=pending;pending=null;}ResourceOperation.Prepared result=null;String problem=null;
        try{result=Objects.requireNonNull(preparer.prepare(job.source(),job.cancelled()::get),"Resource operation preparer returned no plan");if(result.origin!=job.source())throw new IOException("Resource operation plan belongs to another capture");}catch(CancellationException cancelled){problem="Resource operation cancelled; prepare again explicitly.";}catch(OutOfMemoryError exhausted){problem="Not enough memory to prepare this resource operation; clear and retry explicitly.";}catch(Exception failure){problem=InspectorText.label(Objects.toString(failure.getMessage(),failure.getClass().getSimpleName()),512);}
        synchronized(lock){active=null;if(!closed && !job.cancelled().get() && job.request()==generation){prepared=problem==null?result:null;var report=problem==null && result!=null?result.report:null;snapshot=new Snapshot(generation,problem!=null?Phase.FAILED:report.permitted()?Phase.READY:Phase.BLOCKED,report,problem);}}job=null;result=null;
    }}
    @Override public void close(){synchronized(lock){if(closed)return;closed=true;invalidate();owner=null;snapshot=new Snapshot(generation,Phase.CLOSED,null,null);lock.notifyAll();}worker.interrupt();}
}
