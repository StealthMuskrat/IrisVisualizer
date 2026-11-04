package dev.irisvisualizer.renderer;

import java.util.*;
import java.util.function.BooleanSupplier;

/** Pure block-local geometry from complete, explicitly captured vanilla fluid render inputs. */
public final class FluidGeometry {
    private FluidGeometry() {}
    public enum Kind {
        WATER("water_still","water_flow","water_overlay"),LAVA("lava_still","lava_flow",null);
        private final AssetId still,flow;private final Optional<AssetId> overlay;
        Kind(String still,String flow,String overlay) { this.still=AssetId.minecraft("block/"+still);this.flow=AssetId.minecraft("block/"+flow);this.overlay=Optional.ofNullable(overlay).map(value->AssetId.minecraft("block/"+value)); }
        public AssetId still() { return still; }public AssetId flow() { return flow; }public Optional<AssetId> overlay() { return overlay; }
    }
    /** Heights precede the top inset. Faces/overlays use ModelPlan.Direction ordinal bits. */
    public record Cell(Kind kind,float northWest,float southWest,float southEast,float northEast,int faces,int overlays,
                       boolean backTop,boolean flowing,float flowSin,float flowCos,int rgb,int emission) {
        public Cell {
            Objects.requireNonNull(kind);
            if(!height(northWest)||!height(southWest)||!height(southEast)||!height(northEast))throw new IllegalArgumentException("Invalid captured fluid corner height");
            if((faces&~63)!=0||(overlays&~60)!=0||(overlays&~faces)!=0||kind==Kind.LAVA&&overlays!=0||!Float.isFinite(flowSin)||!Float.isFinite(flowCos)||Math.abs(flowSin)>0.25f||Math.abs(flowCos)>0.25f||!flowing&&(flowSin!=0||flowCos!=0)||(rgb&0xff000000)!=0||emission<0||emission>15)throw new IllegalArgumentException("Invalid captured fluid render input");
        }
        public boolean face(ModelPlan.Direction direction) { return (faces&(1<<direction.ordinal()))!=0; }
    }
    private static boolean height(float value) { return Float.isFinite(value)&&value>0&&value<=1; }
    public static final int MAX_QUADS=11;
    public static long packedBytes(Cell cell) { return (long)quadCount(cell)*MeshPacket.PACKED_BYTES_PER_QUAD; }
    public static int quadCount(Cell cell) {
        Objects.requireNonNull(cell);int count=cell.face(ModelPlan.Direction.UP)?cell.backTop()?2:1:0;if(cell.face(ModelPlan.Direction.DOWN))count++;
        for(var face:SIDES)if(cell.face(face))count+=(cell.overlays()&(1<<face.ordinal()))!=0?1:2;return count;
    }
    private static final List<ModelPlan.Direction> SIDES=List.of(ModelPlan.Direction.NORTH,ModelPlan.Direction.EAST,ModelPlan.Direction.SOUTH,ModelPlan.Direction.WEST);
    /** Same complete input key permits prototype reuse within the exact original state/snapshot. */
    public static BlockMesh bake(ClientAssetSnapshot assets,WorldSnapshot snapshot,int cell,Cell input,NativeGeometry.Limits limits,BooleanSupplier cancelled) {
        Objects.requireNonNull(input);Objects.requireNonNull(limits);Objects.requireNonNull(cancelled);
        return NativeGeometry.read(assets,snapshot,cell,NativeMeshSource.Kind.FLUID,"Vanilla fluid render inputs; radius-zero water color; exact-input shared prototype",new NativeGeometry.Reader() {
            private List<NativeGeometry.Quad> values;
            @Override public int size() { return quadCount(input); }
            @Override public NativeGeometry.Quad quad(int index) { if(values==null)values=quads(input,cancelled);return values.get(index); }
        },false,limits,cancelled);
    }
    private static List<NativeGeometry.Quad> quads(Cell cell,BooleanSupplier cancelled) {
        var result=new ArrayList<NativeGeometry.Quad>(quadCount(cell));float inset=cell.face(ModelPlan.Direction.UP)?0.001f:0;
        float nw=cell.northWest()-inset,sw=cell.southWest()-inset,se=cell.southEast()-inset,ne=cell.northEast()-inset,bottom=cell.face(ModelPlan.Direction.DOWN)?0.001f:0;
        if(cell.face(ModelPlan.Direction.UP)) {
            float s=cell.flowSin(),c=cell.flowCos();var sprite=cell.flowing()?cell.kind().flow():cell.kind().still();
            List<BlockMesh.Vertex> vertices=cell.flowing()?List.of(v(0,nw,0,0.5f+(-c-s),0.5f+(-c+s)),v(0,sw,1,0.5f+(-c+s),0.5f+(c+s)),v(1,se,1,0.5f+(c+s),0.5f+(c-s)),v(1,ne,0,0.5f+(c-s),0.5f+(-c-s))):
                List.of(v(0,nw,0,0,0),v(0,sw,1,0,1),v(1,se,1,1,1),v(1,ne,0,1,0));
            add(result,cell,ModelPlan.Direction.UP,sprite,vertices,cell.backTop(),cancelled);
        }
        if(cell.face(ModelPlan.Direction.DOWN))add(result,cell,ModelPlan.Direction.DOWN,cell.kind().still(),List.of(v(0,bottom,0,0,0),v(1,bottom,0,1,0),v(1,bottom,1,1,1),v(0,bottom,1,0,1)),false,cancelled);
        for(var face:SIDES)if(cell.face(face)) {
            float a,b,x0,z0,x1,z1;
            switch(face) {
                case NORTH->{ a=nw;b=ne;x0=0;x1=1;z0=z1=0.001f; }
                case EAST->{ a=ne;b=se;x0=x1=1-0.001f;z0=0;z1=1; }
                case SOUTH->{ a=se;b=sw;x0=1;x1=0;z0=z1=1-0.001f; }
                case WEST->{ a=sw;b=nw;x0=x1=0.001f;z0=1;z1=0; }
                default->throw new AssertionError(face);
            }
            boolean overlay=(cell.overlays()&(1<<face.ordinal()))!=0;var sprite=overlay?cell.kind().overlay().orElseThrow():cell.kind().flow();
            add(result,cell,face,sprite,List.of(v(x0,a,z0,0,(1-a)*0.5f),v(x1,b,z1,0.5f,(1-b)*0.5f),v(x1,bottom,z1,0.5f,0.5f),v(x0,bottom,z0,0,0.5f)),!overlay,cancelled);
        }
        return result;
    }
    private static BlockMesh.Vertex v(float x,float y,float z,float u,float v) { return new BlockMesh.Vertex(x,y,z,u,v); }
    private static void add(List<NativeGeometry.Quad> result,Cell cell,ModelPlan.Direction face,AssetId sprite,List<BlockMesh.Vertex> vertices,boolean back,BooleanSupplier cancelled) {
        if(cancelled.getAsBoolean())throw new java.util.concurrent.CancellationException("Fluid geometry cancelled");var normal=ModelTransforms.normal(face);
        var material=new ModelPlan.Material(sprite,false,cell.rgb());var shade=Optional.of(face);
        result.add(new NativeGeometry.Quad(face,Optional.empty(),new ModelPlan.Vec3((float)normal.x(),(float)normal.y(),(float)normal.z()),material,-1,cell.emission(),false,shade,vertices));
        if(back) {
            var opposite=FaceGeometry.opposite(face);result.add(new NativeGeometry.Quad(opposite,Optional.empty(),new ModelPlan.Vec3((float)-normal.x(),(float)-normal.y(),(float)-normal.z()),material,-1,cell.emission(),false,shade,List.of(vertices.get(0),vertices.get(3),vertices.get(2),vertices.get(1))));
        }
    }
}
