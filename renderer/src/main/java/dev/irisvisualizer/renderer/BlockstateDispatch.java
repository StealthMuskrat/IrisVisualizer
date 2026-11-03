package dev.irisvisualizer.renderer;

import java.util.*;
import static dev.irisvisualizer.renderer.ModelFields.*;

/** Exact property predicates and weighted dispatch; random choice remains the native client's responsibility. */
final class BlockstateDispatch {
    record Apply(AssetId model,int x,int y,int z,boolean uvlock,int weight) {}
    record Selection(String pointer,int sourceIndex,List<Apply> choices) { Selection { choices=List.copyOf(choices); } }
    private interface Condition { boolean matches(Map<String,String> values); }
    private record Property(String name,Set<String> alternatives,boolean negate) implements Condition {
        public boolean matches(Map<String,String> values) { return alternatives.contains(values.get(name))!=negate; }
    }
    private record All(List<Condition> children) implements Condition { public boolean matches(Map<String,String> values){for(var child:children)if(!child.matches(values))return false;return true;} }
    private record Any(List<Condition> children) implements Condition { public boolean matches(Map<String,String> values){for(var child:children)if(child.matches(values))return true;return false;} }
    private static int rotation(Map<String,AssetJson.Value> object,String field,AssetId id,String pointer) {
        int value=integer(object.get(field),0,0,270,id,child(pointer,field));if(!ModelPlan.quarter(value))throw format(id,child(pointer,field),"Blockstate rotation must be 0, 90, 180 or 270");return value;
    }
    private static List<Apply> apply(BlockModels.Work work,AssetJson.Value value,AssetId id,String pointer) {
        List<AssetJson.Value> values=value instanceof AssetJson.ArrayValue array?array.values():List.of(value);
        if(values.isEmpty())throw format(id,pointer,"A weighted model list must contain at least one choice");
        var result=new ArrayList<Apply>();long total=0;
        for(int index=0;index<values.size();index++) {
            work.limit(++work.choices<=work.limits.choices(),id,pointer,"Blockstate model-choice budget reached");
            String here=value instanceof AssetJson.ArrayValue?pointer+"/"+index:pointer;var fields=object(values.get(index),id,here);
            known(fields,Set.of("model","x","y","z","uvlock","weight"),id,here);
            var model=reference(text(fields.get("model"),id,here+"/model"),id,here+"/model");
            int weight=integer(fields.get("weight"),1,1,Integer.MAX_VALUE,id,here+"/weight");total+=weight;
            work.limit(total<=Integer.MAX_VALUE,id,pointer,"Native signed-int model weight total is exceeded");
            result.add(new Apply(model,rotation(fields,"x",id,here),rotation(fields,"y",id,here),rotation(fields,"z",id,here),flag(fields.get("uvlock"),false,id,here+"/uvlock"),weight));
        }
        return List.copyOf(result);
    }
    private static Condition property(BlockModels.Work work,String name,String specification,boolean alternatives,AssetId id,String pointer) {
        work.limit(++work.conditions<=work.limits.conditionNodes(),id,pointer,"Blockstate predicate budget reached");
        var domain=work.state.propertyDomain().get(name);if(domain==null)throw fail(ModelPlan.Code.PROPERTY,id,pointer,"Selector names a property absent from the supplied native definition");
        boolean negate=alternatives && specification.startsWith("!");String body=negate?specification.substring(1):specification;
        if(!alternatives && (body.indexOf('|')>=0 || body.startsWith("!")))throw format(id,pointer,"Variant keys require one literal native property value");
        var values=new LinkedHashSet<String>();
        for(String value:body.split("\\|",-1)) {
            if(value.isEmpty() || !domain.contains(value))throw fail(ModelPlan.Code.PROPERTY,id,pointer,"Selector value is outside the supplied native property domain");
            if(!values.add(value))throw format(id,pointer,"Duplicate alternatives in a property predicate are unsupported");
        }
        return new Property(name,Set.copyOf(values),negate);
    }
    private static Condition variant(BlockModels.Work work,String selector,AssetId id,String pointer) {
        if(selector.isEmpty())return new All(List.of());var predicates=new ArrayList<Condition>();var names=new HashSet<String>();
        for(String token:selector.split(",",-1)) {
            int equals=token.indexOf('=');if(equals<1 || equals!=token.lastIndexOf('='))throw format(id,pointer,"Malformed blockstate variant key");
            String name=token.substring(0,equals);if(!names.add(name))throw format(id,pointer,"Duplicate property in a blockstate variant key");
            predicates.add(property(work,name,token.substring(equals+1),false,id,pointer));
        }
        return new All(List.copyOf(predicates));
    }
    private static Condition condition(BlockModels.Work work,AssetJson.Value value,AssetId id,String pointer,int depth) {
        work.limit(depth<=work.limits.conditionDepth() && ++work.conditions<=work.limits.conditionNodes(),id,pointer,"Multipart condition depth/node budget reached");
        var fields=object(value,id,pointer);boolean or=fields.containsKey("OR"),and=fields.containsKey("AND");
        if(or || and) {
            if(fields.size()!=1)throw format(id,pointer,"OR/AND may not be mixed with properties or each other");String op=or?"OR":"AND";
            var inputs=array(fields.get(op),id,pointer+"/"+op);if(inputs.isEmpty())throw format(id,pointer,"Empty OR/AND predicate arrays are unsupported");
            var children=new ArrayList<Condition>();for(int i=0;i<inputs.size();i++)children.add(condition(work,inputs.get(i),id,pointer+"/"+op+"/"+i,depth+1));
            return or?new Any(List.copyOf(children)):new All(List.copyOf(children));
        }
        var children=new ArrayList<Condition>();for(var entry:fields.entrySet())children.add(property(work,entry.getKey(),text(entry.getValue(),id,child(pointer,entry.getKey())),true,id,child(pointer,entry.getKey())));
        return new All(List.copyOf(children));
    }
    static List<Selection> select(BlockModels.Work work) {
        try { return selectDefinition(work); }
        catch(Failure failure) {
            var problem=failure.problem;
            throw new Failure(new ModelPlan.Problem(problem.code(),ClientAssetSnapshot.Kind.BLOCKSTATE,problem.asset(),problem.pointer(),problem.reason()));
        }
    }
    private static List<Selection> selectDefinition(BlockModels.Work work) {
        AssetId id=work.state.block();var root=work.document(ClientAssetSnapshot.Kind.BLOCKSTATE,id);known(root,Set.of("variants","multipart"),id,"");
        boolean variants=root.containsKey("variants");if(variants==root.containsKey("multipart"))throw format(id,"","Expected exactly one variants or multipart definition");
        var selected=new ArrayList<Selection>();
        if(variants) {
            var cases=object(root.get("variants"),id,"/variants");work.limit(cases.size()<=work.limits.cases(),id,"/variants","Blockstate case budget reached");
            int index=0;for(var entry:cases.entrySet()) {
                work.check();String pointer=child("/variants",entry.getKey());var predicate=variant(work,entry.getKey(),id,pointer);var models=apply(work,entry.getValue(),id,pointer);
                if(predicate.matches(work.state.properties()))selected.add(new Selection(pointer,index,models));index++;
            }
            if(selected.isEmpty())throw fail(ModelPlan.Code.NO_VARIANT,id,"/variants","No blockstate variant matches the native state");
            if(selected.size()!=1)throw fail(ModelPlan.Code.AMBIGUOUS_VARIANT,id,"/variants","More than one blockstate variant matches the native state");
        } else {
            var cases=array(root.get("multipart"),id,"/multipart");work.limit(cases.size()<=work.limits.cases(),id,"/multipart","Multipart case budget reached");
            for(int i=0;i<cases.size();i++) {
                work.check();String pointer="/multipart/"+i;var part=object(cases.get(i),id,pointer);known(part,Set.of("when","apply"),id,pointer);
                var predicate=part.containsKey("when")?condition(work,part.get("when"),id,pointer+"/when",0):new All(List.of());
                if(!part.containsKey("apply"))throw format(id,pointer,"Multipart case is missing apply");
                var models=apply(work,part.get("apply"),id,pointer+"/apply");if(predicate.matches(work.state.properties()))selected.add(new Selection(pointer+"/apply",i,models));
            }
        }
        return List.copyOf(selected);
    }
}
