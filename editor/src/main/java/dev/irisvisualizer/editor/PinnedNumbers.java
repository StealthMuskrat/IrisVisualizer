package dev.irisvisualizer.editor;

import java.math.BigDecimal;
import java.util.Properties;

/** Exact numeric drafts and scalar omission using the owning helper's existing pinned definitions. */
final class PinnedNumbers {
    static final int MAX_CHARACTERS=4096;
    private static BigDecimal nativeNumber(String type,String token,boolean input){
        if(token==null || token.length()>MAX_CHARACTERS || !LosslessJson.numericToken(token))throw new IllegalArgumentException("Enter a JSON number of at most 4096 characters");
        try{
            var value=new BigDecimal(token);
            switch(type==null?"":type){
                case "int","Integer"->value.intValueExact();
                case "long","Long"->value.longValueExact();
                case "double","Double"->{double nativeValue=Double.parseDouble(token);if(!Double.isFinite(nativeValue) || input && value.signum()!=0 && nativeValue==0)throw new ArithmeticException();}
                default->throw new IllegalArgumentException("This field has no supported pinned numeric type");
            }
            return value;
        }catch(NumberFormatException | ArithmeticException invalid){throw new IllegalArgumentException("Use an exact native integer or finite double without input underflow",invalid);}
    }
    static boolean supported(Properties definitions,String field,String token){
        try{nativeNumber(definitions.getProperty(field+".type"),token,false);return true;}
        catch(IllegalArgumentException unsupported){return false;}
    }
    static String input(Properties definitions,String field,String token){
        return input(definitions.getProperty(field+".type"),definitions.getProperty(field+".minimum"),definitions.getProperty(field+".maximum"),definitions.getProperty(field+".options"),token);
    }
    static String input(String type,String minimum,String maximum,String options,String token){
        var value=nativeNumber(type,token,true);
        if(minimum!=null && value.compareTo(new BigDecimal(minimum))<0 || maximum!=null && value.compareTo(new BigDecimal(maximum))>0)throw new IllegalArgumentException("Value violates the pinned Iris numeric bounds");
        if(options!=null && !options.isBlank()){
            boolean allowed=false;for(String option:options.split(","))if(value.compareTo(new BigDecimal(option.strip()))==0){allowed=true;break;}
            if(!allowed)throw new IllegalArgumentException("Choose one of the pinned Iris numeric options: "+options);
        }
        return token;
    }
    static byte[] edit(LosslessJson document,Properties definitions,String field,String token){
        return edit(document,field,definitions.getProperty(field+".type"),definitions.getProperty(field+".minimum"),definitions.getProperty(field+".maximum"),definitions.getProperty(field+".options"),token);
    }
    static byte[] edit(LosslessJson document,String field,String type,String minimum,String maximum,String options,String token){
        input(type,minimum,maximum,options,token);
        // Bounds-invalid stored numbers can be corrected. Wrong native types/widths cannot be silently repaired.
        nativeNumber(type,document.tokenPath(field),false);
        return document.numberTokenPath(field,token);
    }
    /** Explicit omission of a supported scalar, without inserting its class default or pruning parents. */
    static byte[] reset(LosslessJson document,Properties definitions,String field){
        String type=definitions.getProperty(field+".type");
        if(definitions.getProperty(field+".default")==null || !java.util.Set.of("int","Integer","long","Long","double","Double","boolean","Boolean").contains(type==null?"":type))throw new IllegalArgumentException("This field has no supported pinned scalar default");
        String token=document.tokenPath(field);if(token==null)return document.sourceBytes();
        if(type.equals("boolean") || type.equals("Boolean")){if(!token.equals("true") && !token.equals("false"))throw new IllegalArgumentException("Stored toggle is not boolean; it stays read-only");}
        else nativeNumber(type,token,false);
        return document.removePath(field);
    }
    /** Retains the existing double-taking API's canonical serialization; text routes never pass through it. */
    static String canonical(double value){
        if(!Double.isFinite(value))throw new IllegalArgumentException("Finite number required");
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }
    private PinnedNumbers(){}
}
