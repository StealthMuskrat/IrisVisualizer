package dev.irisvisualizer.renderer;

import java.util.*;
import java.util.function.BooleanSupplier;

/** Bounded front-face inspection of opaque scalar bins. No Minecraft block/biome identity is fabricated. */
public final class NoisePick {
    private NoisePick() {}
    public record Hit(int face,NoiseSurface.Face sample,double distance,double localX,double localY,double localZ) {}
    public record Options(int maximumFaces,WorldClip clip) {
        public Options(int maximumFaces) { this(maximumFaces,WorldClip.NONE); }
        public Options { Objects.requireNonNull(clip);if(maximumFaces<0||maximumFaces>1048576)throw new IllegalArgumentException("Invalid scalar query face allowance"); }
        public Options withClip(WorldClip clip) { return new Options(maximumFaces,clip); }
    }
    public record Result(UUID volumeId,WorldRay ray,Optional<Hit> hit,int visitedFaces,int visitedBatches,int culledBatches,List<WorldProblem> problems,WorldClip clip) {
        public Result(UUID volumeId,WorldRay ray,Optional<Hit> hit,int visitedFaces,List<WorldProblem> problems) { this(volumeId,ray,hit,visitedFaces,0,0,problems,WorldClip.NONE); }
        public Result(UUID volumeId,WorldRay ray,Optional<Hit> hit,int visitedFaces,int visitedBatches,int culledBatches,List<WorldProblem> problems) { this(volumeId,ray,hit,visitedFaces,visitedBatches,culledBatches,problems,WorldClip.NONE); }
        public Result { if(visitedFaces<0||visitedFaces>1048576||visitedBatches<0||visitedBatches>1024||culledBatches<0||culledBatches>visitedBatches)throw new IllegalArgumentException("Invalid scalar query work counts");Objects.requireNonNull(volumeId);Objects.requireNonNull(ray);Objects.requireNonNull(hit);Objects.requireNonNull(clip);problems=List.copyOf(problems);if(!problems.isEmpty()&&hit.isPresent())throw new IllegalArgumentException("Partial scalar hit escaped failure"); }
        public boolean requiresPlaceholder() { return !problems.isEmpty(); }
    }
    public static Result query(NoiseSurface surface,WorldRay ray,int maximumFaces,BooleanSupplier cancelled) {
        return query(surface,ray,new Options(maximumFaces),cancelled);
    }
    public static Result query(NoiseSurface surface,WorldRay ray,Options options,BooleanSupplier cancelled) {
        Objects.requireNonNull(surface);Objects.requireNonNull(ray);Objects.requireNonNull(options);Objects.requireNonNull(cancelled);WorldProblem.check(cancelled);
        var volume=surface.volume();if(surface.requiresPlaceholder())return new Result(volume.id(),ray,Optional.empty(),0,0,0,surface.problems(),options.clip());
        try { return execute(surface,ray,options,cancelled); }
        catch(WorldProblem.Failure failure) { WorldProblem.check(cancelled);return new Result(volume.id(),ray,Optional.empty(),0,0,0,List.of(failure.problem),options.clip()); }
        catch(ArithmeticException overflow) { WorldProblem.check(cancelled);return new Result(volume.id(),ray,Optional.empty(),0,0,0,List.of(new WorldProblem(WorldProblem.Code.COORDINATE,Optional.empty(),"Scalar query anchor difference exceeds exact coordinate bounds")),options.clip()); }
    }
    private static Result execute(NoiseSurface surface,WorldRay ray,Options options,BooleanSupplier cancelled) {
        var volume=surface.volume();var g=volume.grid();
        if(surface.size()>options.maximumFaces())throw WorldProblem.fail(WorldProblem.Code.LIMIT,"Complete scalar face query exceeds its allowance; no partial nearest hit returned");
        long ox=Math.subtractExact((long)g.horizontal().originX(),ray.anchor().x()),oy=(long)g.vertical().minY()-ray.anchor().y(),oz=Math.subtractExact((long)g.horizontal().originZ(),ray.anchor().z());
        if(Math.max(Math.abs((double)ox),Math.max(Math.abs((double)oy),Math.abs((double)oz)))>1048576)throw WorldProblem.fail(WorldProblem.Code.COORDINATE,"Scalar query exceeds supported relative anchor precision");
        double x=ray.x()-ox,y=ray.y()-oy,z=ray.z()-oz,scale=g.horizontal().blocksPerSample(),nearest=ray.maximum(),hitX=0,hitY=0,hitZ=0;int found=-1,foundCell=-1,foundSide=-1,foundU=0,foundV=0;
        var clip=options.clip().relative(new WorldSnapshot.Position(g.horizontal().originX(),g.vertical().minY(),g.horizontal().originZ()));
        int visited=0,visitedBatches=0,culledBatches=0;
        for(int batch=0;batch<surface.batchCount();batch++) {
            WorldProblem.check(cancelled);visitedBatches++;
            var bounds=surface.batchBounds(batch);
            if(!intersects(bounds,ray,x,y,z,nearest,clip)) { culledBatches++;continue; }
            for(int face=surface.batchFirst(batch),end=face+surface.batchSize(batch);face<end;face++) {
                visited++;
                if((face&255)==0)WorldProblem.check(cancelled);int cell=surface.cell(face),side=surface.direction(face).ordinal();
                int sx=cell%g.width(),sy=(int)(cell/((long)g.width()*g.depth())),sz=cell/g.width()%g.depth();
                double minX=sx*scale,minY=sy,minZ=sz*scale,maxX=(sx+surface.sizeX(face))*scale,maxY=sy+surface.sizeY(face),maxZ=(sz+surface.sizeZ(face))*scale;
                int axis=side<2?1:side<4?2:0;double direction=axis==0?ray.dx():axis==1?ray.dy():ray.dz();boolean plus=(side&1)!=0;
                if(direction*(plus?1:-1)>=0)continue;
                double plane=axis==0?(plus?maxX:minX):axis==1?(plus?maxY:minY):(plus?maxZ:minZ),origin=axis==0?x:axis==1?y:z,t=(plane-origin)/direction;
                if(!Double.isFinite(t)||t<ray.minimum()||t>nearest)continue;
                // The intersected normal coordinate is known exactly; ray reconstruction can cross a closed clip plane.
                double hx=axis==0?plane:x+t*ray.dx(),hy=axis==1?plane:y+t*ray.dy(),hz=axis==2?plane:z+t*ray.dz();
                if(clip.contains(hx,hy,hz)&&(axis==0||hx>=minX&&hx<=maxX)&&(axis==1||hy>=minY&&hy<=maxY)&&(axis==2||hz>=minZ&&hz<=maxZ)) {
                    int su=side<4?sampleOffset(hx,sx,surface.spanU(face),scale):sampleOffset(hz,sz,surface.spanU(face),scale);
                    int sv=side<2?sampleOffset(hz,sz,surface.spanV(face),scale):sampleOffset(hy,sy,surface.spanV(face),1),sample=surface.sample(face,su,sv);
                    // Preserve original sample/direction tie order independently of rectangle traversal.
                    if(found<0||t<nearest||sample<foundCell||sample==foundCell&&side<foundSide) { found=face;foundCell=sample;foundSide=side;foundU=su;foundV=sv;nearest=t;hitX=hx;hitY=hy;hitZ=hz; }
                }
            }
        }
        WorldProblem.check(cancelled);Optional<Hit> hit=Optional.empty();
        if(found>=0) { int cell=foundCell;double minX=(cell%g.width())*scale,minY=cell/((long)g.width()*g.depth()),minZ=(cell/g.width()%g.depth())*scale;hit=Optional.of(new Hit(found,surface.face(found,foundU,foundV),nearest,hitX-minX,hitY-minY,hitZ-minZ)); }
        return new Result(volume.id(),ray,hit,visited,visitedBatches,culledBatches,List.of(),options.clip());
    }
    /** Expand slabs and interval rounding so a batch cannot hide an edge/corner tie. */
    private static boolean intersects(NoiseSurface.Bounds bounds,WorldRay ray,double x,double y,double z,double nearest,WorldClip.Bounds clip) {
        double enter=ray.minimum(),exit=nearest;
        for(int axis=0;axis<3;axis++) {
            double lo=axis==0?bounds.minX():axis==1?bounds.minY():bounds.minZ(),hi=axis==0?bounds.maxX():axis==1?bounds.maxY():bounds.maxZ();
            double origin=axis==0?x:axis==1?y:z,direction=axis==0?ray.dx():axis==1?ray.dy():ray.dz(),margin=8*Math.ulp(Math.max(Math.abs(origin),Math.max(Math.abs(lo),Math.abs(hi))));
            double clipLow=axis==0?clip.minX():axis==1?clip.minY():clip.minZ(),clipHigh=axis==0?clip.maxX():axis==1?clip.maxY():clip.maxZ();
            lo=Math.max(Math.nextDown(lo-margin),clipLow);hi=Math.min(Math.nextUp(hi+margin),clipHigh);if(lo>hi)return false;
            if(direction==0) { if(origin<lo||origin>hi)return false;continue; }
            double a=(lo-origin)/direction,b=(hi-origin)/direction;enter=Math.max(enter,Math.nextDown(Math.min(a,b)));exit=Math.min(exit,Math.nextUp(Math.max(a,b)));
            if(enter>exit)return false;
        }
        return true;
    }
    /** Inclusive shared edges select the lower original sample, as the unmerged source order did. */
    private static int sampleOffset(double coordinate,int origin,int span,double scale) {
        int low=0,high=span-1;
        while(low<high) { int middle=(low+high)>>>1;if(coordinate<=(origin+middle+1)*scale)high=middle;else low=middle+1; }
        return low;
    }
}
