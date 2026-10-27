package dev.irisvisualizer.editor;

import java.io.IOException;
import java.util.List;
import java.util.Properties;

/** Literal registry keys; runtime registry, structure-role and physical-key resolution stay with Iris. */
public final class BiomeDerivatives {
    public static final List<String> EDITABLE = List.of("derivative", "vanillaDerivative", "biomeScatter", "biomeSkyScatter");
    public static final List<String> SCALARS = List.of("derivative", "vanillaDerivative");
    public static final List<String> LISTS = List.of("biomeScatter", "biomeSkyScatter");
    private static final Properties DATA=new Properties();
    static {
        try (var input=BiomeDerivatives.class.getResourceAsStream("/biome-derivative-fields.properties")) {
            if (input==null) throw new IOException("Missing pinned biome derivative definitions");DATA.load(input);
            if (!"null".equals(DATA.getProperty("vanillaDerivative.default")) || LISTS.stream().anyMatch(f -> !"[]".equals(DATA.getProperty(f+".default")))) throw new IOException("Unsupported derivative defaults");
        } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    private static void field(String field,List<String> allowed) { if (field==null || !allowed.contains(field)) throw new IllegalArgumentException("Unsupported biome derivative field"); }
    private static void authorize(String path,String field,List<String> allowed) { field(field,allowed);if (!BiomeFields.biome(path)) throw new IllegalArgumentException("Derivative settings are biome-only"); }
    public static String description(String field) { field(field,EDITABLE);return DATA.getProperty(field+".description",""); }
    public static String defaultKey() { return DATA.getProperty("derivative.default"); }
    public static String prefix(String field) { field(field,EDITABLE);return "biomeDerivative#"+field+"."; }
    public static String storedKey(LosslessJson document,String field) {
        field(field,SCALARS);String token=document.token(field);
        if (token==null || field.equals("vanillaDerivative") && token.equals("null")) return null;
        return document.stringToken(field);
    }
    public static List<String> entries(LosslessJson document,String field) { field(field,LISTS);return document.token(field)==null ? null : document.stringArrayPath(field); }
    private static void key(String value) { if (value==null || value.isBlank()) throw new IllegalArgumentException("Enter a nonblank biome registry key; runtime registry validation is pending"); }
    public static byte[] set(String path,byte[] original,String field,String value) {
        authorize(path,field,SCALARS);key(value);var document=new LosslessJson(original);
        if (field.equals("vanillaDerivative")) return document.nullableString(field,value);
        return document.token(field)==null ? document.enableStringPath(field,value) : document.stringValue(field,value);
    }
    public static byte[] inherit(String path,byte[] original) {
        authorize(path,"vanillaDerivative",SCALARS);return new LosslessJson(original).nullableString("vanillaDerivative",null);
    }
    public static byte[] enable(String path,byte[] original,String field) { authorize(path,field,LISTS);return new LosslessJson(original).enable(field,"[]"); }
    public static byte[] edit(String path,byte[] original,String field,int index,String value) {
        authorize(path,field,LISTS);key(value);var document=new LosslessJson(original);var entries=document.stringArrayPath(field);
        if (index<0 || index>=entries.size()) throw new IllegalArgumentException("Select an existing scatter key");return document.stringArrayPath(field,index,value);
    }
    public static byte[] change(String path,byte[] original,String field,RiverProfiles.Action action,int index,String value) {
        authorize(path,field,LISTS);if (action==null) throw new IllegalArgumentException("Choose a scatter action");var document=new LosslessJson(original);var entries=document.stringArrayPath(field);
        if (action==RiverProfiles.Action.ADD) { key(value);return document.stringArrayPath(field,entries.size(),value); }
        if (index<0 || index>=entries.size()) throw new IllegalArgumentException("Select an existing scatter key");
        return switch(action) {
            case DUPLICATE -> document.stringArrayDuplicate(field,index);
            case REMOVE -> document.stringArrayPath(field,index,null);
            case UP -> document.stringArrayMove(field,index,index-1);
            case DOWN -> document.stringArrayMove(field,index,index+1);
            default -> throw new IllegalArgumentException("Unsupported scatter action");
        };
    }
}
