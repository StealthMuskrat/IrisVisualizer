package dev.irisvisualizer.renderer;

import java.util.*;
import java.util.function.BooleanSupplier;

/** CPU atlas UV binding. Preserves vertex/index/face order and all native source metadata. */
public final class AtlasMeshes {
    private AtlasMeshes() {}
    public static AtlasMesh bind(AtlasLayout layout,TexturePreparation input,BooleanSupplier cancelled) {
        Objects.requireNonNull(layout);Objects.requireNonNull(input);Objects.requireNonNull(cancelled);AtlasBuilder.check(cancelled);
        try { return execute(layout,input,cancelled); }
        catch(ModelFields.Failure failure) { AtlasBuilder.check(cancelled);return AtlasMesh.placeholder(layout,input,List.of(failure.problem)); }
        catch(OutOfMemoryError exhausted) { AtlasBuilder.check(cancelled);return AtlasMesh.placeholder(layout,input,List.of(AtlasBuilder.blockFailure(ModelPlan.Code.MEMORY,input.state().block(),"Not enough memory to bind atlas UVs; no partial mesh was published").problem)); }
    }
    private static AtlasMesh execute(AtlasLayout layout,TexturePreparation input,BooleanSupplier cancelled) {
        if(layout.requiresPlaceholder())return AtlasMesh.placeholder(layout,input,layout.problems());
        if(input.requiresPlaceholder())return AtlasMesh.placeholder(layout,input,input.problems());
        if(!layout.admits(input)||!AtlasLayout.sameAssets(layout.assets(),input.assets()))throw AtlasBuilder.blockFailure(ModelPlan.Code.ATLAS,input.state().block(),"Mesh preparation was not admitted to this exact atlas layout");
        var packet=input.mesh().packet().orElseThrow();var usage=layout.usage().orElseThrow();
        if(packet.packedBytes()>layout.limits().boundMeshBytes()||packet.packedBytes()>usage.boundMeshBytes()||usage.knownPayloadBytes()>layout.limits().workingBytes())throw AtlasBuilder.blockFailure(ModelPlan.Code.LIMIT,input.state().block(),"Atlas-bound mesh payload budget reached");
        if(packet.faces().size()!=input.faces().size())throw AtlasBuilder.blockFailure(ModelPlan.Code.ATLAS,input.state().block(),"Source face and texture face ordering disagree");
        float[] vertices=new float[packet.vertexCount()*MeshPacket.STRIDE_FLOATS];int[] indices=new int[packet.indexCount()];var sourceVertices=packet.vertexBuffer();var sourceIndices=packet.indexBuffer();
        var faces=new ArrayList<AtlasMesh.Face>(packet.faces().size());var ranges=new ArrayList<AtlasMesh.DrawRange>();int rangeStart=0,rangePage=-1;TexturePreparation.AlphaClass rangeAlpha=null;
        for(int faceIndex=0;faceIndex<packet.faces().size();faceIndex++) {
            AtlasBuilder.check(cancelled);var face=packet.faces().get(faceIndex);var material=input.faces().get(faceIndex);var slot=layout.sprites().get(face.material().sprite());
            var sourceSprite=input.sprites().get(face.material().sprite());
            if(material.index()!=faceIndex||!material.source().equals(face.source())||!material.material().equals(face.material())||slot==null||sourceSprite==null||!AtlasLayout.sameSprite(slot.source(),sourceSprite))
                throw AtlasBuilder.failure(ModelPlan.Code.ATLAS,face.material().sprite(),"","Atlas sprite or face provenance does not match the admitted source");
            var page=layout.pages().get(slot.page());int base=faceIndex*4*MeshPacket.STRIDE_FLOATS;
            for(int vertex=0;vertex<4;vertex++) {
                int offset=base+vertex*MeshPacket.STRIDE_FLOATS;for(int component=0;component<6;component++)vertices[offset+component]=sourceVertices.get(offset+component);
                float u=sourceVertices.get(offset+6),v=sourceVertices.get(offset+7);
                if(!Float.isFinite(u)||!Float.isFinite(v)||u<0||u>1||v<0||v>1)throw AtlasBuilder.failure(ModelPlan.Code.UV_RANGE,face.material().sprite(),"","Atlas binding requires sprite-local UVs in 0..1");
                vertices[offset+6]=(float)((slot.x()+(double)u*slot.width())/page.width());vertices[offset+7]=(float)((slot.y()+(double)v*slot.height())/page.height());
            }
            int index=faceIndex*6;for(int i=0;i<6;i++)indices[index+i]=sourceIndices.get(index+i);
            faces.add(new AtlasMesh.Face(face,slot.page(),material.classification()));
            if(rangePage!=slot.page()||rangeAlpha!=material.classification()) {
                if(rangePage>=0)ranges.add(new AtlasMesh.DrawRange(rangeStart,index-rangeStart,rangePage,rangeAlpha));rangeStart=index;rangePage=slot.page();rangeAlpha=material.classification();
            }
        }
        if(rangePage>=0)ranges.add(new AtlasMesh.DrawRange(rangeStart,indices.length-rangeStart,rangePage,rangeAlpha));
        AtlasBuilder.check(cancelled);return AtlasMesh.success(layout,input,vertices,indices,faces,ranges);
    }
}
