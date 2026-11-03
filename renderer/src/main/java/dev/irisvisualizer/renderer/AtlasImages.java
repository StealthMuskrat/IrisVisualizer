package dev.irisvisualizer.renderer;

import java.util.*;
import java.util.function.BooleanSupplier;

/** CPU page materialization and frame patches. No implicit interpolation, mipmaps or GPU state. */
public final class AtlasImages {
    private AtlasImages() {}
    private static void clock(long tick,double partialTick) { if(tick<0||!Double.isFinite(partialTick)||partialTick<0||partialTick>=1)throw new IllegalArgumentException("Invalid atlas animation clock"); }
    private static void supported(AtlasLayout.Sprite slot) {
        if(slot.source().interpolate()&&slot.source().referencedFrames().size()>1)throw TextureMetadata.failure(ModelPlan.Code.ANIMATION,ClientAssetSnapshot.Kind.TEXTURE_METADATA,slot.source().id(),"/animation/interpolate","Native pixel interpolation must be verified before rasterizing a multi-frame interpolated sprite");
    }
    private static ModelPlan.Problem memory(AtlasLayout layout,String operation) { return AtlasBuilder.blockFailure(ModelPlan.Code.MEMORY,layout.owner(),"Not enough memory for "+operation+"; no partial atlas result was published").problem; }
    public static AtlasRaster capture(AtlasLayout layout,long tick,double partialTick,BooleanSupplier cancelled) {
        Objects.requireNonNull(layout);Objects.requireNonNull(cancelled);clock(tick,partialTick);AtlasBuilder.check(cancelled);
        try { return capturePages(layout,tick,partialTick,cancelled); }
        catch(ModelFields.Failure failure) { AtlasBuilder.check(cancelled);return AtlasRaster.placeholder(layout,tick,partialTick,List.of(failure.problem)); }
        catch(OutOfMemoryError exhausted) { AtlasBuilder.check(cancelled);return AtlasRaster.placeholder(layout,tick,partialTick,List.of(memory(layout,"atlas rasterization"))); }
    }
    private static AtlasRaster capturePages(AtlasLayout layout,long tick,double partialTick,BooleanSupplier cancelled) {
        if(layout.requiresPlaceholder())return AtlasRaster.placeholder(layout,tick,partialTick,layout.problems());
        var frames=new TreeMap<AssetId,Integer>();
        for(var entry:layout.sprites().entrySet()) { AtlasBuilder.check(cancelled);supported(entry.getValue());frames.put(entry.getKey(),entry.getValue().source().sample(tick,partialTick).currentFrame()); }
        var buffers=new ArrayList<byte[]>(layout.pages().size());
        for(var page:layout.pages()) { AtlasBuilder.check(cancelled);buffers.add(new byte[Math.toIntExact(page.bytes())]); }
        for(var entry:layout.sprites().entrySet()) {
            AtlasBuilder.check(cancelled);var slot=entry.getValue();var page=layout.pages().get(slot.page());
            fill(slot,frames.get(entry.getKey()),buffers.get(slot.page()),page.width(),slot.x()-slot.gutter(),slot.y()-slot.gutter(),cancelled);
        }
        var pages=new ArrayList<RgbaImage>(buffers.size());
        for(int i=0;i<buffers.size();i++) { AtlasBuilder.check(cancelled);var page=layout.pages().get(i);pages.add(RgbaImage.owned(page.width(),page.height(),buffers.get(i))); }
        AtlasBuilder.check(cancelled);return AtlasRaster.success(layout,tick,partialTick,pages,frames);
    }
    /** Produces changed whole-slot patches, without changing the expected raster or any source image. */
    public static AtlasUpdate updates(AtlasRaster expected,long tick,double partialTick,BooleanSupplier cancelled) {
        Objects.requireNonNull(expected);Objects.requireNonNull(cancelled);clock(tick,partialTick);AtlasBuilder.check(cancelled);
        try { return patches(expected,tick,partialTick,cancelled); }
        catch(ModelFields.Failure failure) { AtlasBuilder.check(cancelled);return AtlasUpdate.placeholder(expected,tick,partialTick,List.of(failure.problem)); }
        catch(OutOfMemoryError exhausted) { AtlasBuilder.check(cancelled);return AtlasUpdate.placeholder(expected,tick,partialTick,List.of(memory(expected.layout(),"atlas animation patches"))); }
    }
    private static AtlasUpdate patches(AtlasRaster expected,long tick,double partialTick,BooleanSupplier cancelled) {
        if(expected.requiresPlaceholder())return AtlasUpdate.placeholder(expected,tick,partialTick,expected.problems());
        var layout=expected.layout();var frames=new TreeMap<AssetId,Integer>();var changed=new ArrayList<AtlasLayout.Sprite>();long bytes=0;
        for(var entry:layout.sprites().entrySet()) {
            AtlasBuilder.check(cancelled);var slot=entry.getValue();supported(slot);int frame=slot.source().sample(tick,partialTick).currentFrame();frames.put(entry.getKey(),frame);
            if(!Objects.equals(expected.frames().get(entry.getKey()),frame)) { changed.add(slot);bytes+=slot.paddedBytes(); }
        }
        if(bytes>layout.limits().updateBytes()||layout.usage().orElseThrow().knownPayloadBytes()+bytes>layout.limits().workingBytes())throw AtlasBuilder.blockFailure(ModelPlan.Code.LIMIT,layout.owner(),"Atlas animation patch or combined payload budget reached");
        var patches=new ArrayList<AtlasUpdate.Patch>(changed.size());
        for(var slot:changed) {
            AtlasBuilder.check(cancelled);byte[] pixels=new byte[Math.toIntExact(slot.paddedBytes())];int frame=frames.get(slot.source().id());
            fill(slot,frame,pixels,slot.paddedWidth(),0,0,cancelled);
            patches.add(new AtlasUpdate.Patch(slot.source().id(),slot.page(),slot.x()-slot.gutter(),slot.y()-slot.gutter(),frame,RgbaImage.owned(slot.paddedWidth(),slot.paddedHeight(),pixels)));
        }
        AtlasBuilder.check(cancelled);return AtlasUpdate.success(expected,tick,partialTick,patches,frames,bytes);
    }
    /** Pure copy-on-write CPU application. A stale/failed update leaves current untouched. */
    public static AtlasRaster apply(AtlasRaster current,AtlasUpdate update,BooleanSupplier cancelled) {
        Objects.requireNonNull(current);Objects.requireNonNull(update);Objects.requireNonNull(cancelled);AtlasBuilder.check(cancelled);
        try { return applyPatches(current,update,cancelled); }
        catch(ModelFields.Failure failure) { AtlasBuilder.check(cancelled);return AtlasRaster.placeholder(current.layout(),update.tick(),update.partialTick(),List.of(failure.problem)); }
        catch(OutOfMemoryError exhausted) { AtlasBuilder.check(cancelled);return AtlasRaster.placeholder(current.layout(),update.tick(),update.partialTick(),List.of(memory(current.layout(),"atlas patch application"))); }
    }
    private static AtlasRaster applyPatches(AtlasRaster current,AtlasUpdate update,BooleanSupplier cancelled) {
        var layout=current.layout();
        if(current!=update.expected()||layout!=update.layout())throw AtlasBuilder.blockFailure(ModelPlan.Code.STALE_ATLAS,layout.owner(),"Atlas update does not target the exact current raster snapshot");
        if(current.requiresPlaceholder())return AtlasRaster.placeholder(layout,update.tick(),update.partialTick(),current.problems());
        if(update.requiresPlaceholder())return AtlasRaster.placeholder(layout,update.tick(),update.partialTick(),update.problems());
        var changedPages=new TreeSet<Integer>();long copyBytes=0;
        for(var patch:update.patches()) {
            AtlasBuilder.check(cancelled);var slot=layout.sprites().get(patch.sprite());
            if(slot==null||slot.page()!=patch.page()||patch.x()!=slot.x()-slot.gutter()||patch.y()!=slot.y()-slot.gutter()||patch.pixels().width()!=slot.paddedWidth()||patch.pixels().height()!=slot.paddedHeight()||!Objects.equals(update.frames().get(patch.sprite()),patch.frame()))
                throw AtlasBuilder.failure(ModelPlan.Code.ATLAS,patch.sprite(),"","Atlas patch bounds or target frame do not match their slot");
            if(changedPages.add(patch.page()))copyBytes+=layout.pages().get(patch.page()).bytes();
        }
        if(layout.usage().orElseThrow().knownPayloadBytes()+copyBytes+update.bytes()>layout.limits().workingBytes())throw AtlasBuilder.blockFailure(ModelPlan.Code.LIMIT,layout.owner(),"Atlas copy-on-write peak payload budget reached");
        var buffers=new HashMap<Integer,byte[]>();for(int page:changedPages) { AtlasBuilder.check(cancelled);buffers.put(page,current.pages().get(page).copyPixels()); }
        for(var patch:update.patches()) {
            AtlasBuilder.check(cancelled);var target=buffers.get(patch.page());int pageWidth=layout.pages().get(patch.page()).width();var source=patch.pixels().pixels();
            for(int row=0;row<patch.pixels().height();row++) { AtlasBuilder.check(cancelled);source.get(target,((patch.y()+row)*pageWidth+patch.x())*4,patch.rowBytes()); }
        }
        var pages=new ArrayList<>(current.pages());
        for(int page:changedPages) { AtlasBuilder.check(cancelled);var size=layout.pages().get(page);pages.set(page,RgbaImage.owned(size.width(),size.height(),buffers.get(page))); }
        AtlasBuilder.check(cancelled);return AtlasRaster.success(layout,update.tick(),update.partialTick(),pages,update.frames());
    }
    private static void fill(AtlasLayout.Sprite slot,int frame,byte[] target,int targetWidth,int x0,int y0,BooleanSupplier cancelled) {
        var tile=slot.source().tile(frame);var image=slot.source().image();int gutter=slot.gutter();
        for(int y=0;y<slot.paddedHeight();y++) {
            AtlasBuilder.check(cancelled);int sourceY=tile.y()+Math.clamp(y-gutter,0,slot.height()-1);
            for(int x=0;x<slot.paddedWidth();x++) {
                if((x&1023)==0)AtlasBuilder.check(cancelled);int sourceX=tile.x()+Math.clamp(x-gutter,0,slot.width()-1),rgba=image.rgba(sourceX,sourceY),at=((y0+y)*targetWidth+x0+x)*4;
                target[at]=(byte)(rgba>>>24);target[at+1]=(byte)(rgba>>>16);target[at+2]=(byte)(rgba>>>8);target[at+3]=(byte)rgba;
            }
        }
    }
}
