package dev.irisvisualizer.renderer;

import java.math.BigDecimal;
import java.util.*;

/** Closed supported metadata shapes. Unsupported fields remain visible failures, never guessed geometry. */
final class ModelFields {
    static final class Failure extends RuntimeException {
        final ModelPlan.Problem problem;
        Failure(ModelPlan.Problem problem){super(problem.reason());this.problem=problem;}
        Failure(ModelPlan.Code code,AssetId asset,String pointer,String reason){this(new ModelPlan.Problem(code,ClientAssetSnapshot.Kind.MODEL,asset,pointer,reason));}
    }
    static Failure fail(ModelPlan.Code code,AssetId asset,String pointer,String reason) { return new Failure(code,asset,pointer,reason); }
    static Failure format(AssetId asset,String pointer,String reason) { return fail(ModelPlan.Code.FORMAT,asset,pointer,reason); }
    static String child(String pointer,String key) { return pointer+"/"+key.replace("~","~0").replace("/","~1"); }
    static Map<String,AssetJson.Value> object(AssetJson.Value value,AssetId id,String pointer) {
        if(!(value instanceof AssetJson.ObjectValue object))throw format(id,pointer,"Expected a model JSON object");return object.fields();
    }
    static List<AssetJson.Value> array(AssetJson.Value value,AssetId id,String pointer) {
        if(!(value instanceof AssetJson.ArrayValue array))throw format(id,pointer,"Expected a model JSON array");return array.values();
    }
    static String text(AssetJson.Value value,AssetId id,String pointer) {
        if(!(value instanceof AssetJson.Text text))throw format(id,pointer,"Expected a model JSON string");return text.value();
    }
    static boolean flag(AssetJson.Value value,boolean fallback,AssetId id,String pointer) {
        if(value==null)return fallback;if(!(value instanceof AssetJson.Flag flag))throw format(id,pointer,"Expected a model JSON boolean");return flag.value();
    }
    static int integer(AssetJson.Value value,int fallback,int minimum,int maximum,AssetId id,String pointer) {
        if(value==null)return fallback;if(!(value instanceof AssetJson.NumberToken number))throw format(id,pointer,"Expected a model JSON integer");
        try { int result=new BigDecimal(number.spelling()).intValueExact();if(result<minimum || result>maximum)throw new ArithmeticException();return result; }
        catch(ArithmeticException|NumberFormatException invalid) { throw format(id,pointer,"Model integer is outside its exact supported range"); }
    }
    static float number(AssetJson.Value value,AssetId id,String pointer) {
        if(!(value instanceof AssetJson.NumberToken number))throw format(id,pointer,"Expected a model JSON number");
        try { float result=Float.parseFloat(number.spelling());if(!Float.isFinite(result))throw new NumberFormatException();return result; }
        catch(NumberFormatException invalid) { throw format(id,pointer,"Model number is not a finite native float"); }
    }
    static float number(AssetJson.Value value,float fallback,AssetId id,String pointer) { return value==null?fallback:number(value,id,pointer); }
    static ModelPlan.Vec3 vector(AssetJson.Value value,AssetId id,String pointer,boolean bounded) {
        var values=array(value,id,pointer);if(values.size()!=3)throw format(id,pointer,"Expected three model coordinates");
        float x=number(values.get(0),id,pointer+"/0"),y=number(values.get(1),id,pointer+"/1"),z=number(values.get(2),id,pointer+"/2");
        if(bounded && (x< -16||x>32||y< -16||y>32||z< -16||z>32))throw format(id,pointer,"Model bounds exceed the supported -16..32 element range");
        return new ModelPlan.Vec3(x,y,z);
    }
    static ModelPlan.Direction direction(String text,AssetId id,String pointer) {
        return switch(text){case "down"->ModelPlan.Direction.DOWN;case "up"->ModelPlan.Direction.UP;case "north"->ModelPlan.Direction.NORTH;case "south"->ModelPlan.Direction.SOUTH;case "west"->ModelPlan.Direction.WEST;case "east"->ModelPlan.Direction.EAST;default->throw format(id,pointer,"Unsupported model direction");};
    }
    static AssetId reference(String value,AssetId id,String pointer) {
        try { return value.indexOf(':')<0?AssetId.minecraft(value):AssetId.parse(value); }
        catch(IllegalArgumentException invalid) { throw format(id,pointer,"Invalid model resource identifier"); }
    }
    static String variable(String name,AssetId id,String pointer) {
        if(!name.matches("[A-Za-z0-9_./-]{1,128}"))throw format(id,pointer,"Unsupported texture variable name");return name;
    }
    static void known(Map<String,AssetJson.Value> object,Set<String> fields,AssetId id,String pointer) {
        for(String key:object.keySet())if(!fields.contains(key))throw fail(ModelPlan.Code.UNSUPPORTED_FIELD,id,child(pointer,key),"This asset field has no supported renderer semantics");
    }
}
