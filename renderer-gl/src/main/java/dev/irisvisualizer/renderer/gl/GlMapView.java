package dev.irisvisualizer.renderer.gl;

import dev.irisvisualizer.renderer.*;
import java.util.*;

/** One native-map colorization/upload pipeline and independently owned display frames. */
public final class GlMapView implements AutoCloseable {
    public enum Phase { IDLE, WAITING_CPU, COLORIZING, RECOLORING, UPLOADING, WAITING_VIEW, FRAME_READY, DELIVERED, FAILED, CLOSED }
    public record Status(long mapRevision,long viewRevision,Phase phase,Optional<MapSamples.Context> context,List<WorldProblem> problems,Optional<GlFailure.Code> code,String reason) {
        public Status { Objects.requireNonNull(phase);Objects.requireNonNull(context);problems=List.copyOf(problems);Objects.requireNonNull(code);Objects.requireNonNull(reason); }
    }
    private final GlMapRenderer renderer;private final GlDevice device;private final MapRasterWorker worker;private final GlMapQueue uploads;
    private GlMap map;private GlMapFrame completed;private MapSamples.Grid grid;private MapView view;private UUID latestSamples;private MapRaster.Style latestStyle;private MapRaster.Limits latestLimits;private int width,height;private long mapRevision,viewRevision,cpuRevision;private boolean sourceReady,dirty,closed,disposed;
    private volatile Status status=new Status(0,0,Phase.IDLE,Optional.empty(),List.of(),Optional.empty(),"");
    public GlMapView(GlMapRenderer renderer) { this(renderer,128L*1024*1024); }
    public GlMapView(GlMapRenderer renderer,long workerBytes) { this.renderer=Objects.requireNonNull(renderer);device=renderer.device;device.live(renderer.allocation);uploads=new GlMapQueue(device);worker=new MapRasterWorker(workerBytes,device.memory().orElse(null)); }
    public Status status() { return status; }
    public MapRasterWorker.Status workerStatus() { return worker.status(); }
    public long reservedCpuBytes() { return worker.reservedBytes(); }
    public GlMapQueue.Status uploadStatus() { return uploads.status(); }
    private void current() { device.current();if(closed)throw new GlFailure(GlFailure.Code.STATE,"Map view is closed"); }
    private long nextMap() { if(mapRevision==Long.MAX_VALUE)throw new GlFailure(GlFailure.Code.LIMIT,"Map revision counter exhausted");return mapRevision+1; }
    private long nextView() { if(viewRevision==Long.MAX_VALUE)throw new GlFailure(GlFailure.Code.LIMIT,"Map view revision counter exhausted");return viewRevision+1; }
    private void state(Phase phase) { status=new Status(mapRevision,viewRevision,phase,status.context(),List.of(),Optional.empty(),""); }
    /** Preserve upstream diagnostics even when a prior map owner remains for cleanup. */
    private Status displayStatus(long next) {
        boolean sourceFailure=!sourceReady&&status.phase()==Phase.FAILED;
        return new Status(mapRevision,next,sourceReady?Phase.WAITING_VIEW:status.phase(),status.context(),sourceFailure?status.problems():List.of(),sourceFailure?status.code():Optional.empty(),sourceFailure?status.reason():"");
    }
    private void failed(Throwable error) {
        try {
            String reason=Objects.toString(error.getMessage(),error.getClass().getSimpleName());if(reason.length()>8192)reason=reason.substring(0,8192);
            status=new Status(mapRevision,viewRevision,Phase.FAILED,status.context(),List.of(),Optional.of(error instanceof GlFailure gpu?gpu.code():error instanceof OutOfMemoryError?GlFailure.Code.MEMORY:GlFailure.Code.STATE),reason);
        }catch(RuntimeException|Error publication) { if(publication!=error)error.addSuppressed(publication); }
    }
    /** Invalidate immediately when native/resource inputs change, before replacement samples arrive. */
    public long invalidate() {
        current();long next=nextMap(),nextView=nextView();var waiting=new Status(next,nextView,Phase.WAITING_CPU,Optional.empty(),List.of(),Optional.empty(),"");
        worker.invalidate();mapRevision=next;viewRevision=nextView;cpuRevision=0;sourceReady=false;dirty=false;latestSamples=null;latestStyle=null;latestLimits=null;status=waiting;
        try { uploads.invalidate(mapRevision);releaseDisplay();return mapRevision; }catch(RuntimeException|Error failure) { failed(failure);throw failure; }
    }
    /** Capability preflight and worker admission precede changes to the current view, tokens and work. */
    public long submit(MapRasterWorker.Request request) {
        Objects.requireNonNull(request);current();var requestedGrid=request.samples().grid();GlMapUpload.dimensions(device,requestedGrid.width(),requestedGrid.height());long next=nextMap(),nextView=nextView();var queued=new Status(next,nextView,request.source().isPresent()?Phase.RECOLORING:Phase.COLORIZING,Optional.of(request.samples().context()),List.of(),Optional.empty(),"");
        long cpu=worker.submit(request);mapRevision=next;viewRevision=nextView;cpuRevision=cpu;sourceReady=false;dirty=false;latestSamples=request.samples().id();latestStyle=request.style();latestLimits=request.limits();status=queued;grid=request.samples().grid();
        if(view!=null&&(view.width()!=grid.width()||view.height()!=grid.height()))view=null;
        try { uploads.invalidate(mapRevision);releaseDisplay();return mapRevision; }catch(RuntimeException|Error failure) { failed(failure);throw failure; }
    }
    /** Consumes an admitted native map input; CPU admission precedes releasing its source hold. */
    public long submit(RenderInput<MapRasterWorker.Request> input) {
        Objects.requireNonNull(input);
        try(input;var source=input.retain()) {
            current();var memory=device.memory().orElse(null);
            if(source.admission().tracked()&&(memory==null||!source.admission().belongsTo(memory)))throw new IllegalArgumentException("Tracked native map input requires the same device coordinator");
            return submit(source.value());
        }
    }
    public long submit(MapSamples samples,MapRaster.Style style) { return submit(new MapRasterWorker.Request(samples,style)); }
    public long recolor(MapRaster.Style style) { return recolor(style,MapRaster.Limits.DEFAULT); }
    public long recolor(MapRaster.Style style,MapRaster.Limits limits) {
        current();if(!sourceReady||map==null)throw new GlFailure(GlFailure.Code.STATE,"A complete current map upload is required for recoloring");return recolor(map.raster(),style,limits);
    }
    /** A caller-held raster can support rapid replacements; keep its owner live through CPU admission. */
    public long recolor(MapRaster source,MapRaster.Style style,MapRaster.Limits limits) {
        Objects.requireNonNull(source);current();if(!source.samples().id().equals(latestSamples))throw new GlFailure(GlFailure.Code.STALE,"Recolor source differs from the latest native map samples");
        return submit(MapRasterWorker.Request.recolor(source,style,limits));
    }
    /** Consumes independently owned CPU raster data after destination admission or rejection. */
    public long recolor(RenderInput<MapRaster> input,MapRaster.Style style,MapRaster.Limits limits) {
        Objects.requireNonNull(input);
        try(input;var source=input.retain()) {
            current();var memory=device.memory().orElse(null);if(source.admission().tracked()&&(memory==null||!source.admission().belongsTo(memory)))throw new IllegalArgumentException("Tracked map recoloring requires the same device coordinator");
            return recolor(source.value(),style,limits);
        }
    }
    private void dimensions(int width,int height) { GlTarget.validate(device,width,height); }
    /** Display-only framing; the source grid, sampling scale and native values are never changed. */
    public long view(MapView view,int width,int height) {
        Objects.requireNonNull(view);current();dimensions(width,height);if(grid==null||view.width()!=grid.width()||view.height()!=grid.height())throw new GlFailure(GlFailure.Code.STALE,"Map view dimensions must match the latest submitted native grid");
        if(view.equals(this.view)&&width==this.width&&height==this.height)return viewRevision;long next=nextView();var changed=displayStatus(next);
        this.view=view;this.width=width;this.height=height;viewRevision=next;dirty=true;status=changed;
        try { releaseFrame();return viewRevision; }catch(RuntimeException|Error failure) { failed(failure);throw failure; }
    }
    public long fit(int width,int height) { current();dimensions(width,height);if(grid==null)throw new GlFailure(GlFailure.Code.STATE,"Submit native map samples before fitting their view");return view(MapView.fit(grid,0,0,width,height),width,height); }
    public long pan(double dx,double dy) { current();if(view==null)throw new GlFailure(GlFailure.Code.STATE,"Configure a map viewport before panning");return view(view.pan(dx,dy),width,height); }
    public long zoomAt(double factor,double x,double y) { current();if(view==null)throw new GlFailure(GlFailure.Code.STATE,"Configure a map viewport before zooming");return view(view.zoomAt(factor,x,y),width,height); }
    /** Retry drawing the existing complete map after a display failure. CPU/upload failures require resubmission. */
    public long redraw() {
        current();if(!sourceReady||map==null||view==null)throw new GlFailure(GlFailure.Code.STATE,"A complete current map upload and configured viewport are required to redraw");long next=nextView();var waiting=displayStatus(next);viewRevision=next;dirty=true;status=waiting;
        try { releaseFrame();return viewRevision; }catch(RuntimeException|Error failure) { failed(failure);throw failure; }
    }
    /** Restart a failed current upload from complete exact-capture/style data, preserving navigation. */
    public boolean retryUpload(long expectedMapRevision,MapRaster source) {
        Objects.requireNonNull(source);current();if(expectedMapRevision<1)throw new IllegalArgumentException("Invalid map revision");
        if(expectedMapRevision!=mapRevision||sourceReady||status.phase()!=Phase.FAILED)return false;
        if(source.requiresPlaceholder()||!source.samples().id().equals(latestSamples)||!source.style().equals(latestStyle))throw new GlFailure(GlFailure.Code.STALE,"Retry requires the latest complete map capture and display style");
        if(source.image().orElseThrow().bytes()>latestLimits.imageBytes()||source.payloadBytes()>latestLimits.workingBytes())throw new GlFailure(GlFailure.Code.LIMIT,"Retry raster exceeds the latest map output allowance");
        GlMapUpload.validate(device,source);
        long next=nextView();var queued=new Status(mapRevision,next,Phase.UPLOADING,status.context(),List.of(),Optional.empty(),"");
        worker.invalidate();cpuRevision=0;viewRevision=next;dirty=false;
        try {
            releaseDisplay();if(uploads.status().revision()<mapRevision)uploads.invalidate(mapRevision);
            if(!uploads.restart(mapRevision)||!uploads.submit(mapRevision,source))throw new GlFailure(GlFailure.Code.STALE,"Map upload queue no longer matches the retry revision");
            status=queued;return true;
        }catch(RuntimeException|Error failure) { failed(failure);throw failure; }
    }
    /** Consumes the supplied CPU owner through destination admission, including rejection/failure. */
    public boolean retryUpload(long expectedMapRevision,RenderInput<MapRaster> input) {
        Objects.requireNonNull(input);
        try(input;var source=input.retain()) {
            current();var memory=device.memory().orElse(null);if(source.admission().tracked()&&(memory==null||!source.admission().belongsTo(memory)))throw new IllegalArgumentException("Tracked map retry requires the same device coordinator");
            return retryUpload(expectedMapRevision,source.value());
        }
    }
    /** Colorization is off-thread; upload is bounded and one complete frame draw is synchronous. */
    public Status advance(GlUploadBudget budget,long expectedMapRevision) {
        Objects.requireNonNull(budget);current();if(expectedMapRevision<1)throw new IllegalArgumentException("Invalid expected map revision");if(expectedMapRevision!=mapRevision||status.phase()==Phase.FAILED)return status;
        try {
            if(cpuRevision!=0) {
                var result=worker.take(cpuRevision);
                if(result.isPresent())try(var prepared=result.orElseThrow()) {
                    if(!uploads.submit(mapRevision,prepared.raster()))throw new GlFailure(GlFailure.Code.STALE,"Map upload rejected the current CPU raster");cpuRevision=0;state(Phase.UPLOADING);
                } else {
                    var cpu=worker.status();if(cpu.revision()!=cpuRevision||cpu.phase()==MapRasterWorker.Phase.CLOSED)throw new GlFailure(GlFailure.Code.STALE,"Map worker revision changed before CPU adoption");
                    if(cpu.phase()==MapRasterWorker.Phase.FAILED)status=new Status(mapRevision,viewRevision,Phase.FAILED,status.context(),cpu.problems(),Optional.empty(),"Map colorization failed");return status;
                }
            }
            if(status.phase()==Phase.UPLOADING) { uploads.advance(budget,mapRevision);var result=uploads.take(mapRevision);if(result.isPresent()) { map=result.orElseThrow().map();sourceReady=true;dirty=true;state(Phase.WAITING_VIEW); } }
            if(!sourceReady||map==null||view==null||!dirty)return status;
            if(completed!=null)throw new GlFailure(GlFailure.Code.STATE,"An untaken map frame survived view invalidation");
            var frame=GlMapFrame.render(renderer,viewRevision,map,view,width,height,()->false);
            try { var ready=new Status(mapRevision,viewRevision,Phase.FRAME_READY,status.context(),List.of(),Optional.empty(),"");completed=frame;dirty=false;status=ready; }
            catch(RuntimeException|Error failure) { try { frame.close(); }catch(RuntimeException|Error cleanup) { if(cleanup!=failure)failure.addSuppressed(cleanup); }throw failure; }
            return status;
        }catch(RuntimeException|Error failure) { failed(failure);throw failure; }
    }
    public Optional<GlMapFrame> take(long expectedMapRevision,long expectedViewRevision) {
        current();if(!sourceReady||expectedMapRevision!=mapRevision||expectedViewRevision!=viewRevision||status.phase()!=Phase.FRAME_READY||completed==null||completed.info().revision()!=viewRevision)return Optional.empty();var result=Optional.of(completed);var delivered=new Status(mapRevision,viewRevision,Phase.DELIVERED,status.context(),List.of(),Optional.empty(),"");completed=null;status=delivered;return result;
    }
    private void releaseFrame() { if(completed!=null)try { completed.close();completed=null; }catch(RuntimeException|Error error) { if(completed.closed())completed=null;throw error; } }
    private void releaseDisplay() {
        Throwable failure=null;try { releaseFrame(); }catch(RuntimeException|Error error) { failure=error; }
        if(map!=null)try { map.close();map=null; }catch(RuntimeException|Error error) { if(map.closed())map=null;if(failure==null)failure=error;else if(failure!=error)failure.addSuppressed(error); }
        if(failure instanceof RuntimeException runtime)throw runtime;if(failure instanceof Error error)throw error;
    }
    /** Leaves the borrowed renderer/device and caller-transferred frames owned by their callers. */
    @Override public void close() {
        if(disposed)return;device.current();closed=true;sourceReady=false;latestSamples=null;latestStyle=null;latestLimits=null;Throwable failure=null;
        try { worker.close(); }catch(RuntimeException|Error error) { failure=error; }
        try { uploads.close(); }catch(RuntimeException|Error error) { if(failure==null)failure=error;else if(failure!=error)failure.addSuppressed(error); }
        try { releaseDisplay(); }catch(RuntimeException|Error error) { if(failure==null)failure=error;else if(failure!=error)failure.addSuppressed(error); }
        try { status=new Status(mapRevision,viewRevision,Phase.CLOSED,Optional.empty(),List.of(),Optional.empty(),""); }
        catch(RuntimeException|Error error) { if(failure==null)failure=error;else if(failure!=error)failure.addSuppressed(error); }disposed=failure==null;
        if(failure instanceof RuntimeException runtime)throw runtime;if(failure instanceof Error error)throw error;
    }
}
