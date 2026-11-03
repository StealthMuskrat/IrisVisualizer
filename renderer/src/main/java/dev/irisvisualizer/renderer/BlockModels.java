package dev.irisvisualizer.renderer;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Worker-side model planning for the pinned 26.3 asset profile. Does not enable a preview. */
public final class BlockModels {
    private BlockModels() {}
    public static final String PROFILE="26.3";
    public record Limits(int assets,int jsonBytes,int jsonNodes,int parentDepth,int elements,int faces,
                         int cases,int choices,int textureBindings,int conditionNodes,int conditionDepth) {
        public static final Limits DEFAULT=new Limits(256,8*1024*1024,262_144,64,4096,24_576,4096,1024,16_384,4096,32);
        public Limits {
            if(assets<1||assets>256||jsonBytes<1||jsonBytes>8*1024*1024||jsonNodes<1||jsonNodes>262_144||parentDepth<1||parentDepth>64||elements<1||elements>4096||faces<1||faces>24_576||cases<1||cases>4096||choices<1||choices>1024||textureBindings<1||textureBindings>16_384||conditionNodes<1||conditionNodes>4096||conditionDepth<1||conditionDepth>32)
                throw new IllegalArgumentException("Model resolution limits exceed renderer bounds");
        }
    }
    static final class Work {
        final ClientAssetSnapshot snapshot;final RenderBlockState state;final Limits limits;final BooleanSupplier cancelled;
        final Map<ClientAssetSnapshot.Key,Map<String,AssetJson.Value>> documents=new HashMap<>();
        final Map<AssetId,ModelDefinitions.Raw> rawModels=new HashMap<>();final Map<AssetId,ModelPlan.Model> models=new HashMap<>();
        int bytes,nodes,elements,faces,choices,bindings,conditions,resolvedFaces,resolvedElements;
        Work(ClientAssetSnapshot snapshot,RenderBlockState state,Limits limits,BooleanSupplier cancelled) { this.snapshot=snapshot;this.state=state;this.limits=limits;this.cancelled=cancelled; }
        void check() { if(cancelled.getAsBoolean())throw new CancellationException("Block model resolution cancelled"); }
        void limit(boolean permitted,AssetId id,String pointer,String reason) { check();if(!permitted)throw ModelFields.fail(ModelPlan.Code.LIMIT,id,pointer,reason); }
        Map<String,AssetJson.Value> document(ClientAssetSnapshot.Kind kind,AssetId id) {
            check();if(!id.namespace().equals("minecraft"))throw ModelFields.fail(ModelPlan.Code.EXTERNAL_ASSET,id,"","Only captured vanilla assets are available");
            var key=new ClientAssetSnapshot.Key(kind,id);var prior=documents.get(key);if(prior!=null)return prior;
            limit(documents.size()<limits.assets(),id,"","Model source count limit reached");
            var source=snapshot.find(kind,id).orElseThrow(()->ModelFields.fail(ModelPlan.Code.MISSING_ASSET,id,"","Referenced client asset is missing"));
            limit(source.bytes()<=1_048_576 && bytes<limits.jsonBytes() && source.bytes()<=limits.jsonBytes()-bytes && nodes<limits.jsonNodes(),id,"","Model JSON byte/node budget reached");
            try {
                var document=AssetJson.document(source.copyBytes(),new AssetJson.Limits(Math.min(1_048_576,limits.jsonBytes()-bytes),limits.jsonNodes()-nodes,64,16_384),cancelled);
                var result=ModelFields.object(document.root(),id,"");bytes+=source.bytes();nodes+=document.nodes();documents.put(key,result);return result;
            }catch(AssetJson.LimitFailure limited) { throw ModelFields.fail(ModelPlan.Code.LIMIT,id,"",limited.getMessage()); }
            catch(IOException invalid) { throw ModelFields.format(id,"","Asset JSON is malformed: "+invalid.getMessage()); }
        }
    }
    public static ModelPlan resolve(ClientAssetSnapshot assets,RenderBlockState state,BooleanSupplier cancelled) { return resolve(assets,state,Limits.DEFAULT,cancelled); }
    public static ModelPlan resolve(ClientAssetSnapshot assets,RenderBlockState state,Limits limits,BooleanSupplier cancelled) {
        Objects.requireNonNull(assets);Objects.requireNonNull(state);Objects.requireNonNull(limits);Objects.requireNonNull(cancelled);
        var work=new Work(assets,state,limits,cancelled);work.check();
        try {
            if(!assets.receipt().version().equals(PROFILE) || !assets.receipt().expectedVersion().equals(PROFILE))
                throw new ModelFields.Failure(new ModelPlan.Problem(ModelPlan.Code.VERSION,ClientAssetSnapshot.Kind.BLOCKSTATE,state.block(),"","Model semantics are implemented only for the pinned 26.3 profile"));
            var selections=BlockstateDispatch.select(work);var parts=new ArrayList<ModelPlan.Part>();
            for(var group:selections) {
                work.check();var choices=new ArrayList<ModelPlan.Choice>();long total=0;
                for(var choice:group.choices()) {
                    work.check();var model=ModelResolver.resolve(work,choice.model());
                    choices.add(new ModelPlan.Choice(model,choice.x(),choice.y(),choice.z(),choice.uvlock(),choice.weight()));total+=choice.weight();
                }
                parts.add(new ModelPlan.Part(new ModelPlan.Source(ClientAssetSnapshot.Kind.BLOCKSTATE,state.block(),group.pointer()),group.sourceIndex(),choices,Math.toIntExact(total)));
            }
            work.check();var result=new ModelPlan(assets.receipt(),state,parts,List.of());work.check();return result;
        }catch(ModelFields.Failure unsupported) { work.check();var result=new ModelPlan(assets.receipt(),state,List.of(),List.of(unsupported.problem));work.check();return result; }
    }
}
