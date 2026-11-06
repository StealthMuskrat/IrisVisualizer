package dev.irisvisualizer.renderer;

import java.io.*;
import java.math.BigDecimal;
import java.nio.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.zip.*;

/** Synthetic CPU contracts only. Compile during implementation; execute at renderer-stage validation. */
public final class RendererCpuContracts {
    private static final BooleanSupplier LIVE=()->false;
    private static int groups;
    private RendererCpuContracts() {}
    private static void check(boolean condition,String message) { if(!condition)throw new AssertionError(message); }
    private static void equal(Object expected,Object actual,String message) { check(Objects.equals(expected,actual),message+": expected "+expected+", got "+actual); }
    private static void near(double expected,double actual,String message) { check(Double.isFinite(actual)&&Math.abs(expected-actual)<=1e-10*Math.max(1,Math.abs(expected)),message+": expected "+expected+", got "+actual); }
    private static <T extends Throwable> void throwsType(Class<T> type,Runnable action,String message) {
        try { action.run(); }catch(Throwable failure) { if(type.isInstance(failure))return;throw new AssertionError(message+": wrong exception",failure); }
        throw new AssertionError(message+": exception missing");
    }
    private static void group(String name,Runnable action) { action.run();groups++;System.out.println("PASS "+name); }
    private static MapSamples.Context context() {
        return new MapSamples.Context(new WorldSnapshot.Source(UUID.fromString("00000000-0000-0000-0000-000000000001"),"0".repeat(64),"synthetic-contract-fixture",BlockModels.PROFILE,AssetId.minecraft("overworld"),Long.MIN_VALUE),"test","synthetic","supplied samples; no native engine");
    }
    private static byte[] present(int count) { var flags=new byte[count];Arrays.fill(flags,(byte)1);return flags; }
    private static MapRamp constant(int rgba) { return new MapRamp(List.of(new MapRamp.Stop(0,rgba),new MapRamp.Stop(1,rgba))); }
    private static MapRaster.ScalarStyle gray(double minimum,double maximum) { return new MapRaster.ScalarStyle(minimum,maximum,MapRamp.GRAYSCALE); }
    private static MapSamples terrain(int width,int depth,int[] values,byte[] flags) {
        return MapSamples.terrain(context(),new MapSamples.Grid(-17,23,width,depth,2),new MapSamples.Vertical(-64,384),values,flags,LIVE);
    }
    private static MapRaster raster(MapSamples samples,MapRaster.Style style) {
        var result=MapRaster.render(samples,style,LIVE);check(!result.requiresPlaceholder(),"fixture raster complete: "+result.problems());return result;
    }
    private static void problem(WorldProblem.Code code,MapRaster result) {
        check(result.requiresPlaceholder()&&result.image().isEmpty()&&result.summary().isEmpty(),"failure exposes no partial raster");equal(code,result.problems().getFirst().code(),"diagnostic");
    }
    private static ScalarHistogram.Limits histogramLimits(int bins,long samples,long visits,long payload,long working) { return new ScalarHistogram.Limits(bins,samples,visits,payload,working); }
    private static void scalarHistograms() {
        var grid=new MapSamples.Grid(-10,4,9,1,1);double[] values={-2,-1,0,0.25,0.5,0.75,1,2,Double.NaN};byte[] flags={1,1,1,1,1,1,1,1,0};
        var source=MapSamples.noise(context(),grid,values,flags,LIVE);var range=new ScalarHistogram.Range(0,1);var limits=histogramLimits(4,9,18,65536,512L*1024*1024);
        var histogram=ScalarHistogram.capture(source,Optional.of(range),limits,LIVE);check(!histogram.requiresPlaceholder(),"complete scalar histogram");equal(List.of(1,1,1,2),Arrays.stream(histogram.copyCounts()).boxed().toList(),"exact colorizer bins and inclusive maximum");
        var summary=histogram.summary().orElseThrow();equal(8,summary.present(),"all present values counted");equal(1,summary.absent(),"missing sample is not zero");equal(5,summary.inRange(),"only included values enter bins");equal(2,summary.below(),"lower outliers stay separate");equal(1,summary.above(),"upper outliers stay separate");near(-2,summary.minimum().orElseThrow(),"observed minimum includes outliers");near(2,summary.maximum().orElseThrow(),"observed maximum includes outliers");equal(9L,summary.visits(),"explicit range needs one full pass");check(histogram.matches(source),"exact source receipt");
        var duplicate=MapSamples.noise(context(),grid,values,flags,LIVE);check(!histogram.matches(duplicate),"equal context/data cannot reuse another capture receipt");var copy=histogram.copyCounts();copy[0]=999;equal(1,histogram.count(0),"histogram bins are immutable");
        for(int edge=0;edge<=4;edge++)near(edge/4.0,histogram.boundary(edge).orElseThrow(),"display legend edge");equal(range.style(MapRamp.GRAYSCALE),histogram.displayStyle(MapRamp.GRAYSCALE).orElseThrow(),"legend style preserves selected range");
        var displayed=raster(source,gray(0,1));check(histogram.matches(displayed),"histogram matches current display source/range");var recolored=MapRaster.recolor(displayed,new MapRaster.ScalarStyle(0,1,constant(0x123456ff)),MapRaster.Limits.DEFAULT,LIVE);check(histogram.matches(recolored),"gradient-only recoloring reuses source distribution");check(!histogram.matches(raster(source,gray(0,2))),"same-source range change rejects stale histogram");
        var automatic=ScalarHistogram.capture(source,Optional.empty(),limits,LIVE);check(!automatic.requiresPlaceholder(),"automatic observed range");equal(new ScalarHistogram.Range(-2,2),automatic.range().orElseThrow(),"actual source range retained");equal(List.of(1,1,4,2),Arrays.stream(automatic.copyCounts()).boxed().toList(),"automatic bins cover all original finite values");equal(18L,automatic.summary().orElseThrow().visits(),"automatic range uses two complete passes");
        var absent=MapSamples.noise(context(),new MapSamples.Grid(0,0,2,1,1),new double[]{Double.NaN,Double.POSITIVE_INFINITY},new byte[2],LIVE);
        var noValues=ScalarHistogram.capture(absent,Optional.empty(),histogramLimits(4,2,2,65536,512L*1024*1024),LIVE);check(!noValues.requiresPlaceholder()&&noValues.range().isEmpty(),"all-absent automatic source invents no range");equal(0,noValues.summary().orElseThrow().present(),"all-absent count");equal(2,noValues.summary().orElseThrow().absent(),"complete missing coverage");equal(2L,noValues.summary().orElseThrow().visits(),"all-absent automatic range skips unneeded second pass");check(noValues.boundary(0).isEmpty()&&noValues.displayStyle(MapRamp.GRAYSCALE).isEmpty(),"no fake legend for missing data");
        var fixedAbsent=ScalarHistogram.capture(absent,Optional.of(range),limits,LIVE);check(!fixedAbsent.requiresPlaceholder()&&fixedAbsent.range().orElseThrow().equals(range),"explicit range survives absent source");equal(0,Arrays.stream(fixedAbsent.copyCounts()).sum(),"missing values do not populate constant bins");
        for(double magnitude:new double[]{Double.MAX_VALUE,Double.MIN_VALUE}) {
            var extremes=MapSamples.noise(context(),new MapSamples.Grid(0,0,3,1,1),new double[]{-magnitude,0,magnitude},present(3),LIVE);var result=ScalarHistogram.capture(extremes,Optional.empty(),limits,LIVE);check(!result.requiresPlaceholder(),"extreme finite source histogram");equal(List.of(1,0,1,1),Arrays.stream(result.copyCounts()).boxed().toList(),"overflow/subnormal normalized bins");near(-magnitude,result.boundary(0).orElseThrow(),"exact minimum edge");near(0,result.boundary(2).orElseThrow(),"finite symmetric midpoint");near(magnitude,result.boundary(4).orElseThrow(),"exact maximum edge");for(int edge=0;edge<=4;edge++)check(Double.isFinite(result.boundary(edge).orElseThrow()),"every extreme legend edge stays finite");
        }
        var constants=MapSamples.noise(context(),new MapSamples.Grid(0,0,3,1,1),new double[]{7,7,7},present(3),LIVE);var flat=ScalarHistogram.capture(constants,Optional.empty(),limits,LIVE);equal(3,flat.count(2),"constant range uses colorizer midpoint bin");for(int edge=0;edge<=4;edge++)near(7,flat.boundary(edge).orElseThrow(),"constant legend stays exact");
        var aroundZero=MapSamples.noise(context(),new MapSamples.Grid(0,0,3,1,1),new double[]{-1,0,1},present(3),LIVE);var zeroRange=ScalarHistogram.capture(aroundZero,Optional.of(new ScalarHistogram.Range(0,0)),limits,LIVE);equal(1,zeroRange.count(2),"constant-range midpoint contains only its actual value");equal(1,zeroRange.summary().orElseThrow().below(),"constant-range lower outlier");equal(1,zeroRange.summary().orElseThrow().above(),"constant-range upper outlier");
        var zeros=MapSamples.noise(context(),new MapSamples.Grid(0,0,2,1,1),new double[]{-0.0,+0.0},present(2),LIVE);var signed=ScalarHistogram.capture(zeros,Optional.empty(),limits,LIVE);equal(Double.doubleToRawLongBits(-0.0),Double.doubleToRawLongBits(signed.summary().orElseThrow().minimum().orElseThrow()),"original signed minimum");equal(Double.doubleToRawLongBits(+0.0),Double.doubleToRawLongBits(signed.summary().orElseThrow().maximum().orElseThrow()),"original signed maximum");equal(2,signed.count(2),"signed-zero range is constant");equal(Double.doubleToRawLongBits(+0.0),Double.doubleToRawLongBits(gray(+0.0,-0.0).valueAt(0.5)),"numeric-equal reversed zero endpoints have a finite interior");
        var heights=terrain(2,2,new int[]{-64,0,319,0},new byte[]{1,1,1,0});var terrainRaster=raster(heights,MapRaster.terrainStyle(heights));var terrainHistogram=terrainRaster.histogram(limits,LIVE);check(!terrainHistogram.requiresPlaceholder(),"complete terrain histogram");equal(ScalarHistogram.Kind.TERRAIN_MAP,terrainHistogram.receipt().kind(),"terrain receipt remains distinct");equal(List.of(2,0,0,1),Arrays.stream(terrainHistogram.copyCounts()).boxed().toList(),"native full-height range histogram");equal(heights.vertical(),terrainHistogram.receipt().vertical(),"original configured vertical range retained");
        var volume=NoiseVolume.capture(context(),new NoiseVolume.Grid(new MapSamples.Grid(0,0,2,1,1),new MapSamples.Vertical(-64,2)),new double[]{0,1,2,99},new byte[]{1,1,1,0},NoiseVolume.Limits.DEFAULT,LIVE);
        var surface=NoiseSurface.prepare(volume,new NoiseSurface.Style(0,0,gray(0,2)),NoiseSurface.Limits.DEFAULT,LIVE);check(!surface.requiresPlaceholder(),"one-selected-sample scalar surface");var volumeHistogram=surface.histogram(limits,LIVE);equal(3,volumeHistogram.summary().orElseThrow().present(),"histogram covers full volume, not just selected surface");equal(ScalarHistogram.Kind.NOISE_VOLUME,volumeHistogram.receipt().kind(),"volume source kind");equal(2,volumeHistogram.receipt().vertical().orElseThrow().height(),"full configured height retained");check(volumeHistogram.matches(volume)&&!volumeHistogram.matches(source),"map/volume source identities never alias");equal(List.of(1,0,1,1),Arrays.stream(volumeHistogram.copyCounts()).boxed().toList(),"full volume bins preserve original doubles");
        check(volumeHistogram.matches(surface),"histogram matches exact scalar surface source/range");var broader=NoiseSurface.prepare(volume,new NoiseSurface.Style(0,2,gray(0,2)),NoiseSurface.Limits.DEFAULT,LIVE);check(volumeHistogram.matches(broader),"full-volume histogram ignores surface inclusion changes");var newRange=NoiseSurface.recolor(surface,gray(0,1),NoiseSurface.Limits.DEFAULT,LIVE);check(!volumeHistogram.matches(newRange),"surface range change rejects old histogram");
        long rasterPeak=terrainRaster.payloadBytes()+terrainHistogram.bytes();check(!terrainRaster.histogram(histogramLimits(4,4,4,65536,rasterPeak),LIVE).requiresPlaceholder(),"map convenience admits source plus retained RGBA and histogram");check(terrainRaster.histogram(histogramLimits(4,4,4,65536,rasterPeak-1),LIVE).requiresPlaceholder(),"map convenience keeps borrowed RGBA charged");
        long surfacePeak=surface.payloadBytes()+volumeHistogram.bytes();check(!surface.histogram(histogramLimits(4,4,4,65536,surfacePeak),LIVE).requiresPlaceholder(),"surface convenience admits full source geometry and histogram");check(surface.histogram(histogramLimits(4,4,4,65536,surfacePeak-1),LIVE).requiresPlaceholder(),"surface convenience keeps borrowed geometry charged");
        var invalid=MapSamples.noise(context(),new MapSamples.Grid(0,0,1,1,1),new double[]{Double.NaN},present(1),LIVE);var failed=ScalarHistogram.capture(invalid,LIVE);check(failed.requiresPlaceholder()&&failed.problems().equals(invalid.problems()),"source diagnostics preserved");equal(0,failed.bins(),"failed source has no partial bin array");check(failed.summary().isEmpty()&&failed.range().isEmpty()&&failed.bytes()==0,"failed source has no partial statistics");
        long payload=histogram.bytes(),peak=source.payloadBytes()+payload;var exact=histogramLimits(4,9,9,payload,peak);check(!ScalarHistogram.capture(source,Optional.of(range),exact,LIVE).requiresPlaceholder(),"exact sample/visit/payload/working boundaries");
        for(var rejected:List.of(histogramLimits(4,8,9,payload,peak),histogramLimits(4,9,8,payload,peak),histogramLimits(4,9,9,payload-1,peak),histogramLimits(4,9,9,payload,peak-1))) {
            var result=ScalarHistogram.capture(source,Optional.of(range),rejected,LIVE);check(result.requiresPlaceholder()&&result.problems().getFirst().code()==WorldProblem.Code.LIMIT,"histogram limits are explicit");check(result.bins()==0&&result.summary().isEmpty()&&result.range().isEmpty()&&result.bytes()==0,"limit failure publishes no partial histogram");
        }
        var shortAuto=ScalarHistogram.capture(source,Optional.empty(),histogramLimits(4,9,17,payload,peak),LIVE);check(shortAuto.requiresPlaceholder()&&shortAuto.bins()==0,"automatic range cannot silently omit its second pass");
        try(var memory=new RenderMemory(new RenderMemory.Limits(peak,1))) {
            var owned=ScalarHistogram.captureOwned(source,Optional.of(range),exact,memory,LIVE);equal(payload,memory.stats().chargedBytes(),"owned histogram shrinks to detached payload");var retained=owned.retain();owned.close();equal(payload,memory.stats().chargedBytes(),"retained histogram owner keeps charge");equal(5,retained.value().summary().orElseThrow().inRange(),"detached retained counts remain readable");retained.close();equal(0L,memory.stats().chargedBytes(),"final histogram owner releases charge");equal(0,memory.stats().accounts(),"no histogram account leak");
            final int[] checks={0};throwsType(CancellationException.class,()->ScalarHistogram.captureOwned(source,Optional.of(range),exact,memory,()->++checks[0]>2),"admitted histogram cancellation");equal(0L,memory.stats().chargedBytes(),"cancelled histogram releases peak");
            var callbackFailure=new AssertionError("histogram callback failure");checks[0]=0;try { ScalarHistogram.captureOwned(source,Optional.of(range),exact,memory,()->{if(++checks[0]>2)throw callbackFailure;return false;});throw new AssertionError("missing histogram callback error"); }catch(AssertionError actual) { check(actual==callbackFailure,"owned histogram preserves callback error identity"); }equal(0L,memory.stats().chargedBytes(),"failed callback releases admitted peak");
            throwsType(WorldCapture.ReadFailure.class,()->ScalarHistogram.captureOwned(source,Optional.empty(),exact,memory,LIVE),"owned automatic-pass limit failure");equal(0L,memory.stats().chargedBytes(),"failed owned histogram releases peak");
        }
        try(var memory=new RenderMemory(new RenderMemory.Limits(peak,2));var original=memory.hold("borrowed source",source.payloadBytes())) {
            throwsType(RenderMemory.LimitException.class,()->ScalarHistogram.captureOwned(source,Optional.of(range),exact,memory,LIVE),"already-owned source gives no implicit replacement credit");equal(source.payloadBytes(),original.bytes(),"rejected histogram preserves caller's source hold");equal(1,memory.stats().accounts(),"shared rejection leaves only original source account");
        }
        var biome=new WorldSnapshot.Biome(3,"fixture:biome",new AssetId("fixture","biome"));var categorical=MapSamples.biomes(context(),new MapSamples.Grid(0,0,1,1,1),List.of(biome),new int[1],present(1),LIVE);throwsType(IllegalArgumentException.class,()->ScalarHistogram.capture(categorical,LIVE),"categorical biomes are not numeric scalar values");
        throwsType(CancellationException.class,()->ScalarHistogram.capture(source,()->true),"histogram preflight cancellation");throwsType(IndexOutOfBoundsException.class,()->histogram.boundary(5),"legend edge bound");throwsType(IllegalArgumentException.class,()->gray(0,1).valueAt(Double.NaN),"nonfinite legend position");throwsType(IllegalArgumentException.class,()->histogramLimits(4097,1,1,1,1),"bin count hard bound");
    }
    private static void extents() {
        var extent=new WorldSnapshot.Extent(9_007_199_254_740_993L,Integer.MAX_VALUE-2,-9_007_199_254_740_993L,3,3,2);
        for(int at=0;at<18;at++)equal(at,extent.index(extent.position(at)),"exact long-anchor cell round trip");
        equal(2_147_483_648L,extent.maxY(),"exclusive full-height endpoint");
        check(!extent.contains(new WorldSnapshot.Position(extent.maxX(),extent.minY(),extent.minZ())),"X upper edge excluded");
        var clip=new WorldClip(new WorldSnapshot.Extent(extent.minX()+1,extent.minY(),extent.minZ()+1,1,2,1));
        var relative=clip.relative(extent.position(0));near(1,relative.minX(),"exact clip X subtraction");near(2,relative.maxZ(),"exact clip Z subtraction");
    }
    private static void mapCapture() {
        int[] heights={0,15,99,-2};byte[] flags={1,1,0,1};var samples=terrain(2,2,heights,flags);
        check(!samples.requiresPlaceholder(),"terrain capture");heights[0]=99;flags[1]=0;
        equal(0,samples.height(0),"real native zero retained");check(samples.present(1),"presence detached");check(!samples.present(2),"absence retained");
        throwsType(IllegalStateException.class,()->samples.height(2),"absent height never invented");equal(20L,samples.payloadBytes(),"integer/presence payload");
        var second=terrain(2,2,new int[]{0,15,0,-2},new byte[]{1,1,0,1});check(!samples.id().equals(second.id()),"identical capture inputs have distinct identities");
        near(-15,samples.grid().worldX(1),"world X spacing");near(25,samples.grid().worldZ(1),"world Z spacing");
    }
    private static void mapFailure() {
        var bad=terrain(2,1,new int[]{1,500},present(2));check(bad.requiresPlaceholder()&&bad.size()==0&&bad.payloadBytes()==0,"bad height withholds all samples");
        equal(WorldProblem.Code.EXTENT,bad.problems().getFirst().code(),"height diagnostic");
        var flags=terrain(1,1,new int[]{0},new byte[]{2});equal(WorldProblem.Code.ASSIGNMENT,flags.problems().getFirst().code(),"invalid presence");
        var noise=MapSamples.noise(context(),new MapSamples.Grid(0,0,1,1,1),new double[]{Double.NaN},present(1),LIVE);check(noise.requiresPlaceholder()&&noise.size()==0,"nonfinite present noise withheld");
        var absent=MapSamples.noise(context(),new MapSamples.Grid(0,0,1,1,1),new double[]{Double.NaN},new byte[]{0},LIVE);check(!absent.requiresPlaceholder()&&!absent.present(0),"absent noise has no invented finite requirement");
        throwsType(CancellationException.class,()->MapSamples.terrain(context(),new MapSamples.Grid(0,0,1,1,1),new MapSamples.Vertical(0,1),new int[]{0},present(1),()->true),"capture cancellation separate");
    }
    private static void ramps() {
        var stops=new ArrayList<>(List.of(new MapRamp.Stop(0,0x00000000),new MapRamp.Stop(1,0xffffffff)));var a=new MapRamp(stops);stops.clear();
        var b=new MapRamp(List.of(new MapRamp.Stop(0,0),new MapRamp.Stop(1,0xffffffff)));equal(a,b,"ramp values");equal(a.hashCode(),b.hashCode(),"equal hashes");
        equal("equal",Map.of(a,"equal").get(b),"ramp hash lookup");equal(0x80808080,a.rgba(0.5),"independent channel rounding including alpha");
        equal(0,a.rgba(-1),"low clamp");equal(0xffffffff,a.rgba(2),"high clamp");
        throwsType(UnsupportedOperationException.class,()->a.stops().clear(),"immutable stops");throwsType(IllegalArgumentException.class,()->a.rgba(Double.NaN),"nonfinite lookup");
        throwsType(IllegalArgumentException.class,()->new MapRamp(List.of(new MapRamp.Stop(0,0),new MapRamp.Stop(0,1),new MapRamp.Stop(1,2))),"duplicate stops");
    }
    private static void scalarPixels() {
        var samples=MapSamples.noise(context(),new MapSamples.Grid(0,0,3,1,1),new double[]{-Double.MAX_VALUE,0,Double.MAX_VALUE},present(3),LIVE);
        var result=raster(samples,gray(-Double.MAX_VALUE,Double.MAX_VALUE));var image=result.image().orElseThrow();
        equal(0x000000ff,image.rgba(0,0),"extreme minimum");equal(0x808080ff,image.rgba(1,0),"overflow-safe midpoint");equal(0xffffffff,image.rgba(2,0),"extreme maximum");
        var flat=raster(samples,gray(5,5));for(int x=0;x<3;x++)equal(0x808080ff,flat.image().orElseThrow().rgba(x,0),"constant range midpoint");
    }
    private static void recolor() {
        var source=raster(terrain(2,1,new int[]{0,10},present(2)),gray(0,10));
        var same=new MapRaster.ScalarStyle(0,10,new MapRamp(List.of(new MapRamp.Stop(0,0x000000ff),new MapRamp.Stop(1,0xffffffff))));
        equal(source.payloadBytes(),MapRaster.recolorReservation(source,same),"equal-style source-only peak");
        check(source==MapRaster.recolor(source,same,MapRaster.Limits.DEFAULT,LIVE),"equal style reuses exact raster");
        var changed=new MapRaster.ScalarStyle(0,10,constant(0x12345678));equal(source.payloadBytes()+8,MapRaster.recolorReservation(source,changed),"complete old/new peak");
        var result=MapRaster.recolor(source,changed,new MapRaster.Limits(8,26),LIVE);check(!result.requiresPlaceholder(),"exact peak allowed");
        check(result.samples()==source.samples(),"same exact native samples");equal(source.summary(),result.summary(),"native extrema unchanged");equal(0x12345678,result.image().orElseThrow().rgba(1,0),"replacement pixels");equal(0xffffffff,source.image().orElseThrow().rgba(1,0),"old image immutable");
        problem(WorldProblem.Code.LIMIT,MapRaster.recolor(source,changed,new MapRaster.Limits(8,25),LIVE));
        problem(WorldProblem.Code.LIMIT,MapRaster.recolor(source,same,new MapRaster.Limits(7,64),LIVE));
        problem(WorldProblem.Code.LIMIT,MapRaster.recolor(source,same,new MapRaster.Limits(8,17),LIVE));
        problem(WorldProblem.Code.ASSIGNMENT,MapRaster.recolor(source,new MapRaster.BiomeStyle(List.of(0)),MapRaster.Limits.DEFAULT,LIVE));
        throwsType(CancellationException.class,()->MapRaster.recolor(source,same,MapRaster.Limits.DEFAULT,()->true),"equal-style cancellation");
    }
    private static void categorical() {
        var first=new WorldSnapshot.Biome(91,"native:high",new AssetId("native","high"));var second=new WorldSnapshot.Biome(7,"native:low",new AssetId("native","low"));
        var samples=MapSamples.biomes(context(),new MapSamples.Grid(-2,4,3,1,0.5),List.of(first,second),new int[]{1,0,999},new byte[]{1,1,0},LIVE);
        var result=raster(samples,new MapRaster.BiomeStyle(List.of(0x10203000,0xaabbccff)));equal(second,samples.biome(0),"palette order preserves ID");equal(91,samples.biome(1).nativeBiomeId(),"native ID unchanged");
        equal(0xaabbccff,result.image().orElseThrow().rgba(0,0),"palette index color");equal(0,result.image().orElseThrow().rgba(2,0),"absent biome transparent");
        var hit=MapPick.at(result,new MapView(3,1,0,0,1),1.5,0.5).orElseThrow();equal(first,hit.biome().orElseThrow(),"zero-alpha native biome inspectable");equal(0x10203000,hit.rgba(),"transparent color retains RGB");
        problem(WorldProblem.Code.PALETTE,MapRaster.render(samples,new MapRaster.BiomeStyle(List.of(0)),LIVE));
    }
    private static void relief() {
        int[] heights=new int[9];for(int z=0;z<3;z++)for(int x=0;x<3;x++)heights[z*3+x]=10+2*x+4*z;
        var colors=new MapRaster.ScalarStyle(0,30,constant(0xc8643280));var style=new MapRaster.TerrainStyle(colors,new MapRaster.Relief(0,1,0,0.2,0.5));
        var source=terrain(3,3,heights,present(9));var lit=raster(source,style);double factor=0.6+0.4/Math.sqrt(6);
        int expected=((int)Math.round(200*factor)<<24)|((int)Math.round(100*factor)<<16)|((int)Math.round(50*factor)<<8)|128;
        for(int z=0;z<3;z++)for(int x=0;x<3;x++)equal(x==1&&z==1?expected:0xc8643280,lit.image().orElseThrow().rgba(x,z),"independent plane-light oracle");
        equal(1,lit.summary().orElseThrow().reliefSamples(),"one full stencil");near(10,lit.summary().orElseThrow().minimum().orElseThrow(),"native minimum");near(22,lit.summary().orElseThrow().maximum().orElseThrow(),"native maximum");
        var flags=present(9);flags[3]=0;var incomplete=raster(terrain(3,3,heights,flags),style);equal(0xc8643280,incomplete.image().orElseThrow().rgba(1,1),"missing neighbor keeps base color");equal(0,incomplete.summary().orElseThrow().reliefSamples(),"missing stencil excluded");
        var unlit=raster(source,new MapRaster.TerrainStyle(colors,new MapRaster.Relief(1,1,1,0,0)));check(Arrays.equals(raster(source,colors).image().orElseThrow().copyPixels(),unlit.image().orElseThrow().copyPixels()),"zero-amount flat equality");
    }
    /** Exact affine arithmetic over the supplied binary doubles, independent of production origin updates. */
    private static double zoomOracle(double origin,double anchor,double factor) {
        var f=new BigDecimal(factor);
        return f.multiply(new BigDecimal(origin)).add(BigDecimal.ONE.subtract(f).multiply(new BigDecimal(anchor))).doubleValue();
    }
    private static void navigationIdentity() {
        var original=new MapView(8,8,0.1,-0.3,1);
        for(double anchor:new double[]{0,0.5,2047.5,4095.5,1e12,Double.MAX_VALUE})check(original.zoomAt(1,anchor,-anchor)==original,"identity zoom preserves exact origin and transform identity");
        var signed=new MapView(2,2,-0.0,-0.0,1);check(signed.pan(0,-0.0)==signed,"zero pan preserves signed-zero origins");check(signed.zoomAt(1,0.25,0.5)==signed,"identity zoom preserves signed-zero origins");
        throwsType(IllegalArgumentException.class,()->original.zoomAt(1,Double.NaN,0),"identity zoom still validates its anchor");throwsType(IllegalArgumentException.class,()->original.zoomAt(1,0,Double.POSITIVE_INFINITY),"identity zoom rejects infinite anchor");throwsType(IllegalArgumentException.class,()->original.pan(Double.NaN,0),"nonfinite pan still rejected");
        for(double factor:new double[]{Math.nextDown(1.0),Math.nextUp(1.0),1-Math.scalb(1,-20),1+Math.scalb(1,-20),0.5,2,0.25,4,1e-6,1e6}) {
            var changed=original.zoomAt(factor,4095.5,2047.5);double x=zoomOracle(original.left(),4095.5,factor),y=zoomOracle(original.top(),2047.5,factor);
            check(Math.abs(changed.left()-x)<=2*Math.ulp(x),"zoom X stays within two ULPs of exact affine oracle");check(Math.abs(changed.top()-y)<=2*Math.ulp(y),"zoom Y stays within two ULPs of exact affine oracle");equal(original.pixelsPerSample()*factor,changed.pixelsPerSample(),"zoom scale unchanged");equal(original.width(),changed.width(),"zoom source width unchanged");equal(original.height(),changed.height(),"zoom source height unchanged");
        }
        throwsType(IllegalArgumentException.class,()->original.zoomAt(1e-7,0,0),"minimum zoom admission retained");throwsType(IllegalArgumentException.class,()->original.zoomAt(1e7,0,0),"maximum zoom admission retained");
    }
    private static void navigation() {
        navigationIdentity();
        var fitted=MapView.fit(4,2,10,20,100,100);near(25,fitted.pixelsPerSample(),"fit scale");near(45,fitted.top(),"letterbox top");
        check(fitted.pixelAt(10,44).isEmpty(),"letterbox excluded");check(fitted.pixelAt(fitted.right(),50).isEmpty(),"right edge excluded");check(fitted.pixelAt(50,fitted.bottom()).isEmpty(),"bottom edge excluded");
        var p=fitted.pixelAt(46,63).orElseThrow();equal(1,p.index(),"X-fastest hit");var zoomed=fitted.zoomAt(2,46,63);equal(p,zoomed.pixelAt(46,63).orElseThrow(),"zoom anchor preserves source pixel");
        equal(p,fitted.pan(-13,7).pixelAt(33,70).orElseThrow(),"pan hit invariance");check(fitted.pixelAt(Double.NaN,50).isEmpty(),"nonfinite pointer excluded");
        var result=raster(terrain(2,1,new int[]{0,0},new byte[]{1,0}),gray(0,1));var view=new MapView(2,1,0,0,1);
        var zero=MapPick.at(result,view,0.5,0.5).orElseThrow();check(zero.present()&&zero.terrainHeight().orElseThrow()==0,"real zero inspection");
        var absent=MapPick.at(result,view,1.5,0.5).orElseThrow();check(!absent.present()&&absent.terrainHeight().isEmpty(),"absence inspection distinct from zero");near(-15,absent.worldX(),"fractional-world inspection coordinate");
    }
    private static void navigationBoundaries() {
        var view=new MapView(2,2,-1,-1,1);double below=Math.nextDown(0.0),above=Math.nextUp(0.0),inside=Math.nextDown(1.0);
        equal(new MapView.Pixel(0,0,0),view.pixelAt(below,below).orElseThrow(),"subnormal point stays below both internal boundaries");equal(new MapView.Pixel(1,1,3),view.pixelAt(0,0).orElseThrow(),"exact shared edge selects following samples");equal(new MapView.Pixel(1,1,3),view.pixelAt(above,above).orElseThrow(),"point above shared edges selects following samples");equal(new MapView.Pixel(1,1,3),view.pixelAt(inside,inside).orElseThrow(),"interior upper corner cannot be rounded outside");
        equal(new MapView.Pixel(0,0,0),view.pixelAt(-1,-1).orElseThrow(),"lower boundaries remain included");check(view.pixelAt(1,inside).isEmpty()&&view.pixelAt(inside,1).isEmpty(),"upper boundaries remain excluded");check(view.pixelAt(Math.nextDown(-1.0),0).isEmpty()&&view.pixelAt(0,Math.nextDown(-1.0)).isEmpty(),"points below lower boundaries stay outside");
        var positive=new MapView(4,4,-1,-1,1);equal(new MapView.Pixel(1,1,5),positive.pixelAt(inside,inside).orElseThrow(),"positive viewport point below internal edges keeps preceding samples");equal(new MapView.Pixel(2,2,10),positive.pixelAt(1,1).orElseThrow(),"positive exact internal edges select following samples");
        for(double scale:new double[]{1e-6,0.5,1,1e6}) {
            var zoom=new MapView(2,1,-scale,0,scale);equal(0,zoom.pixelAt(below,scale/2).orElseThrow().x(),"rounded inverse stays below internal edge across zoom bounds");equal(1,zoom.pixelAt(0,scale/2).orElseThrow().x(),"exact internal edge keeps upper sample across zoom bounds");equal(1,zoom.pixelAt(Math.nextDown(zoom.right()),scale/2).orElseThrow().x(),"interior upper edge stays inspectable across zoom bounds");
        }
        var decimal=new MapView(8,1,0,0,0.1);double edge=5*decimal.pixelsPerSample();equal(4,decimal.pixelAt(Math.nextDown(edge),0.05).orElseThrow().x(),"fractional zoom point below displayed edge selects preceding sample");equal(5,decimal.pixelAt(edge,0.05).orElseThrow().x(),"fractional zoom exact displayed edge selects following sample");
        var distant=new MapView(512,1,1e12,0,1e-6);equal(0,distant.pixelAt(distant.left(),0).orElseThrow().x(),"coarse screen precision keeps first-sample inverse behavior");equal(122,distant.pixelAt(Math.nextUp(distant.left()),0).orElseThrow().x(),"collapsed display edges retain inverse lookup rather than arbitrary edge selection");
        var raster=raster(terrain(2,2,new int[]{0,0,13,42},new byte[]{1,0,1,1}),gray(-64,320));var original=MapPick.at(raster,view,below,below).orElseThrow();check(original.present()&&original.terrainHeight().orElseThrow()==0,"boundary hit preserves original present zero");equal(new MapView.Pixel(0,0,0),original.pixel(),"map query uses corrected original sample");var absent=MapPick.at(raster,view,0,below).orElseThrow();check(!absent.present()&&absent.terrainHeight().isEmpty(),"boundary hit preserves original absence");var last=MapPick.at(raster,view,inside,inside).orElseThrow();equal(42,last.terrainHeight().orElseThrow(),"upper interior map sample retains original height");near(-15,last.worldX(),"upper interior map sample retains native X");near(25,last.worldZ(),"upper interior map sample retains native Z");
        var source=SourceImage.decode(SourceImage.Encoded.capture(new SourceImage.Identity("fixtures/boundary.png",8),pngFixture()),LIVE);var imageView=new MapView(2,1,-1,0,1);var first=source.hit(imageView,below,0.5).orElseThrow();equal(0,first.x(),"original image keeps pixel below boundary");equal(0xff000080,first.rgba(),"original image keeps straight source RGBA");var transparent=source.hit(imageView,inside,0.5).orElseThrow();equal(1,transparent.x(),"original image upper interior pixel remains inspectable");equal(0x00ff0000,transparent.rgba(),"transparent source RGB remains unchanged");check(source.hit(imageView,imageView.right(),0.5).isEmpty(),"original image still excludes outer upper boundary");
        var zoomedImage=new MapView(2,1,-1,0,2);equal(0xff000080,source.hit(zoomedImage,inside,0.5).orElseThrow().rgba(),"positive viewport point below zoomed image boundary keeps original first pixel");equal(0x00ff0000,source.hit(zoomedImage,1,0.5).orElseThrow().rgba(),"exact zoomed image boundary keeps transparent following pixel");
    }
    private static NoiseVolume volume(int width,int height,int depth,double[] values,byte[] flags) {
        return NoiseVolume.capture(context(),new NoiseVolume.Grid(new MapSamples.Grid(-4,8,width,depth,0.5),new MapSamples.Vertical(-2,height)),values,flags,NoiseVolume.Limits.DEFAULT,LIVE);
    }
    private static void scalarCapture() {
        var values=new double[]{-1,0,1,2,3,4,5,6};var flags=present(8);flags[3]=0;var source=volume(2,2,2,values,flags);check(!source.requiresPlaceholder(),"full-height capture");values[0]=99;flags[0]=0;
        near(-1,source.value(0),"scalar values detached");check(source.present(0),"scalar presence detached");equal(72L,source.payloadBytes(),"full-height source payload");
        var g=source.grid();equal(7,g.index(1,1,1),"X-Z-Y ordering");equal(new NoiseVolume.Point(-3.5,-1,8.5),g.point(7),"fractional native coordinates");
        equal(7,source.summary().orElseThrow().present(),"full height summary");throwsType(IllegalStateException.class,()->source.value(3),"absent scalar not zero");
        var limited=NoiseVolume.capture(context(),g,new double[8],present(8),new NoiseVolume.Limits(2,1,8,72),LIVE);check(limited.requiresPlaceholder()&&limited.size()==0,"height limit never truncates Y");
    }
    private record UnitFace(int cell,ModelPlan.Direction direction) {}
    private static Set<UnitFace> exposed(int width,int height,int depth,double[] values,byte[] flags) {
        var result=new HashSet<UnitFace>();
        for(int y=0;y<height;y++)for(int z=0;z<depth;z++)for(int x=0;x<width;x++) {
            int cell=(y*depth+z)*width+x;if(flags[cell]==0||values[cell]<1||values[cell]>2)continue;
            for(var direction:ModelPlan.Direction.values()) {
                int nx=x,ny=y,nz=z;switch(direction) { case DOWN->ny--;case UP->ny++;case NORTH->nz--;case SOUTH->nz++;case WEST->nx--;case EAST->nx++; }
                boolean outside=nx<0||nx>=width||ny<0||ny>=height||nz<0||nz>=depth;
                if(outside)result.add(new UnitFace(cell,direction));else { int neighbor=(ny*depth+nz)*width+nx;if(flags[neighbor]==0||values[neighbor]<1||values[neighbor]>2)result.add(new UnitFace(cell,direction)); }
            }
        }
        return result;
    }
    private static Set<UnitFace> expanded(NoiseSurface surface) {
        var result=new HashSet<UnitFace>();for(int face=0;face<surface.size();face++)for(int v=0;v<surface.spanV(face);v++)for(int u=0;u<surface.spanU(face);u++) {
            int cell=surface.sample(face,u,v);check(result.add(new UnitFace(cell,surface.direction(face))),"no duplicate surface coverage");
            var original=surface.face(face,u,v);equal(cell,original.sample(),"rectangle sample identity");near(surface.volume().value(cell),original.value(),"original scalar behind rectangle");
            int channel=(int)Math.round(original.value()*85);equal((channel<<24)|(channel<<16)|(channel<<8)|255,surface.rgba(face),"independent prepared color oracle");
        }return result;
    }
    private static void surfaceCoverage() {
        var random=new Random(0x49524953L);
        for(int fixture=0;fixture<18;fixture++) {
            int width=1+fixture%4,height=1+fixture%3,depth=1+fixture%5,count=width*height*depth;double[] values=new double[count];byte[] flags=present(count);
            for(int at=0;at<count;at++) { values[at]=fixture==0?1:random.nextInt(4);if(fixture%3==1&&random.nextInt(5)==0)flags[at]=0; }
            var source=volume(width,height,depth,values,flags);var oracle=exposed(width,height,depth,values,flags);
            for(var mode:NoiseSurface.Meshing.values()) { var surface=NoiseSurface.prepare(source,new NoiseSurface.Style(1,2,gray(0,3),mode),NoiseSurface.Limits.DEFAULT,LIVE);check(!surface.requiresPlaceholder(),"surface fixture complete");equal(oracle,expanded(surface),"independent exposed-cell coverage "+fixture+" "+mode);equal((long)oracle.size(),surface.usage().orElseThrow().unitFaces(),"original unit coverage receipt"); }
        }
        var dense=volume(3,2,4,new double[24],present(24));var merged=NoiseSurface.prepare(dense,new NoiseSurface.Style(0,0,gray(0,1)),NoiseSurface.Limits.DEFAULT,LIVE);equal(6,merged.size(),"uniform cuboid has six greedy rectangles");
    }
    private static NoiseSurface cube() { return NoiseSurface.prepare(volume(1,1,1,new double[]{1},present(1)),new NoiseSurface.Style(1,1,gray(0,3)),NoiseSurface.Limits.DEFAULT,LIVE); }
    private static void scalarRecolor() {
        var source=cube();check(!source.requiresPlaceholder(),"cube fixture");
        var same=NoiseSurface.recolor(source,gray(0,3),NoiseSurface.Limits.DEFAULT,LIVE);check(same.volume()==source.volume(),"equal scalar style retains exact volume");equal(source.payloadBytes(),NoiseSurface.recolorReservation(source,gray(0,3)),"equal scalar source-only peak");equal(0L,same.usage().orElseThrow().scratchBytes(),"equal scalar style needs no replacement colors");
        var colors=new MapRaster.ScalarStyle(0,3,constant(0x112233ff));equal(source.payloadBytes()+24,NoiseSurface.recolorReservation(source,colors),"six replacement colors plus old geometry");
        var changed=NoiseSurface.recolor(source,colors,NoiseSurface.Limits.DEFAULT,LIVE);check(!changed.requiresPlaceholder()&&changed.volume()==source.volume(),"recolor exact volume");
        for(int face=0;face<6;face++) { equal(source.cell(face),changed.cell(face),"recolor geometry");equal(source.spanWord(face),changed.spanWord(face),"recolor spans");equal(0x112233ff,changed.rgba(face),"prepared scalar color");equal(0x555555ff,source.rgba(face),"old scalar color immutable"); }
        var failed=NoiseSurface.recolor(source,colors,new NoiseSurface.Limits(6,NoiseSurface.recolorReservation(source,colors)-1),LIVE);check(failed.requiresPlaceholder()&&failed.size()==0,"scalar peak failure atomic");
    }
    private static NoiseSurface pickingSurface(int width,int depth,double scale,NoiseSurface.Meshing meshing) {
        var grid=new NoiseVolume.Grid(new MapSamples.Grid(0,0,width,depth,scale),new MapSamples.Vertical(0,1));var values=new double[width*depth];Arrays.fill(values,1);
        var volume=NoiseVolume.capture(context(),grid,values,present(values.length),NoiseVolume.Limits.DEFAULT,LIVE);
        var surface=NoiseSurface.prepare(volume,new NoiseSurface.Style(1,1,gray(0,2),meshing),NoiseSurface.Limits.DEFAULT,LIVE);check(!surface.requiresPlaceholder(),"complete picking-plane fixture");return surface;
    }
    private static void scalarPickingPlanes() {
        var anchor=new WorldSnapshot.Position(0,0,0);
        for(var meshing:NoiseSurface.Meshing.values()) {
            var source=pickingSurface(1,1,1,meshing);
            for(int side=0;side<6;side++)for(double distance:new double[]{0.1,0.3,1.1,7.7,31.9,128.125,1_000_000.1,1_048_574.75}) {
                int axis=side<2?1:side<4?2:0;boolean plus=(side&1)!=0;double plane=plus?1:0;
                double[] origin={0.25,0.25,0.25},point={0.5,0.5,0.5};origin[axis]=plus?1+distance:-distance;point[axis]=plane;
                double dx=point[0]-origin[0],dy=point[1]-origin[1],dz=point[2]-origin[2];
                var clip=new WorldClip(new WorldSnapshot.Extent(axis==0?(plus?1:-1):0,axis==1?(plus?1:-1):0,axis==2?(plus?1:-1):0,1,1,1));var options=new NoisePick.Options(6,clip);
                var ray=new WorldRay(anchor,origin[0],origin[1],origin[2],dx,dy,dz,0,2_097_152);var result=NoisePick.query(source,ray,options,LIVE);check(!result.requiresPlaceholder(),"closed-plane query complete");var hit=result.hit().orElseThrow();check(hit.grid().orElseThrow()==source.volume().grid(),"detached hit keeps exact grid metadata without source arrays");
                equal(ModelPlan.Direction.values()[side],hit.sample().direction(),"closed clip retains original face direction");equal(0,hit.sample().sample(),"closed clip retains original sample");near(1,hit.sample().value(),"original scalar unchanged");near(Math.hypot(Math.hypot(dx,dy),dz),hit.distance(),"analytic plane distance");
                double[] local={hit.localX(),hit.localY(),hit.localZ()};equal(plane,local[axis],"normal coordinate is the exact accepted plane");for(int tangent=0;tangent<3;tangent++)if(tangent!=axis)near(0.5,local[tangent],"tangent hit coordinate");
                var endpoint=new WorldRay(anchor,origin[0],origin[1],origin[2],dx,dy,dz,hit.distance(),hit.distance());check(NoisePick.query(source,endpoint,options,LIVE).hit().isPresent(),"closed ray near/far endpoint retained");
                check(NoisePick.query(source,new WorldRay(anchor,origin[0],origin[1],origin[2],dx,dy,dz,0,Math.nextDown(hit.distance())),options,LIVE).hit().isEmpty(),"strict far exclusion unchanged");
                check(NoisePick.query(source,new WorldRay(anchor,origin[0],origin[1],origin[2],dx,dy,dz,Math.nextUp(hit.distance()),2_097_152),options,LIVE).hit().isEmpty(),"strict near exclusion unchanged");
            }
            var decimal=pickingSurface(1,1,0.1,meshing);var hit=NoisePick.query(decimal,new WorldRay(anchor,1_000_000.1,0.5,0.05,-1,0,0,0,2_097_152),6,LIVE).hit().orElseThrow();
            equal(ModelPlan.Direction.EAST,hit.sample().direction(),"decimal-scale positive face");equal(0.1,hit.localX(),"decimal plane survives far-origin cancellation in detached receipt");near(1_000_000,hit.distance(),"decimal plane distance");
            var limited=NoisePick.query(decimal,new WorldRay(anchor,2,0.5,0.05,-1,0,0,0,10),5,LIVE);check(limited.requiresPlaceholder()&&limited.hit().isEmpty(),"plane handling preserves atomic face allowance");
            var merged=pickingSurface(2,2,0.1,meshing);
            for(double z:new double[]{0.1,0.15}) {
                var selected=NoisePick.query(merged,new WorldRay(anchor,1_000_000.2,0.5,z,-1,0,0,0,2_097_152),merged.size(),LIVE).hit().orElseThrow();
                equal(z==0.1?1:3,selected.sample().sample(),"rectangle hit maps to original sample, including lower shared-edge tie");equal(0.1,selected.localX(),"accepted rectangle plane stays sample-local");near(z==0.1?0.1:0.05,selected.localZ(),"original sample-local tangent coordinate");equal(ModelPlan.Direction.EAST,selected.sample().direction(),"rectangle direction unchanged");
            }
        }
    }
    private static void scalarPicking() {
        scalarPickingPlanes();
        var source=cube();var g=source.volume().grid();var anchor=new WorldSnapshot.Position(g.horizontal().originX(),g.vertical().minY(),g.horizontal().originZ());
        var ray=new WorldRay(anchor,0.25,0.5,2,0,0,-1,0,10);var result=NoisePick.query(source,ray,6,LIVE);check(!result.requiresPlaceholder(),"pick complete");var hit=result.hit().orElseThrow();
        near(1.5,hit.distance(),"front plane intersection");equal(ModelPlan.Direction.SOUTH,hit.sample().direction(),"front direction");equal(0,hit.sample().sample(),"original sample");near(1,hit.sample().value(),"native scalar");
        var limited=NoisePick.query(source,ray,5,LIVE);check(limited.requiresPlaceholder()&&limited.hit().isEmpty(),"budget never returns partial nearest");
        var clipped=NoisePick.query(source,ray,new NoisePick.Options(6,new WorldClip(new WorldSnapshot.Extent(100,0,100,1,1,1))),LIVE);check(!clipped.requiresPlaceholder()&&clipped.hit().isEmpty(),"clip excludes complete source");
        var inside=NoisePick.query(source,new WorldRay(anchor,0.25,0.5,0.25,0,0,1,0,10),6,LIVE);check(inside.hit().isEmpty(),"back faces excluded from inside");
    }
    private static void imageBuilder() {
        var builder=RgbaImage.builder(1,2,8);throwsType(IllegalStateException.class,builder::finish,"partial builder cannot publish");
        var source=ByteBuffer.wrap(new byte[]{99,1,2,3,4,5,6,7,8,99});source.position(1);source.limit(9);builder.appendRows(source,2,true);equal(1,source.position(),"source cursor unchanged");
        var image=builder.finish();equal(0x05060708,image.rgba(0,0),"bottom-to-top copy");equal(0x01020304,image.rgba(0,1),"second row");
        throwsType(ReadOnlyBufferException.class,()->image.pixels().put(0,(byte)0),"immutable pixel view");var copied=image.copyPixels();copied[0]=0;equal(0x05060708,image.rgba(0,0),"defensive pixel copy");
        throwsType(IllegalStateException.class,builder::finish,"single finish");throwsType(IllegalStateException.class,()->builder.appendRows(ByteBuffer.allocate(4),1,false),"sealed append");
    }
    private static void pngChunk(DataOutputStream output,String type,byte[] payload) throws IOException {
        byte[] name=type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);var crc=new CRC32();crc.update(name);crc.update(payload);
        output.writeInt(payload.length);output.write(name);output.write(payload);output.writeInt((int)crc.getValue());
    }
    /** Tiny standard RGBA PNG fixture; no production encoder/decoder helps build the input. */
    private static byte[] pngFixture() {
        try {
            var bytes=new ByteArrayOutputStream();var output=new DataOutputStream(bytes);output.writeLong(0x89504e470d0a1a0aL);
            var headerBytes=new ByteArrayOutputStream();var header=new DataOutputStream(headerBytes);header.writeInt(2);header.writeInt(1);header.write(new byte[]{8,6,0,0,0});pngChunk(output,"IHDR",headerBytes.toByteArray());
            var compressed=new ByteArrayOutputStream();try(var deflate=new DeflaterOutputStream(compressed)) { deflate.write(new byte[]{0,(byte)255,0,0,(byte)128,0,(byte)255,0,0}); }
            pngChunk(output,"IDAT",compressed.toByteArray());pngChunk(output,"IEND",new byte[0]);return bytes.toByteArray();
        }catch(IOException failure) { throw new AssertionError("in-memory fixture encoding",failure); }
    }
    private static void originalImage() {
        var identity=new SourceImage.Identity("fixtures/original.png",7);byte[] bytes=pngFixture();var encoded=SourceImage.Encoded.capture(identity,bytes);bytes[0]=0;
        var image=SourceImage.decode(encoded,LIVE);check(!image.requiresPlaceholder(),"standard PNG fixture decodes");equal(0xff000080,image.image().orElseThrow().rgba(0,0),"straight source RGBA");equal(0x00ff0000,image.image().orElseThrow().rgba(1,0),"transparent source RGB preserved");
        var receipt=image.receipt().orElseThrow();equal(identity,receipt.source(),"original resource revision");equal(SourceImage.Format.PNG,receipt.format(),"signature codec");equal(2,receipt.width(),"stored source width");equal(1,receipt.height(),"stored source height");equal(encoded.bytes(),receipt.encodedBytes(),"encoded length");
        equal(receipt,SourceImage.decode(encoded,LIVE).receipt().orElseThrow(),"independent identical-content receipt");
        var hit=image.hit(new MapView(2,1,10,20,4),16,22).orElseThrow();equal(1,hit.x(),"original source pixel");equal(0x00ff0000,hit.rgba(),"transparent original remains inspectable");
        var limited=SourceImage.decode(encoded,new SourceImage.Limits(1,1,1024,4,1024*1024),LIVE);check(limited.requiresPlaceholder()&&limited.image().isEmpty()&&limited.receipt().isEmpty(),"oversize source never resized or partially published");equal(SourceImage.Code.LIMIT,limited.problems().getFirst().code(),"image limit diagnostic");
        var corrupt=pngFixture();corrupt[corrupt.length-1]^=1;var failed=SourceImage.decode(SourceImage.Encoded.capture(identity,corrupt),LIVE);check(failed.requiresPlaceholder()&&failed.image().isEmpty()&&failed.receipt().isEmpty(),"bad PNG CRC withholds pixels and receipt");
        throwsType(CancellationException.class,()->SourceImage.decode(encoded,()->true),"decode cancellation separate");
    }
    /** Test-only faults cover live registration rollback and replacement's prospective registry copy. */
    private static final class OwnersFault implements AutoCloseable {
        final RenderMemory memory;final java.lang.reflect.Field field;final Set<RenderMemory.Account> original,installed;final Error primary,rollback;final boolean readFailure;boolean readFired;int removals;
        OwnersFault(RenderMemory memory,Error primary,Error rollback) { this(memory,primary,rollback,false); }
        @SuppressWarnings("unchecked") OwnersFault(RenderMemory memory,Error primary,Error rollback,boolean readFailure) {
            this.memory=memory;this.primary=primary;this.rollback=rollback;this.readFailure=readFailure;
            try {
                field=RenderMemory.class.getDeclaredField("owners");field.setAccessible(true);original=(Set<RenderMemory.Account>)field.get(memory);
                installed=new AbstractSet<RenderMemory.Account>() {
                    @Override public int size() { return original.size(); }
                    @Override public Iterator<RenderMemory.Account> iterator() {
                        var iterator=original.iterator();if(!OwnersFault.this.readFailure)return iterator;
                        return new Iterator<>() {
                            @Override public boolean hasNext() { return iterator.hasNext(); }
                            @Override public RenderMemory.Account next() { var value=iterator.next();if(!readFired) { readFired=true;throw OwnersFault.this.primary; }return value; }
                        };
                    }
                    @Override public boolean add(RenderMemory.Account value) { boolean added=original.add(value);if(OwnersFault.this.primary!=null)throw OwnersFault.this.primary;return added; }
                    @Override public boolean remove(Object value) { removals++;boolean removed=original.remove(value);if(OwnersFault.this.rollback!=null)throw OwnersFault.this.rollback;return removed; }
                };field.set(memory,installed);
            }catch(ReflectiveOperationException failure) { throw new AssertionError("controlled account registration fault unavailable",failure); }
        }
        @Override public void close() {
            try { if(field.get(memory)==installed)field.set(memory,original); }catch(ReflectiveOperationException failure) { throw new AssertionError("account registration fault restore failed",failure); }
        }
    }
    private static void accountRegistrationFailures() {
        for(int kind=0;kind<3;kind++)for(boolean oom:new boolean[]{false,true})for(boolean cleanupError:new boolean[]{false,true}) {
            try(var memory=new RenderMemory(new RenderMemory.Limits(100,kind==2?1:2));var old=kind==2?memory.hold("old",80):RenderMemory.Hold.none()) {
                var before=memory.stats();Error primary=oom?new OutOfMemoryError("injected account registration OOM"):new AssertionError("injected account registration error");Error rollback=cleanupError?new AssertionError("injected post-remove rollback error"):null;
                try(var fault=new OwnersFault(memory,primary,rollback,kind==2)) {
                    Throwable actual=null;
                    try { switch(kind) { case 0->memory.open("new account");case 1->memory.hold("new hold",10);default->memory.holdReplacing("replacement",90,old); } }catch(RuntimeException|Error failure) { actual=failure; }
                    check(actual==primary,"account preparation preserves exact original Error");equal(cleanupError&&kind!=2?1:0,primary.getSuppressed().length,"account rollback suppression count");if(cleanupError&&kind!=2)check(primary.getSuppressed()[0]==rollback,"rollback error suppressed onto registration error");equal(before,memory.stats(),"preparation failure preserves exact old admission and removes partial account");if(kind==2) { equal(80L,old.bytes(),"failed registry copy consumes no replaced owner credit");check(fault.readFired&&fault.removals==0,"failed registry copy never mutates live owners"); }
                }
                if(kind==2)try(var retry=memory.holdReplacing("retry",90,old)) { equal(0L,old.bytes(),"successful retry consumes old owner once");equal(90L,retry.bytes(),"successful retry retains exact replacement charge");equal(1,memory.stats().accounts(),"successful retry remains within one-account limit"); }
                else try(var retry=memory.hold("retry",10)) { equal(10L,retry.bytes(),"failed registration leaves account slot reusable"); }
                equal(0L,memory.stats().chargedBytes(),"registration/retry leaves no final charge");equal(0,memory.stats().accounts(),"registration/retry leaves no final account");
            }
        }
        try(var memory=new RenderMemory(new RenderMemory.Limits(100,1))) {
            var rollback=new AssertionError("injected failed-hold account close error");
            try(var fault=new OwnersFault(memory,null,rollback)) {
                Throwable actual=null;try { memory.hold("oversized",101); }catch(RuntimeException|Error failure) { actual=failure; }
                check(actual instanceof RenderMemory.LimitException,"failed hold preserves original payload-limit rejection");check(actual.getSuppressed().length==1&&actual.getSuppressed()[0]==rollback,"account-close error suppressed onto payload-limit rejection");equal(0L,memory.stats().chargedBytes(),"failed oversized hold releases charge");equal(0,memory.stats().accounts(),"failed oversized hold removes empty account");
            }
            try(var retry=memory.hold("retry",10)) { equal(10L,retry.bytes(),"account close failure leaves slot reusable after rollback"); }
            equal(0,memory.stats().accounts(),"failed-hold retry leaves no account");
        }
    }
    private static void memoryOwnership() {
        accountRegistrationFailures();
        coordinatorCloseFailures();
        try(var memory=new RenderMemory(new RenderMemory.Limits(100,4))) {
            var owner=memory.hold("source",60);var input=RenderInput.own("immutable",owner);var retained=input.retain();input.close();
            equal(60L,memory.stats().chargedBytes(),"retained input remains charged");equal("immutable",retained.value(),"independent input live");
            throwsType(RenderMemory.LimitException.class,()->memory.hold("too large",41),"overlap admission");equal(1,memory.stats().accounts(),"no leaked failed account");
            retained.admission().shrinkTo(20);equal(20L,memory.stats().chargedBytes(),"scratch shrinks");memory.close();
            throwsType(IllegalStateException.class,()->memory.hold("closed",1),"closed coordinator rejects growth");equal(20L,memory.stats().chargedBytes(),"close preserves live charge");retained.close();retained.close();equal(0L,memory.stats().chargedBytes(),"last input release");equal(0,memory.stats().accounts(),"all accounts released");
        }
    }
    private static void coordinatorCloseFailures() {
        for(boolean oom:new boolean[]{false,true})try(var memory=new RenderMemory(new RenderMemory.Limits(100,4));var empty=memory.open("empty");var zero=memory.hold("zero hold",0);var held=memory.hold("held",30);var shared=held.retain();var cache=memory.open("cache")) {
            cache.set(20,40);var before=memory.stats();var owners=memory.owners();Error primary=oom?new OutOfMemoryError("injected close registry copy OOM"):new AssertionError("injected close registry copy error");var removal=new AssertionError("coordinator close must not mutate the old registry");
            try(var fault=new OwnersFault(memory,primary,removal,true)) {
                Throwable actual=null;try { memory.close(); }catch(RuntimeException|Error failure) { actual=failure; }
                check(actual==primary&&primary.getSuppressed().length==0,"failed coordinator close preserves original preparation error");check(fault.readFired&&fault.removals==0,"close preparation never removes live owners");equal(before,memory.stats(),"failed close preserves gate and exact admission");equal(owners,memory.owners(),"failed close does not retire or release any owner");
                cache.reserve(41);equal(71L,memory.stats().chargedBytes(),"failed preparation leaves ordinary account growth open");cache.reserve(40);equal(before,memory.stats(),"growth probe restores exact pre-close state");equal(30L,held.bytes(),"failed close preserves source hold");equal(30L,shared.bytes(),"failed close preserves independently retained owner");
                memory.close();equal(0,fault.removals,"successful close commits a private registry without old-set cleanup callbacks");
            }
            var closed=memory.stats();check(closed.closed(),"successful retry seals coordinator");equal(70L,closed.chargedBytes(),"close preserves every live byte");equal(20L,closed.retainedBytes(),"close preserves retained cache payload");equal(70L,closed.reservedBytes(),"close preserves covering reservations");equal(2,closed.accounts(),"close removes ordinary and tracked zero-byte accounts");check(empty.stats().retiring()&&memory.owners().stream().allMatch(RenderMemory.Owner::retiring),"all surviving and empty owners are retiring");
            memory.close();equal(closed,memory.stats(),"successful coordinator close is idempotent");throwsType(IllegalStateException.class,()->memory.open("closed"),"closed coordinator rejects new accounts");throwsType(IllegalStateException.class,()->cache.reserve(41),"closed coordinator rejects existing-account growth");throwsType(IllegalStateException.class,()->empty.reserve(1),"released empty account cannot be revived");
            try(var retainedZero=zero.retain();var retained=shared.retain()) {
                equal(0L,retainedZero.bytes(),"released zero-byte account retains uncharged compatibility lifetime");cache.set(35,40);equal(70L,memory.stats().chargedBytes(),"retiring owner permits retained moves within its covering reservation");cache.set(0,0);equal(30L,memory.stats().chargedBytes(),"cache release removes only its own charge");equal(1,memory.stats().accounts(),"cache release updates the committed remaining-owner registry");held.close();shared.close();equal(30L,retained.bytes(),"independent retained hold survives coordinator and original owners closing");equal(30L,memory.stats().chargedBytes(),"last retained owner stays charged");
            }
            equal(0L,memory.stats().chargedBytes(),"final retained release clears closed coordinator charge");equal(0,memory.stats().accounts(),"final retained release clears every account");zero.close();memory.close();equal(0,memory.stats().accounts(),"zero hold and repeated close cannot restore retired accounts");
        }
        try(var memory=new RenderMemory(new RenderMemory.Limits(0,1))) { memory.close();memory.close();check(memory.stats().closed()&&memory.stats().accounts()==0,"empty zero-byte coordinator closes idempotently"); }
    }
    private static void replacements() {
        mixedReplacements();
        try(var memory=new RenderMemory(new RenderMemory.Limits(100,1));var foreign=new RenderMemory(new RenderMemory.Limits(100,1))) {
            var old=memory.hold("old",80);var retained=old.retain();
            throwsType(RenderMemory.LimitException.class,()->memory.holdReplacing("active overlap",90,old),"shared owner prevents replacement credit");equal(80L,old.bytes(),"rejection preserves owner");
            retained.close();var next=memory.holdReplacing("replacement",90,old,old);equal(0L,old.bytes(),"duplicate replaced handle consumed once");equal(90L,next.bytes(),"replacement charge");equal(1,memory.stats().accounts(),"replacement reuses bounded slot");
            var other=foreign.hold("foreign",1);throwsType(IllegalArgumentException.class,()->memory.holdReplacing("bad",1,next,other),"foreign replacement");equal(90L,next.bytes(),"foreign rejection atomic");other.close();next.close();equal(0,memory.stats().accounts(),"replacement final release");
        }
    }
    private static void mixedReplacements() {
        try(var memory=new RenderMemory(new RenderMemory.Limits(200,4));var first=memory.hold("first",20);var firstTwin=first.retain();var second=memory.hold("second",30);var partial=memory.hold("partial",40);var external=partial.retain()) {
            var before=memory.stats();var primary=new AssertionError("injected mixed replacement copy error");var rollback=new AssertionError("replacement must not remove live registry entries");
            try(var fault=new OwnersFault(memory,primary,rollback,true)) {
                Throwable actual=null;try { memory.holdReplacing("failed mixed",100,first,firstTwin,second,partial,first,RenderMemory.Hold.none()); }catch(RuntimeException|Error failure) { actual=failure; }
                check(actual==primary&&primary.getSuppressed().length==0,"mixed replacement preserves copy error without cleanup callbacks");check(fault.readFired&&fault.removals==0,"mixed copy failure never removes live accounts");equal(before,memory.stats(),"mixed copy failure preserves exact accounting");
                equal(20L,first.bytes(),"first handle remains live");equal(20L,firstTwin.bytes(),"fully supplied retained handle remains live");equal(30L,second.bytes(),"second exclusive handle remains live");equal(40L,partial.bytes(),"supplied shared handle remains live");equal(40L,external.bytes(),"external shared handle remains live");
            }
            RenderMemory.Hold next;
            try(var fault=new OwnersFault(memory,null,rollback)) {
                next=memory.holdReplacing("mixed",100,first,firstTwin,second,partial,first,RenderMemory.Hold.none());equal(0,fault.removals,"successful replacement uses no live-registry cleanup callbacks");
            }
            try(next) {
                equal(0L,first.bytes(),"first handle consumed");equal(0L,firstTwin.bytes(),"fully supplied retained handle consumed");equal(0L,second.bytes(),"second exclusive handle consumed");equal(0L,partial.bytes(),"supplied shared handle consumed once");equal(40L,external.bytes(),"external shared owner retains its charge");equal(100L,next.bytes(),"mixed replacement owns requested bytes");equal(140L,memory.stats().chargedBytes(),"only fully supplied groups supply replacement credit");equal(2,memory.stats().accounts(),"shared account survives registry swap");
            }
            equal(40L,memory.stats().chargedBytes(),"replacement close preserves external owner");external.close();equal(0L,memory.stats().chargedBytes(),"last external release clears shared charge");equal(0,memory.stats().accounts(),"mixed replacement releases every account");
        }
    }
    private static void completionInputs() {
        var source=raster(terrain(1,1,new int[]{0},present(1)),gray(0,1));try(var memory=new RenderMemory(new RenderMemory.Limits(100,2))) {
            var completion=new MapRasterWorker.Completion(1,source,memory.hold("completed map",source.payloadBytes()));var input=completion.retainInput();completion.close();
            check(input.value()==source,"completion shares exact raster");equal(source.payloadBytes(),memory.stats().chargedBytes(),"completion close preserves independent input");input.close();equal(0L,memory.stats().chargedBytes(),"completion input released");
        }
        try(var memory=new RenderMemory(new RenderMemory.Limits(100,2))) {
            var image=SourceImage.decode(SourceImage.Encoded.capture(new SourceImage.Identity("synthetic.png",1),pngFixture()),LIVE);check(!image.requiresPlaceholder(),"completion PNG fixture");
            var completion=new ImageDecodeWorker.Completion(1,image,memory.hold("completed pixels",image.payloadBytes()));var input=completion.retainInput();completion.close();check(input.value()==image,"image completion exact value");equal(8L,memory.stats().chargedBytes(),"retained decoded pixels charged");input.close();equal(0,memory.stats().accounts(),"image completion slot released");
        }
    }
    public static void main(String[] arguments) {
        if(arguments.length!=0)throw new IllegalArgumentException("No arguments supported");
        group("exact extents and clipping",RendererCpuContracts::extents);
        group("detached map capture",RendererCpuContracts::mapCapture);
        group("atomic map failure and cancellation",RendererCpuContracts::mapFailure);
        group("gradient equality and RGBA arithmetic",RendererCpuContracts::ramps);
        group("scalar range pixels",RendererCpuContracts::scalarPixels);
        group("detached scalar histograms and legend boundaries",RendererCpuContracts::scalarHistograms);
        group("retained map recoloring",RendererCpuContracts::recolor);
        group("native biome identity and alpha",RendererCpuContracts::categorical);
        group("independent terrain relief oracle",RendererCpuContracts::relief);
        group("map navigation and original-value hits",RendererCpuContracts::navigation);
        group("half-open map and image boundary precision",RendererCpuContracts::navigationBoundaries);
        group("full-height scalar capture",RendererCpuContracts::scalarCapture);
        group("independent exposed-face oracle",RendererCpuContracts::surfaceCoverage);
        group("geometry-sharing scalar recoloring",RendererCpuContracts::scalarRecolor);
        group("scalar front-face picking",RendererCpuContracts::scalarPicking);
        group("atomic immutable RGBA builder",RendererCpuContracts::imageBuilder);
        group("original PNG pixels and receipts",RendererCpuContracts::originalImage);
        group("independent CPU admission lifetime",RendererCpuContracts::memoryOwnership);
        group("atomic shared hold replacement",RendererCpuContracts::replacements);
        group("retained completion inputs",RendererCpuContracts::completionInputs);
        System.out.println("PASS "+groups+" synthetic CPU contract groups; native/GPU parity is outside this harness.");
    }
}
