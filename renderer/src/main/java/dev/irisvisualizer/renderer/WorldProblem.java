package dev.irisvisualizer.renderer;

import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Scene-level diagnostics, separate from source-asset model problems and engine readiness. */
public record WorldProblem(Code code,Optional<WorldSnapshot.Position> position,String reason) {
    public enum Code { VERSION, LIMIT, PALETTE, STATE, ASSIGNMENT, ATLAS, VISIBILITY, EXTENT, COORDINATE, MEMORY }
    public WorldProblem { Objects.requireNonNull(code);Objects.requireNonNull(position);Objects.requireNonNull(reason); }
    static final class Failure extends RuntimeException {
        final WorldProblem problem;Failure(WorldProblem problem) { super(problem.reason());this.problem=problem; }
    }
    static Failure fail(Code code,String reason) { return new Failure(new WorldProblem(code,Optional.empty(),reason)); }
    static Failure at(Code code,WorldSnapshot.Position position,String reason) { return new Failure(new WorldProblem(code,Optional.of(position),reason)); }
    static void check(BooleanSupplier cancelled) { if(cancelled.getAsBoolean())throw new CancellationException("World mesh work cancelled"); }
}
