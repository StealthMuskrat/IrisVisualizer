package dev.irisvisualizer.editor;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;

/** Sea-cave authoring with the pinned spacing/radius relation; planning and containment are not run. */
public final class SeaCaveFields {
    public static final List<String> OBJECT_FIELDS = List.of("seaCaves");
    public static final List<String> EDITABLE = List.of("enabled", "maximumPerTile", "minimumSpacing", "minimumCoastHeight", "depth", "sweepJitterDegrees");
    private static final Properties DATA=new Properties();
    static {
        try (var input=SeaCaveFields.class.getResourceAsStream("/sea-cave-fields.properties")) {
            if (input==null) throw new IOException("Missing pinned sea-cave definitions");
            DATA.load(input);
            if (!"{}".equals(DATA.getProperty("seaCaves.default")) || !"twiceCoastalRadius".equals(DATA.getProperty("minimumSpacing.rule"))
                    || !"coastalAndSeaEnabled".equals(DATA.getProperty("enabled.rule"))) throw new IOException("Unsupported sea-cave defaults/rules");
        } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    private static void authorize(String path) { if (!DimensionFields.dimension(path)) throw new IllegalArgumentException("Sea caves are dimension-only"); }
    private static void field(String name) { if (!EDITABLE.contains(name)) throw new IllegalArgumentException("Sea-cave field is read-only"); }
    public static String description() { return DATA.getProperty("description",""); }
    public static String help(String field) { field(field); return DATA.getProperty(field+".description",""); }
    public static boolean booleanField(String field) { return "boolean".equals(DATA.getProperty(field+".type")); }
    public static boolean integer(String field) { return "int".equals(DATA.getProperty(field+".type")); }
    public static String defaultToken(String field) { field(field); String value=DATA.getProperty(field+".default"); return booleanField(field) ? value : new BigDecimal(value).stripTrailingZeros().toPlainString(); }
    public static String constraints(String field) { field(field); return (integer(field) ? "Signed 32-bit integer" : "Finite number")+", minimum "+DATA.getProperty(field+".minimum")+", maximum "+DATA.getProperty(field+".maximum")+(field.equals("minimumSpacing") ? "; at least twice coastal horizontalRadius" : ""); }
    public static String draftPrefix() { return "hydrology#rivers.grottos.coastal.seaCaves."; }
    public static String creationDraftKey() { return draftPrefix()+"createSpacing"; }
    static byte[] editCoastal(byte[] original,java.util.function.Function<LosslessJson,byte[]> action) {
        return HydrologyFields.editRivers(original,rivers -> rivers.objectEdit("grottos",grottos -> grottos.objectEdit("coastal",action,false),false));
    }
    private static byte[] edit(byte[] original,java.util.function.Function<LosslessJson,byte[]> action) { return editCoastal(original,coastal -> coastal.objectEdit("seaCaves",action,false)); }
    private static int exactInt(String input) {
        try { return new BigDecimal(input).intValueExact(); }
        catch (NumberFormatException | ArithmeticException invalid) { throw new IllegalArgumentException("Enter an exact signed 32-bit integer",invalid); }
    }
    private static int checkedInt(String field,String input) {
        int value=exactInt(input);
        if (value<Integer.parseInt(DATA.getProperty(field+".minimum")) || value>Integer.parseInt(DATA.getProperty(field+".maximum"))) throw new IllegalArgumentException("Value violates pinned "+field+" bounds");
        return value;
    }
    static int coastalRadius(LosslessJson coastal) {
        String token=coastal.token("horizontalRadius");
        if (token!=null && !LosslessJson.numericToken(token)) throw new IllegalArgumentException("Stored coastal horizontalRadius has an unsupported type");
        return checkedInt("radius",token==null ? DATA.getProperty("radius.default") : token);
    }
    private static int spacing(LosslessJson caves) {
        String token=caves.token("minimumSpacing");
        if (token!=null && !LosslessJson.numericToken(token)) throw new IllegalArgumentException("Stored minimumSpacing has an unsupported type");
        return checkedInt("minimumSpacing",token==null ? defaultToken("minimumSpacing") : token);
    }
    private static void relation(int radius,int spacing) { if (spacing<radius*2) throw new IllegalArgumentException("Sea-cave minimumSpacing must be at least twice coastal horizontalRadius (required: "+(radius*2)+")"); }
    static void validateRadiusEdit(LosslessJson coastal,int radius) {
        if (coastal.token("seaCaves")==null) return; // The pinned pack check applies inside an explicit seaCaves object.
        relation(radius,spacing(object(coastal)));
    }
    public static byte[] enableObject(String path,byte[] original,String initialSpacing) {
        authorize(path); if (initialSpacing==null) throw new IllegalArgumentException("Enter an initial sea-cave spacing");
        int spacing=checkedInt("minimumSpacing",initialSpacing);
        return editCoastal(original,coastal -> {
            if (coastal.token("seaCaves")!=null) throw new IllegalArgumentException("Sea-cave settings are already present or unsupported");
            relation(coastalRadius(coastal),spacing);
            return coastal.objectEdit("seaCaves",caves -> spacing==Integer.parseInt(defaultToken("minimumSpacing")) ? caves.sourceBytes() : caves.enable("minimumSpacing",Integer.toString(spacing)),true);
        });
    }
    public static byte[] enable(String path,byte[] original,String field) {
        authorize(path); field(field);
        return editCoastal(original,coastal -> coastal.objectEdit("seaCaves",caves -> {
            if (field.equals("minimumSpacing")) relation(coastalRadius(coastal),Integer.parseInt(defaultToken(field)));
            return caves.enable(field,defaultToken(field));
        },false));
    }
    public static byte[] setEnabled(String path,byte[] original,boolean value) { authorize(path); return edit(original,caves -> caves.token("enabled")==null ? caves.enable("enabled",Boolean.toString(value)) : caves.bool("enabled",value)); }
    public static byte[] setText(String path,byte[] original,String field,String input) {
        authorize(path); field(field); if (booleanField(field) || input==null) throw new IllegalArgumentException("Enter a numeric sea-cave value");
        double value=integer(field) ? checkedInt(field,input) : Double.parseDouble(input);
        if (!Double.isFinite(value) || value<Double.parseDouble(DATA.getProperty(field+".minimum")) || value>Double.parseDouble(DATA.getProperty(field+".maximum"))) throw new IllegalArgumentException("Value violates pinned sea-cave bounds");
        return editCoastal(original,coastal -> coastal.objectEdit("seaCaves",caves -> {
            if (field.equals("minimumSpacing")) relation(coastalRadius(coastal),(int)value);
            return caves.number(field,value);
        },false));
    }
    private static byte[] writeInt(LosslessJson object,String field,int value) { return object.token(field)==null ? object.enable(field,Integer.toString(value)) : object.number(field,value); }
    public static byte[] setSpacingAndRadius(String path,byte[] original,String radiusText,String spacingText) {
        authorize(path); if (radiusText==null || spacingText==null) throw new IllegalArgumentException("Enter radius and spacing");
        int radius=checkedInt("radius",radiusText),spacing=checkedInt("minimumSpacing",spacingText); relation(radius,spacing);
        return editCoastal(original,coastal -> {
            byte[] withRadius=writeInt(coastal,"horizontalRadius",radius);
            return new LosslessJson(withRadius).objectEdit("seaCaves",caves -> writeInt(caves,"minimumSpacing",spacing),false);
        });
    }
    static LosslessJson object(LosslessJson coastal) { String token=coastal.token("seaCaves"); return token==null ? null : new LosslessJson(token.getBytes(StandardCharsets.UTF_8)); }
    static List<String> readOnlyPaths(LosslessJson coastal) {
        try { var caves=object(coastal); return caves==null ? List.of() : caves.fields().stream().filter(field -> !EDITABLE.contains(field)).map(field -> "seaCaves."+field).toList(); }
        catch (IllegalArgumentException mismatch) { return List.of("seaCaves"); }
    }
}
