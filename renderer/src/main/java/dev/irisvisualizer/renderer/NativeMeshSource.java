package dev.irisvisualizer.renderer;

import java.util.*;

/** Detached native visual provenance. It deliberately retains no snapshot or native/client object. */
public record NativeMeshSource(UUID snapshotId,WorldSnapshot.Source generation,int nativeStateId,Optional<WorldSnapshot.Position> position,Kind kind,String policy) {
    public enum Kind { BLOCK, FLUID, SPECIAL, COMPOSITE }
    public NativeMeshSource {
        Objects.requireNonNull(snapshotId);Objects.requireNonNull(generation);Objects.requireNonNull(position);Objects.requireNonNull(kind);Objects.requireNonNull(policy);
        if(nativeStateId<0||policy.isBlank()||policy.length()>256)throw new IllegalArgumentException("Native geometry requires bounded state/producer provenance");
    }
}
