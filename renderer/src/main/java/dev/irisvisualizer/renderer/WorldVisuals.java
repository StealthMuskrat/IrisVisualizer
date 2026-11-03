package dev.irisvisualizer.renderer;

import java.util.*;
import java.util.function.BooleanSupplier;

/** Atomic CPU visual assembly from explicit native choices. Never selects native renderers or random tickets. */
public final class WorldVisuals {
    private WorldVisuals() {}
    public sealed interface Choice permits ModelChoice,NativeChoice,EmptyChoice,ProblemChoice { int stateIndex(); }
    public record ModelChoice(int stateIndex,List<Integer> tickets,Optional<BlockMesh> nativeLayer) implements Choice {
        public ModelChoice { Objects.requireNonNull(tickets);Objects.requireNonNull(nativeLayer);if(stateIndex<0||tickets.size()>1024)throw new IllegalArgumentException("Invalid native model choice");tickets=List.copyOf(tickets);for(int ticket:tickets)if(ticket<0)throw new IllegalArgumentException("Negative native ticket"); }
        public ModelChoice(int stateIndex,List<Integer> tickets) { this(stateIndex,tickets,Optional.empty()); }
    }
    public record NativeChoice(int stateIndex,BlockMesh mesh) implements Choice {
        public NativeChoice { Objects.requireNonNull(mesh);if(stateIndex<0)throw new IllegalArgumentException("Invalid native state palette index"); }
    }
    /** Empty is an explicit native decision; the assembler never infers it from a block name. */
    public record EmptyChoice(int stateIndex) implements Choice { public EmptyChoice { if(stateIndex<0)throw new IllegalArgumentException("Invalid empty state index"); } }
    public record ProblemChoice(int stateIndex,List<ModelPlan.Problem> problems) implements Choice {
        public ProblemChoice { Objects.requireNonNull(problems);if(stateIndex<0||problems.isEmpty()||problems.size()>256)throw new IllegalArgumentException("Invalid explicit visual problems");problems=List.copyOf(problems); }
    }
    public record Limits(int choices,int modelStates,int meshVariants,int ticketReferences,int problemReferences,long sourcePackedBytes,long workingBytes) {
        public static final Limits DEFAULT=new Limits(65536,1024,1024,65536,65536,64L*1024*1024,512L*1024*1024);
        public Limits { if(choices<1||choices>65536||modelStates<1||modelStates>1024||meshVariants<1||meshVariants>1024||ticketReferences<0||ticketReferences>65536||problemReferences<0||problemReferences>65536||sourcePackedBytes<0||sourcePackedBytes>64L*1024*1024||workingBytes<0||workingBytes>512L*1024*1024)throw new IllegalArgumentException("World visual limits exceed renderer bounds"); }
    }
    /** Captured before scheduling. The assignment backing array is private and never exposed or mutated. */
    public static final class Request {
        private final ClientAssetSnapshot assets;private final WorldSnapshot snapshot;private final List<Choice> choices;private final int[] assignments;
        private final Limits limits;private final long tick;private final double partialTick;private final long inputBytes,sourcePackedBytes;
        private Request(ClientAssetSnapshot assets,WorldSnapshot snapshot,List<Choice> choices,int[] owned,Limits limits,long tick,double partialTick,long inputBytes,long sourcePackedBytes) {
            this.assets=assets;this.snapshot=snapshot;this.choices=choices;assignments=owned;this.limits=limits;this.tick=tick;this.partialTick=partialTick;this.inputBytes=inputBytes;this.sourcePackedBytes=sourcePackedBytes;
        }
        public static Request capture(ClientAssetSnapshot assets,WorldSnapshot snapshot,List<Choice> choices,int[] assignments,long tick,double partialTick,BooleanSupplier cancelled) { return capture(assets,snapshot,choices,assignments,tick,partialTick,Limits.DEFAULT,cancelled); }
        public static Request capture(ClientAssetSnapshot assets,WorldSnapshot snapshot,List<Choice> choices,int[] assignments,long tick,double partialTick,Limits limits,BooleanSupplier cancelled) {
            Objects.requireNonNull(assets);Objects.requireNonNull(snapshot);Objects.requireNonNull(choices);Objects.requireNonNull(assignments);Objects.requireNonNull(limits);Objects.requireNonNull(cancelled);WorldProblem.check(cancelled);
            if(snapshot.requiresPlaceholder()||!snapshot.source().minecraftVersion().equals(BlockModels.PROFILE)||!assets.receipt().version().equals(BlockModels.PROFILE)||!assets.receipt().expectedVersion().equals(BlockModels.PROFILE))throw new IllegalArgumentException("Visual requests require a complete pinned native snapshot and selected client assets");
            if(tick<0||!Double.isFinite(partialTick)||partialTick<0||partialTick>=1)throw new IllegalArgumentException("Invalid visual animation clock");
            if(choices.isEmpty()||choices.size()>limits.choices()||assignments.length!=snapshot.cellCount())throw new IllegalArgumentException("Supply a bounded choice palette and exactly one choice per captured cell");
            long base=assets.receipt().capturedAssetBytes()+snapshot.indexBytes()+(long)assignments.length*4;
            if(base+(long)assignments.length*4>limits.workingBytes())throw new IllegalArgumentException("Visual input capture and assignment copy exceed the working budget");
            var frozen=List.copyOf(choices);var packets=new IdentityHashMap<MeshPacket,Boolean>();long packed=0,tickets=0,problems=0;var states=new HashSet<Integer>();var variants=new HashSet<Choice>();
            for(var choice:frozen) {
                WorldProblem.check(cancelled);if(choice.stateIndex()>=snapshot.states().size())throw new IllegalArgumentException("Visual choice references an absent native state");
                BlockMesh nativeMesh=null;
                if(choice instanceof ModelChoice model) { tickets+=model.tickets().size();states.add(model.stateIndex());variants.add(choice);nativeMesh=model.nativeLayer().orElse(null); }
                else if(choice instanceof NativeChoice nativeChoice) { nativeMesh=nativeChoice.mesh();variants.add(choice); }
                else if(choice instanceof ProblemChoice problem)problems+=problem.problems().size();
                if(nativeMesh!=null) {
                    validateNative(assets,snapshot,choice.stateIndex(),nativeMesh);problems+=nativeMesh.problems().size();
                    if(nativeMesh.packet().isPresent()&&packets.put(nativeMesh.packet().orElseThrow(),Boolean.TRUE)==null)packed+=nativeMesh.packet().orElseThrow().packedBytes();
                }
                if(states.size()>limits.modelStates()||variants.size()>limits.meshVariants()||tickets>limits.ticketReferences()||problems>limits.problemReferences()||packed>limits.sourcePackedBytes()||base+packed+(long)assignments.length*4>limits.workingBytes())throw new IllegalArgumentException("Visual choice metadata, source mesh or capture working budget exceeded");
            }
            int[] captured=assignments.clone();
            for(int cell=0;cell<captured.length;cell++) {
                if((cell&4095)==0)WorldProblem.check(cancelled);int index=captured[cell];
                if(index<0||index>=frozen.size()||frozen.get(index).stateIndex()!=snapshot.stateIndex(cell))throw new IllegalArgumentException("Per-cell choice does not preserve the exact native state");
                var choice=frozen.get(index);var mesh=choice instanceof NativeChoice n?n.mesh():choice instanceof ModelChoice m?m.nativeLayer().orElse(null):null;
                if(mesh!=null&&mesh.nativeSource().isPresent()) { var position=mesh.nativeSource().orElseThrow().position();if(position.isPresent()&&!position.orElseThrow().equals(snapshot.extent().position(cell)))throw new IllegalArgumentException("Position-dependent native visual assigned to another cell"); }
            }
            WorldProblem.check(cancelled);return new Request(assets,snapshot,frozen,captured,limits,tick,partialTick,base+packed,packed);
        }
        private static void validateNative(ClientAssetSnapshot assets,WorldSnapshot snapshot,int stateIndex,BlockMesh mesh) {
            if(!mesh.state().equals(snapshot.states().get(stateIndex))||!AtlasLayout.sameAssets(assets.receipt(),mesh.assets()))throw new IllegalArgumentException("Native visual state or asset capture differs from its request");
            if(mesh.requiresPlaceholder())return;var source=mesh.nativeSource().orElseThrow(()->new IllegalArgumentException("Native choices/layers require captured native provenance"));
            if(!source.snapshotId().equals(snapshot.id())||!source.generation().equals(snapshot.source())||source.nativeStateId()!=mesh.state().nativeStateId())throw new IllegalArgumentException("Native visual belongs to a different exact world capture");
        }
        public WorldSnapshot.Source source() { return snapshot.source(); }
        public UUID snapshotId() { return snapshot.id(); }
        public Limits limits() { return limits; }
        public long inputBytes() { return inputBytes; }
        /** Immutable native decision palette, including original-state unsupported visual diagnostics. */
        public List<Choice> choices() { return choices; }
        public WorldSnapshot snapshot() { return snapshot; }
        /** Lower admission without copying or exposing the frozen native inputs. */
        public Request withWorkingBytes(long bytes) {
            if(bytes<inputBytes||bytes>limits.workingBytes())throw new IllegalArgumentException("Reduced visual working budget cannot hold its captured inputs");
            var bounded=new Limits(limits.choices(),limits.modelStates(),limits.meshVariants(),limits.ticketReferences(),limits.problemReferences(),limits.sourcePackedBytes(),bytes);
            return new Request(assets,snapshot,choices,assignments,bounded,tick,partialTick,inputBytes,sourcePackedBytes);
        }
        public int choiceAt(int cell) { return assignments[cell]; }
    }
    /** Primitive payload accounting, not a total Java heap/native/GPU memory measurement. */
    public record Usage(int choices,int modelStates,int meshVariants,int meshEntries,int emptyEntries,int placeholderEntries,long sourcePackedBytes,long rgbaBytes,long boundMeshBytes,long atlasBytes,long knownWorkingBytes) {}
    public record Result(WorldSnapshot.Source source,UUID snapshotId,Optional<WorldModels> models,Optional<AtlasRaster> raster,Optional<Usage> usage,List<WorldProblem> problems,List<ModelPlan.Problem> assetProblems) {
        public Result {
            Objects.requireNonNull(source);Objects.requireNonNull(snapshotId);Objects.requireNonNull(models);Objects.requireNonNull(raster);Objects.requireNonNull(usage);problems=List.copyOf(problems);assetProblems=List.copyOf(assetProblems);
            boolean success=problems.isEmpty()&&assetProblems.isEmpty();
            if(success!=(models.isPresent()&&raster.isPresent()&&usage.isPresent())||!success&&(models.isPresent()||raster.isPresent()||usage.isPresent()))throw new IllegalArgumentException("Partial visual result escaped failure");
            if(success&&(models.orElseThrow().requiresPlaceholder()||raster.orElseThrow().requiresPlaceholder()||models.orElseThrow().layout()!=raster.orElseThrow().layout()||!models.orElseThrow().snapshot().id().equals(snapshotId)||!models.orElseThrow().snapshot().source().equals(source)))throw new IllegalArgumentException("Visual output epochs differ");
        }
        public boolean requiresPlaceholder() { return !problems.isEmpty()||!assetProblems.isEmpty(); }
        static Result failed(Request request,List<WorldProblem> problems,List<ModelPlan.Problem> assets) { return new Result(request.source(),request.snapshotId(),Optional.empty(),Optional.empty(),Optional.empty(),problems,assets); }
    }
    public static Result prepare(Request request,BooleanSupplier cancelled) {
        Objects.requireNonNull(request);Objects.requireNonNull(cancelled);WorldProblem.check(cancelled);
        try { return new Work(request,cancelled).execute(); }
        catch(ModelFields.Failure failure) { WorldProblem.check(cancelled);return Result.failed(request,List.of(),List.of(failure.problem)); }
        catch(WorldProblem.Failure failure) { WorldProblem.check(cancelled);return Result.failed(request,List.of(failure.problem),List.of()); }
        catch(OutOfMemoryError exhausted) { WorldProblem.check(cancelled);return Result.failed(request,List.of(new WorldProblem(WorldProblem.Code.MEMORY,Optional.empty(),"Not enough memory for complete CPU visual assembly; no partial result was published")),List.of()); }
    }
    private static final class Work {
        final Request request;final BooleanSupplier cancelled;final IdentityHashMap<MeshPacket,Boolean> packets=new IdentityHashMap<>();
        final BlockMesh[] meshes;final WorldModels.Entry[] entries;final int[] canonical;long known,packed;int modelStates;
        Work(Request request,BooleanSupplier cancelled) {
            this.request=request;this.cancelled=cancelled;known=request.inputBytes;packed=request.sourcePackedBytes;meshes=new BlockMesh[request.choices.size()];entries=new WorldModels.Entry[meshes.length];canonical=new int[meshes.length];
            for(var choice:request.choices) { var mesh=choice instanceof NativeChoice n?n.mesh():choice instanceof ModelChoice m?m.nativeLayer().orElse(null):null;if(mesh!=null)mesh.packet().ifPresent(packet->packets.put(packet,Boolean.TRUE)); }
        }
        void check() { WorldProblem.check(cancelled); }
        RenderBlockState state(int index) { return request.snapshot.states().get(request.choices.get(index).stateIndex()); }
        void fatal(List<ModelPlan.Problem> problems) { for(var problem:problems)if(TextureBatch.fatal(problem.code()))throw new ModelFields.Failure(problem); }
        long remaining() { return request.limits.workingBytes()-known; }
        long packetAllowance(long maximum) { return Math.min(maximum,Math.min(remaining(),request.limits.sourcePackedBytes()-packed)); }
        void retain(BlockMesh mesh) {
            fatal(mesh.problems());mesh.packet().ifPresent(packet->{ if(packets.put(packet,Boolean.TRUE)==null) { packed+=packet.packedBytes();known+=packet.packedBytes(); } });
            if(packed>request.limits.sourcePackedBytes()||known>request.limits.workingBytes())throw WorldProblem.fail(WorldProblem.Code.LIMIT,"Combined visual source mesh/working budget reached");
        }
        void bakeState(int stateIndex,List<Integer> variants) {
            check();modelStates++;var plan=BlockModels.resolve(request.assets,request.snapshot.states().get(stateIndex),cancelled);fatal(plan.problems());
            for(int index:variants) {
                check();var choice=(ModelChoice)request.choices.get(index);int[] tickets=new int[choice.tickets().size()];for(int i=0;i<tickets.length;i++)tickets[i]=choice.tickets().get(i);
                var defaults=GeometryBaker.Limits.DEFAULT;var limits=new GeometryBaker.Limits(defaults.parts(),defaults.elements(),defaults.quads(),packetAllowance(defaults.packedBytes()),defaults.coordinateBlocks(),defaults.angleDegrees());
                var mesh=GeometryBaker.bake(plan,tickets,limits,cancelled);retain(mesh);
                if(!mesh.requiresPlaceholder()&&choice.nativeLayer().isPresent()) {
                    var layer=choice.nativeLayer().orElseThrow();fatal(layer.problems());var nativeLimits=NativeGeometry.Limits.DEFAULT;
                    long source=mesh.packet().orElseThrow().packedBytes()+layer.packet().map(MeshPacket::packedBytes).orElse(0L);
                    mesh=NativeGeometry.combine(mesh,layer,new NativeGeometry.Limits(nativeLimits.quads(),nativeLimits.selections(),packetAllowance(nativeLimits.packedBytes()),Math.min(nativeLimits.workingBytes(),remaining()+source),nativeLimits.coordinateBlocks()),cancelled);retain(mesh);
                }
                meshes[index]=mesh;
            }
        }
        Result execute() {
            var equal=new HashMap<Choice,Integer>();var groups=new LinkedHashMap<Integer,List<Integer>>();
            for(int i=0;i<meshes.length;i++) {
                check();var choice=request.choices.get(i);var previous=equal.putIfAbsent(choice,i);canonical[i]=previous==null?i:previous;if(previous!=null)continue;
                if(choice instanceof ModelChoice model) { model.nativeLayer().ifPresent(layer->fatal(layer.problems()));groups.computeIfAbsent(model.stateIndex(),ignored->new ArrayList<>()).add(i); }
                else if(choice instanceof NativeChoice nativeChoice) { retain(nativeChoice.mesh());meshes[i]=nativeChoice.mesh(); }
                else if(choice instanceof EmptyChoice)entries[i]=WorldModels.Entry.empty(state(i));
                else if(choice instanceof ProblemChoice problem) { fatal(problem.problems());entries[i]=WorldModels.Entry.placeholder(state(i),problem.problems()); }
            }
            for(var group:groups.entrySet())bakeState(group.getKey(),group.getValue());
            var unique=new ArrayList<BlockMesh>();var ordinals=new IdentityHashMap<BlockMesh,Integer>();
            for(int i=0;i<meshes.length;i++)if(canonical[i]==i&&meshes[i]!=null) {
                check();var mesh=meshes[i];if(mesh.requiresPlaceholder())entries[i]=WorldModels.Entry.placeholder(state(i),mesh.problems());
                else if(!ordinals.containsKey(mesh)) { ordinals.put(mesh,unique.size());unique.add(mesh); }
            }
            var textureDefaults=TextureMaterials.Limits.DEFAULT;
            var resources=new TextureMaterials.Limits(textureDefaults.sprites(),textureDefaults.faces(),textureDefaults.imageDimension(),textureDefaults.imagePixels(),Math.min(textureDefaults.rgbaBytes(),remaining()),textureDefaults.encodedBytes(),textureDefaults.metadataBytes(),textureDefaults.metadataNodes(),textureDefaults.frames(),textureDefaults.regions(),textureDefaults.alphaPixelVisits());
            var textureLimits=new TextureBatch.Limits(request.limits.meshVariants(),262144,4096,request.limits.sourcePackedBytes(),request.limits.workingBytes(),resources);
            var textures=TextureBatch.prepare(request.assets,unique,textureLimits,cancelled);if(textures.requiresPlaceholder())throw new ModelFields.Failure(textures.problems().getFirst());
            long rgba=textures.usage().orElseThrow().retainedRgbaBytes();known+=rgba;
            var admitted=new ArrayList<TexturePreparation>();var rejected=new IdentityHashMap<TexturePreparation,List<ModelPlan.Problem>>();var images=new IdentityHashMap<RgbaImage,Boolean>();var sourcePackets=new IdentityHashMap<MeshPacket,Boolean>();long usedRgba=0,usedPacked=0;
            for(var prep:textures.preparations()) {
                check();if(prep.requiresPlaceholder()) { fatal(prep.problems());rejected.put(prep,prep.problems());continue; }
                ModelPlan.Problem unsupported=null;
                for(var sprite:prep.sprites().values())if(sprite.interpolate()&&sprite.referencedFrames().size()>1) { unsupported=TextureMetadata.failure(ModelPlan.Code.ANIMATION,ClientAssetSnapshot.Kind.TEXTURE_METADATA,sprite.id(),"/animation/interpolate","Native multi-frame pixel interpolation remains unsupported").problem;break; }
                if(unsupported!=null) { rejected.put(prep,List.of(unsupported));continue; }
                admitted.add(prep);var packet=prep.mesh().packet().orElseThrow();if(sourcePackets.put(packet,Boolean.TRUE)==null)usedPacked+=packet.packedBytes();
                for(var sprite:prep.sprites().values())if(images.put(sprite.image(),Boolean.TRUE)==null)usedRgba+=sprite.image().bytes();
            }
            var atlasDefaults=AtlasBuilder.Limits.DEFAULT;long excluded=known-usedPacked-usedRgba;
            var atlasLimits=new AtlasBuilder.Limits(atlasDefaults.inputs(),atlasDefaults.sprites(),atlasDefaults.faces(),atlasDefaults.scheduleEntries(),atlasDefaults.pageDimension(),atlasDefaults.pages(),atlasDefaults.gutter(),atlasDefaults.sourceRgbaBytes(),atlasDefaults.sourcePackedBytes(),atlasDefaults.atlasBytes(),atlasDefaults.boundMeshBytes(),atlasDefaults.updateBytes(),Math.min(atlasDefaults.workingBytes(),request.limits.workingBytes()-excluded),atlasDefaults.packingChecks());
            var layout=admitted.isEmpty()?AtlasBuilder.empty(request.assets,state(0).block(),atlasLimits,cancelled):AtlasBuilder.plan(admitted,atlasLimits,cancelled);
            if(layout.requiresPlaceholder())throw new ModelFields.Failure(layout.problems().getFirst());
            var bound=new IdentityHashMap<TexturePreparation,WorldModels.Entry>();
            for(var prep:admitted) { check();var mesh=AtlasMeshes.bind(layout,prep,cancelled);if(mesh.requiresPlaceholder())throw new ModelFields.Failure(mesh.problems().getFirst());bound.put(prep,WorldModels.Entry.from(mesh)); }
            int meshCount=0,emptyCount=0,placeholderCount=0;
            for(int i=0;i<entries.length;i++) {
                check();if(canonical[i]!=i)entries[i]=entries[canonical[i]];
                else if(entries[i]==null) { var prep=textures.preparations().get(ordinals.get(meshes[i]));var problems=rejected.get(prep);entries[i]=problems==null?bound.get(prep):WorldModels.Entry.placeholder(state(i),problems); }
                switch(entries[i].kind()) { case MESH->meshCount++;case EMPTY->emptyCount++;case PLACEHOLDER->placeholderCount++; }
            }
            var models=WorldModels.captureShared(request.snapshot,layout,Arrays.asList(entries),request.assignments,WorldModels.Limits.DEFAULT,cancelled);
            if(models.requiresPlaceholder())return Result.failed(request,models.problems(),List.of());
            var raster=AtlasImages.capture(layout,request.tick,request.partialTick,cancelled);if(raster.requiresPlaceholder())return Result.failed(request,List.of(),raster.problems());
            var atlasUsage=layout.usage().orElseThrow();known+=atlasUsage.boundMeshBytes()+atlasUsage.atlasBytes();
            check();return new Result(request.source(),request.snapshotId(),Optional.of(models),Optional.of(raster),Optional.of(new Usage(entries.length,modelStates,unique.size(),meshCount,emptyCount,placeholderCount,packed,rgba,atlasUsage.boundMeshBytes(),atlasUsage.atlasBytes(),known)),List.of(),List.of());
        }
    }
}
