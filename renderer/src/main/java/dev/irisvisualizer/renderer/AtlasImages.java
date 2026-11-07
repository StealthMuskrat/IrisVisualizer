package dev.irisvisualizer.renderer;

import java.util.*;
import java.util.function.BooleanSupplier;

/** CPU page materialization and tick-quantized RGBA frame patches. No mipmaps or GPU state. */
public final class AtlasImages {
    private AtlasImages() {}
    /** FULL_SLOTS preserves the metadata-only reference path; DIRTY_RECTANGLES scans exact RGBA output. */
    public enum PatchMode { FULL_SLOTS, DIRTY_RECTANGLES }
    private record Region(int x,int y,int width,int height) { long bytes() { return (long)width*height*4; } }
    private record Change(AtlasLayout.Sprite slot,Region region) {}
    /** One slot's immutable sampling inputs; no per-pixel allocation. */
    private static final class Pixels {
        final AtlasLayout.Sprite slot;final TextureSprite.Tile current,next;final RgbaImage image;final float blend;final boolean blended;
        Pixels(AtlasLayout.Sprite slot,TextureSprite.PixelSample sample) { this.slot=slot;current=slot.source().tile(sample.currentFrame());next=slot.source().tile(sample.nextFrame());image=slot.source().image();blend=sample.blendSteps()/1000.0f;blended=sample.blended(); }
        int rgba(int x,int y) {
            int localX=Math.clamp(x-slot.gutter(),0,slot.width()-1),localY=Math.clamp(y-slot.gutter(),0,slot.height()-1);int value=image.rgba(current.x()+localX,current.y()+localY);
            return blended?mix(value,image.rgba(next.x()+localX,next.y()+localY),blend):value;
        }
    }
    private static void clock(long tick,double partialTick) { if(tick<0||!Double.isFinite(partialTick)||partialTick<0||partialTick>=1)throw new IllegalArgumentException("Invalid atlas animation clock"); }
    private static ModelPlan.Problem memory(AtlasLayout layout,String operation) { return AtlasBuilder.blockFailure(ModelPlan.Code.MEMORY,layout.owner(),"Not enough memory for "+operation+"; no partial atlas result was published").problem; }
    public static AtlasRaster capture(AtlasLayout layout,long tick,double partialTick,BooleanSupplier cancelled) {
        Objects.requireNonNull(layout);Objects.requireNonNull(cancelled);clock(tick,partialTick);AtlasBuilder.check(cancelled);
        try { return capturePages(layout,tick,partialTick,cancelled); }
        catch(ModelFields.Failure failure) { AtlasBuilder.check(cancelled);return AtlasRaster.placeholder(layout,tick,partialTick,List.of(failure.problem)); }
        catch(OutOfMemoryError exhausted) { AtlasBuilder.check(cancelled);return AtlasRaster.placeholder(layout,tick,partialTick,List.of(memory(layout,"atlas rasterization"))); }
    }
    private static AtlasRaster capturePages(AtlasLayout layout,long tick,double partialTick,BooleanSupplier cancelled) {
        if(layout.requiresPlaceholder())return AtlasRaster.placeholder(layout,tick,partialTick,layout.problems());
        var samples=new TreeMap<AssetId,TextureSprite.PixelSample>();
        for(var entry:layout.sprites().entrySet()) { AtlasBuilder.check(cancelled);samples.put(entry.getKey(),entry.getValue().source().pixelSample(tick,partialTick)); }
        var buffers=new ArrayList<byte[]>(layout.pages().size());
        for(var page:layout.pages()) { AtlasBuilder.check(cancelled);buffers.add(new byte[Math.toIntExact(page.bytes())]); }
        for(var entry:layout.sprites().entrySet()) {
            AtlasBuilder.check(cancelled);var slot=entry.getValue();var page=layout.pages().get(slot.page());
            fill(slot,samples.get(entry.getKey()),buffers.get(slot.page()),page.width(),slot.x()-slot.gutter(),slot.y()-slot.gutter(),cancelled);
        }
        var pages=new ArrayList<RgbaImage>(buffers.size());
        for(int i=0;i<buffers.size();i++) { AtlasBuilder.check(cancelled);var page=layout.pages().get(i);pages.add(RgbaImage.owned(page.width(),page.height(),buffers.get(i))); }
        AtlasBuilder.check(cancelled);return AtlasRaster.success(layout,tick,partialTick,pages,samples);
    }
    /** Produces changed whole-slot patches, without changing the expected raster or any source image. */
    public static AtlasUpdate updates(AtlasRaster expected,long tick,double partialTick,BooleanSupplier cancelled) { return updates(expected,tick,partialTick,PatchMode.FULL_SLOTS,cancelled); }
    /** Emits one enclosing rectangle per actually dirty slot in DIRTY_RECTANGLES mode. */
    public static AtlasUpdate updates(AtlasRaster expected,long tick,double partialTick,PatchMode mode,BooleanSupplier cancelled) {
        Objects.requireNonNull(expected);Objects.requireNonNull(mode);Objects.requireNonNull(cancelled);clock(tick,partialTick);AtlasBuilder.check(cancelled);
        try { return patches(expected,tick,partialTick,mode,cancelled); }
        catch(ModelFields.Failure failure) { AtlasBuilder.check(cancelled);return AtlasUpdate.placeholder(expected,tick,partialTick,List.of(failure.problem)); }
        catch(OutOfMemoryError exhausted) { AtlasBuilder.check(cancelled);return AtlasUpdate.placeholder(expected,tick,partialTick,List.of(memory(expected.layout(),"atlas animation patches"))); }
    }
    private static Region difference(AtlasRaster expected,AtlasLayout.Sprite slot,TextureSprite.PixelSample sample,BooleanSupplier cancelled) {
        int minX=slot.paddedWidth(),minY=slot.paddedHeight(),maxX=-1,maxY=-1;var pixels=new Pixels(slot,sample);var old=expected.pages().get(slot.page());int x0=slot.x()-slot.gutter(),y0=slot.y()-slot.gutter();
        for(int y=0;y<slot.paddedHeight();y++) { AtlasBuilder.check(cancelled);for(int x=0;x<slot.paddedWidth();x++) {
            if((x&1023)==0)AtlasBuilder.check(cancelled);
            if(pixels.rgba(x,y)!=old.rgba(x0+x,y0+y)) { minX=Math.min(minX,x);minY=Math.min(minY,y);maxX=Math.max(maxX,x);maxY=Math.max(maxY,y); }
        } }
        return maxX<0?null:new Region(minX,minY,maxX-minX+1,maxY-minY+1);
    }
    private static AtlasUpdate patches(AtlasRaster expected,long tick,double partialTick,PatchMode mode,BooleanSupplier cancelled) {
        if(expected.requiresPlaceholder())return AtlasUpdate.placeholder(expected,tick,partialTick,expected.problems());
        var layout=expected.layout();var samples=new TreeMap<>(expected.samples());var changed=new ArrayList<Change>();long bytes=0;
        for(var entry:layout.timeVaryingSprites().entrySet()) {
            AtlasBuilder.check(cancelled);var slot=entry.getValue();var sample=slot.source().pixelSample(tick,partialTick);samples.put(entry.getKey(),sample);
            if(!Objects.equals(expected.samples().get(entry.getKey()),sample)) {
                var region=mode==PatchMode.FULL_SLOTS?new Region(0,0,slot.paddedWidth(),slot.paddedHeight()):difference(expected,slot,sample,cancelled);
                if(region!=null) { changed.add(new Change(slot,region));bytes+=region.bytes(); }
            }
        }
        if(bytes>layout.limits().updateBytes()||layout.usage().orElseThrow().knownPayloadBytes()+bytes>layout.limits().workingBytes())throw AtlasBuilder.blockFailure(ModelPlan.Code.LIMIT,layout.owner(),"Atlas animation patch or combined payload budget reached");
        var patches=new ArrayList<AtlasUpdate.Patch>(changed.size());
        for(var change:changed) {
            AtlasBuilder.check(cancelled);var slot=change.slot();var region=change.region();byte[] pixels=new byte[Math.toIntExact(region.bytes())];var sample=samples.get(slot.source().id());
            fill(slot,sample,pixels,region.width(),0,0,region,cancelled);
            patches.add(new AtlasUpdate.Patch(slot.source().id(),slot.page(),slot.x()-slot.gutter()+region.x(),slot.y()-slot.gutter()+region.y(),sample.currentFrame(),RgbaImage.owned(region.width(),region.height(),pixels)));
        }
        AtlasBuilder.check(cancelled);return AtlasUpdate.success(expected,tick,partialTick,patches,samples,bytes);
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
            if(slot==null||slot.page()!=patch.page()||patch.x()<slot.x()-slot.gutter()||patch.y()<slot.y()-slot.gutter()||(long)patch.x()+patch.pixels().width()>(long)slot.x()-slot.gutter()+slot.paddedWidth()||(long)patch.y()+patch.pixels().height()>(long)slot.y()-slot.gutter()+slot.paddedHeight()||!Objects.equals(update.frames().get(patch.sprite()),patch.frame()))
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
        AtlasBuilder.check(cancelled);return AtlasRaster.success(layout,update.tick(),update.partialTick(),pages,update.samples());
    }
    private static void fill(AtlasLayout.Sprite slot,TextureSprite.PixelSample sample,byte[] target,int targetWidth,int x0,int y0,BooleanSupplier cancelled) { fill(slot,sample,target,targetWidth,x0,y0,new Region(0,0,slot.paddedWidth(),slot.paddedHeight()),cancelled); }
    private static void fill(AtlasLayout.Sprite slot,TextureSprite.PixelSample sample,byte[] target,int targetWidth,int x0,int y0,Region region,BooleanSupplier cancelled) {
        var pixels=new Pixels(slot,sample);
        for(int y=0;y<region.height();y++) { AtlasBuilder.check(cancelled);for(int x=0;x<region.width();x++) {
            if((x&1023)==0)AtlasBuilder.check(cancelled);int rgba=pixels.rgba(region.x()+x,region.y()+y),at=((y0+y)*targetWidth+x0+x)*4;
            target[at]=(byte)(rgba>>>24);target[at+1]=(byte)(rgba>>>16);target[at+2]=(byte)(rgba>>>8);target[at+3]=(byte)rgba;
        } }
    }
    /** Encoded, unpremultiplied RGBA channels; nearest 8-bit CPU result, not driver bit parity. */
    private static int mix(int current,int next,float blend) {
        if(current==next)return current;int result=0;float inverse=1-blend;
        for(int shift=24;shift>=0;shift-=8)result|=Math.clamp(Math.round(((current>>>shift)&255)*inverse+((next>>>shift)&255)*blend),0,255)<<shift;
        return result;
    }
}
