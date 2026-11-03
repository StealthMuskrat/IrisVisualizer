package dev.irisvisualizer.renderer;

import java.util.*;
import java.util.function.BooleanSupplier;

/** Opaque display-bin boundary mesh of captured scalar samples; never generated Minecraft blocks. */
public final class NoiseSurface {
    public record Style(double includeMinimum,double includeMaximum,MapRaster.ScalarStyle colors) {
        public Style {
            Objects.requireNonNull(colors);if(!Double.isFinite(includeMinimum)||!Double.isFinite(includeMaximum)||includeMinimum>includeMaximum)throw new IllegalArgumentException("Invalid scalar inclusion interval");
            for(var stop:colors.ramp().stops())if((stop.rgba()&255)!=255)throw new IllegalArgumentException("Scalar boundary surfaces require opaque colors; translucent volume ordering is unsupported");
        }
        public boolean includes(double value) { return value>=includeMinimum&&value<=includeMaximum; }
    }
    public record Limits(int faces,long workingBytes) {
        public static final Limits DEFAULT=new Limits(262144,512L*1024*1024);
        public Limits { if(faces<0||faces>1048576||workingBytes<0||workingBytes>512L*1024*1024)throw new IllegalArgumentException("Scalar surface exceeds renderer bounds"); }
    }
    /** Relative to the grid's exact integer origin/minimum Y; X/Z widths use the selected display scale. */
    public record Bounds(double minX,double minY,double minZ,double maxX,double maxY,double maxZ) {}
    public record Usage(int selectedSamples,int faces,long meshBytes,long sourceBytes) {}
    /** Detached inspection of the original native scalar and its displayed bin face, without volume arrays. */
    public record Face(UUID volumeId,int sample,NoiseVolume.Point point,double value,ModelPlan.Direction direction,int rgba) {}
    private final NoiseVolume volume;private final Style style;private final int[] cells;private final byte[] sides;private final Optional<Bounds> bounds;private final Optional<Usage> usage;private final List<WorldProblem> problems;
    private NoiseSurface(NoiseVolume volume,Style style,int[] cells,byte[] sides,Optional<Bounds> bounds,Optional<Usage> usage,List<WorldProblem> problems) {
        this.volume=volume;this.style=style;this.cells=cells;this.sides=sides;this.bounds=bounds;this.usage=usage;this.problems=List.copyOf(problems);
    }
    private static final ModelPlan.Direction[] DIRECTIONS=ModelPlan.Direction.values();
    private static final int[] X={0,0,0,0,-1,1},Y={-1,1,0,0,0,0},Z={0,0,-1,1,0,0};
    private static boolean selected(NoiseVolume volume,Style style,int cell) { return volume.present(cell)&&style.includes(volume.value(cell)); }
    private static boolean exterior(NoiseVolume volume,Style style,int x,int y,int z,int side) {
        var g=volume.grid();int nx=x+X[side],ny=y+Y[side],nz=z+Z[side];
        return nx<0||nx>=g.width()||ny<0||ny>=g.height()||nz<0||nz>=g.depth()||!selected(volume,style,g.index(nx,ny,nz));
    }
    public static NoiseSurface prepare(NoiseVolume volume,Style style,Limits limits,BooleanSupplier cancelled) {
        Objects.requireNonNull(volume);Objects.requireNonNull(style);Objects.requireNonNull(limits);Objects.requireNonNull(cancelled);WorldProblem.check(cancelled);
        try { return execute(volume,style,limits,cancelled); }
        catch(WorldProblem.Failure failure) { WorldProblem.check(cancelled);return failed(volume,style,List.of(failure.problem)); }
        catch(OutOfMemoryError exhausted) { WorldProblem.check(cancelled);return failed(volume,style,List.of(new WorldProblem(WorldProblem.Code.MEMORY,Optional.empty(),"Not enough memory for scalar boundary surfaces; no partial mesh published"))); }
    }
    private static NoiseSurface failed(NoiseVolume volume,Style style,List<WorldProblem> problems) { return new NoiseSurface(volume,style,new int[0],new byte[0],Optional.empty(),Optional.empty(),problems); }
    private static NoiseSurface execute(NoiseVolume volume,Style style,Limits limits,BooleanSupplier cancelled) {
        if(volume.requiresPlaceholder())return failed(volume,style,volume.problems());
        if(volume.payloadBytes()>limits.workingBytes())throw WorldProblem.fail(WorldProblem.Code.LIMIT,"Scalar source alone exceeds surface working allowance");
        var g=volume.grid();int count=0,selected=0,minX=g.width(),minY=g.height(),minZ=g.depth(),maxX=0,maxY=0,maxZ=0;
        for(int y=0,cell=0;y<g.height();y++)for(int z=0;z<g.depth();z++)for(int x=0;x<g.width();x++,cell++) {
            if((cell&1023)==0)WorldProblem.check(cancelled);if(!selected(volume,style,cell))continue;selected++;
            minX=Math.min(minX,x);minY=Math.min(minY,y);minZ=Math.min(minZ,z);maxX=Math.max(maxX,x+1);maxY=Math.max(maxY,y+1);maxZ=Math.max(maxZ,z+1);
            for(int side=0;side<6;side++)if(exterior(volume,style,x,y,z,side)) {
                if(count>=limits.faces()||volume.payloadBytes()+((long)count+1)*5>limits.workingBytes())throw WorldProblem.fail(WorldProblem.Code.LIMIT,"Scalar boundary face/source payload budget exceeded; no volume area or height was omitted");count++;
            }
        }
        int[] cells=new int[count];byte[] sides=new byte[count];int at=0;
        for(int y=0,cell=0;y<g.height();y++)for(int z=0;z<g.depth();z++)for(int x=0;x<g.width();x++,cell++) {
            if((cell&1023)==0)WorldProblem.check(cancelled);if(!selected(volume,style,cell))continue;
            for(int side=0;side<6;side++)if(exterior(volume,style,x,y,z,side)) { cells[at]=cell;sides[at++]=(byte)side; }
        }
        if(at!=count)throw new IllegalStateException("Immutable scalar surface plan changed during emission");double scale=g.horizontal().blocksPerSample();
        var bounds=selected==0?Optional.<Bounds>empty():Optional.of(new Bounds(minX*scale,minY,minZ*scale,maxX*scale,maxY,maxZ*scale));WorldProblem.check(cancelled);
        return new NoiseSurface(volume,style,cells,sides,bounds,Optional.of(new Usage(selected,count,(long)count*5,volume.payloadBytes())),List.of());
    }
    public NoiseVolume volume() { return volume; }
    public Style style() { return style; }
    public int size() { return cells.length; }
    public int cell(int face) { return cells[face]; }
    public ModelPlan.Direction direction(int face) { return DIRECTIONS[sides[face]]; }
    public int rgba(int face) { double value=volume.value(cell(face));return style.colors().ramp().rgba(style.colors().normalize(value)); }
    public Face face(int face) { int cell=cell(face);return new Face(volume.id(),cell,volume.grid().point(cell),volume.value(cell),direction(face),rgba(face)); }
    public Optional<Bounds> bounds() { return bounds; }
    public Optional<Usage> usage() { return usage; }
    public long payloadBytes() { return volume.payloadBytes()+(long)cells.length*4+sides.length; }
    public List<WorldProblem> problems() { return problems; }
    public boolean requiresPlaceholder() { return !problems.isEmpty(); }
}
