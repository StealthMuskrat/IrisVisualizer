package dev.irisvisualizer.editor;

import java.io.IOException;
import java.util.*;

/** Shared pinned-source metadata only. No method authorizes edits or gates file operations. */
public final class FieldCoverage {
    private static final Properties DATA=new Properties();
    private static final Map<String,List<Field>> FIELDS;
    private static final Map<String,Map<String,Field>> BY_NAME;
    private static final Map<String,List<String>> REACHABLE;
    static {
        try(var input=FieldCoverage.class.getResourceAsStream("/field-coverage.properties")){
            if(input==null)throw new IOException("Missing field coverage report");DATA.load(input);
        }catch(IOException failure){throw new ExceptionInInitializerError(failure);}
        var fields=new HashMap<String,List<Field>>();var byName=new HashMap<String,Map<String,Field>>();var reachable=new HashMap<String,List<String>>();
        for(String group:split(DATA.getProperty("groups",""))){
            var rows=new ArrayList<Field>();var names=new HashMap<String,Field>();
            for(String name:split(DATA.getProperty(group+".fields",""))){
                String key=group+"."+name;var row=new Field(name,DATA.getProperty(key+".classification","unclassified"),DATA.getProperty(key+".reason","Awaiting audited classification."),Boolean.parseBoolean(DATA.getProperty(key+".implemented")));
                rows.add(row);names.put(name,row);
            }
            fields.put(group,List.copyOf(rows));byName.put(group,Map.copyOf(names));
        }
        for(String key:DATA.stringPropertyNames())if(key.startsWith("class.") && key.endsWith(".groups"))reachable.put(key.substring(6,key.length()-7),split(DATA.getProperty(key)));
        FIELDS=Map.copyOf(fields);BY_NAME=Map.copyOf(byName);REACHABLE=Map.copyOf(reachable);
    }
    public record Field(String name,String classification,String reason,boolean implemented){}
    public record Summary(int fields,int generation,int implemented,int readOnly,int unclassified,int missingPolicies,int models,int sources){}
    /** Effective inspection metadata, including restrictions inherited from a containing field/resource. */
    public record Inspection(String group,String field,String classification,String reason,boolean controlsAvailable,boolean readOnlyContext){}
    private static List<String> split(String value){return value==null || value.isEmpty()?List.of():List.of(value.split(",",-1));}
    private static int count(String key){return Integer.parseInt(DATA.getProperty(key,"0"));}
    public static Summary summary(){return new Summary(count("summary.fields"),count("summary.generation"),count("summary.implemented"),count("summary.read-only"),count("summary.unclassified"),count("summary.missingPolicies"),count("inventory.models"),count("inventory.sources"));}
    public static String readOnlyReason(String group,String name){
        if(group==null || name==null)return "Unknown or unclassified field; original value is preserved.";
        Field field=BY_NAME.getOrDefault(group,Map.of()).get(name);
        if(field==null || field.classification().equals("unclassified"))return field==null?"Unknown or unclassified field; original value is preserved.":field.reason();
        return field.classification().equals("read-only") || !field.implemented()?field.reason():"Unsupported value or context; original value is preserved.";
    }
    public static List<Field> fields(String group){return group==null?List.of():FIELDS.getOrDefault(group,List.of());}
    public static List<String> groups(String path){String model=ResourceOperation.ownerModel(path);return model==null?List.of():REACHABLE.getOrDefault(model,List.of());}
    public static String description(){return "Pinned serialized fields include inherited and nested models and all instance-field access levels. Static/transient state is excluded. Coverage describes controls in supported contexts; it grants no editing permission and does not validate stored values or native generation. Unclassified fields remain read-only.";}
    private static Inspection unknown(){return new Inspection("","","unknown","Unknown field, unsupported stored structure or unavailable source model; preserved read-only.",false,true);}
    private static String segment(String encoded){
        var result=new StringBuilder();
        for(int i=0;i<encoded.length();i++){
            char c=encoded.charAt(i);if(c!='~'){result.append(c);continue;}
            if(++i>=encoded.length())return null;c=encoded.charAt(i);if(c=='0')result.append('~');else if(c=='1')result.append('/');else return null;
        }
        return result.toString();
    }
    private static boolean index(String value){if(!value.matches("0|[1-9][0-9]{0,9}"))return false;try{return Integer.parseInt(value)>=0;}catch(NumberFormatException failure){return false;}}
    /** Resolves declared pointer structure only. It neither reads a pack nor expands snippet contents. */
    public static Inspection inspect(String path,String pointer){
        if(pointer==null || pointer.length()>JsonStructure.MAX_POINTER_CHARACTERS || (!pointer.isEmpty() && !pointer.startsWith("/")))return unknown();
        String model=ResourceOperation.ownerModel(path);
        if(model==null)return path!=null && path.equals("Schema.json")?new Inspection("","","read-only","Expected field-order metadata. The JSON source view remains read-only and does not certify its shape.",false,true):unknown();
        boolean resourceReadOnly=ResourceTypes.base(path)==null;
        if(pointer.isEmpty())return new Inspection("","","model","Known pinned source model. Class coverage does not authorize an edit or materialize defaults.",false,resourceReadOnly);
        if(!pointer.startsWith("/"))return unknown();String[] parts=pointer.substring(1).split("/",-1);if(parts.length>JsonStructure.MAX_DEPTH)return unknown();
        String kind="object:"+model,group="";Field field=null,restriction=null;boolean dataMember=false;
        for(String encoded:parts){
            String name=segment(encoded);if(name==null)return unknown();
            if(kind.startsWith("list:")){if(!index(name))return unknown();kind=kind.substring(5);continue;}
            if(kind.equals("map")){dataMember=true;continue;}
            if(!kind.startsWith("object:"))return unknown();
            String owner=kind.substring(7);group=DATA.getProperty("class."+owner+".group","");field=BY_NAME.getOrDefault(group,Map.of()).get(name);if(field==null)return unknown();
            if(restriction==null && (!field.classification().equals("generation") || !field.implemented()))restriction=field;
            kind=DATA.getProperty(group+"."+field.name()+".kind","value");
        }
        if(field==null)return unknown();Field effective=restriction==null?field:restriction;
        String classification=effective.classification(),reason=effective.reason();boolean controls=field.implemented() && restriction==null && !resourceReadOnly && !dataMember;
        if(restriction!=null && restriction!=field)reason+=" This nested value inherits its containing field's restriction.";
        if(resourceReadOnly && classification.equals("generation")){classification="read-only";reason="This resource is read-only. Model controls are available only in supported editable contexts. "+reason;}
        if(classification.equals("generation") && !effective.implemented())reason+=" Authoring controls are pending; preserved read-only.";
        if(dataMember)reason+=" Map members have no independently declared Iris fields; their data remains read-only in this inspector.";
        return new Inspection(group,field.name(),classification,reason,controls,resourceReadOnly || restriction!=null || dataMember);
    }
    private FieldCoverage(){}
}
