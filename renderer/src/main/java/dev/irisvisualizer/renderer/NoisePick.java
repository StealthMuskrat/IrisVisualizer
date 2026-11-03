package dev.irisvisualizer.renderer;

import java.util.*;
import java.util.function.BooleanSupplier;

/** Bounded front-face inspection of opaque scalar bins. No Minecraft block/biome identity is fabricated. */
public final class NoisePick {
    private NoisePick() {}
    public record Hit(int face,NoiseSurface.Face sample,double distance,double localX,double localY,double localZ) {}
    public record Result(UUID volumeId,WorldRay ray,Optional<Hit> hit,int visitedFaces,List<WorldProblem> problems) {
        public Result { Objects.requireNonNull(volumeId);Objects.requireNonNull(ray);Objects.requireNonNull(hit);problems=List.copyOf(problems);if(!problems.isEmpty()&&hit.isPresent())throw new IllegalArgumentException("Partial scalar hit escaped failure"); }
        public boolean requiresPlaceholder() { return !problems.isEmpty(); }
    }
    public static Result query(NoiseSurface surface,WorldRay ray,int maximumFaces,BooleanSupplier cancelled) {
        Objects.requireNonNull(surface);Objects.requireNonNull(ray);Objects.requireNonNull(cancelled);if(maximumFaces<0||maximumFaces>1048576)throw new IllegalArgumentException("Invalid scalar query face allowance");WorldProblem.check(cancelled);
        var volume=surface.volume();if(surface.requiresPlaceholder())return new Result(volume.id(),ray,Optional.empty(),0,surface.problems());
        try { return execute(surface,ray,maximumFaces,cancelled); }
        catch(WorldProblem.Failure failure) { WorldProblem.check(cancelled);return new Result(volume.id(),ray,Optional.empty(),0,List.of(failure.problem)); }
        catch(ArithmeticException overflow) { WorldProblem.check(cancelled);return new Result(volume.id(),ray,Optional.empty(),0,List.of(new WorldProblem(WorldProblem.Code.COORDINATE,Optional.empty(),"Scalar query anchor difference exceeds exact coordinate bounds"))); }
    }
    private static Result execute(NoiseSurface surface,WorldRay ray,int maximumFaces,BooleanSupplier cancelled) {
        var volume=surface.volume();var g=volume.grid();
        if(surface.size()>maximumFaces)throw WorldProblem.fail(WorldProblem.Code.LIMIT,"Complete scalar face query exceeds its allowance; no partial nearest hit returned");
        long ox=Math.subtractExact((long)g.horizontal().originX(),ray.anchor().x()),oy=(long)g.vertical().minY()-ray.anchor().y(),oz=Math.subtractExact((long)g.horizontal().originZ(),ray.anchor().z());
        if(Math.max(Math.abs((double)ox),Math.max(Math.abs((double)oy),Math.abs((double)oz)))>1048576)throw WorldProblem.fail(WorldProblem.Code.COORDINATE,"Scalar query exceeds supported relative anchor precision");
        double x=ray.x()-ox,y=ray.y()-oy,z=ray.z()-oz,scale=g.horizontal().blocksPerSample(),nearest=ray.maximum();int found=-1;
        for(int face=0;face<surface.size();face++) {
            if((face&255)==0)WorldProblem.check(cancelled);int cell=surface.cell(face),side=surface.direction(face).ordinal();
            double minX=(cell%g.width())*scale,minY=cell/((long)g.width()*g.depth()),minZ=(cell/g.width()%g.depth())*scale,maxX=minX+scale,maxY=minY+1,maxZ=minZ+scale;
            int axis=side<2?1:side<4?2:0;double direction=axis==0?ray.dx():axis==1?ray.dy():ray.dz();boolean plus=(side&1)!=0;
            if(direction*(plus?1:-1)>=0)continue;
            double plane=axis==0?(plus?maxX:minX):axis==1?(plus?maxY:minY):(plus?maxZ:minZ),origin=axis==0?x:axis==1?y:z,t=(plane-origin)/direction;
            if(!Double.isFinite(t)||t<ray.minimum()||t>nearest||found>=0&&t==nearest)continue;
            double hx=x+t*ray.dx(),hy=y+t*ray.dy(),hz=z+t*ray.dz();
            if((axis==0||hx>=minX&&hx<=maxX)&&(axis==1||hy>=minY&&hy<=maxY)&&(axis==2||hz>=minZ&&hz<=maxZ)) { found=face;nearest=t; }
        }
        WorldProblem.check(cancelled);Optional<Hit> hit=Optional.empty();
        if(found>=0) { int cell=surface.cell(found);double minX=(cell%g.width())*scale,minY=cell/((long)g.width()*g.depth()),minZ=(cell/g.width()%g.depth())*scale;hit=Optional.of(new Hit(found,surface.face(found),nearest,x+nearest*ray.dx()-minX,y+nearest*ray.dy()-minY,z+nearest*ray.dz()-minZ)); }
        return new Result(volume.id(),ray,hit,surface.size(),List.of());
    }
}
