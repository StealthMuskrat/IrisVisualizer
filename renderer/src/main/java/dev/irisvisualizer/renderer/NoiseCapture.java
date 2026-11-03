package dev.irisvisualizer.renderer;

import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Synchronous worker-side native scalar detachment. This class evaluates no noise or terrain formula. */
public final class NoiseCapture {
    private NoiseCapture() {}
    public interface Sampler {
        default void begin() {}
        default boolean present(double x,int y,double z) { return true; }
        double value(double x,int y,double z);
        default void complete() {}
    }
    public record Limits(NoiseVolume.Limits volume,long workingBytes) {
        public static final Limits DEFAULT=new Limits(NoiseVolume.Limits.DEFAULT,512L*1024*1024);
        public Limits { Objects.requireNonNull(volume);if(workingBytes<0||workingBytes>512L*1024*1024)throw new IllegalArgumentException("Scalar capture exceeds its working bound"); }
    }
    public static NoiseVolume capture(MapSamples.Context context,NoiseVolume.Grid grid,Sampler sampler,Limits limits,BooleanSupplier cancelled) {
        Objects.requireNonNull(context);Objects.requireNonNull(grid);Objects.requireNonNull(sampler);Objects.requireNonNull(limits);Objects.requireNonNull(cancelled);WorldProblem.check(cancelled);
        try { return execute(context,grid,sampler,limits,cancelled); }
        catch(WorldCapture.ReadFailure failure) { WorldProblem.check(cancelled);return NoiseVolume.failed(context,grid,failure.problem()); }
        catch(WorldProblem.Failure failure) { WorldProblem.check(cancelled);return NoiseVolume.failed(context,grid,failure.problem); }
        catch(CancellationException cancellation) { throw cancellation; }
        catch(RuntimeException failure) { WorldProblem.check(cancelled);return NoiseVolume.failed(context,grid,new WorldProblem(WorldProblem.Code.WORKER,Optional.empty(),"Native scalar sampler failed: "+failure.getClass().getSimpleName())); }
        catch(OutOfMemoryError exhausted) { WorldProblem.check(cancelled);return NoiseVolume.failed(context,grid,new WorldProblem(WorldProblem.Code.MEMORY,Optional.empty(),"Not enough memory for native scalar capture; no partial volume published")); }
    }
    private static NoiseVolume execute(MapSamples.Context context,NoiseVolume.Grid grid,Sampler sampler,Limits limits,BooleanSupplier cancelled) {
        NoiseVolume.preflight(context,grid,limits.volume());if(grid.count()*18>limits.workingBytes())throw WorldProblem.fail(WorldProblem.Code.LIMIT,"Native scalar sampling and detached copy exceed the full-height working bound");
        sampler.begin();WorldProblem.check(cancelled);
        double[] values=new double[Math.toIntExact(grid.count())];byte[] present=new byte[values.length];
        for(int y=0,at=0;y<grid.height();y++)for(int z=0;z<grid.depth();z++)for(int x=0;x<grid.width();x++,at++) {
            WorldProblem.check(cancelled);double wx=grid.horizontal().worldX(x),wz=grid.horizontal().worldZ(z);int wy=Math.toIntExact((long)grid.vertical().minY()+y);
            if(!sampler.present(wx,wy,wz))continue;double value=sampler.value(wx,wy,wz);if(!Double.isFinite(value))throw WorldProblem.fail(WorldProblem.Code.ASSIGNMENT,"Native 3D scalar sampler returned a nonfinite value");present[at]=1;values[at]=value;
        }
        sampler.complete();WorldProblem.check(cancelled);var volume=NoiseVolume.capture(context,grid,values,present,limits.volume(),cancelled);sampler.complete();WorldProblem.check(cancelled);return volume;
    }
}
