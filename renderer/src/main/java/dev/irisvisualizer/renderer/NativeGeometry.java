package dev.irisvisualizer.renderer;

import java.util.*;
import java.util.function.BooleanSupplier;

/** Captures supplied native visual quads. No fluid simulation, renderer selection or native class loading. */
public final class NativeGeometry {
    private NativeGeometry() {}
    public record Limits(int quads,int selections,long packedBytes,long workingBytes,double coordinateBlocks) {
        public static final Limits DEFAULT=new Limits(24576,1024,16L*1024*1024,64L*1024*1024,8);
        public Limits { if(quads<0||quads>24576||selections<0||selections>4096||packedBytes<0||packedBytes>16L*1024*1024||workingBytes<0||workingBytes>64L*1024*1024||!Double.isFinite(coordinateBlocks)||coordinateBlocks<=0||coordinateBlocks>64)throw new IllegalArgumentException("Native geometry limits exceed renderer bounds"); }
    }
    /** Block-local coordinates in blocks, sprite-local UV, native face label and one supplied unit normal. */
    public record Quad(ModelPlan.Direction face,Optional<ModelPlan.Direction> cullface,ModelPlan.Vec3 normal,ModelPlan.Material material,int tintIndex,int lightEmission,boolean ambientOcclusion,Optional<ModelPlan.Direction> shadeDirectionOverride,List<BlockMesh.Vertex> vertices) {
        public Quad { Objects.requireNonNull(face);Objects.requireNonNull(cullface);Objects.requireNonNull(normal);Objects.requireNonNull(material);Objects.requireNonNull(shadeDirectionOverride);Objects.requireNonNull(vertices);if(vertices.size()!=4||tintIndex< -1||lightEmission<0||lightEmission>15)throw new IllegalArgumentException("Invalid native quad description");vertices=List.copyOf(vertices); }
    }
    /** Caller freezes the native producer until both completion checks return; no reader escapes. */
    public interface Reader {
        int size();
        Quad quad(int index);
        default void complete() {}
    }
    private static void require(boolean valid,RenderBlockState state,String pointer,ModelPlan.Code code,String reason) { if(!valid)throw TextureMetadata.failure(code,ClientAssetSnapshot.Kind.BLOCKSTATE,state.block(),pointer,reason); }
    public static BlockMesh capture(ClientAssetSnapshot assets,WorldSnapshot snapshot,int cell,NativeMeshSource.Kind kind,String policy,List<Quad> quads,BooleanSupplier cancelled) { return capture(assets,snapshot,cell,kind,policy,quads,true,Limits.DEFAULT,cancelled); }
    /** Only explicitly position-independent native output can be reused at other cells of this exact snapshot. */
    public static BlockMesh capture(ClientAssetSnapshot assets,WorldSnapshot snapshot,int cell,NativeMeshSource.Kind kind,String policy,List<Quad> quads,boolean positionDependent,Limits limits,BooleanSupplier cancelled) {
        Objects.requireNonNull(quads);return read(assets,snapshot,cell,kind,policy,new Reader() {
            @Override public int size() { return quads.size(); }
            @Override public Quad quad(int index) { return quads.get(index); }
        },positionDependent,limits,cancelled);
    }
    /** Streams a declared bounded number of native quads without retaining an intermediate input list. */
    public static BlockMesh read(ClientAssetSnapshot assets,WorldSnapshot snapshot,int cell,NativeMeshSource.Kind kind,String policy,Reader reader,boolean positionDependent,Limits limits,BooleanSupplier cancelled) {
        Objects.requireNonNull(assets);Objects.requireNonNull(snapshot);Objects.requireNonNull(kind);Objects.requireNonNull(policy);Objects.requireNonNull(reader);Objects.requireNonNull(limits);Objects.requireNonNull(cancelled);
        if(snapshot.requiresPlaceholder())throw new IllegalArgumentException("A complete native snapshot is required before capturing visual quads");var state=snapshot.state(cell);
        try {
            AtlasBuilder.check(cancelled);var origin=new NativeMeshSource(snapshot.id(),snapshot.source(),state.nativeStateId(),positionDependent?Optional.of(snapshot.extent().position(cell)):Optional.empty(),kind,policy);
            var result=captureQuads(assets,state,origin,reader,limits,cancelled);reader.complete();AtlasBuilder.check(cancelled);require(reader.size()==result.quads().size(),state,"/native",ModelPlan.Code.SELECTION,"Native quad count changed before final publication");return result;
        }
        catch(ModelFields.Failure failure) { AtlasBuilder.check(cancelled);return BlockMesh.placeholder(assets.receipt(),state,List.of(failure.problem)); }
        catch(OutOfMemoryError exhausted) { AtlasBuilder.check(cancelled);return BlockMesh.placeholder(assets.receipt(),state,List.of(AtlasBuilder.blockFailure(ModelPlan.Code.MEMORY,state.block(),"Not enough memory to capture native geometry; no partial visual was published").problem)); }
    }
    private static BlockMesh captureQuads(ClientAssetSnapshot assets,RenderBlockState state,NativeMeshSource origin,Reader reader,Limits limits,BooleanSupplier cancelled) {
        var receipt=assets.receipt();require(state.block().namespace().equals("minecraft"),state,"/native",ModelPlan.Code.EXTERNAL_ASSET,"External block visuals remain unsupported; preserve the original native block/state");
        require(receipt.version().equals(BlockModels.PROFILE)&&receipt.expectedVersion().equals(BlockModels.PROFILE)&&origin.generation().minecraftVersion().equals(receipt.version()),state,"/native",ModelPlan.Code.VERSION,"Native visual and user-selected assets must target the pinned Minecraft profile");
        int count=reader.size();long bytes=(long)count*MeshPacket.PACKED_BYTES_PER_QUAD;require(count>=0&&count<=limits.quads()&&bytes<=limits.packedBytes()&&bytes<=limits.workingBytes(),state,"/native",ModelPlan.Code.LIMIT,"Native visual quad or packed payload budget exceeded");
        var output=new ArrayList<BlockMesh.Quad>(count);var bounds=new Bounds();var provenance=Optional.of(origin);
        for(int at=0;at<count;at++) {
            AtlasBuilder.check(cancelled);var value=Objects.requireNonNull(reader.quad(at));String pointer="/native/quads/"+at;
            require(value.material().sprite().namespace().equals("minecraft")&&assets.find(ClientAssetSnapshot.Kind.TEXTURE,value.material().sprite()).isPresent(),state,pointer,ModelPlan.Code.MISSING_ASSET,"Native visual sprite must be present in the selected vanilla client archive");
            validate(state,pointer,value.vertices(),value.normal(),limits,bounds,cancelled);
            output.add(new BlockMesh.Quad(new ModelPlan.Source(ClientAssetSnapshot.Kind.BLOCKSTATE,state.block(),pointer),-1,value.face(),value.cullface(),value.normal(),value.material(),value.tintIndex(),value.lightEmission(),value.ambientOcclusion(),value.shadeDirectionOverride(),value.vertices(),provenance));
        }
        reader.complete();AtlasBuilder.check(cancelled);require(reader.size()==count,state,"/native",ModelPlan.Code.SELECTION,"Native quad count changed during its frozen capture");
        var packet=MeshPacket.pack(receipt,state,output,limits.packedBytes(),cancelled);return BlockMesh.captured(receipt,state,List.of(),output,bounds.value(),packet,origin);
    }
    public static BlockMesh combine(BlockMesh base,BlockMesh nativeLayer,BooleanSupplier cancelled) { return combine(base,nativeLayer,Limits.DEFAULT,cancelled); }
    /** Atomic ordered composition, such as a JSON waterlogged model followed by supplied fluid quads. */
    public static BlockMesh combine(BlockMesh base,BlockMesh nativeLayer,Limits limits,BooleanSupplier cancelled) {
        Objects.requireNonNull(base);Objects.requireNonNull(nativeLayer);Objects.requireNonNull(limits);Objects.requireNonNull(cancelled);AtlasBuilder.check(cancelled);
        try { return combineQuads(base,nativeLayer,limits,cancelled); }
        catch(ModelFields.Failure failure) { AtlasBuilder.check(cancelled);return BlockMesh.placeholder(base.assets(),base.state(),List.of(failure.problem)); }
        catch(OutOfMemoryError exhausted) { AtlasBuilder.check(cancelled);return BlockMesh.placeholder(base.assets(),base.state(),List.of(AtlasBuilder.blockFailure(ModelPlan.Code.MEMORY,base.state().block(),"Not enough memory to compose native visual layers; no partial visual was published").problem)); }
    }
    private static BlockMesh combineQuads(BlockMesh base,BlockMesh layer,Limits limits,BooleanSupplier cancelled) {
        var state=base.state();require(state.equals(layer.state()),state,"/native",ModelPlan.Code.SELECTION,"Visual layers must preserve the exact same native state");require(AtlasLayout.sameAssets(base.assets(),layer.assets()),state,"/native",ModelPlan.Code.VERSION,"Visual layers must share the exact captured asset archive");
        if(base.requiresPlaceholder())return base;if(layer.requiresPlaceholder())return BlockMesh.placeholder(base.assets(),state,layer.problems());
        require(layer.nativeSource().isPresent(),state,"/native",ModelPlan.Code.SELECTION,"The added visual layer requires native provenance");var origin=layer.nativeSource().orElseThrow();
        if(base.nativeSource().isPresent()) {
            var first=base.nativeSource().orElseThrow();require(first.snapshotId().equals(origin.snapshotId())&&first.generation().equals(origin.generation())&&first.nativeStateId()==origin.nativeStateId(),state,"/native",ModelPlan.Code.SELECTION,"Native layers belong to different exact captures");
            require(first.position().isEmpty()||origin.position().isEmpty()||first.position().equals(origin.position()),state,"/native",ModelPlan.Code.SELECTION,"Position-dependent native layers belong to different cells");
            origin=new NativeMeshSource(origin.snapshotId(),origin.generation(),origin.nativeStateId(),first.position().isPresent()?first.position():origin.position(),NativeMeshSource.Kind.COMPOSITE,"Ordered composition of supplied native layers");
        }else origin=new NativeMeshSource(origin.snapshotId(),origin.generation(),origin.nativeStateId(),origin.position(),NativeMeshSource.Kind.COMPOSITE,"Ordered composition of JSON and supplied native geometry");
        long count=(long)base.quads().size()+layer.quads().size(),bytes=count*MeshPacket.PACKED_BYTES_PER_QUAD,sourceBytes=base.packet().orElseThrow().packedBytes();if(layer.packet().orElseThrow()!=base.packet().orElseThrow())sourceBytes+=layer.packet().orElseThrow().packedBytes();
        require(count<=limits.quads()&&(long)base.selections().size()+layer.selections().size()<=limits.selections()&&bytes<=limits.packedBytes()&&sourceBytes+bytes<=limits.workingBytes(),state,"/native",ModelPlan.Code.LIMIT,"Combined visual quad, selection metadata, packed or source/output payload budget exceeded");
        var choices=new ArrayList<BlockMesh.Selection>(base.selections().size()+layer.selections().size());choices.addAll(base.selections());choices.addAll(layer.selections());
        var output=new ArrayList<BlockMesh.Quad>((int)count);output.addAll(base.quads());int shift=base.selections().size();
        for(var quad:layer.quads()) { AtlasBuilder.check(cancelled);output.add(quad.selection()<0||shift==0?quad:new BlockMesh.Quad(quad.source(),quad.selection()+shift,quad.modelFace(),quad.cullface(),quad.normal(),quad.material(),quad.tintIndex(),quad.lightEmission(),quad.ambientOcclusion(),quad.shadeDirectionOverride(),quad.vertices(),quad.nativeSource())); }
        var bounds=new Bounds();for(var quad:output) { AtlasBuilder.check(cancelled);validate(state,quad.source().pointer(),quad.vertices(),quad.normal(),limits,bounds,cancelled);require(quad.selection()<0||quad.selection()<choices.size(),state,quad.source().pointer(),ModelPlan.Code.SELECTION,"Composed model selection index is absent"); }
        var packet=MeshPacket.pack(base.assets(),state,output,limits.packedBytes(),cancelled);AtlasBuilder.check(cancelled);return BlockMesh.captured(base.assets(),state,choices,output,bounds.value(),packet,origin);
    }
    private static final class Bounds {
        float minX=Float.POSITIVE_INFINITY,minY=minX,minZ=minX,maxX=Float.NEGATIVE_INFINITY,maxY=maxX,maxZ=maxX;
        void add(BlockMesh.Vertex value) { minX=Math.min(minX,value.x());minY=Math.min(minY,value.y());minZ=Math.min(minZ,value.z());maxX=Math.max(maxX,value.x());maxY=Math.max(maxY,value.y());maxZ=Math.max(maxZ,value.z()); }
        Optional<BlockMesh.Bounds> value() { return Float.isFinite(minX)?Optional.of(new BlockMesh.Bounds(new ModelPlan.Vec3(minX,minY,minZ),new ModelPlan.Vec3(maxX,maxY,maxZ))):Optional.empty(); }
    }
    private static void validate(RenderBlockState state,String pointer,List<BlockMesh.Vertex> vertices,ModelPlan.Vec3 normal,Limits limits,Bounds bounds,BooleanSupplier cancelled) {
        double length=Math.hypot(Math.hypot(normal.x(),normal.y()),normal.z());require(Math.abs(length-1)<=0.001,state,pointer,ModelPlan.Code.GEOMETRY_RANGE,"Native quad normal must already be a finite unit vector");
        for(var value:vertices) { AtlasBuilder.check(cancelled);require(Math.max(Math.max(Math.abs((double)value.x()),Math.abs((double)value.y())),Math.abs((double)value.z()))<=limits.coordinateBlocks(),state,pointer,ModelPlan.Code.GEOMETRY_RANGE,"Native quad exceeds the local-coordinate bound");require(value.u()>=0&&value.u()<=1&&value.v()>=0&&value.v()<=1,state,pointer,ModelPlan.Code.UV_RANGE,"Native quad requires sprite-local UV in [0,1]");bounds.add(value); }
        for(int triangle=0;triangle<2;triangle++) {
            var a=vertices.getFirst();var b=vertices.get(triangle==0?1:2);var c=vertices.get(triangle==0?2:3);double ex=(double)b.x()-a.x(),ey=(double)b.y()-a.y(),ez=(double)b.z()-a.z(),fx=(double)c.x()-a.x(),fy=(double)c.y()-a.y(),fz=(double)c.z()-a.z(),nx=ey*fz-ez*fy,ny=ez*fx-ex*fz,nz=ex*fy-ey*fx,area=Math.hypot(Math.hypot(nx,ny),nz);
            require(area==0||nx*normal.x()+ny*normal.y()+nz*normal.z()>0,state,pointer,ModelPlan.Code.UNSUPPORTED_GEOMETRY,"Native quad winding opposes its supplied shading normal; no face was reordered");
        }
    }
}
