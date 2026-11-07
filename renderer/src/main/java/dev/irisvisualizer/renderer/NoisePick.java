package dev.irisvisualizer.renderer;

import java.util.*;
import java.util.function.BooleanSupplier;

/** Bounded front-face inspection of opaque scalar bins. No Minecraft block/biome identity is fabricated. */
public final class NoisePick {
    private NoisePick() {}
    /** Grid metadata contains no sample arrays and preserves fractional coordinates before absolute-double rounding. */
    public record Hit(int face,NoiseSurface.Face sample,double distance,double localX,double localY,double localZ,Optional<NoiseVolume.Grid> grid) {
        public Hit(int face,NoiseSurface.Face sample,double distance,double localX,double localY,double localZ) { this(face,sample,distance,localX,localY,localZ,Optional.empty()); }
        public Hit {
            Objects.requireNonNull(grid);
            if(grid.isPresent()) {
                var source=grid.orElseThrow();
                if(sample==null||sample.sample()<0||sample.sample()>=source.count()||!source.point(sample.sample()).equals(sample.point()))throw new IllegalArgumentException("Scalar hit sample does not match its grid metadata");
            }
        }
    }
    public static final int MAX_RANGE_NODES=2047;
    public enum FaceBudget { SOURCE, VISITED }
    public record Options(int maximumFaces,WorldClip clip,FaceBudget faceBudget,boolean batchCulling,boolean rangeHierarchy,int rangeNodeTests) {
        public Options(int maximumFaces) { this(maximumFaces,WorldClip.NONE); }
        public Options(int maximumFaces,WorldClip clip) { this(maximumFaces,clip,FaceBudget.SOURCE,true,true,MAX_RANGE_NODES); }
        public Options { Objects.requireNonNull(clip);Objects.requireNonNull(faceBudget);if(maximumFaces<0||maximumFaces>1048576||rangeNodeTests<0||rangeNodeTests>MAX_RANGE_NODES)throw new IllegalArgumentException("Invalid scalar query allowance"); }
        public Options withClip(WorldClip clip) { return new Options(maximumFaces,clip,faceBudget,batchCulling,rangeHierarchy,rangeNodeTests); }
        /** Opt in to charging actual primitive tests; legacy constructors still bound the complete source. */
        public Options withVisitedFaceBudget(int maximum) { return new Options(maximum,clip,FaceBudget.VISITED,batchCulling,rangeHierarchy,rangeNodeTests); }
        public Options withBatchCulling(boolean enabled) { return new Options(maximumFaces,clip,faceBudget,enabled,rangeHierarchy,rangeNodeTests); }
        public Options withRangeHierarchy(boolean enabled) { return new Options(maximumFaces,clip,faceBudget,batchCulling,enabled,rangeNodeTests); }
        public Options withRangeNodeTests(int tests) { return new Options(maximumFaces,clip,faceBudget,batchCulling,rangeHierarchy,tests); }
    }
    public record Result(UUID volumeId,WorldRay ray,Optional<Hit> hit,int visitedFaces,int visitedBatches,int culledBatches,List<WorldProblem> problems,WorldClip clip,int rangeNodes,int culledRangeNodes) {
        public Result(UUID volumeId,WorldRay ray,Optional<Hit> hit,int visitedFaces,List<WorldProblem> problems) { this(volumeId,ray,hit,visitedFaces,0,0,problems,WorldClip.NONE); }
        public Result(UUID volumeId,WorldRay ray,Optional<Hit> hit,int visitedFaces,int visitedBatches,int culledBatches,List<WorldProblem> problems) { this(volumeId,ray,hit,visitedFaces,visitedBatches,culledBatches,problems,WorldClip.NONE); }
        public Result(UUID volumeId,WorldRay ray,Optional<Hit> hit,int visitedFaces,int visitedBatches,int culledBatches,List<WorldProblem> problems,WorldClip clip) { this(volumeId,ray,hit,visitedFaces,visitedBatches,culledBatches,problems,clip,0,0); }
        public Result { if(visitedFaces<0||visitedFaces>1048576||visitedBatches<0||visitedBatches>1024||culledBatches<0||culledBatches>visitedBatches||rangeNodes<0||rangeNodes>MAX_RANGE_NODES||culledRangeNodes<0||culledRangeNodes>rangeNodes)throw new IllegalArgumentException("Invalid scalar query work counts");Objects.requireNonNull(volumeId);Objects.requireNonNull(ray);Objects.requireNonNull(hit);Objects.requireNonNull(clip);problems=List.copyOf(problems);if(!problems.isEmpty()&&hit.isPresent())throw new IllegalArgumentException("Partial scalar hit escaped failure"); }
        public boolean requiresPlaceholder() { return !problems.isEmpty(); }
    }
    public static Result query(NoiseSurface surface,WorldRay ray,int maximumFaces,BooleanSupplier cancelled) {
        return query(surface,ray,new Options(maximumFaces),cancelled);
    }
    public static Result query(NoiseSurface surface,WorldRay ray,Options options,BooleanSupplier cancelled) {
        Objects.requireNonNull(surface);Objects.requireNonNull(ray);Objects.requireNonNull(options);Objects.requireNonNull(cancelled);WorldProblem.check(cancelled);
        var volume=surface.volume();if(surface.requiresPlaceholder())return new Result(volume.id(),ray,Optional.empty(),0,0,0,surface.problems(),options.clip());
        Query work=null;
        try {
            if(options.faceBudget()==FaceBudget.SOURCE&&surface.size()>options.maximumFaces())throw WorldProblem.fail(WorldProblem.Code.LIMIT,"Complete scalar face query exceeds its allowance; no partial nearest hit returned");
            work=new Query(surface,ray,options,cancelled);return work.execute();
        }catch(WorldProblem.Failure failure) { WorldProblem.check(cancelled);return failed(volume.id(),ray,options,work,failure.problem); }
        catch(ArithmeticException overflow) { WorldProblem.check(cancelled);return failed(volume.id(),ray,options,work,new WorldProblem(WorldProblem.Code.COORDINATE,Optional.empty(),"Scalar query anchor difference exceeds exact coordinate bounds")); }
    }
    private static Result failed(UUID volume,WorldRay ray,Options options,Query work,WorldProblem problem) {
        return new Result(volume,ray,Optional.empty(),work==null?0:work.visited,work==null?0:work.visitedBatches,work==null?0:work.culledBatches,List.of(problem),options.clip(),work==null?0:work.rangeNodes,work==null?0:work.culledRangeNodes);
    }
    private static final class Query {
        final NoiseSurface surface;final NoiseVolume volume;final NoiseVolume.Grid g;final WorldRay ray;final Options options;final BooleanSupplier cancelled;final WorldClip.Bounds clip;
        final double x,y,z,scale;double nearest,hitX,hitY,hitZ;int found=-1,foundCell=-1,foundSide=-1,foundU,foundV,visited,visitedBatches,culledBatches,rangeNodes,culledRangeNodes;
        Query(NoiseSurface surface,WorldRay ray,Options options,BooleanSupplier cancelled) {
            this.surface=surface;volume=surface.volume();g=volume.grid();this.ray=ray;this.options=options;this.cancelled=cancelled;
            long ox=Math.subtractExact((long)g.horizontal().originX(),ray.anchor().x()),oy=(long)g.vertical().minY()-ray.anchor().y(),oz=Math.subtractExact((long)g.horizontal().originZ(),ray.anchor().z());
            if(Math.max(Math.abs((double)ox),Math.max(Math.abs((double)oy),Math.abs((double)oz)))>1048576)throw WorldProblem.fail(WorldProblem.Code.COORDINATE,"Scalar query exceeds supported relative anchor precision");
            x=ray.x()-ox;y=ray.y()-oy;z=ray.z()-oz;scale=g.horizontal().blocksPerSample();nearest=ray.maximum();clip=options.clip().relative(new WorldSnapshot.Position(g.horizontal().originX(),g.vertical().minY(),g.horizontal().originZ()));
        }
        Result execute() {
            if(!options.batchCulling())faces(0,surface.size());
            else if(surface.batchCount()>0) {
                if(options.rangeHierarchy())visit(0,surface.batchCount(),0,entry(0,surface.batchCount(),0));
                else for(int batch=0;batch<surface.batchCount();batch++) { WorldProblem.check(cancelled);visitedBatches++;if(!Double.isFinite(boundsEntry(surface,batch,false,ray,x,y,z,nearest,clip))) { culledBatches++;continue; }batch(batch); }
            }
            WorldProblem.check(cancelled);Optional<Hit> hit=Optional.empty();
            if(found>=0) { int cell=foundCell;double minX=(cell%g.width())*scale,minY=cell/((long)g.width()*g.depth()),minZ=(cell/g.width()%g.depth())*scale;hit=Optional.of(new Hit(found,surface.face(found,foundU,foundV),nearest,hitX-minX,hitY-minY,hitZ-minZ,Optional.of(g))); }
            return new Result(volume.id(),ray,hit,visited,visitedBatches,culledBatches,List.of(),options.clip(),rangeNodes,culledRangeNodes);
        }
        void batch(int batch) { faces(surface.batchFirst(batch),surface.batchFirst(batch)+surface.batchSize(batch)); }
        double entry(int first,int end,int node) {
            WorldProblem.check(cancelled);if(++rangeNodes>options.rangeNodeTests())throw WorldProblem.fail(WorldProblem.Code.LIMIT,"Scalar range-node query allowance exceeded; no partial nearest hit returned");
            boolean leaf=end-first==1;if(leaf)visitedBatches++;double result=boundsEntry(surface,leaf?first:node,!leaf,ray,x,y,z,nearest,clip);
            if(!Double.isFinite(result)) { culledRangeNodes++;if(leaf)culledBatches++; }return result;
        }
        void visit(int first,int end,int node,double distance) {
            WorldProblem.check(cancelled);if(!Double.isFinite(distance))return;
            if(distance>nearest) { culledRangeNodes++;if(end-first==1)culledBatches++;return; }
            if(end-first==1) { batch(first);return; }
            int middle=(first+end)>>>1,left=node+1,right=node+middle-first;double a=entry(first,middle,left),b=entry(middle,end,right);
            if(a<=b) { visit(first,middle,left,a);visit(middle,end,right,b); }else { visit(middle,end,right,b);visit(first,middle,left,a); }
        }
        void faces(int first,int end) {
            for(int face=first;face<end;face++) {
                if(++visited>options.maximumFaces())throw WorldProblem.fail(WorldProblem.Code.LIMIT,"Visited scalar face query allowance exceeded; no partial nearest hit returned");
                if((face&255)==0)WorldProblem.check(cancelled);int cell=surface.cell(face),side=surface.direction(face).ordinal();
                int sx=cell%g.width(),sy=(int)(cell/((long)g.width()*g.depth())),sz=cell/g.width()%g.depth();
                double minX=sx*scale,minY=sy,minZ=sz*scale,maxX=(sx+surface.sizeX(face))*scale,maxY=sy+surface.sizeY(face),maxZ=(sz+surface.sizeZ(face))*scale;
                int axis=side<2?1:side<4?2:0;double direction=axis==0?ray.dx():axis==1?ray.dy():ray.dz();boolean plus=(side&1)!=0;
                if(direction*(plus?1:-1)>=0)continue;
                double plane=axis==0?(plus?maxX:minX):axis==1?(plus?maxY:minY):(plus?maxZ:minZ),origin=axis==0?x:axis==1?y:z,t=(plane-origin)/direction;
                if(!Double.isFinite(t)||t<ray.minimum()||t>nearest)continue;
                // Reuse the exact intersected plane, preserving closed clipping and ray endpoints.
                double hx=axis==0?plane:x+t*ray.dx(),hy=axis==1?plane:y+t*ray.dy(),hz=axis==2?plane:z+t*ray.dz();
                if(clip.contains(hx,hy,hz)&&(axis==0||hx>=minX&&hx<=maxX)&&(axis==1||hy>=minY&&hy<=maxY)&&(axis==2||hz>=minZ&&hz<=maxZ)) {
                    int su=side<4?sampleOffset(hx,sx,surface.spanU(face),scale):sampleOffset(hz,sz,surface.spanU(face),scale);
                    int sv=side<2?sampleOffset(hz,sz,surface.spanV(face),scale):sampleOffset(hy,sy,surface.spanV(face),1),sample=surface.sample(face,su,sv);
                    if(found<0||t<nearest||sample<foundCell||sample==foundCell&&side<foundSide) { found=face;foundCell=sample;foundSide=side;foundU=su;foundV=sv;nearest=t;hitX=hx;hitY=hy;hitZ=hz; }
                }
            }
        }
    }
    /** Same outward-rounded candidate slabs for leaves and exact integer parent unions. */
    private static double boundsEntry(NoiseSurface surface,int index,boolean hierarchy,WorldRay ray,double x,double y,double z,double nearest,WorldClip.Bounds clip) {
        double enter=ray.minimum(),exit=nearest,scale=surface.volume().grid().horizontal().blocksPerSample();
        for(int axis=0;axis<3;axis++) {
            double factor=axis==1?1:scale,lo=(hierarchy?surface.batchHierarchyMinimum(index,axis):surface.batchMinimum(index,axis))*factor,hi=(hierarchy?surface.batchHierarchyMaximum(index,axis):surface.batchMaximum(index,axis))*factor;
            double origin=axis==0?x:axis==1?y:z,direction=axis==0?ray.dx():axis==1?ray.dy():ray.dz(),margin=8*Math.ulp(Math.max(Math.abs(origin),Math.max(Math.abs(lo),Math.abs(hi))));
            double clipLow=axis==0?clip.minX():axis==1?clip.minY():clip.minZ(),clipHigh=axis==0?clip.maxX():axis==1?clip.maxY():clip.maxZ();
            lo=Math.max(Math.nextDown(lo-margin),clipLow);hi=Math.min(Math.nextUp(hi+margin),clipHigh);if(lo>hi)return Double.POSITIVE_INFINITY;
            if(direction==0) { if(origin<lo||origin>hi)return Double.POSITIVE_INFINITY;continue; }
            double a=(lo-origin)/direction,b=(hi-origin)/direction;enter=Math.max(enter,Math.nextDown(Math.min(a,b)));exit=Math.min(exit,Math.nextUp(Math.max(a,b)));
            if(enter>exit)return Double.POSITIVE_INFINITY;
        }
        return enter;
    }
    /** Inclusive shared edges select the lower original sample, as the unmerged source order did. */
    private static int sampleOffset(double coordinate,int origin,int span,double scale) {
        int low=0,high=span-1;
        while(low<high) { int middle=(low+high)>>>1;if(coordinate<=(origin+middle+1)*scale)high=middle;else low=middle+1; }
        return low;
    }
}
