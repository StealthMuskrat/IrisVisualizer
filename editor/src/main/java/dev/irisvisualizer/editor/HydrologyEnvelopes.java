package dev.irisvisualizer.editor;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Read-only reproduction of the pinned pack validator's deep-fluid footprint-margin checks. */
public final class HydrologyEnvelopes {
    public enum Kind { ERROR, UNAVAILABLE, NOTE }
    public record Finding(Kind kind,String path,String detail) {}
    public record Report(int checked,List<Finding> findings) { public Report { findings=List.copyOf(findings); } }
    private static double number(LosslessJson object,String field,String fallback,String path) {
        String token=object.token(field);
        if (token==null) return Double.parseDouble(fallback);
        if (!LosslessJson.numericToken(token)) throw new IllegalArgumentException(path+"."+field+" must be numeric to inspect");
        double value=Double.parseDouble(token);if (!Double.isFinite(value)) throw new IllegalArgumentException(path+"."+field+" must be finite to inspect");return value;
    }
    private static double[] range(LosslessJson parent,String field,String minimum,String maximum,String path,List<Finding> findings) {
        String token=parent.token(field);
        if (token==null) return new double[]{Double.parseDouble(minimum),Double.parseDouble(maximum)};
        LosslessJson range;
        try { range=new LosslessJson(token.getBytes(StandardCharsets.UTF_8)); }
        catch (IllegalArgumentException unsupported) { throw new IllegalArgumentException(path+" is a snippet or unsupported value; bounds are not resolved"); }
        if (range.token("min")==null || range.token("max")==null) findings.add(new Finding(Kind.NOTE,path,"Missing members use pack hydrology fallback values here. Runtime range class defaults may differ; write both bounds explicitly to remove that ambiguity."));
        double min=number(range,"min",minimum,path),max=number(range,"max",maximum,path);
        if (min>max) throw new IllegalArgumentException(path+" is unordered; apply ordered bounds before inspecting envelopes");
        return new double[]{min,max};
    }
    private static int integer(LosslessJson object,String field,String path) {
        String token=object.token(field);if (token!=null && !LosslessJson.numericToken(token)) throw new IllegalArgumentException(path+"."+field+" must be numeric to inspect");
        try { return new java.math.BigDecimal(token==null ? DeepFluids.defaultToken(field) : token).intValueExact(); }
        catch (ArithmeticException | NumberFormatException invalid) { throw new IllegalArgumentException(path+"."+field+" must be an exact signed 32-bit integer to inspect"); }
    }
    private static boolean pools(LosslessJson object,String path) {
        String token=object.token("containedPools");if (token==null) return Boolean.parseBoolean(DeepFluids.defaultToken("containedPools"));
        if (!token.equals("true") && !token.equals("false")) throw new IllegalArgumentException(path+".containedPools must be boolean to inspect");return token.equals("true");
    }
    public static Report inspect(byte[] original) {
        var findings=new ArrayList<Finding>();int checked=0;
        LosslessJson document,hydro;List<LosslessJson> systems;
        try {
            document=new LosslessJson(original);hydro=HydrologyFields.hydrology(document);
            if (hydro==null) return new Report(0,findings);
            systems=DeepFluids.entries(hydro);if (systems==null || systems.isEmpty()) return new Report(0,findings);
        } catch (IllegalArgumentException unsupported) { return new Report(0,List.of(new Finding(Kind.UNAVAILABLE,"hydrology.deepFluids",unsupported.getMessage()))); }
        double[] dimension;
        try { dimension=range(document,"dimensionHeight",DimensionHeights.nativeBound("min"),DimensionHeights.nativeBound("max"),"dimensionHeight",findings); }
        catch (IllegalArgumentException unsupported) { findings.add(new Finding(Kind.UNAVAILABLE,"dimensionHeight",unsupported.getMessage()));return new Report(0,findings); }
        if (dimension[0]!=Math.rint(dimension[0]) || dimension[1]!=Math.rint(dimension[1])) findings.add(new Finding(Kind.NOTE,"dimensionHeight","Fractional bounds are converted to integers by the dimension runtime. These hydrology checks use the stored numbers; apply exact integer dimension bounds to remove that difference."));
        for (int index=0;index<systems.size();index++) {
            String path="hydrology.deepFluids["+index+"]";var system=systems.get(index);
            try {
                double[] height=range(system,"height",DeepFluids.heightDefault("min",true),DeepFluids.heightDefault("max",true),path+".height",findings);
                int depth=integer(system,"depth",path),vertical=integer(system,"verticalRadius",path),headroom=integer(system,"headroom",path);
                int lower=pools(system,path) ? Math.max(depth,vertical) : depth;
                double bottom=height[0]-lower,top=height[1]+headroom;
                if (!Double.isFinite(bottom) || !Double.isFinite(top)) throw new IllegalArgumentException(path+" footprint arithmetic is not finite");
                if (bottom<=dimension[0]) findings.add(new Finding(Kind.ERROR,path+".height.min","Lower footprint "+bottom+" must be strictly above dimension minimum "+dimension[0]+"."));
                if (top>=dimension[1]) findings.add(new Finding(Kind.ERROR,path+".height.max","Upper footprint "+top+" must be strictly below dimension maximum "+dimension[1]+"."));
                checked++;
            } catch (IllegalArgumentException unsupported) { findings.add(new Finding(Kind.UNAVAILABLE,path,unsupported.getMessage())); }
        }
        return new Report(checked,findings);
    }
}
