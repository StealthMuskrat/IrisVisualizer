package dev.irisvisualizer.renderer.nativebridge;

import dev.irisvisualizer.renderer.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** One native-session handoff to the detached single-worker model/visibility/scene renderer path. */
public final class NativeSceneInputs {
    private NativeSceneInputs() {}
    public record Limits(WorldModelInputs.Limits models,NativeVisibility.Limits visibility,WorldOffsets.Limits offsets,WorldScenePlan.Limits scene,WorldMesher.Limits section,long workingBytes) {
        public static final Limits DEFAULT=new Limits(WorldModelInputs.Limits.DEFAULT,NativeVisibility.Limits.DEFAULT,WorldOffsets.Limits.DEFAULT,WorldScenePlan.Limits.DEFAULT,WorldMesher.Limits.DEFAULT,512L*1024*1024);
        public Limits { Objects.requireNonNull(models);Objects.requireNonNull(visibility);Objects.requireNonNull(offsets);Objects.requireNonNull(scene);Objects.requireNonNull(section);if(workingBytes<0||workingBytes>512L*1024*1024)throw new IllegalArgumentException("Native scene input working limit exceeded"); }
        public Limits(WorldModelInputs.Limits models,NativeVisibility.Limits visibility,WorldScenePlan.Limits scene,WorldMesher.Limits section,long workingBytes) { this(models,visibility,WorldOffsets.Limits.DEFAULT,scene,section,workingBytes); }
    }
    public record Result(WorldSnapshot.Source source,UUID snapshotId,Optional<WorldRenderWorker.ModelRequest> request,Optional<NativeVisibility.Usage> visibilityUsage,List<WorldProblem> problems) {
        public Result {
            Objects.requireNonNull(source);Objects.requireNonNull(snapshotId);Objects.requireNonNull(request);Objects.requireNonNull(visibilityUsage);problems=List.copyOf(problems);
            if(problems.isEmpty()!=(request.isPresent()&&visibilityUsage.isPresent())||!problems.isEmpty()&&(request.isPresent()||visibilityUsage.isPresent())||request.isPresent()&&(!request.orElseThrow().inputs().snapshot().id().equals(snapshotId)||!request.orElseThrow().inputs().snapshot().source().equals(source)))throw new IllegalArgumentException("Partial native scene input escaped failure");
        }
        public boolean requiresPlaceholder() { return !problems.isEmpty(); }
    }
    public static Result capture(NativeRenderContext context,ClientAssetSnapshot assets,WorldSnapshot snapshot,long tick,double partialTick,BooleanSupplier cancelled) { return capture(context,assets,snapshot,tick,partialTick,Limits.DEFAULT,cancelled); }
    public static Result capture(NativeRenderContext context,ClientAssetSnapshot assets,WorldSnapshot snapshot,long tick,double partialTick,Limits limits,BooleanSupplier cancelled) { return capture(context,assets,snapshot,tick,partialTick,limits,WorldTintInput.none(snapshot),cancelled); }
    public static Result capture(NativeRenderContext context,ClientAssetSnapshot assets,WorldSnapshot snapshot,long tick,double partialTick,Limits limits,WorldTintInput tints,BooleanSupplier cancelled) {
        Objects.requireNonNull(context);Objects.requireNonNull(assets);Objects.requireNonNull(snapshot);Objects.requireNonNull(limits);Objects.requireNonNull(cancelled);
        if(tick<0||!Double.isFinite(partialTick)||partialTick<0||partialTick>=1)throw new IllegalArgumentException("Invalid native scene clock");
        try {
            check(cancelled);context.check();if(snapshot.requiresPlaceholder())return failed(snapshot,snapshot.problems());
            if(tints.snapshot()!=snapshot||tints.requiresPlaceholder()||!tints.matchesAssets(assets.receipt()))throw NativeIdentities.failure(WorldProblem.Code.ASSIGNMENT,"Native scene tints require the exact snapshot/assets");
            long catalog=assets.receipt().capturedAssetBytes(),source=catalog+snapshot.indexBytes()+tints.bytes();
            // One retained mask plus prospective assignment/copy arrays; no full height is omitted.
            if(source+(long)snapshot.cellCount()*9>limits.workingBytes())throw NativeIdentities.failure(WorldProblem.Code.LIMIT,"Native scene catalog/world/mask/choice-copy working budget reached");
            var o=limits.offsets();long offsetWorking=Math.min(o.workingBytes(),limits.workingBytes()-catalog-tints.bytes());
            var capturedOffsets=NativeOffsets.capture(context,snapshot,new WorldOffsets.Limits(o.translations(),o.payloadBytes(),offsetWorking,o.componentBlocks()),cancelled);if(capturedOffsets.requiresPlaceholder())return failed(snapshot,capturedOffsets.problems());
            var offsets=capturedOffsets.offsets().orElseThrow();
            if(source+offsets.bytes()+(long)snapshot.cellCount()*9>limits.workingBytes())throw NativeIdentities.failure(WorldProblem.Code.LIMIT,"Native scene offsets/mask/choice-copy peak exceeds its working budget");
            var original=limits.models();var v=original.visuals();long modelWorking=Math.min(v.workingBytes(),limits.workingBytes());
            var visualLimits=new WorldVisuals.Limits(v.choices(),v.modelStates(),v.meshVariants(),v.ticketReferences(),v.problemReferences(),v.sourcePackedBytes(),modelWorking);
            var modelLimits=new WorldModelInputs.Limits(visualLimits,original.models(),original.inspectedStates(),original.inspectedFaces());
            var models=NativeModelInputs.capture(context,assets,snapshot,tick,partialTick,modelLimits,offsets,tints,cancelled);
            if(models.requiresPlaceholder())return failed(snapshot,models.problems());
            var visibility=limits.visibility();var maskLimits=visibility.visibility();long maskWorking=Math.min(maskLimits.workingBytes(),limits.workingBytes()-catalog-offsets.bytes()-tints.bytes());
            var boundedVisibility=new NativeVisibility.Limits(new WorldVisibility.Limits(maskLimits.maskBytes(),maskWorking),visibility.cachedPairs(),visibility.nativeQueries(),visibility.pairProbes());
            var masks=NativeVisibility.capture(context,snapshot,boundedVisibility,cancelled);if(masks.requiresPlaceholder())return failed(snapshot,masks.problems());
            var input=models.inputs().orElseThrow();var mask=masks.input().orElseThrow();long available=Math.min(input.limits().visuals().workingBytes(),limits.workingBytes()-mask.bytes());
            if(input.inputBytes()+(long)snapshot.cellCount()*8>available)throw NativeIdentities.failure(WorldProblem.Code.LIMIT,"Native scene model inspection and mask cannot coexist within the job budget");
            var request=new WorldRenderWorker.ModelRequest(input.withWorkingBytes(available),mask,limits.scene(),limits.section(),limits.workingBytes());
            context.check();check(cancelled);return new Result(snapshot.source(),snapshot.id(),Optional.of(request),masks.usage(),List.of());
        }catch(WorldCapture.ReadFailure failure) { check(cancelled);return failed(snapshot,List.of(failure.problem())); }
        catch(CancellationException cancellation) { throw cancellation; }
        catch(RuntimeException failure) { check(cancelled);return failed(snapshot,List.of(new WorldProblem(WorldProblem.Code.WORKER,Optional.empty(),"Native scene input capture failed: "+failure.getClass().getSimpleName()))); }
        catch(OutOfMemoryError exhausted) { check(cancelled);return failed(snapshot,List.of(new WorldProblem(WorldProblem.Code.MEMORY,Optional.empty(),"Not enough memory to capture complete native scene inputs"))); }
    }
    private static Result failed(WorldSnapshot snapshot,List<WorldProblem> problems) { return new Result(snapshot.source(),snapshot.id(),Optional.empty(),Optional.empty(),problems); }
    private static void check(BooleanSupplier cancelled) { if(cancelled.getAsBoolean())throw new CancellationException("Native scene input capture cancelled"); }
}
