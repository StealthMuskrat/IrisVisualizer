package dev.irisvisualizer.renderer;

import java.util.*;
import java.util.function.BooleanSupplier;

/** Worker-side immutable CPU preparation for a copy-on-write GPU atlas update. */
public final class AtlasFramePlan {
    private final AtlasRaster expected;private final AtlasUpdate update;private final Optional<AtlasRaster> raster;
    private final List<Integer> changedPages;private final List<List<AtlasUpdate.Patch>> patches;private final long copiedBytes;private final List<ModelPlan.Problem> problems;
    private AtlasFramePlan(AtlasRaster expected,AtlasUpdate update,Optional<AtlasRaster> raster,List<Integer> changedPages,List<List<AtlasUpdate.Patch>> patches,long copiedBytes,List<ModelPlan.Problem> problems) {
        this.expected=expected;this.update=update;this.raster=raster;this.changedPages=List.copyOf(changedPages);this.patches=List.copyOf(patches);this.copiedBytes=copiedBytes;this.problems=List.copyOf(problems);
        if(!problems.isEmpty()&&(raster.isPresent()||!changedPages.isEmpty()||copiedBytes!=0))throw new IllegalArgumentException("Partial atlas frame plan escaped failure");
    }
    public static AtlasFramePlan prepare(AtlasRaster current,AtlasUpdate update,BooleanSupplier cancelled) {
        Objects.requireNonNull(current);Objects.requireNonNull(update);Objects.requireNonNull(cancelled);AtlasBuilder.check(cancelled);
        try { return execute(current,update,cancelled); }
        catch(OutOfMemoryError exhausted) {
            AtlasBuilder.check(cancelled);return new AtlasFramePlan(current,update,Optional.empty(),List.of(),List.of(),0,List.of(new ModelPlan.Problem(ModelPlan.Code.MEMORY,ClientAssetSnapshot.Kind.BLOCKSTATE,current.layout().owner(),"","Not enough memory to prepare atlas frame pages; no partial plan was published")));
        }
    }
    private static AtlasFramePlan execute(AtlasRaster current,AtlasUpdate update,BooleanSupplier cancelled) {
        var raster=AtlasImages.apply(current,update,cancelled);
        if(raster.requiresPlaceholder())return new AtlasFramePlan(current,update,Optional.empty(),List.of(),List.of(),0,raster.problems());
        var changed=new ArrayList<Integer>();long bytes=0;
        for(int page=0;page<raster.pages().size();page++) { AtlasBuilder.check(cancelled);if(raster.pages().get(page)!=current.pages().get(page)) { changed.add(page);bytes+=raster.pages().get(page).bytes(); } }
        var grouped=new ArrayList<List<AtlasUpdate.Patch>>();for(int i=0;i<raster.pages().size();i++)grouped.add(new ArrayList<>());
        for(var patch:update.patches()) { AtlasBuilder.check(cancelled);grouped.get(patch.page()).add(patch); }
        for(int i=0;i<grouped.size();i++)grouped.set(i,List.copyOf(grouped.get(i)));
        AtlasBuilder.check(cancelled);return new AtlasFramePlan(current,update,Optional.of(raster),changed,grouped,bytes,List.of());
    }
    public AtlasRaster expected() { return expected; }
    public AtlasUpdate update() { return update; }
    public Optional<AtlasRaster> raster() { return raster; }
    public List<Integer> changedPages() { return changedPages; }
    public List<AtlasUpdate.Patch> patches(int page) { return patches.get(page); }
    public long copiedBytes() { return copiedBytes; }
    public long extraCpuBytes() { return copiedBytes+update.bytes(); }
    public List<ModelPlan.Problem> problems() { return problems; }
    public boolean requiresPlaceholder() { return !problems.isEmpty(); }
}
