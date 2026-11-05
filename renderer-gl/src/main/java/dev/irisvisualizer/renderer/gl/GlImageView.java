package dev.irisvisualizer.renderer.gl;

import dev.irisvisualizer.renderer.*;
import java.util.*;

/** One original-image decode/upload pipeline and independently owned display frames. */
public final class GlImageView implements AutoCloseable {
    public enum Phase { IDLE, WAITING_CPU, DECODING, UPLOADING, WAITING_VIEW, FRAME_READY, DELIVERED, FAILED, CLOSED }
    public record Status(long imageRevision,long viewRevision,Phase phase,Optional<SourceImage.Identity> source,List<SourceImage.Problem> problems,Optional<GlFailure.Code> code,String reason) {
        public Status { Objects.requireNonNull(phase);Objects.requireNonNull(source);problems=List.copyOf(problems);Objects.requireNonNull(code);Objects.requireNonNull(reason); }
    }
    private final GlMapRenderer renderer;private final GlDevice device;private final ImageDecodeWorker worker;private final GlImageQueue uploads;
    private GlImage image;private GlImageFrame completed;private int imageWidth,imageHeight;private MapView view;private SourceImage.Receipt latestReceipt;private int width,height;private long imageRevision,viewRevision,cpuRevision;private boolean sourceReady,dirty,closed,disposed;
    private volatile Status status=new Status(0,0,Phase.IDLE,Optional.empty(),List.of(),Optional.empty(),"");
    public GlImageView(GlMapRenderer renderer) { this(renderer,256L*1024*1024); }
    public GlImageView(GlMapRenderer renderer,long workerBytes) { this.renderer=Objects.requireNonNull(renderer);device=renderer.device;device.live(renderer.allocation);uploads=new GlImageQueue(device);worker=new ImageDecodeWorker(workerBytes,device.memory().orElse(null)); }
    public Status status() { return status; }
    public ImageDecodeWorker.Status workerStatus() { return worker.status(); }
    public long reservedCpuBytes() { return worker.reservedBytes(); }
    public GlImageQueue.Status uploadStatus() { return uploads.status(); }
    private void current() { device.current();if(closed)throw new GlFailure(GlFailure.Code.STATE,"Image view is closed"); }
    private long nextImage() { if(imageRevision==Long.MAX_VALUE)throw new GlFailure(GlFailure.Code.LIMIT,"Image revision counter exhausted");return imageRevision+1; }
    private long nextView() { if(viewRevision==Long.MAX_VALUE)throw new GlFailure(GlFailure.Code.LIMIT,"Image view revision counter exhausted");return viewRevision+1; }
    private void state(Phase phase) { status=new Status(imageRevision,viewRevision,phase,status.source(),List.of(),Optional.empty(),""); }
    /** A prior image retained for cleanup cannot satisfy a newer source revision. */
    private Status displayStatus(long next) {
        boolean sourceFailure=!sourceReady&&status.phase()==Phase.FAILED;
        return new Status(imageRevision,next,sourceReady?Phase.WAITING_VIEW:status.phase(),status.source(),sourceFailure?status.problems():List.of(),sourceFailure?status.code():Optional.empty(),sourceFailure?status.reason():"");
    }
    private void failed(Throwable error) {
        try {
            String reason=Objects.toString(error.getMessage(),error.getClass().getSimpleName());if(reason.length()>8192)reason=reason.substring(0,8192);
            status=new Status(imageRevision,viewRevision,Phase.FAILED,status.source(),List.of(),Optional.of(error instanceof GlFailure gpu?gpu.code():error instanceof OutOfMemoryError?GlFailure.Code.MEMORY:GlFailure.Code.STATE),reason);
        }catch(RuntimeException|Error publication) { if(publication!=error)error.addSuppressed(publication); }
    }
    /** Invalidate immediately when resource inputs change, before replacement bytes arrive. */
    public long invalidate() {
        current();long next=nextImage(),nextView=nextView();var waiting=new Status(next,nextView,Phase.WAITING_CPU,Optional.empty(),List.of(),Optional.empty(),"");
        worker.invalidate();imageRevision=next;viewRevision=nextView;cpuRevision=0;sourceReady=false;dirty=false;latestReceipt=null;imageWidth=0;imageHeight=0;view=null;status=waiting;
        try { uploads.invalidate(imageRevision);releaseDisplay();return imageRevision; }catch(RuntimeException|Error failure) { failed(failure);throw failure; }
    }
    /** Worker admission occurs first; a budget rejection leaves the current view, tokens and work intact. */
    public long submit(ImageDecodeWorker.Request request) {
        Objects.requireNonNull(request);current();long next=nextImage(),nextView=nextView();var queued=new Status(next,nextView,Phase.DECODING,Optional.of(request.source().identity()),List.of(),Optional.empty(),"");
        long cpu=worker.submit(request);imageRevision=next;viewRevision=nextView;cpuRevision=cpu;sourceReady=false;dirty=false;latestReceipt=null;status=queued;imageWidth=0;imageHeight=0;
        view=null;
        try { uploads.invalidate(imageRevision);releaseDisplay();return imageRevision; }catch(RuntimeException|Error failure) { failed(failure);throw failure; }
    }
    /** Consumes an admitted image input; CPU admission precedes releasing its source hold. */
    public long submit(RenderInput<ImageDecodeWorker.Request> input) {
        Objects.requireNonNull(input);
        try(var source=input.retain()) {
            current();var memory=device.memory().orElse(null);
            if(source.admission().tracked()&&(memory==null||!source.admission().belongsTo(memory)))throw new IllegalArgumentException("Tracked image input requires the same device coordinator");
            return submit(source.value());
        }finally { input.close(); }
    }
    public long submit(SourceImage.Encoded source) { return submit(new ImageDecodeWorker.Request(source)); }
    private void dimensions(int width,int height) { GlTarget.validate(device,width,height); }
    /** Display-only framing; original dimensions and pixels are never changed. */
    public long view(MapView view,int width,int height) {
        Objects.requireNonNull(view);current();dimensions(width,height);if(imageWidth==0||view.width()!=imageWidth||view.height()!=imageHeight)throw new GlFailure(GlFailure.Code.STALE,"Image view dimensions must match the decoded original image");
        if(view.equals(this.view)&&width==this.width&&height==this.height)return viewRevision;long next=nextView();var changed=displayStatus(next);
        this.view=view;this.width=width;this.height=height;viewRevision=next;dirty=true;status=changed;
        try { releaseFrame();return viewRevision; }catch(RuntimeException|Error failure) { failed(failure);throw failure; }
    }
    public long fit(int width,int height) { current();dimensions(width,height);if(imageWidth==0)throw new GlFailure(GlFailure.Code.STATE,"Decode an original image before fitting its view");return view(MapView.fit(imageWidth,imageHeight,0,0,width,height),width,height); }
    public long pan(double dx,double dy) { current();if(view==null)throw new GlFailure(GlFailure.Code.STATE,"Configure a image viewport before panning");return view(view.pan(dx,dy),width,height); }
    public long zoomAt(double factor,double x,double y) { current();if(view==null)throw new GlFailure(GlFailure.Code.STATE,"Configure a image viewport before zooming");return view(view.zoomAt(factor,x,y),width,height); }
    /** Retry drawing the existing complete image after a display failure. CPU/upload failures require resubmission. */
    public long redraw() {
        current();if(!sourceReady||image==null||view==null)throw new GlFailure(GlFailure.Code.STATE,"A complete current image upload and configured viewport are required to redraw");long next=nextView();var waiting=displayStatus(next);viewRevision=next;dirty=true;status=waiting;
        try { releaseFrame();return viewRevision; }catch(RuntimeException|Error failure) { failed(failure);throw failure; }
    }
    /** Retry GPU work from the exact decoded content receipt, preserving the source token and navigation. */
    public boolean retryUpload(long expectedImageRevision,SourceImage source) {
        Objects.requireNonNull(source);current();if(expectedImageRevision<1)throw new IllegalArgumentException("Invalid image revision");
        if(expectedImageRevision!=imageRevision||sourceReady||status.phase()!=Phase.FAILED)return false;
        if(source.requiresPlaceholder()||latestReceipt==null||!source.receipt().orElseThrow().equals(latestReceipt))throw new GlFailure(GlFailure.Code.STALE,"Retry requires the exact latest successful decoded image receipt");
        GlImageUpload.validate(device,source);
        long next=nextView();var queued=new Status(imageRevision,next,Phase.UPLOADING,status.source(),List.of(),Optional.empty(),"");
        worker.invalidate();cpuRevision=0;viewRevision=next;dirty=false;
        try {
            releaseDisplay();if(uploads.status().revision()<imageRevision)uploads.invalidate(imageRevision);
            if(!uploads.restart(imageRevision)||!uploads.submit(imageRevision,source))throw new GlFailure(GlFailure.Code.STALE,"Image upload queue no longer matches the retry revision");
            status=queued;return true;
        }catch(RuntimeException|Error failure) { failed(failure);throw failure; }
    }
    /** Consumes the supplied decoded CPU owner through admission, including rejection/failure. */
    public boolean retryUpload(long expectedImageRevision,RenderInput<SourceImage> input) {
        Objects.requireNonNull(input);
        try(var source=input.retain()) {
            current();var memory=device.memory().orElse(null);if(source.admission().tracked()&&(memory==null||!source.admission().belongsTo(memory)))throw new IllegalArgumentException("Tracked image retry requires the same device coordinator");
            return retryUpload(expectedImageRevision,source.value());
        }finally { input.close(); }
    }
    /** Decoding is off-thread; upload is bounded and one complete frame draw is synchronous. */
    public Status advance(GlUploadBudget budget,long expectedImageRevision) {
        Objects.requireNonNull(budget);current();if(expectedImageRevision<1)throw new IllegalArgumentException("Invalid expected image revision");if(expectedImageRevision!=imageRevision||status.phase()==Phase.FAILED)return status;
        try {
            if(cpuRevision!=0) {
                var result=worker.take(cpuRevision);
                if(result.isPresent())try(var prepared=result.orElseThrow()) {
                    latestReceipt=prepared.image().receipt().orElseThrow();
                    if(!uploads.submit(imageRevision,prepared.image()))throw new GlFailure(GlFailure.Code.STALE,"Image upload rejected the current CPU result");cpuRevision=0;state(Phase.UPLOADING);
                } else {
                    var cpu=worker.status();if(cpu.revision()!=cpuRevision||cpu.phase()==ImageDecodeWorker.Phase.CLOSED)throw new GlFailure(GlFailure.Code.STALE,"Image worker revision changed before CPU adoption");
                    if(cpu.phase()==ImageDecodeWorker.Phase.FAILED)status=new Status(imageRevision,viewRevision,Phase.FAILED,status.source(),cpu.problems(),Optional.empty(),"Image decoding failed");return status;
                }
            }
            if(status.phase()==Phase.UPLOADING) { uploads.advance(budget,imageRevision);var result=uploads.take(imageRevision);if(result.isPresent()) { image=result.orElseThrow().image();imageWidth=image.width();imageHeight=image.height();if(view!=null&&(view.width()!=imageWidth||view.height()!=imageHeight))view=null;sourceReady=true;dirty=true;state(Phase.WAITING_VIEW); } }
            if(!sourceReady||image==null||view==null||!dirty)return status;
            if(completed!=null)throw new GlFailure(GlFailure.Code.STATE,"An untaken image frame survived view invalidation");
            var frame=GlImageFrame.render(renderer,viewRevision,image,view,width,height,()->false);
            try { var ready=new Status(imageRevision,viewRevision,Phase.FRAME_READY,status.source(),List.of(),Optional.empty(),"");completed=frame;dirty=false;status=ready; }
            catch(RuntimeException|Error failure) { try { frame.close(); }catch(RuntimeException|Error cleanup) { if(cleanup!=failure)failure.addSuppressed(cleanup); }throw failure; }
            return status;
        }catch(RuntimeException|Error failure) { failed(failure);throw failure; }
    }
    public Optional<GlImageFrame> take(long expectedImageRevision,long expectedViewRevision) {
        current();if(!sourceReady||expectedImageRevision!=imageRevision||expectedViewRevision!=viewRevision||status.phase()!=Phase.FRAME_READY||completed==null||completed.info().revision()!=viewRevision)return Optional.empty();var result=Optional.of(completed);var delivered=new Status(imageRevision,viewRevision,Phase.DELIVERED,status.source(),List.of(),Optional.empty(),"");completed=null;status=delivered;return result;
    }
    private void releaseFrame() { if(completed!=null)try { completed.close();completed=null; }catch(RuntimeException|Error error) { if(completed.closed())completed=null;throw error; } }
    private void releaseDisplay() {
        Throwable failure=null;try { releaseFrame(); }catch(RuntimeException|Error error) { failure=error; }
        if(image!=null)try { image.close();image=null; }catch(RuntimeException|Error error) { if(image.closed())image=null;if(failure==null)failure=error;else if(failure!=error)failure.addSuppressed(error); }
        if(failure instanceof RuntimeException runtime)throw runtime;if(failure instanceof Error error)throw error;
    }
    /** Leaves the borrowed renderer/device and caller-transferred frames owned by their callers. */
    @Override public void close() {
        if(disposed)return;device.current();closed=true;sourceReady=false;latestReceipt=null;Throwable failure=null;
        try { worker.close(); }catch(RuntimeException|Error error) { failure=error; }
        try { uploads.close(); }catch(RuntimeException|Error error) { if(failure==null)failure=error;else if(failure!=error)failure.addSuppressed(error); }
        try { releaseDisplay(); }catch(RuntimeException|Error error) { if(failure==null)failure=error;else if(failure!=error)failure.addSuppressed(error); }
        try { status=new Status(imageRevision,viewRevision,Phase.CLOSED,Optional.empty(),List.of(),Optional.empty(),""); }
        catch(RuntimeException|Error error) { if(failure==null)failure=error;else if(failure!=error)failure.addSuppressed(error); }disposed=failure==null;
        if(failure instanceof RuntimeException runtime)throw runtime;if(failure instanceof Error error)throw error;
    }
}
