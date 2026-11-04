package dev.irisvisualizer.renderer;

import java.util.*;
import java.util.function.BooleanSupplier;

/** Opaque display-bin boundary mesh of captured scalar samples; never generated Minecraft blocks. */
public final class NoiseSurface {
    public enum Meshing { CELLS, GREEDY }
    public record Style(double includeMinimum,double includeMaximum,MapRaster.ScalarStyle colors,Meshing meshing) {
        public Style(double includeMinimum,double includeMaximum,MapRaster.ScalarStyle colors) { this(includeMinimum,includeMaximum,colors,Meshing.GREEDY); }
        public Style {
            Objects.requireNonNull(colors);Objects.requireNonNull(meshing);if(!Double.isFinite(includeMinimum)||!Double.isFinite(includeMaximum)||includeMinimum>includeMaximum)throw new IllegalArgumentException("Invalid scalar inclusion interval");
            for(var stop:colors.ramp().stops())if((stop.rgba()&255)!=255)throw new IllegalArgumentException("Scalar boundary surfaces require opaque colors; translucent volume ordering is unsupported");
        }
        public boolean includes(double value) { return value>=includeMinimum&&value<=includeMaximum; }
        public boolean sameGeometry(Style other) {
            Objects.requireNonNull(other);
            return Double.doubleToLongBits(includeMinimum)==Double.doubleToLongBits(other.includeMinimum)&&Double.doubleToLongBits(includeMaximum)==Double.doubleToLongBits(other.includeMaximum)&&meshing==other.meshing;
        }
    }
    public record Limits(int faces,long workingBytes) {
        public static final Limits DEFAULT=new Limits(262144,512L*1024*1024);
        public Limits { if(faces<0||faces>1048576||workingBytes<0||workingBytes>512L*1024*1024)throw new IllegalArgumentException("Scalar surface exceeds renderer bounds"); }
    }
    /** Relative to the grid's exact integer origin/minimum Y; X/Z widths use the selected display scale. */
    public record Bounds(double minX,double minY,double minZ,double maxX,double maxY,double maxZ) {}
    public record Usage(int selectedSamples,int faces,long meshBytes,long sourceBytes,long unitFaces,long scratchBytes) {
        public Usage(int selectedSamples,int faces,long meshBytes,long sourceBytes) { this(selectedSamples,faces,meshBytes,sourceBytes,faces,0); }
        public long knownPeakBytes() { return sourceBytes+meshBytes+scratchBytes; }
    }
    public static final int UNIT_FACE_BYTES=9,GREEDY_FACE_BYTES=13,COLOR_BYTES=4,BATCH_FACES=1024;
    private static long batchBytes(int faces) { return ((faces+(long)BATCH_FACES-1)/BATCH_FACES)*6*Integer.BYTES; }
    private static long meshBytes(int faces,Meshing mode) { return (long)faces*(mode==Meshing.GREEDY?GREEDY_FACE_BYTES:UNIT_FACE_BYTES)+batchBytes(faces); }
    /** Detached inspection of the original native scalar and its displayed bin face, without volume arrays. */
    public record Face(UUID volumeId,int sample,NoiseVolume.Point point,double value,ModelPlan.Direction direction,int rgba) {}
    private final NoiseVolume volume;private final Style style;private final int[] cells,spans,batches,colors;private final byte[] sides;private final Optional<Bounds> bounds;private final Optional<Usage> usage;private final List<WorldProblem> problems;
    private NoiseSurface(NoiseVolume volume,Style style,int[] cells,byte[] sides,int[] spans,int[] batches,int[] colors,Optional<Bounds> bounds,Optional<Usage> usage,List<WorldProblem> problems) {
        this.volume=volume;this.style=style;this.cells=cells;this.sides=sides;this.spans=spans;this.batches=batches;this.colors=colors;this.bounds=bounds;this.usage=usage;this.problems=List.copyOf(problems);
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
    private static NoiseSurface failed(NoiseVolume volume,Style style,List<WorldProblem> problems) { return new NoiseSurface(volume,style,new int[0],new byte[0],new int[0],new int[0],new int[0],Optional.empty(),Optional.empty(),problems); }
    /** Full immutable source plus replacement colors; equal settings share the original color array. */
    public static long recolorReservation(NoiseSurface source,MapRaster.ScalarStyle colors) {
        Objects.requireNonNull(source);Objects.requireNonNull(colors);
        return source.payloadBytes()+(source.style.colors().equals(colors)?0:(long)source.size()*COLOR_BYTES);
    }
    /** Reuse complete geometry and native samples; this operation never scans the full scalar volume. */
    public static NoiseSurface recolor(NoiseSurface source,MapRaster.ScalarStyle colors,Limits limits,BooleanSupplier cancelled) {
        Objects.requireNonNull(source);Objects.requireNonNull(colors);Objects.requireNonNull(limits);Objects.requireNonNull(cancelled);WorldProblem.check(cancelled);
        var old=source.style();var style=new Style(old.includeMinimum(),old.includeMaximum(),colors,old.meshing());
        try {
            if(source.requiresPlaceholder())return failed(source.volume(),style,source.problems());
            if(source.size()>limits.faces()||recolorReservation(source,colors)>limits.workingBytes())throw WorldProblem.fail(WorldProblem.Code.LIMIT,"Complete scalar geometry and replacement colors exceed their working allowance");
            boolean same=old.colors().equals(colors);int[] rgba=same?source.colors:new int[source.size()];
            if(!same)for(int face=0;face<rgba.length;face++) { if((face&255)==0)WorldProblem.check(cancelled);rgba[face]=colors.ramp().rgba(colors.normalize(source.volume().value(source.cell(face)))); }
            var usage=source.usage().orElseThrow();long scratch=same?0:(long)source.colors.length*COLOR_BYTES;
            var receipt=new Usage(usage.selectedSamples(),source.size(),usage.meshBytes(),usage.sourceBytes(),usage.unitFaces(),scratch);WorldProblem.check(cancelled);
            return new NoiseSurface(source.volume,style,source.cells,source.sides,source.spans,source.batches,rgba,source.bounds,Optional.of(receipt),List.of());
        }catch(WorldProblem.Failure failure) { WorldProblem.check(cancelled);return failed(source.volume(),style,List.of(failure.problem)); }
        catch(OutOfMemoryError exhausted) { WorldProblem.check(cancelled);return failed(source.volume(),style,List.of(new WorldProblem(WorldProblem.Code.MEMORY,Optional.empty(),"Not enough memory to recolor scalar surfaces; no partial colors were published"))); }
    }
    private static NoiseSurface execute(NoiseVolume volume,Style style,Limits limits,BooleanSupplier cancelled) {
        if(volume.requiresPlaceholder())return failed(volume,style,volume.problems());
        if(volume.payloadBytes()>limits.workingBytes())throw WorldProblem.fail(WorldProblem.Code.LIMIT,"Scalar source alone exceeds surface working allowance");
        if(style.meshing()==Meshing.GREEDY)return greedy(volume,style,limits,cancelled);
        var g=volume.grid();int count=0,selected=0,minX=g.width(),minY=g.height(),minZ=g.depth(),maxX=0,maxY=0,maxZ=0;
        for(int y=0,cell=0;y<g.height();y++)for(int z=0;z<g.depth();z++)for(int x=0;x<g.width();x++,cell++) {
            if((cell&1023)==0)WorldProblem.check(cancelled);if(!selected(volume,style,cell))continue;selected++;
            minX=Math.min(minX,x);minY=Math.min(minY,y);minZ=Math.min(minZ,z);maxX=Math.max(maxX,x+1);maxY=Math.max(maxY,y+1);maxZ=Math.max(maxZ,z+1);
            for(int side=0;side<6;side++)if(exterior(volume,style,x,y,z,side)) {
                if(count>=limits.faces()||volume.payloadBytes()+meshBytes(count+1,Meshing.CELLS)>limits.workingBytes())throw WorldProblem.fail(WorldProblem.Code.LIMIT,"Scalar boundary face/source payload budget exceeded; no volume area or height was omitted");count++;
            }
        }
        int[] cells=new int[count];byte[] sides=new byte[count];int at=0;
        for(int y=0,cell=0;y<g.height();y++)for(int z=0;z<g.depth();z++)for(int x=0;x<g.width();x++,cell++) {
            if((cell&1023)==0)WorldProblem.check(cancelled);if(!selected(volume,style,cell))continue;
            for(int side=0;side<6;side++)if(exterior(volume,style,x,y,z,side)) { cells[at]=cell;sides[at++]=(byte)side; }
        }
        if(at!=count)throw new IllegalStateException("Immutable scalar surface plan changed during emission");double scale=g.horizontal().blocksPerSample();
        var bounds=selected==0?Optional.<Bounds>empty():Optional.of(new Bounds(minX*scale,minY,minZ*scale,maxX*scale,maxY,maxZ*scale));WorldProblem.check(cancelled);
        return complete(volume,style,cells,sides,new int[0],bounds,new Usage(selected,count,meshBytes(count,Meshing.CELLS),volume.payloadBytes()),cancelled);
    }
    /** Prospective primitive peak; count/emit reject before allocating any array beyond this allowance. */
    public static long reservation(NoiseVolume volume,Style style,Limits limits) {
        Objects.requireNonNull(volume);Objects.requireNonNull(style);Objects.requireNonNull(limits);
        return Math.min(limits.workingBytes(),volume.payloadBytes()+scratchBytes(volume,style)+meshBytes(limits.faces(),style.meshing()));
    }
    public static long scratchBytes(NoiseVolume volume,Style style) {
        Objects.requireNonNull(volume);Objects.requireNonNull(style);if(style.meshing()==Meshing.CELLS)return 0;var g=volume.grid();
        return 4*Math.max((long)g.width()*g.depth(),Math.max((long)g.width()*g.height(),(long)g.depth()*g.height()));
    }
    private static NoiseSurface greedy(NoiseVolume volume,Style style,Limits limits,BooleanSupplier cancelled) {
        var g=volume.grid();int selected=0,minX=g.width(),minY=g.height(),minZ=g.depth(),maxX=0,maxY=0,maxZ=0;
        for(int y=0,cell=0;y<g.height();y++)for(int z=0;z<g.depth();z++)for(int x=0;x<g.width();x++,cell++) {
            if((cell&1023)==0)WorldProblem.check(cancelled);if(!selected(volume,style,cell))continue;selected++;
            minX=Math.min(minX,x);minY=Math.min(minY,y);minZ=Math.min(minZ,z);maxX=Math.max(maxX,x+1);maxY=Math.max(maxY,y+1);maxZ=Math.max(maxZ,z+1);
        }
        if(selected==0)return complete(volume,style,new int[0],new byte[0],new int[0],Optional.empty(),new Usage(0,0,0,volume.payloadBytes(),0,0),cancelled);
        int width=maxX-minX,height=maxY-minY,depth=maxZ-minZ;
        long scratch=4*Math.max((long)width*depth,Math.max((long)width*height,(long)depth*height));if(volume.payloadBytes()+scratch>limits.workingBytes())throw WorldProblem.fail(WorldProblem.Code.LIMIT,"Scalar greedy plane scratch and source exceed the working allowance");
        WorldProblem.check(cancelled);var work=new Greedy(volume,style,limits,cancelled,new int[Math.toIntExact(scratch/4)],minX,minY,minZ,width,height,depth);work.walk(false);int count=work.count;long units=work.units;
        WorldProblem.check(cancelled);work.cells=new int[count];work.sides=new byte[count];work.spans=new int[count];work.walk(true);
        if(work.count!=count||work.units!=units)throw new IllegalStateException("Immutable greedy scalar plan changed during emission");
        double scale=g.horizontal().blocksPerSample();var bounds=Optional.of(new Bounds(minX*scale,minY,minZ*scale,maxX*scale,maxY,maxZ*scale));WorldProblem.check(cancelled);
        return complete(volume,style,work.cells,work.sides,work.spans,bounds,new Usage(selected,count,meshBytes(count,Meshing.GREEDY),volume.payloadBytes(),units,scratch),cancelled);
    }
    /** Contiguous original-order ranges; exact integer face bounds avoid another vertex/face copy. */
    private static NoiseSurface complete(NoiseVolume volume,Style style,int[] cells,byte[] sides,int[] spans,Optional<Bounds> bounds,Usage usage,BooleanSupplier cancelled) {
        int count=(cells.length+BATCH_FACES-1)/BATCH_FACES;int[] batches=new int[count*6],colors=new int[cells.length];var g=volume.grid();
        for(int batch=0;batch<count;batch++) {
            WorldProblem.check(cancelled);int at=batch*6;
            for(int axis=0;axis<3;axis++) { batches[at+axis]=Integer.MAX_VALUE;batches[at+axis+3]=Integer.MIN_VALUE; }
            for(int face=batch*BATCH_FACES,end=Math.min(cells.length,(batch+1)*BATCH_FACES);face<end;face++) {
                if((face&255)==0)WorldProblem.check(cancelled);int cell=cells[face],side=sides[face],word=spans.length==0?0:spans[face],u=(word&4095)+1,v=(word>>>12)+1;
                colors[face]=style.colors().ramp().rgba(style.colors().normalize(volume.value(cell)));
                int x=cell%g.width(),y=(int)(cell/((long)g.width()*g.depth())),z=cell/g.width()%g.depth(),mx=x+(side<4?u:1),my=y+(side<2?1:v),mz=z+(side<2?v:side<4?1:u);
                switch(side) { case 0->my=y;case 1->y=my;case 2->mz=z;case 3->z=mz;case 4->mx=x;case 5->x=mx;default->throw new IllegalStateException("Invalid scalar face direction"); }
                batches[at]=Math.min(batches[at],x);batches[at+1]=Math.min(batches[at+1],y);batches[at+2]=Math.min(batches[at+2],z);
                batches[at+3]=Math.max(batches[at+3],mx);batches[at+4]=Math.max(batches[at+4],my);batches[at+5]=Math.max(batches[at+5],mz);
            }
        }
        WorldProblem.check(cancelled);return new NoiseSurface(volume,style,cells,sides,spans,batches,colors,bounds,Optional.of(usage),List.of());
    }
    private static final class Greedy {
        final NoiseVolume volume;final NoiseVolume.Grid grid;final Style style;final Limits limits;final BooleanSupplier cancelled;final int[] mask;
        final int minX,minY,minZ,width,height,depth;int count;long units;int[] cells,spans;byte[] sides;
        Greedy(NoiseVolume volume,Style style,Limits limits,BooleanSupplier cancelled,int[] mask,int minX,int minY,int minZ,int width,int height,int depth) { this.volume=volume;grid=volume.grid();this.style=style;this.limits=limits;this.cancelled=cancelled;this.mask=mask;this.minX=minX;this.minY=minY;this.minZ=minZ;this.width=width;this.height=height;this.depth=depth; }
        boolean same(int entry,long bits) { return entry!=0&&Double.doubleToLongBits(volume.value(entry-1))==bits; }
        void walk(boolean emit) {
            count=0;units=0;
            for(int side=0;side<6;side++) {
                int width=side<4?this.width:depth,height=side<2?depth:this.height,planes=side<2?this.height:side<4?depth:this.width;
                for(int plane=0;plane<planes;plane++) {
                    WorldProblem.check(cancelled);
                    for(int v=0,at=0;v<height;v++)for(int u=0;u<width;u++,at++) {
                        if((at&1023)==0)WorldProblem.check(cancelled);int x=minX+(side<4?u:plane),y=minY+(side<2?plane:v),z=minZ+(side<2?v:side<4?plane:u),cell=grid.index(x,y,z);
                        mask[at]=selected(volume,style,cell)&&exterior(volume,style,x,y,z,side)?cell+1:0;
                    }
                    for(int v=0;v<height;v++)for(int u=0;u<width;) {
                        int at=v*width+u;if((at&1023)==0)WorldProblem.check(cancelled);int entry=mask[at];if(entry==0) { u++;continue; }
                        long bits=Double.doubleToLongBits(volume.value(entry-1));int w=1,h=1;
                        while(u+w<width&&same(mask[at+w],bits)) { if((w&1023)==0)WorldProblem.check(cancelled);w++; }
                        rows:while(v+h<height) {
                            for(int x=0;x<w;x++) { if((x&1023)==0)WorldProblem.check(cancelled);if(!same(mask[(v+h)*width+u+x],bits))break rows; }h++;
                        }
                        if(count>=limits.faces()||volume.payloadBytes()+(long)mask.length*4+meshBytes(count+1,Meshing.GREEDY)>limits.workingBytes())throw WorldProblem.fail(WorldProblem.Code.LIMIT,"Scalar greedy rectangle/source/scratch budget exceeded; no samples or height were omitted");
                        if(emit) { cells[count]=entry-1;sides[count]=(byte)side;spans[count]=(w-1)|((h-1)<<12); }count++;units+=(long)w*h;
                        for(int row=0;row<h;row++) { WorldProblem.check(cancelled);Arrays.fill(mask,(v+row)*width+u,(v+row)*width+u+w,0); }u+=w;
                    }
                }
            }
        }
    }
    public NoiseVolume volume() { return volume; }
    public Style style() { return style; }
    public int size() { return cells.length; }
    public int cell(int face) { return cells[face]; }
    public ModelPlan.Direction direction(int face) { return DIRECTIONS[sides[face]]; }
    public int spanWord(int face) { Objects.checkIndex(face,cells.length);return spans.length==0?0:spans[face]; }
    public int spanU(int face) { return (spanWord(face)&4095)+1; }
    public int spanV(int face) { return (spanWord(face)>>>12)+1; }
    public int sizeX(int face) { return direction(face).ordinal()<4?spanU(face):1; }
    public int sizeY(int face) { return direction(face).ordinal()<2?1:spanV(face); }
    public int sizeZ(int face) { return direction(face).ordinal()<2?spanV(face):direction(face).ordinal()<4?1:spanU(face); }
    public int sample(int face,int u,int v) {
        if(u<0||u>=spanU(face)||v<0||v>=spanV(face))throw new IndexOutOfBoundsException("Sample outside scalar rectangle");var g=volume.grid();int side=direction(face).ordinal();
        return cell(face)+u*(side<4?1:g.width())+v*(side<2?g.width():g.width()*g.depth());
    }
    public int rgba(int face) { return colors[face]; }
    public Face face(int face) { return face(face,0,0); }
    public Face face(int face,int u,int v) { int cell=sample(face,u,v);return new Face(volume.id(),cell,volume.grid().point(cell),volume.value(cell),direction(face),rgba(face)); }
    public Optional<Bounds> bounds() { return bounds; }
    public Optional<Usage> usage() { return usage; }
    public long payloadBytes() { return volume.payloadBytes()+(long)cells.length*4+sides.length+((long)spans.length+colors.length)*4+batchBytes(); }
    public int batchCount() { return batches.length/6; }
    public int batchFirst(int batch) { Objects.checkIndex(batch,batchCount());return batch*BATCH_FACES; }
    public int batchSize(int batch) { return Math.min(BATCH_FACES,size()-batchFirst(batch)); }
    public int batchMinimum(int batch,int axis) { Objects.checkIndex(batch,batchCount());Objects.checkIndex(axis,3);return batches[batch*6+axis]; }
    public int batchMaximum(int batch,int axis) { Objects.checkIndex(batch,batchCount());Objects.checkIndex(axis,3);return batches[batch*6+axis+3]; }
    public long batchBytes() { return (long)batches.length*Integer.BYTES; }
    public Bounds batchBounds(int batch) {
        double scale=volume.grid().horizontal().blocksPerSample();return new Bounds(batchMinimum(batch,0)*scale,batchMinimum(batch,1),batchMinimum(batch,2)*scale,batchMaximum(batch,0)*scale,batchMaximum(batch,1),batchMaximum(batch,2)*scale);
    }
    public List<WorldProblem> problems() { return problems; }
    public boolean requiresPlaceholder() { return !problems.isEmpty(); }
}
