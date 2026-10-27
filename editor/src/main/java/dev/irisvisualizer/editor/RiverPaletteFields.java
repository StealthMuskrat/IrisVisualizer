package dev.irisvisualizer.editor;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.function.Function;

/** Noise settings on existing river material palettes; does not sample or resolve blocks. */
public final class RiverPaletteFields {
    public static final List<String> EDITABLE = List.of("style", "zoom");
    private static final Properties DATA=new Properties();
    static {
        try (var input=RiverPaletteFields.class.getResourceAsStream("/river-palette-fields.properties")) {
            if (input==null) throw new IOException("Missing pinned river palette noise definitions");
            DATA.load(input);
            if (!"finiteAtLeastSourceMinimum".equals(DATA.getProperty("zoom.rule"))) throw new IOException("Unsupported palette zoom policy");
        } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    public static List<String> targets() {
        var targets=new ArrayList<>(RiverMaterialFields.MATERIAL_TARGETS); targets.add(RiverMaterialFields.PADDING); return List.copyOf(targets);
    }
    private static void target(String target) { if (!targets().contains(target)) throw new IllegalArgumentException("Unsupported river palette noise target"); }
    public static String stylePath(String target) { target(target); return "hydrology.rivers."+target+(target.equals(RiverMaterialFields.PADDING) ? ".style" : ".palette.style"); }
    public static String targetForStylePath(String path) { return targets().stream().filter(target -> stylePath(target).equals(path)).findFirst().orElse(null); }
    public static String styleObjectDefault() { return DATA.getProperty("style.objectDefault"); }
    public static String styleDefault() { return DATA.getProperty("style.default"); }
    public static String description(String field) { if (!EDITABLE.contains(field)) throw new IllegalArgumentException("Unsupported palette setting"); return DATA.getProperty(field+".description",""); }
    public static String zoomDefault() { return new BigDecimal(DATA.getProperty("zoom.default")).stripTrailingZeros().toPlainString(); }
    public static String zoomMinimum() { return DATA.getProperty("zoom.minimum"); }
    public static String zoomConstraints() { return "Finite number, minimum "+zoomMinimum()+". This palette zoom is separate from the generator style zoom."; }
    public static String draftPrefix(String target) { target(target); return RiverMaterialFields.draftPrefix(target)+"paletteNoise."; }
    static byte[] editPalette(byte[] original,String target,Function<LosslessJson,byte[]> action) { target(target); return RiverMaterialFields.editPalette(original,target,action); }
    private static void authorize(String path,String target) { target(target); if (!DimensionFields.dimension(path)) throw new IllegalArgumentException("River palette noise is dimension-only"); }
    public static byte[] enableZoom(String path,byte[] original,String target) { authorize(path,target); return editPalette(original,target,palette -> palette.enable("zoom",zoomDefault())); }
    public static byte[] setZoom(String path,byte[] original,String target,String input) {
        authorize(path,target); double value=checkedZoom(input);
        return editPalette(original,target,palette -> palette.number("zoom",value));
    }
    static double checkedZoom(String input) {
        double value;
        try { value=Double.parseDouble(input); } catch (NumberFormatException | NullPointerException invalid) { throw new IllegalArgumentException("Enter a finite palette zoom",invalid); }
        if (!Double.isFinite(value) || value<Double.parseDouble(zoomMinimum())) throw new IllegalArgumentException("Palette zoom must be finite and at least "+zoomMinimum());
        return value;
    }
    static List<String> readOnlyStylePaths(LosslessJson palette,String target) {
        var paths=new ArrayList<String>();
        try {
            var style=NoiseStyleFields.object(palette,NoiseStyleFields.Target.palette(target));
            if (style!=null) for (String field : style.fields()) if (NoiseStyleFields.readOnly(style,field)) paths.add("style."+field);
        } catch (IllegalArgumentException mismatch) { paths.add("style"); }
        return List.copyOf(paths);
    }
}
