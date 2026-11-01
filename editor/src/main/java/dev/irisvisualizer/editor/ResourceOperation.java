package dev.irisvisualizer.editor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Closed, source-audited typed-reference planning. Partial diagnostic reports never authorize an operation. */
final class ResourceOperation {
    static final int MAX_JSON_FILES=4096,MAX_JSON_BYTES=1_000_000,MAX_REFERENCES=16384,MAX_ISSUES=256;
    static final long MAX_BYTES=64L*1024*1024,MAX_PATH_CHARACTERS=16_000_000;
    enum Mode {RENAME,MOVE,DELETE}
    record Plan(Mode mode,String source,String value,String target){boolean changes(){return mode==Mode.DELETE || !source.equals(target);}}
    record Reference(String path,String pointer,String family,String key,String resolved,String replacement,boolean implicit){
        Reference(String path,String pointer,String family,String key,String resolved,String replacement){this(path,pointer,family,key,resolved,replacement,false);}
    }
    record Issue(String path,String pointer,String message){}
    record Report(Plan plan,int inspected,List<Reference> references,List<Issue> issues,int omittedIssues,int changedFiles){
        Report {references=List.copyOf(references);issues=List.copyOf(issues);}
        boolean permitted(){return issues.isEmpty() && omittedIssues==0;}
    }
    static final class Source {
        final Plan plan;final Set<String> paths;private final Map<String,byte[]> json;final List<Issue> issues;private final Object authority;private final long revision,saved;
        Source(Plan plan,Set<String> names,Map<String,byte[]> owned,Object authority,long revision,long saved)throws IOException{
            this.authority=authority;this.revision=revision;this.saved=saved;
            this.plan=plan;var paths=new TreeSet<String>();long characters=0;for(String name:names){characters+=name.length();if(paths.size()>=PackWorkspace.MAX_FILES || characters>MAX_PATH_CHARACTERS)throw new IOException("Resource operation exceeds the complete path catalog budget");paths.add(name);}if(!paths.contains(plan.source()))throw new IOException("Resource source is no longer present");this.paths=Collections.unmodifiableSet(paths);
            var payload=new TreeMap<String,byte[]>();var problems=new ArrayList<Issue>();long bytes=0;int files=0;
            if(plan.changes())for(String path:paths)if(path.toLowerCase(Locale.ROOT).endsWith(".json")){
                if(problems.size()>=MAX_ISSUES)break;
                if(++files>MAX_JSON_FILES){problems.add(new Issue(path,"","Complete reference scan exceeds 4,096 JSON files; operation blocked."));break;}
                byte[] data=Objects.requireNonNull(owned.get(path));if(data.length>MAX_JSON_BYTES || data.length>MAX_BYTES-bytes){problems.add(new Issue(path,"","Complete reference scan exceeds 1 MB/file or 64 MiB total; operation blocked."));continue;}
                bytes+=data.length;payload.put(path,data);
            }
            json=Collections.unmodifiableMap(payload);issues=List.copyOf(problems);
        }
        boolean current(Object authority,long revision,long saved){return this.authority==authority && this.revision==revision && this.saved==saved;}
    }
    static final class Prepared {
        final Source origin;final Report report;private Map<String,byte[]> replacements;private final Map<String,byte[]> hashes;
        private Prepared(Source origin,Report report,Map<String,byte[]> replacements)throws Exception{this.origin=origin;this.report=report;this.replacements=new TreeMap<>(replacements);var hashes=new TreeMap<String,byte[]>();for(var row:replacements.entrySet())hashes.put(row.getKey(),MessageDigest.getInstance("SHA-256").digest(row.getValue()));this.hashes=Collections.unmodifiableMap(hashes);}
        boolean available(){return replacements!=null;}
        Map<String,Integer> sizes(){var result=new TreeMap<String,Integer>();for(var row:replacements.entrySet())result.put(row.getKey(),row.getValue().length);return Collections.unmodifiableMap(result);}
        byte[] hash(String path){return Objects.requireNonNull(hashes.get(path)).clone();}
        Map<String,byte[]> transfer(){if(replacements==null)throw new IllegalStateException("Resource operation already consumed");var result=replacements;replacements=null;return result;}
    }
    private static final Properties SCHEMA=new Properties();
    static {try(var input=ResourceOperation.class.getResourceAsStream("/resource-operation-schema.properties")){if(input==null)throw new IOException("Missing typed resource operation schema");SCHEMA.load(input);}catch(IOException failure){throw new ExceptionInInitializerError(failure);}}
    static boolean targetFamily(String family){return family!=null && (SCHEMA.getProperty("root."+family)!=null || Set.of("objects","images").contains(family));}
    static void check(BooleanSupplier cancelled){if(cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())throw new CancellationException("Resource operation review cancelled");}
    private static byte[] copy(byte[] owned,BooleanSupplier cancelled){var bytes=new byte[owned.length];for(int i=0;i<bytes.length;i+=65536){check(cancelled);System.arraycopy(owned,i,bytes,i,Math.min(65536,bytes.length-i));}check(cancelled);return bytes;}
    private static final class Scan {
        final Source source;final BooleanSupplier cancelled;final Resolver resolver;final List<Reference> refs=new ArrayList<>();final List<Issue> issues=new ArrayList<>();final Map<String,JsonStructure.Snapshot> documents=new TreeMap<>();final Map<String,List<JsonStructure.Node>> rewrites=new TreeMap<>();int omitted,inspected;
        Scan(Source source,BooleanSupplier cancelled){this.source=source;this.cancelled=cancelled;resolver=new Resolver(source.paths,cancelled);issues.addAll(source.issues);}
        void issue(String path,String pointer,String detail){if(issues.size()<MAX_ISSUES)issues.add(new Issue(path,pointer,detail));else omitted++;}
        String string(JsonStructure.Snapshot snapshot,JsonStructure.Node node){return new LosslessJson(("{\"x\":"+snapshot.source().substring(node.start(),node.end())+"}").getBytes(StandardCharsets.UTF_8)).stringToken("x");}
        void reference(String path,JsonStructure.Snapshot document,JsonStructure.Node node,String pointer,String family,String key,boolean implicit){
            if(key.isEmpty() || key.equals("null"))return;
            boolean block=family.equals("blocks");
            // Iris consults the custom-block loader before the vanilla registry for bare block strings.
            // Namespaced block IDs cannot name a local Windows resource path and remain untouched.
            if(block && key.contains(":"))return;
            if(!key.equals(key.strip()) || !ResourceTypes.referenceKey(key) || !key.matches("[A-Za-z0-9_./-]+")){
                if(!block || "blocks".equals(ResourceTypes.fileFamily(source.plan.source())))
                    issue(path,pointer,"Whitespace, namespaced or nonlocal reference cannot be resolved confidently.");
                return;
            }
            String resolved=resolver.resolve(family,key);boolean incoming=source.plan.source().equals(resolved);
            String replacement=incoming && source.plan.mode()!=Mode.DELETE && !implicit?key(source.plan.target()):null;
            if(refs.size()>=MAX_REFERENCES){issue(path,pointer,"Complete typed reference set exceeds 16,384 references.");return;}
            refs.add(new Reference(path,pointer,family,key,resolved,replacement,implicit));
            if(incoming){
                if(implicit)issue(path,pointer,"An omitted setting implicitly references this resource. Set an explicit value before this operation; absent defaults are never materialized by resource maintenance.");
                else if(source.plan.mode()==Mode.DELETE)issue(path,pointer,"Resource is referenced here; remove or redirect this reference before deleting.");
                else rewrites.computeIfAbsent(path,p->new ArrayList<>()).add(node);
            }
        }
        void defaults(String path,String model,Set<String> present,String pointer){
            for(String field:SCHEMA.getProperty(model+".fields").split(",")){
                if(present.contains(field))continue;
                String implicit=SCHEMA.getProperty(model+"."+field+".implicit");if(implicit==null)continue;
                String[] parts=implicit.split("\\|",-1);
                String next=pointer+"/"+field+(parts[1].isEmpty()?"":"/"+parts[1]);
                reference(path,null,null,next,parts[0],parts[2],true);
            }
        }
        void node(String path,JsonStructure.Snapshot document,int id,String type,String pointer){
            check(cancelled);var node=document.node(id);if(node.kind()==JsonStructure.Kind.NULL)return;
            if(type.startsWith("list:")){
                if(node.kind()!=JsonStructure.Kind.ARRAY){issue(path,pointer,"Typed array is a snippet or unsupported value; reference coverage is unresolved.");return;}
                for(int child:node.children())node(path,document,child,type.substring(5),pointer+"/"+document.node(child).segment());return;
            }
            if(type.startsWith("object:")){
                if(node.kind()!=JsonStructure.Kind.OBJECT){issue(path,pointer,"Typed object is a snippet or unsupported value; reference coverage is unresolved.");return;}
                String model=type.substring(7);var present=new HashSet<String>();
                for(int child:node.children()){
                    var member=document.node(child);String field=member.segment(),next=pointer+"/"+field.replace("~","~0").replace("/","~1");present.add(field);
                    String descriptor=SCHEMA.getProperty(model+"."+field);
                    if(descriptor==null){
                        if(pointer.isEmpty() && field.equals("$schema") && member.kind()==JsonStructure.Kind.STRING)continue;
                        issue(path,next,"Unknown field is outside the certified typed-reference schema.");
                    }else node(path,document,child,descriptor,next);
                }
                defaults(path,model,present,pointer);return;
            }
            if(type.equals("opaque-object")){
                if(node.kind()!=JsonStructure.Kind.OBJECT)issue(path,pointer,"Opaque block-state or tile-data map is not an object.");
                return; // These runtime maps contain state/NBT data, not pack-resource references.
            }
            if(type.startsWith("context-")){
                boolean map=type.startsWith("context-map:");String families=type.substring(type.indexOf(':')+1);
                if(map){
                    if(node.kind()!=JsonStructure.Kind.OBJECT){issue(path,pointer,"Image-map color legend is not an object.");return;}
                    for(int child:node.children())node(path,document,child,"context-string:"+families,pointer+"/"+document.node(child).segment().replace("~","~0").replace("/","~1"));
                }else if(node.kind()!=JsonStructure.Kind.STRING)issue(path,pointer,"Image-map categorical target is not a plain string.");
                else if(Arrays.asList(families.split(",")).contains(ResourceTypes.fileFamily(source.plan.source())) && !string(document,node).isBlank())
                    issue(path,pointer,"Image-map categorical reference requires its owning region/biome binding; operation blocked until that context is certified.");
                return;
            }
            if(type.startsWith("unsupported:")){issue(path,pointer,"Nested type "+type.substring(12)+" is not certified for reference maintenance.");return;}
            if(type.startsWith("ref:") || type.equals("block-ref")){
                if(node.kind()!=JsonStructure.Kind.STRING){issue(path,pointer,"Resource reference is not a plain string.");return;}
                reference(path,document,node,pointer,type.equals("block-ref")?"blocks":type.substring(4),string(document,node),false);return;
            }
            boolean valid=switch(type){case "string"->node.kind()==JsonStructure.Kind.STRING;case "number"->node.kind()==JsonStructure.Kind.NUMBER;case "boolean"->node.kind()==JsonStructure.Kind.BOOLEAN;default->false;};
            if(!valid)issue(path,pointer,"Stored value does not have the certified field type.");
        }
    }
    /** Mirrors the pinned loader: Windows exact path first, then root-only dotted-prefix fallback. */
    private static final class Resolver {
        private final Map<String,String> exact=new HashMap<>(),fallback=new HashMap<>();
        Resolver(Set<String> paths,BooleanSupplier cancelled){int count=0;for(String path:new TreeSet<>(paths)){if((count++&4095)==0)check(cancelled);exact.put(path.toLowerCase(Locale.ROOT),path);int slash=path.indexOf('/');if(slash<0 || path.indexOf('/',slash+1)>=0)continue;String family=path.substring(0,slash),name=path.substring(slash+1),extension=extension(family);if(name.endsWith(extension))fallback.putIfAbsent(family+"/"+name.split("\\.",-1)[0]+extension,path);}}
        private static String extension(String family){return switch(family){case "objects"->".iob";case "images"->".png";default->".json";};}
        String resolve(String family,String key){if(key==null || key.equals("null") || key.trim().isEmpty())return null;String extension=extension(family),wanted=family+"/"+key+extension;String path=exact.get(wanted.toLowerCase(Locale.ROOT));return path!=null?path:key.contains("/")?null:fallback.get(wanted);}
    }
    static String resolve(Set<String> paths,String family,String key){return new Resolver(paths,()->false).resolve(family,key);}
    static String key(String path){String family=ResourceTypes.fileFamily(path);return path.substring(family.length()+1,path.length()-ResourceTypes.extension(path).length());}
    static Prepared prepare(Source source,BooleanSupplier cancelled)throws Exception{
        Objects.requireNonNull(source);Objects.requireNonNull(cancelled);check(cancelled);var scan=new Scan(source,cancelled);
        if(source.plan.changes()){
            if(!targetFamily(ResourceTypes.fileFamily(source.plan.source())))scan.issue(source.plan.source(),"","This resource family has no certified mutation/reference policy yet.");
            for(String path:source.paths)if(ResourceTypes.fileFamily(path)!=null && !path.matches("[A-Za-z0-9_./-]+"))scan.issue(path,"","Resource path is outside certified ASCII Windows resource-key resolution.");
            for(var row:source.json.entrySet()){
                check(cancelled);String path=row.getKey(),model=SCHEMA.getProperty("root."+Objects.toString(ResourceTypes.base(path),""));
                if(model==null){scan.issue(path,"","This JSON reference owner is outside the closed typed operation schema; operation blocked.");continue;}
                var document=JsonStructure.inspect(copy(row.getValue(),cancelled));scan.inspected++;if(!document.indexed() || document.status()!=JsonStructure.Status.READY){scan.issue(path,"","Malformed, duplicate-key or node/depth-limited JSON prevents a complete typed reference scan.");continue;}
                scan.documents.put(path,document);scan.node(path,document,0,"object:"+model,"");
            }
            var resulting=new TreeSet<>(source.paths);resulting.remove(source.plan.source());if(source.plan.target()!=null)resulting.add(source.plan.target());
            var nextResolver=new Resolver(resulting,cancelled);if(source.plan.mode()!=Mode.DELETE)for(var reference:scan.refs){String expected=Objects.equals(reference.resolved(),source.plan.source())?source.plan.target():reference.resolved();String next=nextResolver.resolve(reference.family(),reference.replacement()==null?reference.key():reference.replacement());if(!Objects.equals(next,expected))scan.issue(reference.path(),reference.pointer(),"Operation would change another reference's resolution, including an unresolved or dotted-prefix alias.");}
        }
        var replacements=new TreeMap<String,byte[]>();if(scan.issues.isEmpty() && scan.omitted==0)for(var row:scan.rewrites.entrySet()){
            check(cancelled);var document=scan.documents.get(row.getKey());var text=new StringBuilder(document.source());var spans=new ArrayList<>(row.getValue());spans.sort(Comparator.comparingInt(JsonStructure.Node::start).reversed());String quoted="\""+key(source.plan.target())+"\"";for(var node:spans)text.replace(node.start(),node.end(),quoted);byte[] bytes=text.toString().getBytes(StandardCharsets.UTF_8);if(bytes.length>MAX_JSON_BYTES){scan.issue(row.getKey(),"","Rewritten JSON would exceed the 1 MB supported-source limit.");break;}new LosslessJson(bytes);replacements.put(row.getKey(),bytes);
        }
        check(cancelled);if(!scan.issues.isEmpty() || scan.omitted!=0)replacements.clear();var report=new Report(source.plan,scan.inspected,scan.refs,scan.issues,scan.omitted,replacements.size());return new Prepared(source,report,replacements);
    }
    private ResourceOperation(){}
}
