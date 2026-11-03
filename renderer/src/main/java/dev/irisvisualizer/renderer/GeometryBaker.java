package dev.irisvisualizer.renderer;

import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Worker-side CPU baking. Native ticket production, world placement, neighbor visibility and GL remain separate. */
public final class GeometryBaker {
    private GeometryBaker() {}
    public record Limits(int parts,int elements,int quads,long packedBytes,double coordinateBlocks,double angleDegrees) {
        public static final Limits DEFAULT=new Limits(1024,4096,24_576,16L*1024*1024,8,360);
        public Limits {
            if(parts<1||parts>1024||elements<1||elements>4096||quads<1||quads>24_576||packedBytes<0||packedBytes>16L*1024*1024||!Double.isFinite(coordinateBlocks)||coordinateBlocks<=0||coordinateBlocks>64||!Double.isFinite(angleDegrees)||angleDegrees<0||angleDegrees>36_000)
                throw new IllegalArgumentException("Geometry limits exceed renderer bounds");
        }
    }
    private record Selected(ModelPlan.Part part,ModelPlan.Choice choice,int ticket) {}
    private static final class Work {
        final ModelPlan plan;final Limits limits;final BooleanSupplier cancelled;final List<BlockMesh.Quad> quads=new ArrayList<>();
        int elements;double minX=Double.POSITIVE_INFINITY,minY=minX,minZ=minX,maxX=Double.NEGATIVE_INFINITY,maxY=maxX,maxZ=maxX;
        Work(ModelPlan plan,Limits limits,BooleanSupplier cancelled) { this.plan=plan;this.limits=limits;this.cancelled=cancelled; }
        void check() { if(cancelled.getAsBoolean())throw new CancellationException("Block geometry baking cancelled"); }
        void require(boolean valid,ModelPlan.Code code,ModelPlan.Source source,String reason) {
            check();if(!valid)throw new ModelFields.Failure(new ModelPlan.Problem(code,source.kind(),source.asset(),source.pointer(),reason));
        }
        void bounds(ModelTransforms.V value,ModelPlan.Source source) {
            require(value.finite() && Math.abs(value.x())<=limits.coordinateBlocks() && Math.abs(value.y())<=limits.coordinateBlocks() && Math.abs(value.z())<=limits.coordinateBlocks(),ModelPlan.Code.GEOMETRY_RANGE,source,"Baked vertex is outside the finite local-coordinate budget");
            minX=Math.min(minX,value.x());minY=Math.min(minY,value.y());minZ=Math.min(minZ,value.z());maxX=Math.max(maxX,value.x());maxY=Math.max(maxY,value.y());maxZ=Math.max(maxZ,value.z());
        }
        Optional<BlockMesh.Bounds> bounds() {
            if(quads.isEmpty())return Optional.empty();return Optional.of(new BlockMesh.Bounds(new ModelPlan.Vec3((float)minX,(float)minY,(float)minZ),new ModelPlan.Vec3((float)maxX,(float)maxY,(float)maxZ)));
        }
        void bake(Selected selected,int selection) {
            check();var choice=selected.choice();var model=choice.model();var dispatch=new ModelTransforms.Quarter(choice.x(),choice.y(),choice.z());
            require(model.elements().size()<=limits.elements()-elements,ModelPlan.Code.LIMIT,selected.part().dispatch(),"Selected model-element budget reached");elements+=model.elements().size();
            for(var element:model.elements()) {
                check();var source=element.source();var from=element.from();var to=element.to();
                require(from.x()<=to.x() && from.y()<=to.y() && from.z()<=to.z(),ModelPlan.Code.UNSUPPORTED_GEOMETRY,source,"Reversed element endpoints require native winding verification");
                ModelTransforms.Element transform=null;
                if(element.rotation().isPresent()) {
                    var rotation=element.rotation().get();
                    require(!rotation.rescale(),ModelPlan.Code.UNSUPPORTED_GEOMETRY,new ModelPlan.Source(source.asset(),source.pointer()+"/rotation/rescale"),"Element rescale requires pinned native verification before baking");
                    double originLimit=limits.coordinateBlocks()*16;var origin=rotation.origin();
                    require(Math.abs((double)origin.x())<=originLimit && Math.abs((double)origin.y())<=originLimit && Math.abs((double)origin.z())<=originLimit,ModelPlan.Code.GEOMETRY_RANGE,source,"Element origin exceeds the supported local precision budget");
                    require(Math.abs((double)rotation.x())<=limits.angleDegrees() && Math.abs((double)rotation.y())<=limits.angleDegrees() && Math.abs((double)rotation.z())<=limits.angleDegrees(),ModelPlan.Code.GEOMETRY_RANGE,source,"Element angle exceeds the supported bake precision budget");
                    transform=new ModelTransforms.Element(rotation);
                }
                for(var entry:element.faces().entrySet()) {
                    check();var side=entry.getKey();var face=entry.getValue();var faceSource=new ModelPlan.Source(source.asset(),source.pointer()+"/faces/"+side.name().toLowerCase(Locale.ROOT));
                    require(quads.size()<limits.quads() && (long)(quads.size()+1)*MeshPacket.PACKED_BYTES_PER_QUAD<=limits.packedBytes(),ModelPlan.Code.LIMIT,faceSource,"Quad or packed geometry byte budget reached");
                    var rectangle=face.uv().orElseGet(()->FaceGeometry.uv(element,side));var locked=choice.uvlock()?dispatch.uvlock(side):null;
                    var vertices=new ArrayList<BlockMesh.Vertex>(4);
                    for(int vertex=0;vertex<4;vertex++) {
                        check();var position=FaceGeometry.corner(element,side,vertex);if(transform!=null)position=transform.position(position);position=dispatch.position(position).scale(1.0/16.0);bounds(position,faceSource);
                        double u=FaceGeometry.u(rectangle,vertex,face.rotation()),v=FaceGeometry.v(rectangle,vertex,face.rotation());
                        if(locked!=null){double beforeU=u,beforeV=v;u=locked.u(beforeU,beforeV);v=locked.v(beforeU,beforeV);}
                        require(Double.isFinite(u)&&Double.isFinite(v)&&u>=0&&u<=16&&v>=0&&v<=16,ModelPlan.Code.UV_RANGE,faceSource,"UVs outside the source sprite require atlas-sampling verification");
                        vertices.add(new BlockMesh.Vertex((float)position.x(),(float)position.y(),(float)position.z(),(float)(u/16.0),(float)(v/16.0)));
                    }
                    var normal=ModelTransforms.normal(side);if(transform!=null)normal=transform.normal(normal);normal=dispatch.vector(normal);double length=StrictMath.sqrt(normal.dot(normal));
                    require(normal.finite()&&Double.isFinite(length)&&length>0,ModelPlan.Code.GEOMETRY_RANGE,faceSource,"Baked normal is not finite");normal=normal.scale(1.0/length);
                    quads.add(new BlockMesh.Quad(faceSource,selection,side,face.cullface().map(dispatch::direction),new ModelPlan.Vec3((float)normal.x(),(float)normal.y(),(float)normal.z()),face.material(),face.tintIndex(),element.lightEmission(),model.ambientOcclusion(),element.shadeDirectionOverride(),vertices));
                }
            }
        }
    }
    public static BlockMesh bake(ModelPlan plan,int[] nativeTickets,BooleanSupplier cancelled) { return bake(plan,nativeTickets,Limits.DEFAULT,cancelled); }
    public static BlockMesh bake(ModelPlan plan,int[] nativeTickets,Limits limits,BooleanSupplier cancelled) {
        Objects.requireNonNull(plan);Objects.requireNonNull(nativeTickets);Objects.requireNonNull(limits);Objects.requireNonNull(cancelled);
        if(cancelled.getAsBoolean())throw new CancellationException("Block geometry baking cancelled");
        try { return execute(plan,nativeTickets,limits,cancelled); }
        catch(ModelFields.Failure failure) { if(cancelled.getAsBoolean())throw new CancellationException("Block geometry baking cancelled");return BlockMesh.placeholder(plan,List.of(failure.problem)); }
        catch(OutOfMemoryError exhausted) {
            if(cancelled.getAsBoolean())throw new CancellationException("Block geometry baking cancelled");
            return BlockMesh.placeholder(plan,List.of(new ModelPlan.Problem(ModelPlan.Code.MEMORY,ClientAssetSnapshot.Kind.BLOCKSTATE,plan.state().block(),"","Not enough memory to bake block geometry; no partial mesh was published")));
        }
    }
    private static BlockMesh execute(ModelPlan plan,int[] tickets,Limits limits,BooleanSupplier cancelled) {
        if(plan.requiresPlaceholder())return BlockMesh.placeholder(plan,plan.problems());
        var work=new Work(plan,limits,cancelled);var owner=new ModelPlan.Source(ClientAssetSnapshot.Kind.BLOCKSTATE,plan.state().block(),"");
        work.require(plan.assets().version().equals(BlockModels.PROFILE) && plan.assets().expectedVersion().equals(BlockModels.PROFILE),ModelPlan.Code.VERSION,owner,"Geometry semantics are implemented only for the pinned 26.3 profile");
        work.require(plan.parts().size()<=limits.parts() && tickets.length==plan.parts().size(),ModelPlan.Code.SELECTION,owner,"Supply exactly one verified native ticket for each bounded model part");
        int[] captured=tickets.clone();var selected=new ArrayList<Selected>(captured.length);var choices=new ArrayList<BlockMesh.Selection>(captured.length);
        for(int index=0;index<captured.length;index++) {
            work.check();var part=plan.parts().get(index);int ticket=captured[index];
            work.require(ticket>=0 && ticket<part.totalWeight(),ModelPlan.Code.SELECTION,part.dispatch(),"Native model ticket is outside the declared weight range");
            var choice=part.choose(ticket);selected.add(new Selected(part,choice,ticket));
            choices.add(new BlockMesh.Selection(part.dispatch(),part.sourceIndex(),ticket,choice.model().id(),choice.x(),choice.y(),choice.z(),choice.uvlock()));
        }
        for(int index=0;index<selected.size();index++)work.bake(selected.get(index),index);
        work.check();var packet=MeshPacket.pack(plan,work.quads,limits.packedBytes(),cancelled);var result=BlockMesh.success(plan,choices,work.quads,work.bounds(),packet);work.check();return result;
    }
}
