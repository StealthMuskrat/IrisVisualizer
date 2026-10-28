package dev.irisvisualizer.editor;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.function.Function;

/** Styled river ranges only; no noise sampling or physical river validation runs. */
public final class RiverChannelRanges {
    public static final List<String> OBJECT_FIELDS = List.of("width", "depth");
    public static final List<String> UNDERGROUND_FIELDS = List.of("fluidLevel", "channelWidth", "depth", "headroom");
    public static List<String> rangeKeys() { return List.of("width", "depth", "underground.fluidLevel", "underground.channelWidth", "underground.depth", "underground.headroom"); }
    public static String undergroundKey(String field) { if (!UNDERGROUND_FIELDS.contains(field)) throw new IllegalArgumentException("Unsupported underground range"); return "underground."+field; }
    public static final List<String> RANGE_FIELDS = List.of("min", "max", "style");
    private static final Properties DATA=new Properties();
    static {
        try (var input=RiverChannelRanges.class.getResourceAsStream("/river-channel-ranges.properties")) {
            if (input==null) throw new IOException("Missing pinned channel range definitions");
            DATA.load(input);
            try (var underground=RiverChannelRanges.class.getResourceAsStream("/underground-river-fields.properties")) {
                if (underground==null) throw new IOException("Missing pinned underground ranges");
                DATA.load(underground);
            }
            for (String field : rangeKeys()) {
                double min=Double.parseDouble(contextBound(field,"min")),max=Double.parseDouble(contextBound(field,"max")),zoom=Double.parseDouble(contextZoom(field));
                if (!Double.isFinite(min) || !Double.isFinite(max) || min>max || !Double.isFinite(zoom) || zoom<=0) throw new IOException("Unsupported channel range defaults");
            }
        } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    private static void field(String name) { if (!rangeKeys().contains(name)) throw new IllegalArgumentException("River range is read-only"); }
    private static boolean underground(String field) { return field.startsWith("underground."); }
    private static String member(String field) { return underground(field) ? field.substring("underground.".length()) : field; }
    public static NoiseStyleFields.Target styleTarget(String field) { field(field); return underground(field) ? NoiseStyleFields.Target.undergroundRange(member(field)) : NoiseStyleFields.Target.channelRange(field); }
    private static void bound(String name) { if (!List.of("min","max").contains(name)) throw new IllegalArgumentException("Not a channel range bound"); }
    private static void authorize(String path,String name) { if (!DimensionFields.dimension(path)) throw new IllegalArgumentException("Channel ranges are dimension-only"); field(name); }
    private static String number(String key) { return new BigDecimal(DATA.getProperty(key)).stripTrailingZeros().toPlainString(); }
    public static String description(String field) { field(field); return DATA.getProperty(field+".description",""); }
    public static String styleDescription() { return DATA.getProperty("style.description",""); }
    public static String classBound(String member) { bound(member); return number(member+".default"); }
    public static String classStyle() { return DATA.getProperty("style.default"); }
    public static String contextBound(String field,String member) { field(field); bound(member); return number(field+"."+member+".default"); }
    public static String contextStyle(String field) { field(field); return DATA.getProperty(field+".style.default"); }
    public static String contextZoom(String field) { field(field); return number(field+".zoom.default"); }
    public static String draftPrefix(String field) { field(field); return underground(field) ? "hydrology#rivers.underground."+member(field)+"." : "hydrology#rivers.surface.channel."+field+"."; }
    static byte[] editRange(byte[] original,String field,Function<LosslessJson,byte[]> action) {
        field(field);
        return editParent(original,field,parent -> parent.objectEdit(member(field),action,false));
    }
    private static byte[] editParent(byte[] original,String field,Function<LosslessJson,byte[]> action) {
        return underground(field) ? UndergroundRiverFields.editUnderground(original,action) : SurfaceRiverFields.editSurface(original,surface -> surface.objectEdit("channel",action,false));
    }
    public static byte[] enable(String path,byte[] original,String field) {
        authorize(path,field);
        return editParent(original,field,channel -> {
            if (channel.token(member(field))!=null) throw new IllegalArgumentException("River range is already present or unsupported");
            return channel.objectEdit(member(field),range -> {
                byte[] next=range.enable("min",contextBound(field,"min"));
                next=new LosslessJson(next).enable("max",contextBound(field,"max"));
                next=new LosslessJson(next).enableStringPath("style.style",contextStyle(field));
                return new LosslessJson(next).enablePath("style.zoom",contextZoom(field));
            },true);
        });
    }
    private static byte[] writeBound(LosslessJson range,String member,double value) { return range.token(member)==null ? range.enable(member,BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()) : range.number(member,value); }
    public static byte[] editBounds(String path,byte[] original,String field,String minimum,String maximum) {
        authorize(path,field);
        if (minimum==null || maximum==null) throw new IllegalArgumentException("Enter both channel range bounds");
        double min=Double.parseDouble(minimum),max=Double.parseDouble(maximum);
        if (!Double.isFinite(min) || !Double.isFinite(max) || min>max) throw new IllegalArgumentException("Enter finite bounds with minimum at most maximum");
        return editRange(original,field,range -> {
            byte[] next=writeBound(range,"min",min);
            return writeBound(new LosslessJson(next),"max",max);
        });
    }
    static LosslessJson object(LosslessJson channel,String field) { field(field); String token=channel.token(member(field)); return token==null ? null : new LosslessJson(token.getBytes(StandardCharsets.UTF_8)); }
    static List<String> readOnlyPaths(LosslessJson channel,String field) {
        field(field); var paths=new ArrayList<String>();
        try {
            var range=object(channel,field); if (range==null) return List.of();
            for (String member : range.fields()) {
                if (member.equals("style")) {
                    try {
                        var style=NoiseStyleFields.object(range,styleTarget(field));
                        for (String setting : StyleFractures.readOnlyPaths(style)) paths.add(member(field)+".style."+setting);
                    } catch (IllegalArgumentException mismatch) { paths.add(member(field)+".style"); }
                } else if (!RANGE_FIELDS.contains(member)) paths.add(member(field)+"."+member);
            }
        } catch (IllegalArgumentException mismatch) { paths.add(member(field)); }
        return List.copyOf(paths);
    }
}
