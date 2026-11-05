package dev.irisvisualizer.renderer.gl;

import dev.irisvisualizer.renderer.*;
import java.util.*;

/** Immutable atlas page snapshot for one GPU scene; close the owner after its last submitted use. */
public final class GlAtlasFrame implements AutoCloseable {
    static final class Page {
        final GlDevice.Allocation allocation;final int slot;final RgbaImage pixels;final boolean owned;int references=1;
        Page(GlDevice.Allocation allocation,int slot,RgbaImage pixels,boolean owned) { this.allocation=allocation;this.slot=slot;this.pixels=pixels;this.owned=owned; }
        void retain(GlDevice device) { device.live(allocation);if(references<1||references>=65536)throw new GlFailure(GlFailure.Code.STATE,"Invalid atlas page ownership");references++; }
        void release(GlDevice device) { if(!allocation.released)device.current();if(references<1)throw new GlFailure(GlFailure.Code.STATE,"Atlas page was released twice");if(--references==0&&owned)device.retire(allocation); }
        int texture(GlDevice device) { device.live(allocation);if(references<1)throw new GlFailure(GlFailure.Code.STATE,"Atlas page is no longer owned");return allocation.textures[slot]; }
    }
    final GlDevice device;private final Object token=new Object();private GlDevice.Allocation journal;
    private GlScene scene;private AtlasRaster raster;private List<Page> pages;private boolean closed;private final int[] textures;
    private GlAtlasFrame(GlScene scene,AtlasRaster raster,List<Page> pages,GlDevice.Allocation journal) {
        this.scene=scene;device=scene.device;this.raster=raster;this.pages=List.copyOf(pages);this.journal=journal;textures=new int[pages.size()];for(int i=0;i<textures.length;i++)textures[i]=pages.get(i).allocation.textures[pages.get(i).slot];
    }
    public static GlAtlasFrame capture(GlScene scene) {
        Objects.requireNonNull(scene);scene.device.live(scene.allocation);var raster=scene.cpuScene().raster();var pages=new ArrayList<Page>(raster.pages().size());
        for(int i=0;i<raster.pages().size();i++)pages.add(new Page(scene.allocation,i,raster.pages().get(i),false));
        GlAtlasFrame frame=null;Throwable failure=null;
        try { frame=create(scene,raster,pages); }catch(RuntimeException|Error error) { failure=error; }
        failure=releasePages(scene.device,pages,pages.size(),failure);
        if(failure!=null) {
            // A completed frame cannot escape when temporary-owner cleanup rejects its return.
            if(frame!=null)try { frame.close(); }catch(RuntimeException|Error cleanup) { if(cleanup!=failure)failure.addSuppressed(cleanup); }
            if(failure instanceof RuntimeException runtime)throw runtime;throw (Error)failure;
        }
        return frame;
    }
    static GlAtlasFrame create(GlScene scene,AtlasRaster raster,List<Page> pages) {
        var device=scene.device;device.live(scene.allocation);
        if(raster.requiresPlaceholder()||raster.layout()!=scene.cpuScene().layout()||pages.size()!=raster.pages().size())throw new GlFailure(GlFailure.Code.STALE,"Atlas frame does not match its original scene/layout");
        GlDevice.Allocation journal=null;int retained=0;
        try {
            journal=device.reserve(0,0,0,0,0,0,0,0,0,null);device.depend(journal,scene.allocation);
            for(int i=0;i<pages.size();i++) { var page=pages.get(i);if(page.pixels!=raster.pages().get(i))throw new GlFailure(GlFailure.Code.STATE,"Atlas page CPU/GPU ownership disagrees");page.retain(device);retained++;if(page.owned)device.depend(journal,page.allocation); }
            var frame=new GlAtlasFrame(scene,raster,pages,journal);journal.retained=raster;return frame;
        }catch(RuntimeException|Error failure) {
            releasePages(device,pages,retained,failure);
            if(journal!=null)try { device.retire(journal); }catch(RuntimeException|Error cleanup) { if(cleanup!=failure)failure.addSuppressed(cleanup); }throw failure;
        }
    }
    /** Preserve an earlier setup error while visiting every retained or temporary page owner. */
    private static Throwable releasePages(GlDevice device,List<Page> pages,int count,Throwable failure) {
        for(int i=0;i<count;i++)try { pages.get(i).release(device); }
        catch(RuntimeException|Error cleanup) { if(failure==null)failure=cleanup;else if(cleanup!=failure)failure.addSuppressed(cleanup); }
        return failure;
    }
    void requireScene(GlScene expected) {
        if(closed)throw new GlFailure(GlFailure.Code.STATE,"Atlas frame is closed");device.live(journal);device.live(scene.allocation);
        if(scene!=expected)throw new GlFailure(GlFailure.Code.STALE,"Atlas frame belongs to a different GPU scene");
    }
    GlAtlasFrame retain() { requireScene(scene);return create(scene,raster,pages); }
    Object token() { return token; }
    boolean closedOrReleased() { return closed||journal.released; }
    GlDevice.Allocation journal() { requireScene(scene);return journal; }
    List<Page> pages() { requireScene(scene);return pages; }
    GlScene scene() { requireScene(scene);return scene; }
    public AtlasRaster raster() { requireScene(scene);return raster; }
    int texture(int page) { requireScene(scene);return pages.get(page).texture(device); }
    int[] textures(GlScene expected) { requireScene(expected);return textures; }
    @Override public void close() {
        if(closed)return;if(!journal.released)device.current();closed=true;var release=pages;var allocation=journal;pages=List.of();raster=null;scene=null;journal=null;Throwable failure=null;
        failure=releasePages(device,release,release.size(),failure);
        try { device.retire(allocation); }catch(RuntimeException|Error cleanup) { if(failure==null)failure=cleanup;else if(cleanup!=failure)failure.addSuppressed(cleanup); }
        if(failure instanceof RuntimeException runtime)throw runtime;if(failure instanceof Error error)throw error;
    }
}
