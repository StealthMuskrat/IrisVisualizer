package dev.irisvisualizer.editor;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.function.Function;

/** Bounded coastal/inland outlet configuration; acceptance and containment are not evaluated. */
public final class GrottoFields {
    public static final List<String> OBJECT_FIELDS = List.of("grottos");
    public static final List<String> SECTIONS = List.of("coastal", "inland");
    public static final List<String> COASTAL_FIELDS = List.of("enabled", "poolLevel", "horizontalRadius", "verticalRadius", "headroom", "maximumVolume", "cliffMinimumHeight", "cliffSlopeFactor");
    public static final List<String> INLAND_FIELDS = List.of("enabled", "connectSurfaceRivers", "horizontalRadius", "verticalRadius", "headroom", "maximumVolume");
    private static final Properties DATA=new Properties();
    static {
        try (var input=GrottoFields.class.getResourceAsStream("/grotto-fields.properties")) {
            if (input==null) throw new IOException("Missing pinned grotto definitions");
            DATA.load(input);
            for (String object : List.of("grottos","coastal","inland")) if (!"{}".equals(DATA.getProperty(object+".default"))) throw new IOException("Unsupported grotto object defaults");
            if (!"null".equals(DATA.getProperty("coastal.cliffMinimumHeight.default")) || !"Integer".equals(DATA.getProperty("coastal.cliffMinimumHeight.type"))) throw new IOException("Unsupported nullable cliff-height contract");
            for (String section : SECTIONS) for (String field : fields(section))
                if (!List.of("boolean","int","Integer","double","enum").contains(DATA.getProperty(prefix(section,field)+".type", ""))) throw new IOException("Missing grotto scalar type");
            var choices=choices();
            if (choices.isEmpty() || choices.stream().anyMatch(value -> !value.matches("[A-Z][A-Z0-9_]*")) || choices.stream().distinct().count()!=choices.size() || !choices.contains(defaultToken("coastal","poolLevel"))) throw new IOException("Invalid pinned grotto pool levels");
        } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    private static String prefix(String section,String field) { return section+"."+field; }
    private static void authorize(String path) { if (!DimensionFields.dimension(path)) throw new IllegalArgumentException("Grotto configuration is dimension-only"); }
    private static void authorize(String path,String section,String field) { authorize(path); if (!fields(section).contains(field)) throw new IllegalArgumentException("Grotto field is read-only"); }
    public static List<String> fields(String section) { return switch(section) { case "coastal" -> COASTAL_FIELDS; case "inland" -> INLAND_FIELDS; default -> throw new IllegalArgumentException("Unsupported grotto section"); }; }
    public static String description(String object) { return DATA.getProperty(object+".description",""); }
    public static String help(String section,String field) { return DATA.getProperty(prefix(section,field)+".description",""); }
    public static boolean nullable(String section,String field) { return "Integer".equals(DATA.getProperty(prefix(section,field)+".type")); }
    public static boolean booleanField(String section,String field) { return "boolean".equals(DATA.getProperty(prefix(section,field)+".type")); }
    public static boolean enumField(String section,String field) { return "enum".equals(DATA.getProperty(prefix(section,field)+".type")); }
    public static boolean integer(String section,String field) { return List.of("int","Integer").contains(DATA.getProperty(prefix(section,field)+".type", "")); }
    public static List<String> choices() { return List.of(DATA.getProperty("coastal.poolLevel.choices", "").split(",")); }
    public static String choiceHelp(String value) { return DATA.getProperty("coastal.poolLevel."+value+".description",""); }
    public static String fallbackHelp() { return DATA.getProperty("coastal.cliffMinimumHeight.fallback",""); }
    public static String defaultToken(String section,String field) {
        if (!fields(section).contains(field)) throw new IllegalArgumentException("Grotto field is read-only");
        String value=DATA.getProperty(prefix(section,field)+".default");
        return booleanField(section,field) || enumField(section,field) || nullable(section,field) ? value : new BigDecimal(value).stripTrailingZeros().toPlainString();
    }
    public static String constraints(String section,String field) {
        String p=prefix(section,field);
        return (integer(section,field) ? "Signed 32-bit integer" : "Finite number")+", minimum "+DATA.getProperty(p+".minimum")+", maximum "+DATA.getProperty(p+".maximum");
    }
    public static String draftPrefix(String section) { fields(section); return "hydrology#rivers.grottos."+section+"."; }
    private static byte[] create(LosslessJson parent,String field) {
        if (parent.token(field)!=null) throw new IllegalArgumentException("Grotto object is already present or unsupported");
        return parent.enableObject(field);
    }
    public static byte[] enableGrottos(String path,byte[] original) { authorize(path); return HydrologyFields.editRivers(original,rivers -> create(rivers,"grottos")); }
    private static byte[] editGrottos(byte[] original,Function<LosslessJson,byte[]> action) { return HydrologyFields.editRivers(original,rivers -> rivers.objectEdit("grottos",action,false)); }
    private static byte[] edit(byte[] original,String section,Function<LosslessJson,byte[]> action) { return editGrottos(original,grottos -> grottos.objectEdit(section,action,false)); }
    public static byte[] enableSection(String path,byte[] original,String section) { authorize(path); fields(section); return editGrottos(original,grottos -> create(grottos,section)); }
    public static byte[] enableScalar(String path,byte[] original,String section,String field) {
        authorize(path,section,field);
        if (nullable(section,field)) throw new IllegalArgumentException("Supply an explicit cliff-height override or keep the fallback");
        return edit(original,section,object -> enumField(section,field) ? object.enableStringPath(field,defaultToken(section,field)) : object.enable(field,defaultToken(section,field)));
    }
    public static byte[] setBoolean(String path,byte[] original,String section,String field,boolean value) {
        authorize(path,section,field); if (!booleanField(section,field)) throw new IllegalArgumentException("This grotto field is not boolean");
        return edit(original,section,object -> object.token(field)==null ? object.enable(field,Boolean.toString(value)) : object.bool(field,value));
    }
    public static byte[] setEnum(String path,byte[] original,String section,String field,String value) {
        authorize(path,section,field);
        if (!enumField(section,field) || value==null || !choices().contains(value)) throw new IllegalArgumentException("Choose a pinned grotto pool level");
        return edit(original,section,object -> object.stringValue(field,value));
    }
    public static byte[] setText(String path,byte[] original,String section,String field,String input) {
        authorize(path,section,field);
        if (booleanField(section,field) || enumField(section,field) || input==null) throw new IllegalArgumentException("Enter a numeric grotto value");
        double value;
        if (integer(section,field)) {
            try { value=new BigDecimal(input).intValueExact(); }
            catch (NumberFormatException | ArithmeticException invalid) { throw new IllegalArgumentException("Enter an exact signed 32-bit integer",invalid); }
        } else value=Double.parseDouble(input);
        String p=prefix(section,field);
        if (!Double.isFinite(value) || value<Double.parseDouble(DATA.getProperty(p+".minimum")) || value>Double.parseDouble(DATA.getProperty(p+".maximum"))) throw new IllegalArgumentException("Value violates pinned grotto bounds");
        return edit(original,section,object -> nullable(section,field) ? object.nullableNumber(field,value) : object.number(field,value));
    }
    public static byte[] reset(String path,byte[] original,String section,String field) {
        authorize(path,section,field); if (!nullable(section,field)) throw new IllegalArgumentException("This grotto field has no null fallback");
        return edit(original,section,object -> object.nullableNumber(field,null));
    }
    private static LosslessJson child(LosslessJson parent,String field) { String token=parent.token(field); return token==null ? null : new LosslessJson(token.getBytes(StandardCharsets.UTF_8)); }
    static LosslessJson grottos(LosslessJson rivers) { return child(rivers,"grottos"); }
    static LosslessJson section(LosslessJson grottos,String name) { fields(name); return child(grottos,name); }
    static List<String> readOnlyPaths(LosslessJson rivers) {
        var paths=new ArrayList<String>();
        try {
            var grottos=grottos(rivers); if (grottos==null) return List.of();
            for (String section : grottos.fields()) {
                if (!SECTIONS.contains(section)) { paths.add("grottos."+section); continue; }
                try {
                    var object=section(grottos,section);
                    for (String field : object.fields()) if (!fields(section).contains(field)) paths.add("grottos."+section+"."+field);
                } catch (IllegalArgumentException mismatch) { paths.add("grottos."+section); }
            }
        } catch (IllegalArgumentException mismatch) { paths.add("grottos"); }
        return List.copyOf(paths);
    }
}
