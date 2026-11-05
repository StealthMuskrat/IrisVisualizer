package dev.irisvisualizer.renderer;

import java.nio.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Section hierarchy and original-order primitive ranges; owns its scene until close and queries finish. */
public final class WorldPickIndex implements AutoCloseable {
    private static final double[] EMPTY_BOUNDS=new double[0];private static final int[] EMPTY_NODES=new int[0];
    private static final float[] EMPTY_RANGE_BOUNDS=new float[0];
    public static final int BATCH_PRIMITIVES=WorldMesh.BATCH_FACES;
    private static final class Ranges {
        static final Ranges EMPTY=new Ranges(0,0);
        final float[] bounds;final int[] starts;
        Ranges(int batches,int sections) { bounds=batches==0?EMPTY_RANGE_BOUNDS:new float[batches*6];starts=batches==0?EMPTY_NODES:new int[sections+1]; }
        long bytes() { return ((long)bounds.length+starts.length)*4; }
    }
    public record Limits(long indexBytes,long workingBytes) {
        public static final Limits DEFAULT=new Limits(16L*1024*1024,512L*1024*1024);
        public Limits { if(indexBytes<0||indexBytes>16L*1024*1024||workingBytes<0||workingBytes>512L*1024*1024)throw new IllegalArgumentException("Picking index limits exceed renderer bounds"); }
    }
    public enum Alpha { GEOMETRY, DISPLAYED_BASE_LEVEL }
    public record Options(int nodeTests,int faceTests,int markerTests,boolean backFaces,Alpha alpha,WorldClip clip,boolean batchCulling) {
        public static final Options DEFAULT=new Options(131071,262144,65536,false,Alpha.DISPLAYED_BASE_LEVEL);
        public Options(int nodeTests,int faceTests,int markerTests,boolean backFaces,Alpha alpha) { this(nodeTests,faceTests,markerTests,backFaces,alpha,WorldClip.NONE); }
        public Options(int nodeTests,int faceTests,int markerTests,boolean backFaces,Alpha alpha,WorldClip clip) { this(nodeTests,faceTests,markerTests,backFaces,alpha,clip,true); }
        public Options withClip(WorldClip clip) { return new Options(nodeTests,faceTests,markerTests,backFaces,alpha,clip,batchCulling); }
        /** Exhaustive section-primitive mode remains available for independent culling comparisons. */
        public Options withBatchCulling(boolean enabled) { return new Options(nodeTests,faceTests,markerTests,backFaces,alpha,clip,enabled); }
        public Options { Objects.requireNonNull(alpha);Objects.requireNonNull(clip);if(nodeTests<0||nodeTests>131071||faceTests<0||faceTests>2097152||markerTests<0||markerTests>1048576)throw new IllegalArgumentException("Picking query limits exceed renderer bounds"); }
    }
    private final UUID id=UUID.randomUUID();private final WorldSnapshot.Source source;private final WorldSnapshot.Position anchor;private final long bytes;private final List<WorldProblem> problems;private final RenderMemory memory;private final RenderMemory.Hold admission;
    private WorldScene.Result owner;private WorldScene scene;private double[] bounds;private int[] left,right,section;private Ranges faceRanges,markerRanges;private volatile boolean closed;private boolean released;private int queries;
    private WorldPickIndex(WorldSnapshot.Source source,WorldSnapshot.Position anchor,WorldScene.Result owner,WorldScene scene,double[] bounds,int[] left,int[] right,int[] section,Ranges faceRanges,Ranges markerRanges,List<WorldProblem> problems,RenderMemory memory,RenderMemory.Hold admission) {
        this.source=source;this.anchor=anchor;this.memory=memory;this.admission=admission;this.owner=owner;this.scene=scene;this.bounds=bounds;this.left=left;this.right=right;this.section=section;this.faceRanges=faceRanges;this.markerRanges=markerRanges;
        bytes=(long)bounds.length*8+((long)left.length+right.length+section.length)*4+faceRanges.bytes()+markerRanges.bytes();this.problems=List.copyOf(problems);released=owner==null;
    }
    /** Consumes this fresh Result owner on all outcomes after nonnull checks; pass retain() for a shared scene. */
    public static WorldPickIndex prepare(WorldScene.Result owner,BooleanSupplier cancelled) { return prepare(owner,Limits.DEFAULT,cancelled); }
    public static WorldPickIndex prepare(WorldScene.Result owner,Limits limits,BooleanSupplier cancelled) { return prepare(owner,limits,null,cancelled); }
    public static WorldPickIndex prepare(WorldScene.Result owner,Limits limits,RenderMemory memory,BooleanSupplier cancelled) {
        return prepare(owner,limits,memory,RenderMemory.Hold.none(),-1,cancelled);
    }
    private static long indexBytes(int sections,int drawable,int faceBatches,int markerBatches) {
        int nodes=drawable==0?0:drawable*2-1,starts=(faceBatches==0?0:sections+1)+(markerBatches==0?0:sections+1);
        return (long)nodes*(6*Double.BYTES+3*Integer.BYTES)+((long)faceBatches+markerBatches)*6*Float.BYTES+(long)starts*Integer.BYTES;
    }
    private static long preparationBytes(int sections,int drawable,int faceBatches,int markerBatches) { return indexBytes(sections,drawable,faceBatches,markerBatches)+(long)drawable*Integer.BYTES+(drawable==0?0:Integer.BYTES); }
    /** Verified scene counters size the peak in constant time; actual counts are rechecked on the worker. */
    static long reservation(WorldScene scene,Limits limits) {
        var usage=scene.usage();long source=usage.knownPayloadBytes(),peak=preparationBytes(usage.sections(),usage.drawableSections(),usage.faceBatches(),usage.markerBatches());
        if(source>limits.workingBytes())throw new IllegalArgumentException("Picking source scene exceeds the preparation allowance");
        return Math.min(limits.workingBytes(),source+Math.min(limits.indexBytes(),peak));
    }
    /** Consumes an independently retained worker hold; the worker keeps its original handle through unwind. */
    static WorldPickIndex prepareAdmitted(WorldScene.Result owner,Limits limits,RenderMemory memory,RenderMemory.Hold admission,long reserved,BooleanSupplier cancelled) {
        if(reserved<0)throw new IllegalArgumentException("Invalid admitted picking reservation");
        return prepare(owner,limits,memory,Objects.requireNonNull(admission),reserved,cancelled);
    }
    private static WorldPickIndex prepare(WorldScene.Result owner,Limits limits,RenderMemory memory,RenderMemory.Hold admission,long reserved,BooleanSupplier cancelled) {
        Objects.requireNonNull(owner);Objects.requireNonNull(limits);Objects.requireNonNull(cancelled);boolean transferred=false;var source=owner.source();
        try {
            WorldProblem.check(cancelled);if(owner.requiresPlaceholder())return failed(source,owner.problems());var scene=owner.scene().orElseThrow();int count=0,faces=0,markers=0;
            for(var mesh:scene.sections()) { WorldProblem.check(cancelled);if(mesh.bounds().isPresent())count++;faces+=mesh.faceBatchCount();markers+=WorldMesh.batchCount(mesh.markers().size()); }
            var usage=scene.usage();if(scene.sections().size()!=usage.sections()||count!=usage.drawableSections()||faces!=usage.faceBatches()||markers!=usage.markerBatches())throw WorldProblem.fail(WorldProblem.Code.ASSIGNMENT,"Picking source sections/ranges differ from the complete scene receipt");
            int nodes=count==0?0:count*2-1;
            long bytes=indexBytes(scene.sections().size(),count,faces,markers),peak=preparationBytes(scene.sections().size(),count,faces,markers);
            if(peak>limits.indexBytes()||scene.usage().knownPayloadBytes()+peak>limits.workingBytes())throw WorldProblem.fail(WorldProblem.Code.LIMIT,"Picking hierarchy/range arrays or combined CPU payload budget exceeded");
            if(reserved>=0) {
                if(memory!=null&&!admission.tracked()||admission.tracked()&&!admission.belongsTo(memory))throw new IllegalArgumentException("Admitted picking preparation requires its exact memory coordinator");
                if(scene.usage().knownPayloadBytes()+peak>reserved||admission.tracked()&&scene.usage().knownPayloadBytes()+peak>admission.bytes())throw WorldProblem.fail(WorldProblem.Code.LIMIT,"Picking preparation exceeds its previously admitted source/index peak");
            }else if(memory!=null)try { admission=memory.hold("World picking index",scene.usage().knownPayloadBytes()+peak); }
            catch(RenderMemory.LimitException failure) { throw WorldProblem.fail(WorldProblem.Code.LIMIT,failure.getMessage()); }
            var extent=scene.snapshot().extent();var anchor=new WorldSnapshot.Position(extent.minX(),extent.minY(),extent.minZ());int[] admitted=new int[count];int at=0;
            for(int i=0;i<scene.sections().size();i++) { WorldProblem.check(cancelled);if(scene.sections().get(i).bounds().isPresent())admitted[at++]=i; }
            var result=new WorldPickIndex(source,anchor,owner,scene,new double[nodes*6],new int[nodes],new int[nodes],new int[nodes],faces==0?Ranges.EMPTY:new Ranges(faces,scene.sections().size()),markers==0?Ranges.EMPTY:new Ranges(markers,scene.sections().size()),List.of(),memory,admission);Arrays.fill(result.section,-1);
            if(count>0)result.build(admitted,0,count,new int[1],cancelled);result.buildRanges(cancelled);WorldProblem.check(cancelled);admitted=null;
            if(reserved<0)admission.shrinkTo(scene.usage().knownPayloadBytes()+bytes);transferred=true;return result;
        }catch(WorldProblem.Failure failure) { WorldProblem.check(cancelled);return failed(source,List.of(failure.problem)); }
        catch(ArithmeticException overflow) { WorldProblem.check(cancelled);return failed(source,List.of(new WorldProblem(WorldProblem.Code.COORDINATE,Optional.empty(),"Picking index anchor subtraction exceeds exact coordinate bounds"))); }
        catch(OutOfMemoryError exhausted) { WorldProblem.check(cancelled);return failed(source,List.of(new WorldProblem(WorldProblem.Code.MEMORY,Optional.empty(),"Not enough memory to index the complete scene; no partial picking index was published"))); }
        finally { if(!transferred) { owner.close();admission.close(); } }
    }
    private static WorldPickIndex failed(WorldSnapshot.Source source,List<WorldProblem> problems) { return new WorldPickIndex(source,new WorldSnapshot.Position(0,0,0),null,null,EMPTY_BOUNDS,EMPTY_NODES,EMPTY_NODES,EMPTY_NODES,Ranges.EMPTY,Ranges.EMPTY,problems,null,RenderMemory.Hold.none()); }
    private static double relative(long value,long origin) { long difference=Math.subtractExact(value,origin);if(Math.abs((double)difference)>1048576)throw WorldProblem.fail(WorldProblem.Code.COORDINATE,"Picking coordinates exceed the relative precision bound");return difference; }
    private int build(int[] admitted,int from,int to,int[] next,BooleanSupplier cancelled) {
        WorldProblem.check(cancelled);int node=next[0]++,offset=node*6;
        if(to-from==1) {
            int index=admitted[from];section[node]=index;var mesh=scene.sections().get(index);var box=mesh.bounds().orElseThrow();double x=relative(mesh.origin().x(),anchor.x()),y=relative(mesh.origin().y(),anchor.y()),z=relative(mesh.origin().z(),anchor.z());
            bounds[offset]=x+box.min().x();bounds[offset+1]=y+box.min().y();bounds[offset+2]=z+box.min().z();bounds[offset+3]=x+box.max().x();bounds[offset+4]=y+box.max().y();bounds[offset+5]=z+box.max().z();
        }else {
            int middle=(from+to)>>>1;left[node]=build(admitted,from,middle,next,cancelled);right[node]=build(admitted,middle,to,next,cancelled);
            for(int axis=0;axis<3;axis++) { bounds[offset+axis]=Math.min(bounds[left[node]*6+axis],bounds[right[node]*6+axis]);bounds[offset+axis+3]=Math.max(bounds[left[node]*6+axis+3],bounds[right[node]*6+axis+3]); }
        }
        return node;
    }
    /** Preserve original face order; bounds come from emitted vertices, including native offsets and overhangs. */
    private void buildRanges(BooleanSupplier cancelled) {
        if(faceRanges==Ranges.EMPTY&&markerRanges==Ranges.EMPTY)return;int faceBatch=0,markerBatch=0;
        for(int sectionIndex=0;sectionIndex<scene.sections().size();sectionIndex++) {
            WorldProblem.check(cancelled);var mesh=scene.sections().get(sectionIndex);
            if(faceRanges!=Ranges.EMPTY)faceRanges.starts[sectionIndex]=faceBatch;
            if(markerRanges!=Ranges.EMPTY)markerRanges.starts[sectionIndex]=markerBatch;
            for(int batch=0;batch<mesh.faceBatchCount();batch++) {
                WorldProblem.check(cancelled);int at=faceBatch++*6;
                for(int axis=0;axis<3;axis++) { faceRanges.bounds[at+axis]=mesh.faceBatchMinimum(batch,axis);faceRanges.bounds[at+axis+3]=mesh.faceBatchMaximum(batch,axis); }
            }
            for(int first=0;first<mesh.markers().size();first+=BATCH_PRIMITIVES) {
                WorldProblem.check(cancelled);int at=markerBatch++*6,end=Math.min(mesh.markers().size(),first+BATCH_PRIMITIVES);emptyBounds(markerRanges,at);
                for(int marker=first;marker<end;marker++) {
                    var position=mesh.markers().get(marker).position();
                    float x=(float)relative(position.x(),mesh.origin().x()),y=(float)relative(position.y(),mesh.origin().y()),z=(float)relative(position.z(),mesh.origin().z());
                    include(markerRanges,at,0,x,x+1);include(markerRanges,at,1,y,y+1);include(markerRanges,at,2,z,z+1);
                }
            }
        }
        if(faceRanges!=Ranges.EMPTY)faceRanges.starts[scene.sections().size()]=faceBatch;
        if(markerRanges!=Ranges.EMPTY)markerRanges.starts[scene.sections().size()]=markerBatch;
        if(faceBatch*6!=faceRanges.bounds.length||markerBatch*6!=markerRanges.bounds.length)throw new IllegalStateException("Picking range count changed during construction");
    }
    private static void emptyBounds(Ranges ranges,int at) { for(int axis=0;axis<3;axis++) { ranges.bounds[at+axis]=Float.POSITIVE_INFINITY;ranges.bounds[at+axis+3]=Float.NEGATIVE_INFINITY; } }
    private static void include(Ranges ranges,int at,int axis,float low,float high) { ranges.bounds[at+axis]=Math.min(ranges.bounds[at+axis],low);ranges.bounds[at+axis+3]=Math.max(ranges.bounds[at+axis+3],high); }
    public UUID id() { return id; }
    public Optional<RenderMemory> memory() { return Optional.ofNullable(memory); }
    /** Exact scene identity, not just matching native source, volume or asset metadata. */
    public synchronized boolean matches(WorldScene expected) { return !closed&&!requiresPlaceholder()&&scene==Objects.requireNonNull(expected); }
    RenderMemory.Hold idleAdmission() { return admission; }
    public WorldSnapshot.Source source() { return source; }
    public List<WorldProblem> problems() { return problems; }
    public boolean requiresPlaceholder() { return !problems.isEmpty(); }
    public synchronized long retainedBytes() { return released?0:bytes; }
    /** Includes the source scene for tracked indices; retainedBytes() reports hierarchy and range arrays only. */
    public long admittedBytes() { return admission.bytes(); }
    private long displayedBytes(AtlasRaster displayed) {
        if(displayed==null)return 0;
        if(displayed.requiresPlaceholder()||displayed.layout()!=scene.layout())throw WorldProblem.fail(WorldProblem.Code.ATLAS,"Picking raster must match the exact displayed scene layout");
        var known=new IdentityHashMap<RgbaImage,Boolean>();for(var page:scene.raster().pages())known.put(page,Boolean.TRUE);long bytes=0;
        for(var page:displayed.pages())if(known.put(page,Boolean.TRUE)==null)bytes+=page.bytes();return bytes;
    }
    /** Full immutable request source bound for an independently admitted pointer worker. */
    synchronized long queryPayloadBytes(AtlasRaster displayed) {
        if(closed)throw new IllegalStateException("Picking index is closed");
        if(requiresPlaceholder())return 0;return scene.usage().knownPayloadBytes()+bytes+displayedBytes(displayed);
    }
    public boolean closed() { return closed; }
    public synchronized boolean released() { return released; }
    public WorldPick query(WorldRay ray,BooleanSupplier cancelled) { return query(ray,Options.DEFAULT,null,cancelled); }
    /** Supply the exact displayed animated raster for alpha tests; null uses the scene's original raster. */
    public WorldPick query(WorldRay ray,Options options,AtlasRaster displayed,BooleanSupplier cancelled) {
        Objects.requireNonNull(ray);Objects.requireNonNull(options);Objects.requireNonNull(cancelled);if(cancelled.getAsBoolean())throw new CancellationException("Scene picking cancelled");
        synchronized(this) { if(closed)throw new IllegalStateException("Picking index is closed");if(requiresPlaceholder())return new WorldPick(id,source,ray,Optional.empty(),new WorldPick.Stats(0,0,0,0,0),problems,options.clip());if(queries>=64)throw new IllegalStateException("Concurrent picking query limit reached");queries++; }
        Query work=null;RenderMemory.Hold displayAdmission=RenderMemory.Hold.none();
        try {
            if(memory!=null) {
                long extra=displayedBytes(displayed);
                if(extra>0)try { displayAdmission=memory.hold("Picking displayed atlas",extra); }
                catch(RenderMemory.LimitException failure) { throw WorldProblem.fail(WorldProblem.Code.LIMIT,failure.getMessage()); }
            }
            work=new Query(ray,options,displayed,cancelled);return work.execute(); }
        catch(WorldProblem.Failure failure) { checkQuery(cancelled);return new WorldPick(id,source,ray,Optional.empty(),work==null?new WorldPick.Stats(0,0,0,0,0):work.stats(),List.of(failure.problem),options.clip()); }
        catch(ArithmeticException overflow) { checkQuery(cancelled);return new WorldPick(id,source,ray,Optional.empty(),work==null?new WorldPick.Stats(0,0,0,0,0):work.stats(),List.of(new WorldProblem(WorldProblem.Code.COORDINATE,Optional.empty(),"Ray and picking index anchors exceed exact coordinate bounds")),options.clip()); }
        catch(OutOfMemoryError exhausted) { checkQuery(cancelled);return new WorldPick(id,source,ray,Optional.empty(),work==null?new WorldPick.Stats(0,0,0,0,0):work.stats(),List.of(new WorldProblem(WorldProblem.Code.MEMORY,Optional.empty(),"Not enough memory to complete picking; no partial hit was published")),options.clip()); }
        finally { try { synchronized(this) { queries--;releaseIfIdle(); } }finally { displayAdmission.close(); } }
    }
    private void checkQuery(BooleanSupplier cancelled) { if(closed||cancelled.getAsBoolean())throw new CancellationException("Scene picking cancelled"); }
    private void releaseIfIdle() { if(closed&&queries==0&&!released) { owner.close();owner=null;scene=null;bounds=EMPTY_BOUNDS;left=EMPTY_NODES;right=EMPTY_NODES;section=EMPTY_NODES;faceRanges=Ranges.EMPTY;markerRanges=Ranges.EMPTY;released=true;admission.close(); } }
    @Override public synchronized void close() { closed=true;releaseIfIdle(); }
    private final class Query {
        final WorldRay ray;final Options options;final WorldClip.Bounds clip;final AtlasRaster raster;final BooleanSupplier cancelled;final double ox,oy,oz;double nearest,hitU,hitV,normalX,normalY,normalZ;int hitSection=-1,hitFace=-1,hitMarker=-1;int nodes,sections,faces,markers,alphaRejected,batches,culledBatches;
        Query(WorldRay ray,Options options,AtlasRaster displayed,BooleanSupplier cancelled) {
            this.ray=ray;this.options=options;clip=options.clip().relative(anchor);this.cancelled=cancelled;raster=displayed==null?scene.raster():displayed;
            if(raster.requiresPlaceholder()||raster.layout()!=scene.layout())throw WorldProblem.fail(WorldProblem.Code.ATLAS,"Picking raster must match the exact displayed scene layout");
            ox=relative(ray.anchor().x(),anchor.x())+ray.x();oy=relative(ray.anchor().y(),anchor.y())+ray.y();oz=relative(ray.anchor().z(),anchor.z())+ray.z();nearest=ray.maximum();
        }
        void check() { checkQuery(cancelled); }
        WorldPick.Stats stats() { return new WorldPick.Stats(nodes,sections,faces,markers,alphaRejected,batches,culledBatches); }
        WorldPick execute() { check();if(section.length>0)visit(0,nodeEntry(0));check();return new WorldPick(id,source,ray,Optional.ofNullable(hit()),stats(),List.of(),options.clip()); }
        double box(double minX,double minY,double minZ,double maxX,double maxY,double maxZ,double x,double y,double z) {
            minX=Math.max(minX,clip.minX());minY=Math.max(minY,clip.minY());minZ=Math.max(minZ,clip.minZ());maxX=Math.min(maxX,clip.maxX());maxY=Math.min(maxY,clip.maxY());maxZ=Math.min(maxZ,clip.maxZ());
            if(minX>maxX||minY>maxY||minZ>maxZ)return Double.POSITIVE_INFINITY;
            double low=ray.minimum(),high=nearest;
            if(ray.dx()==0) { if(x<minX||x>maxX)return Double.POSITIVE_INFINITY; }else { double a=(minX-x)/ray.dx(),b=(maxX-x)/ray.dx();low=Math.max(low,Math.min(a,b));high=Math.min(high,Math.max(a,b)); }
            if(ray.dy()==0) { if(y<minY||y>maxY)return Double.POSITIVE_INFINITY; }else { double a=(minY-y)/ray.dy(),b=(maxY-y)/ray.dy();low=Math.max(low,Math.min(a,b));high=Math.min(high,Math.max(a,b)); }
            if(ray.dz()==0) { if(z<minZ||z>maxZ)return Double.POSITIVE_INFINITY; }else { double a=(minZ-z)/ray.dz(),b=(maxZ-z)/ray.dz();low=Math.max(low,Math.min(a,b));high=Math.min(high,Math.max(a,b)); }
            return low<=high?low:Double.POSITIVE_INFINITY;
        }
        double nodeEntry(int node) { check();if(++nodes>options.nodeTests())throw WorldProblem.fail(WorldProblem.Code.LIMIT,"Picking node-test budget exceeded; no nearest hit can be certified");int at=node*6;return box(bounds[at],bounds[at+1],bounds[at+2],bounds[at+3],bounds[at+4],bounds[at+5],ox,oy,oz); }
        void visit(int node,double entry) {
            check();if(!Double.isFinite(entry)||entry>nearest)return;
            if(section[node]>=0) { mesh(section[node]);return; }int a=left[node],b=right[node];double first=nodeEntry(a),second=nodeEntry(b);
            if(first<=second) { visit(a,first);visit(b,second); }else { visit(b,second);visit(a,first); }
        }
        boolean better(double distance,int sectionIndex,int face,int marker) {
            if(distance<ray.minimum()||distance>nearest)return false;if(hitSection<0||distance<nearest)return true;
            if(sectionIndex!=hitSection)return sectionIndex<hitSection;int kind=face>=0?0:1,old=hitFace>=0?0:1;if(kind!=old)return kind<old;return (face>=0?face:marker)<(hitFace>=0?hitFace:hitMarker);
        }
        void mesh(int sectionIndex) {
            sections++;var mesh=scene.sections().get(sectionIndex);var vertices=mesh.vertexBuffer();var indices=mesh.indexBuffer();double x=relative(mesh.origin().x(),anchor.x()),y=relative(mesh.origin().y(),anchor.y()),z=relative(mesh.origin().z(),anchor.z());
            // Include the triangle query's barycentric edge allowance and relative-coordinate slab rounding.
            double magnitude=Math.max(Math.abs(ox),Math.max(Math.abs(oy),Math.abs(oz))),margin=1e-6+8*Math.ulp(Math.max(magnitude,Math.max(Math.abs(x),Math.max(Math.abs(y),Math.abs(z)))+128));
            if(options.batchCulling()&&!mesh.faces().isEmpty()) {
                int start=faceRanges.starts[sectionIndex];
                for(int batch=start;batch<faceRanges.starts[sectionIndex+1];batch++) {
                    check();batches++;if(!range(faceRanges,batch,x,y,z,margin)) { culledBatches++;continue; }
                    int first=(batch-start)*BATCH_PRIMITIVES;
                    faceRange(mesh,sectionIndex,first,Math.min(mesh.faces().size(),first+BATCH_PRIMITIVES),vertices,indices,ox-x,oy-y,oz-z);
                }
            }else faceRange(mesh,sectionIndex,0,mesh.faces().size(),vertices,indices,ox-x,oy-y,oz-z);
            if(options.batchCulling()&&!mesh.markers().isEmpty()) {
                int start=markerRanges.starts[sectionIndex];
                for(int batch=start;batch<markerRanges.starts[sectionIndex+1];batch++) {
                    check();batches++;if(!range(markerRanges,batch,x,y,z,margin)) { culledBatches++;continue; }
                    int first=(batch-start)*BATCH_PRIMITIVES;markerRange(mesh,sectionIndex,first,Math.min(mesh.markers().size(),first+BATCH_PRIMITIVES));
                }
            }else markerRange(mesh,sectionIndex,0,mesh.markers().size());
        }
        boolean range(Ranges ranges,int batch,double x,double y,double z,double margin) {
            int at=batch*6;var b=ranges.bounds;
            double entry=box(x+b[at]-margin,y+b[at+1]-margin,z+b[at+2]-margin,x+b[at+3]+margin,y+b[at+4]+margin,z+b[at+5]+margin,ox,oy,oz);
            return Double.isFinite(entry)&&entry<=nearest;
        }
        void markerRange(WorldMesh mesh,int sectionIndex,int first,int end) {
            for(int marker=first;marker<end;marker++) {
                if((marker&255)==0)check();if(++markers>options.markerTests())throw WorldProblem.fail(WorldProblem.Code.LIMIT,"Picking marker-test budget exceeded; no partial hit was published");var value=mesh.markers().get(marker);var position=value.position();double px=Math.subtractExact(position.x(),anchor.x()),py=(long)position.y()-anchor.y(),pz=Math.subtractExact(position.z(),anchor.z());double distance=box(px,py,pz,px+1,py+1,pz+1,ox,oy,oz);
                if(!Double.isFinite(distance)||!better(distance,sectionIndex,-1,marker))continue;hitSection=sectionIndex;hitFace=-1;hitMarker=marker;nearest=distance;
            }
        }
        void faceRange(WorldMesh mesh,int sectionIndex,int first,int end,FloatBuffer vertices,IntBuffer indices,double x,double y,double z) {
            for(int face=first;face<end;face++) {
                if((face&255)==0)check();if(++faces>options.faceTests())throw WorldProblem.fail(WorldProblem.Code.LIMIT,"Picking face-test budget exceeded; no partial hit was published");
                for(int triangle=0;triangle<2;triangle++)triangle(mesh,sectionIndex,face,triangle,vertices,indices,x,y,z);
            }
        }
        WorldPick.Hit hit() {
            if(hitSection<0)return null;var mesh=scene.sections().get(hitSection);WorldSnapshot.Position position;RenderBlockState state;WorldSnapshot.Biome biome;Optional<WorldPick.Surface> surface;List<ModelPlan.Problem> visualProblems;
            if(hitFace>=0) { position=mesh.positionForFace(hitFace);state=mesh.stateForFace(hitFace);biome=mesh.biomeForFace(hitFace);surface=Optional.of(new WorldPick.Surface(mesh.faces().get(hitFace).source().source(),hitU,hitV,normalX,normalY,normalZ));visualProblems=List.of(); }
            else { var marker=mesh.markers().get(hitMarker);position=marker.position();state=marker.state();biome=mesh.snapshot().biome(mesh.cells().get(marker.cell()).sourceCell());surface=Optional.empty();visualProblems=marker.problems(); }
            double x=Math.subtractExact(position.x(),anchor.x()),y=(long)position.y()-anchor.y(),z=Math.subtractExact(position.z(),anchor.z());
            return new WorldPick.Hit(position,state,biome,hitSection,hitFace,hitMarker,nearest,ox+ray.dx()*nearest-x,oy+ray.dy()*nearest-y,oz+ray.dz()*nearest-z,surface,visualProblems);
        }
        void triangle(WorldMesh mesh,int sectionIndex,int face,int triangle,FloatBuffer v,IntBuffer indices,double x,double y,double z) {
            int start=face*6+triangle*3,a=indices.get(start)*8,b=indices.get(start+1)*8,c=indices.get(start+2)*8;
            double ax=v.get(a),ay=v.get(a+1),az=v.get(a+2),ex=v.get(b)-ax,ey=v.get(b+1)-ay,ez=v.get(b+2)-az,fx=v.get(c)-ax,fy=v.get(c+1)-ay,fz=v.get(c+2)-az;
            double px=ray.dy()*fz-ray.dz()*fy,py=ray.dz()*fx-ray.dx()*fz,pz=ray.dx()*fy-ray.dy()*fx,det=ex*px+ey*py+ez*pz;
            double epsilon=1e-12*Math.hypot(Math.hypot(ex,ey),ez)*Math.hypot(Math.hypot(fx,fy),fz);if(options.backFaces()?Math.abs(det)<=epsilon:det<=epsilon)return;
            double tx=x-ax,ty=y-ay,tz=z-az,beta=(tx*px+ty*py+tz*pz)/det,qx=ty*ez-tz*ey,qy=tz*ex-tx*ez,qz=tx*ey-ty*ex,gamma=(ray.dx()*qx+ray.dy()*qy+ray.dz()*qz)/det;
            if(beta< -1e-10||gamma< -1e-10||beta+gamma>1+1e-10)return;double distance=(fx*qx+fy*qy+fz*qz)/det;if(!better(distance,sectionIndex,face,-1)||!clip.contains(ox+ray.dx()*distance,oy+ray.dy()*distance,oz+ray.dz()*distance))return;
            beta=Math.max(0,beta);gamma=Math.max(0,gamma);double sum=beta+gamma;if(sum>1) { beta/=sum;gamma/=sum; }double weight=1-beta-gamma;
            double u=weight*v.get(a+6)+beta*v.get(b+6)+gamma*v.get(c+6),vv=weight*v.get(a+7)+beta*v.get(b+7)+gamma*v.get(c+7);var material=mesh.faces().get(face).source();
            if(options.alpha()==Alpha.DISPLAYED_BASE_LEVEL&&material.alpha()!=TexturePreparation.AlphaClass.OPAQUE) {
                var image=raster.pages().get(material.page());int ix=Math.clamp((int)Math.floor(u*image.width()),0,image.width()-1),iy=Math.clamp((int)Math.floor(vv*image.height()),0,image.height()-1),alpha=image.alpha(ix,iy);
                if(!material.alpha().acceptsAlpha(alpha)) { alphaRejected++;return; }
            }
            double nx=ey*fz-ez*fy,ny=ez*fx-ex*fz,nz=ex*fy-ey*fx,length=Math.hypot(Math.hypot(nx,ny),nz);
            hitSection=sectionIndex;hitFace=face;hitMarker=-1;hitU=u;hitV=vv;normalX=nx/length;normalY=ny/length;normalZ=nz/length;nearest=distance;
        }
    }
}
