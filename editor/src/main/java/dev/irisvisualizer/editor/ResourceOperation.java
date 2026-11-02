package dev.irisvisualizer.editor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Closed, source-audited typed-reference planning. Partial diagnostic reports never authorize an operation. */
final class ResourceOperation {
    static final int MAX_JSON_FILES=4096,MAX_JSON_BYTES=1_000_000,MAX_REFERENCES=16384,MAX_ISSUES=256,MAX_SNIPPET_LINKS=16384,MAX_SNIPPET_DEPTH=32,MAX_SYMBOLIC_IDS=16384,MAX_SYMBOLIC_CHARACTERS=1_000_000;
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
    private static final Map<String,Set<String>> MODEL_FIELDS;
    static {
        try(var input=ResourceOperation.class.getResourceAsStream("/resource-operation-schema.properties")){if(input==null)throw new IOException("Missing typed resource operation schema");SCHEMA.load(input);}catch(IOException failure){throw new ExceptionInInitializerError(failure);}
        var fields=new HashMap<String,Set<String>>();
        for(String property:SCHEMA.stringPropertyNames())if(property.endsWith(".fields")){
            String value=SCHEMA.getProperty(property);fields.put(property.substring(0,property.length()-7),value.isEmpty()?Set.of():Set.copyOf(Arrays.asList(value.split(",",-1))));
        }
        MODEL_FIELDS=Map.copyOf(fields);
    }
    static boolean targetFamily(String family){return family!=null && (SCHEMA.getProperty("root."+family)!=null || Set.of("objects","images").contains(family));}
    /** Reference ownership is broader than editable file-operation targets. */
    static String ownerModel(String path){
        String family=ResourceTypes.base(path);if(family!=null)return SCHEMA.getProperty("owner."+family);
        if(path==null || !path.endsWith(".json"))return null;
        int slash=path.indexOf('/');if(slash<1)return null;family=path.substring(0,slash);
        if(family.equals("dimensions"))return null; // Never certify nested dimension files as valid owners.
        if(family.equals("snippet")){
            int kindEnd=path.indexOf('/',slash+1);if(kindEnd<0)return null;
            String key=path.substring(kindEnd+1,path.length()-5);
            if(!ResourceTypes.referenceKey(key) || !key.matches("[A-Za-z0-9_./-]+"))return null;
            return SCHEMA.getProperty("snippet."+path.substring(slash+1,kindEnd));
        }
        return SCHEMA.getProperty("owner."+family);
    }
    static String ownerDescriptor(String path){String metadata=SCHEMA.getProperty("metadata."+path);if(metadata!=null)return metadata;String model=ownerModel(path);return model==null?null:"object:"+model;}
    static String scopeDescription(){return "Current closed scope: ten editable JSON families plus typed read-only loot, spawner, entity, marker, modification and annotated snippet owners. The additional families remain read-only except for required typed-reference maintenance. Unknown owners/fields, unsupported shapes, missing/cyclic/deep snippets, unresolved defaults and uncertified runtime comparisons block operations. Canonical region/deposit filters, normalized local cave-biome selectors, exact object-collision comparisons and unambiguous carving fallback links are maintained; ambiguous identities, procedural collision-marker namespaces and affected qualified cave lookups remain blocked. Categorical targets require unambiguous image-map applications. Apply changes memory and clears acknowledged applied-edit history; Save independently checks and publishes files, then removes old paths.";}
    static void check(BooleanSupplier cancelled){if(cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())throw new CancellationException("Resource operation review cancelled");}
    private static byte[] copy(byte[] owned,BooleanSupplier cancelled){var bytes=new byte[owned.length];for(int i=0;i<bytes.length;i+=65536){check(cancelled);System.arraycopy(owned,i,bytes,i,Math.min(65536,bytes.length-i));}check(cancelled);return bytes;}
    private record Rewrite(JsonStructure.Node node,String value){}
    private record ReservedKey(String path,String pointer,String family){}
    private record CaveComparison(Reference reference,String compatibilityKey,String compatibilityResolved){}
    private static final class Scan {
        final Source source;final BooleanSupplier cancelled;final Resolver resolver;final List<Reference> refs=new ArrayList<>();final List<Issue> issues=new ArrayList<>();final Map<String,JsonStructure.Snapshot> documents=new TreeMap<>();final Map<String,List<Rewrite>> rewrites=new TreeMap<>();final Map<String,Set<String>> imageMapUses=new TreeMap<>();final List<Reference> categoricalBiomes=new ArrayList<>();final Map<String,Set<String>> snippetEdges=new TreeMap<>();final Set<Reference> exactComparisons=new HashSet<>();final List<ReservedKey> reservedKeys=new ArrayList<>();final Set<String> carvingIds=new HashSet<>();int omitted,inspected,snippetLinks,symbolicCharacters;boolean incompleteCarvingIds;
        Scan(Source source,BooleanSupplier cancelled){
            this.source=source;this.cancelled=cancelled;resolver=new Resolver(source.paths,cancelled);issues.addAll(source.issues);
            // IrisComplex also constructs a default empty cave biome, outside the pack documents.
            if(affected("biomes"))depositDerivative(Objects.requireNonNull(SCHEMA.getProperty("context.biome.derivative.default"),"Missing pinned biome derivative default"));
        }
        void issue(String path,String pointer,String detail){if(issues.size()<MAX_ISSUES)issues.add(new Issue(path,pointer,detail));else omitted++;}
        String string(JsonStructure.Snapshot snapshot,JsonStructure.Node node){return new LosslessJson(("{\"x\":"+snapshot.source().substring(node.start(),node.end())+"}").getBytes(StandardCharsets.UTF_8)).stringToken("x");}
        void reference(String path,JsonStructure.Snapshot document,JsonStructure.Node node,String pointer,String family,String key,boolean implicit){
            reference(path,document,node,pointer,family,key,implicit,"","");
        }
        void reference(String path,JsonStructure.Snapshot document,JsonStructure.Node node,String pointer,String family,String key,boolean implicit,String prefix,String suffix){
            boolean block=family.equals("blocks");
            if(key.isEmpty())return;
            if(key.equals("null") && !block){
                // Standard calls refuse this literal, but quiet native calls can load it.
                if(affected(family)){
                    if(refs.size()+reservedKeys.size()>=MAX_REFERENCES)issue(path,pointer,"Complete typed reference set exceeds 16,384 references.");
                    else reservedKeys.add(new ReservedKey(path,pointer,family));
                }
                return;
            }
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
            if(refs.size()+reservedKeys.size()>=MAX_REFERENCES){issue(path,pointer,"Complete typed reference set exceeds 16,384 references.");return;}
            refs.add(new Reference(path,pointer,family,key,resolved,replacement,implicit));
            if(incoming){
                if(implicit)issue(path,pointer,"An omitted setting implicitly references this resource. Set an explicit value before this operation; absent defaults are never materialized by resource maintenance.");
                else if(source.plan.mode()==Mode.DELETE)issue(path,pointer,"Resource is referenced here; remove or redirect this reference before deleting.");
                else rewrites.computeIfAbsent(path,p->new ArrayList<>()).add(new Rewrite(node,prefix+replacement+suffix));
            }
        }
        boolean affected(String families){return Arrays.asList(families.split(",")).contains(ResourceTypes.fileFamily(source.plan.source()));}
        void defaults(String path,String model,Set<String> present,String pointer){
            for(String field:SCHEMA.getProperty(model+".fields").split(",")){
                if(present.contains(field))continue;
                String qualified=model+"."+field,blocked=SCHEMA.getProperty(qualified+".defaultBlock");
                if(blocked!=null && affected(blocked))issue(path,pointer+"/"+field,"Omitted default has an uncertified resource-bearing initializer: "+SCHEMA.getProperty(qualified+".defaultReason")+". Set an explicit supported value before this operation.");
                int count=Integer.parseInt(SCHEMA.getProperty(qualified+".implicit.count","0"));
                for(int i=0;i<count;i++){
                    String[] parts=SCHEMA.getProperty(qualified+".implicit."+i).split("\\|",-1);
                    String next=pointer+"/"+field+(parts[1].isEmpty()?"":"/"+parts[1]);
                    reference(path,null,null,next,parts[0],parts[2],true);
                }
            }
        }
        JsonStructure.Node member(JsonStructure.Snapshot document,JsonStructure.Node object,String field){
            if(object.kind()!=JsonStructure.Kind.OBJECT)return null;
            for(int id:object.children())if(document.node(id).segment().equals(field))return document.node(id);
            return null;
        }
        void mapUse(String map,String application){if(!Set.of("BIOME","REGION","SURFACE_BLOCK","TERRAIN_HEIGHT","MASK","CUSTOM","SCALAR").contains(application))application="UNKNOWN";String resolved=resolver.resolve("image-maps",map);if(resolved!=null)imageMapUses.computeIfAbsent(resolved,p->new TreeSet<>()).add(application);}
        // Frozen owners contribute binding roles, carving IDs and derivative collisions before classification.
        void collectContexts(String path,JsonStructure.Snapshot document,int id,String type){
            check(cancelled);var current=document.node(id);
            if(current.kind()==JsonStructure.Kind.NULL)return;
            if(type.startsWith("list:") && current.kind()==JsonStructure.Kind.ARRAY){for(int child:current.children())collectContexts(path,document,child,type.substring(5));return;}
            if(!type.startsWith("object:") || current.kind()!=JsonStructure.Kind.OBJECT)return;
            String model=type.substring(7);
            if(model.equals("IrisBiome") && affected("biomes")){
                var derivative=member(document,current,"derivative");var vanilla=member(document,current,"vanillaDerivative");
                if(derivative==null)depositDerivative(Objects.requireNonNull(SCHEMA.getProperty("context.biome.derivative.default"),"Missing pinned biome derivative default"));
                else if(derivative.kind()==JsonStructure.Kind.STRING)depositDerivative(string(document,derivative));
                if(vanilla!=null && vanilla.kind()==JsonStructure.Kind.STRING)depositDerivative(string(document,vanilla));
            }
            if(model.equals("IrisDimensionCarvingEntry") && affected("biomes") && !incompleteCarvingIds){
                var identifier=member(document,current,"id");
                if(identifier!=null && identifier.kind()==JsonStructure.Kind.STRING){
                    String value=string(document,identifier);
                    if(!value.isBlank()){
                        String normalized=value.trim();
                        if(!carvingIds.contains(normalized)){
                            if(carvingIds.size()>=MAX_SYMBOLIC_IDS || normalized.length()>MAX_SYMBOLIC_CHARACTERS-symbolicCharacters){incompleteCarvingIds=true;issue(path,document.pointer(identifier.id()).text(),"Complete carving-entry context exceeds 16,384 IDs or 1,000,000 UTF-16 characters; fallback biome reference maintenance is blocked.");}
                            else{carvingIds.add(normalized);symbolicCharacters+=normalized.length();}
                        }
                    }
                }
            }
            if(model.equals("IrisImageMapBinding")){
                var map=member(document,current,"map");var application=member(document,current,"application");
                if(map!=null && map.kind()==JsonStructure.Kind.STRING)mapUse(string(document,map).trim(),application==null?"CUSTOM":application.kind()==JsonStructure.Kind.STRING?string(document,application):"UNKNOWN");
            }else if(model.equals("IrisGeneratorStyle")){
                var map=member(document,current,"imageMap");if(map!=null && map.kind()==JsonStructure.Kind.STRING)mapUse(string(document,map),"SCALAR");
            }
            for(int child:current.children()){
                String field=document.node(child).segment();String childType=MODEL_FIELDS.getOrDefault(model,Set.of()).contains(field)?SCHEMA.getProperty(model+"."+field):null;
                if(childType!=null)collectContexts(path,document,child,childType);
            }
        }
        void categorical(String path,JsonStructure.Snapshot document,JsonStructure.Node node,String pointer,String families){
            if(node.kind()!=JsonStructure.Kind.STRING){issue(path,pointer,"Image-map categorical target is not a plain string.");return;}
            String value=string(document,node);if(value.isBlank())return;
            Set<String> uses=imageMapUses.getOrDefault(path,Set.of());
            String family=uses.equals(Set.of("BIOME"))?"biomes":uses.equals(Set.of("REGION"))?"regions":null;
            if(family==null){
                // Surface-block labels go straight to the native registry, never to blocks/. Pure scalar roles ignore labels.
                if(!uses.isEmpty() && Set.of("SURFACE_BLOCK","TERRAIN_HEIGHT","MASK","CUSTOM","SCALAR").containsAll(uses))return;
                if(affected(families))issue(path,pointer,"Unbound, mixed or unsupported image-map applications leave the categorical target family unresolved.");
                return;
            }
            int first=0,last=value.length();while(first<last && value.charAt(first)<=32)first++;while(last>first && value.charAt(last-1)<=32)last--;
            String normalized=value.substring(first,last),prefix=value.substring(0,first);
            if(normalized.contains(":")){
                if(!normalized.startsWith("iris:") || normalized.length()==5){issue(path,pointer,"Categorical biome/region targets require a bare key or the exact iris: namespace.");return;}
                prefix+="iris:";normalized=normalized.substring(5);
            }
            int before=refs.size();reference(path,document,node,pointer,family,normalized,false,prefix,value.substring(last));
            if(family.equals("biomes") && refs.size()>before)categoricalBiomes.add(refs.getLast());
        }
        void checkCategoricalComparisons(){
            if(!"biomes".equals(ResourceTypes.fileFamily(source.plan.source())) || source.plan.mode()==Mode.DELETE)return;
            var poolKeys=new HashSet<String>();
            for(var ref:refs){check(cancelled);if(source.plan.source().equals(ref.resolved()) && "regions".equals(ResourceTypes.base(ref.path())) && ref.pointer().matches("/(landBiomes|seaBiomes|shoreBiomes)/[0-9]+"))poolKeys.add(ref.key());}
            for(var ref:categoricalBiomes){
                check(cancelled);if(!source.plan.source().equals(ref.resolved()) || poolKeys.isEmpty())continue;
                if(poolKeys.size()!=1 || !poolKeys.contains(ref.key()))issue(ref.path(),ref.pointer(),"Categorical biome role uses exact region-pool spelling. Collapsing distinct loader aliases could change its inferred role; operation blocked.");
            }
        }
        void exactKey(String path,JsonStructure.Snapshot document,JsonStructure.Node node,String pointer,String family){
            if(node.kind()!=JsonStructure.Kind.STRING){issue(path,pointer,"Exact resource-name comparison is not a plain string.");return;}
            if(!affected(family))return;
            String value=string(document,node);
            if(family.equals("objects")){
                if(value.isBlank())return;
                if(key(source.plan.source()).startsWith("procedural/") || source.plan.target()!=null && key(source.plan.target()).startsWith("procedural/")){
                    issue(path,pointer,"Native collision markers reserve procedural/ identities. This operation could change whether a placed object participates in collision comparisons; operation blocked.");return;
                }
            }
            String resolved=resolver.resolve(family,value);
            if(source.plan.source().equals(resolved) && !value.equals(key(source.plan.source())))issue(path,pointer,"Exact comparison uses a noncanonical loader alias; rewriting it could change string matching. Set an explicit canonical key before this operation.");
            int before=refs.size();reference(path,document,node,pointer,family,value,false);
            if(refs.size()>before)exactComparisons.add(refs.getLast());
        }
        void checkExactComparisons(){
            if(source.plan.mode()==Mode.DELETE || exactComparisons.isEmpty())return;
            String oldKey=key(source.plan.source());boolean alias=false;
            for(var ref:refs){check(cancelled);if(source.plan.source().equals(ref.resolved()) && !exactComparisons.contains(ref) && !oldKey.equals(ref.key())){alias=true;break;}}
            if(alias)for(var ref:exactComparisons){check(cancelled);if(source.plan.source().equals(ref.resolved()))issue(ref.path(),ref.pointer(),"Incoming loader aliases would collapse to one replacement key and change an exact resource-name comparison; operation blocked.");}
        }
        // Only the source and destination identities can collide with a rewritten deposit selector.
        // Store at most two collisions rather than retaining an unbounded derivative-ID catalog.
        final Set<String> depositDerivativeCollisions=new HashSet<>();
        final Set<Reference> depositComparisons=new HashSet<>();
        void depositDerivative(String value){
            if(value==null || value.isBlank())return;
            String normalized=value.trim();if(normalized.indexOf(':')<0)normalized="minecraft:"+normalized;
            if(normalized.equals("minecraft:"+key(source.plan.source())) || source.plan.target()!=null && normalized.equals("minecraft:"+key(source.plan.target())))depositDerivativeCollisions.add(normalized);
        }
        void deposit(String path,JsonStructure.Snapshot document,JsonStructure.Node node,String pointer){
            if(node.kind()!=JsonStructure.Kind.STRING){issue(path,pointer,"Deposit biome-or-derivative comparison is not a plain string.");return;}
            if(!affected("biomes"))return;
            String value=string(document,node);
            // The native comparison does not trim selectors. Namespaced/unsafe file spellings
            // cannot equal a certified resource load key, and retain their derivative meaning.
            if(value.isEmpty() || value.indexOf(':')>=0 || !value.equals(value.strip()) || !ResourceTypes.referenceKey(value) || !value.matches("[A-Za-z0-9_./-]+"))return;
            int before=refs.size();exactKey(path,document,node,pointer,"biomes");
            if(refs.size()>before)depositComparisons.add(refs.getLast());
        }
        void checkDepositComparisons(){
            if(source.plan.mode()==Mode.DELETE || depositComparisons.isEmpty())return;
            String replacement="minecraft:"+key(source.plan.target());
            for(var reference:depositComparisons){
                check(cancelled);if(!source.plan.source().equals(reference.resolved()))continue;
                if(depositDerivativeCollisions.contains("minecraft:"+reference.key()) || depositDerivativeCollisions.contains(replacement))issue(reference.path(),reference.pointer(),"Deposit selector also has an existing or replacement derivative identity; rewriting it could change biome filtering. Use an unambiguous selector before this operation.");
            }
        }
        final List<CaveComparison> caveComparisons=new ArrayList<>();
        void caveBiome(String path,JsonStructure.Snapshot document,JsonStructure.Node node,String pointer){
            if(node.kind()!=JsonStructure.Kind.STRING){issue(path,pointer,"Cave-biome comparison is not a plain string.");return;}
            if(!affected("biomes"))return;
            String value=string(document,node);if(value.isBlank())return;
            int first=0,last=value.length();while(first<last && value.charAt(first)<=0x20)first++;while(last>first && value.charAt(last-1)<=0x20)last--;
            String body=value.substring(first,last);if(body.isEmpty())return;
            int colon=body.lastIndexOf(':');String namespace=colon<0?"":body.substring(0,colon+1),rawKey=body.substring(colon+1);
            // Single-letter prefixes can be Windows drive-relative paths. Unusual namespaces
            // and Unicode case folding remain guarded rather than borrowing local-key meaning.
            if(!namespace.isEmpty() && !namespace.matches("[A-Za-z0-9_.-]{2,}:")){
                issue(path,pointer,"Cave-biome namespace is outside certified local comparison/lookup rules; Windows drive-like and compound prefixes remain guarded.");return;
            }
            if(!ResourceTypes.referenceKey(rawKey) || !rawKey.matches("[A-Za-z0-9_./-]+")){
                issue(path,pointer,"Cave-biome key is outside certified ASCII comparison/lookup rules.");return;
            }
            String normalized=rawKey.toLowerCase(Locale.ROOT);int before=refs.size();
            reference(path,document,node,pointer,"biomes",normalized,false,value.substring(0,first)+namespace,value.substring(last));
            if(refs.size()>before){
                if(!namespace.isEmpty() && value.startsWith("biomes:") && refs.getLast().replacement()!=null)issue(path,pointer,"The raw biomes: compatibility lookup passes the native folder filter without stripping its prefix. Its Windows file/stream lookup cannot be rewritten confidently; choose a canonical local key before this operation.");
                // Native content-gate reporting also loads the original untrimmed string.
                // All qualified forms not starting with the exact biomes: folder prefix are
                // rejected before any file lookup; changing their suffix preserves that rejection.
                // Literal biomes: rewrites remain guarded above. Unqualified raw lookups are
                // checked independently, including case-sensitive dotted fallback.
                String compatibilityKey=first==0 && last==value.length() && namespace.isEmpty()?rawKey:null;
                caveComparisons.add(new CaveComparison(refs.getLast(),compatibilityKey,compatibilityKey==null?null:resolver.resolve("biomes",compatibilityKey)));
            }
        }
        void checkCaveComparisons(Resolver nextResolver){
            if(caveComparisons.isEmpty())return;
            var actualKeys=new HashSet<String>();actualKeys.add(key(source.plan.source()).toLowerCase(Locale.ROOT));
            for(var ref:refs){check(cancelled);if(source.plan.source().equals(ref.resolved()))actualKeys.add(ref.key().trim().toLowerCase(Locale.ROOT));}
            for(var entry:caveComparisons){check(cancelled);if(source.plan.source().equals(entry.compatibilityResolved()))actualKeys.add(entry.compatibilityKey().toLowerCase(Locale.ROOT));}
            String replacement=source.plan.target()==null?null:key(source.plan.target()).toLowerCase(Locale.ROOT);
            for(var entry:caveComparisons){
                check(cancelled);var ref=entry.reference();boolean beforeMatches=actualKeys.contains(ref.key());
                if(source.plan.mode()==Mode.DELETE){
                    if(beforeMatches || source.plan.source().equals(entry.compatibilityResolved()))issue(ref.path(),ref.pointer(),"Resource is used by a cave-biome comparison or compatibility lookup; remove or redirect it before deleting.");
                    continue;
                }
                String after=ref.replacement()==null?ref.key():ref.replacement().toLowerCase(Locale.ROOT);
                boolean afterMatches=replacement.equals(after);
                if(afterMatches?(!beforeMatches || actualKeys.size()!=1):beforeMatches)issue(ref.path(),ref.pointer(),"Operation would change cave-biome matching for a canonical or requested loader key; dotted/unequal aliases and newly captured comparisons remain blocked.");
                if(entry.compatibilityKey()!=null){
                    String expected=source.plan.source().equals(entry.compatibilityResolved())?source.plan.target():entry.compatibilityResolved();
                    String next=nextResolver.resolve("biomes",ref.replacement()==null?entry.compatibilityKey():ref.replacement());
                    if(!Objects.equals(next,expected))issue(ref.path(),ref.pointer(),"Operation would change the original cave-biome compatibility lookup, including case-sensitive dotted fallback or a missing name.");
                }
            }
        }
        void dualCarving(String path,JsonStructure.Snapshot document,JsonStructure.Node node,String pointer){
            if(node.kind()!=JsonStructure.Kind.STRING){issue(path,pointer,"Carving entry-or-biome reference is not a plain string.");return;}
            if(!affected("biomes") || incompleteCarvingIds)return;
            String value=string(document,node);if(value.isBlank() || value.equals("null"))return; // This fallback uses the standard warning loader, not a quiet lookup.
            boolean incoming=source.plan.source().equals(resolver.resolve("biomes",value));
            if(incoming && source.plan.mode()!=Mode.DELETE){
                if(carvingIds.contains(value))issue(path,pointer,"Carving value can name a dimension entry as well as this biome. A global rename/move cannot preserve both contexts; operation blocked.");
                if(carvingIds.contains(key(source.plan.target())))issue(path,pointer,"Replacement biome key is a carving-entry ID in a known dimension/snippet context; entry lookup would take precedence over biome lookup.");
            }
            reference(path,document,node,pointer,"biomes",value,false);
        }
        void checkReservedKeys(Resolver next){
            for(var ref:reservedKeys){
                check(cancelled);String before=resolver.resolve(ref.family(),"null",true),after=next.resolve(ref.family(),"null",true);
                if(source.plan.source().equals(before) || !Objects.equals(before,after))issue(ref.path(),ref.pointer(),"Literal null may be accepted by a quiet native lookup. Its file resolution would be affected; operation blocked until this lookup mode is certified.");
            }
        }
        void snippet(String path,JsonStructure.Snapshot document,JsonStructure.Node node,String model,String pointer){
            if(++snippetLinks>MAX_SNIPPET_LINKS){issue(path,pointer,"Complete snippet reference scan exceeds 16,384 links.");return;}
            String kind=SCHEMA.getProperty(model+".snippet"),value=string(document,node);
            if(kind==null || !value.startsWith("snippet/")){issue(path,pointer,"Typed object string is not a certified Iris snippet reference.");return;}
            String base="snippet/"+kind+"/",relative=value.startsWith(base)?value:base+value.substring(8);
            if(!ResourceTypes.referenceKey(relative) || !relative.matches("[A-Za-z0-9_./-]+")){issue(path,pointer,"Snippet path is outside certified relative ASCII Windows resolution.");return;}
            // Iris opens the exact JSON file, with class-specific shorthand and no dotted-prefix loader fallback.
            String resolved=resolver.exactPath(relative+".json");var payload=resolved==null?null:documents.get(resolved);
            if(payload==null){issue(path,pointer,"Snippet payload is missing, malformed, unsupported or outside the complete scan: "+InspectorText.label(relative,256)+".json");return;}
            if(!model.equals(ownerModel(resolved))){issue(path,pointer,"Snippet payload kind does not match the expected model.");return;}
            if(payload.node(0).kind()!=JsonStructure.Kind.OBJECT && payload.node(0).kind()!=JsonStructure.Kind.NULL){issue(path,pointer,"Snippet payload must be an object or null; the native delegate does not follow root string aliases.");return;}
            snippetEdges.computeIfAbsent(path,p->new TreeSet<>()).add(resolved);
        }
        void checkSnippetGraph(){
            var indegrees=new TreeMap<String,Integer>();
            for(var row:snippetEdges.entrySet()){
                check(cancelled);indegrees.putIfAbsent(row.getKey(),0);
                for(String next:row.getValue())indegrees.merge(next,1,Integer::sum);
            }
            var queue=new ArrayDeque<String>();var depths=new HashMap<String,Integer>();
            for(var row:indegrees.entrySet())if(row.getValue()==0){queue.add(row.getKey());depths.put(row.getKey(),0);}
            int visited=0;boolean depthReported=false;
            while(!queue.isEmpty()){
                check(cancelled);String current=queue.remove();visited++;
                for(String next:snippetEdges.getOrDefault(current,Set.of())){
                    int depth=depths.merge(next,depths.get(current)+1,Math::max);
                    if(depth>MAX_SNIPPET_DEPTH && !depthReported){issue(next,"","Snippet expansion exceeds the certified 32-link depth budget.");depthReported=true;}
                    if(indegrees.merge(next,-1,Integer::sum)==0)queue.add(next);
                }
            }
            if(visited!=indegrees.size())for(var row:indegrees.entrySet())if(row.getValue()>0){issue(row.getKey(),"","Cyclic snippet expansion prevents a complete, finite typed review.");break;}
        }
        void node(String path,JsonStructure.Snapshot document,int id,String type,String pointer){
            check(cancelled);var node=document.node(id);
            if(type.startsWith("fallback-ref:")){
                String[] policy=type.substring(13).split("\\|",-1);
                if(node.kind()==JsonStructure.Kind.NULL || (node.kind()==JsonStructure.Kind.STRING && string(document,node).isBlank())){
                    reference(path,null,null,pointer,policy[0],policy[1],true);return;
                }
                type="ref:"+policy[0];
            }
            if(node.kind()==JsonStructure.Kind.NULL)return;
            if(type.startsWith("list:")){
                if(node.kind()!=JsonStructure.Kind.ARRAY){issue(path,pointer,"Typed array is a snippet or unsupported value; reference coverage is unresolved.");return;}
                for(int child:node.children())node(path,document,child,type.substring(5),pointer+"/"+document.node(child).segment());return;
            }
            if(type.startsWith("map:")){
                if(node.kind()!=JsonStructure.Kind.OBJECT){issue(path,pointer,"Typed map is not an object; reference coverage is unresolved.");return;}
                for(int child:node.children())node(path,document,child,type.substring(4),pointer+"/"+document.node(child).segment().replace("~","~0").replace("/","~1"));return;
            }
            if(type.startsWith("object:")){
                String model=type.substring(7);
                if(node.kind()==JsonStructure.Kind.STRING){snippet(path,document,node,model,pointer);return;}
                if(node.kind()!=JsonStructure.Kind.OBJECT){issue(path,pointer,"Typed object has an unsupported value; reference coverage is unresolved.");return;}
                var present=new HashSet<String>();
                for(int child:node.children()){
                    var member=document.node(child);String field=member.segment(),next=pointer+"/"+field.replace("~","~0").replace("/","~1");present.add(field);
                    String descriptor=MODEL_FIELDS.getOrDefault(model,Set.of()).contains(field)?SCHEMA.getProperty(model+"."+field):null;
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
                }else categorical(path,document,node,pointer,families);
                return;
            }
            if(type.startsWith("exact-key:")){exactKey(path,document,node,pointer,type.substring(10));return;}
            if(type.equals("dual-carving:biomes")){dualCarving(path,document,node,pointer);return;}
            if(type.equals("dual-deposit:biomes")){deposit(path,document,node,pointer);return;}
            if(type.equals("cave-match:biomes")){caveBiome(path,document,node,pointer);return;}
            if(type.startsWith("guard:")){
                if(node.kind()!=JsonStructure.Kind.STRING)issue(path,pointer,"Runtime-comparison reference is not a plain string.");
                else if(affected(type.substring(6)) && !string(document,node).isBlank())issue(path,pointer,"Runtime comparison, prefix or dual-domain reference is not certified for this resource family; operation blocked.");
                return;
            }
            if(type.startsWith("sentinel-ref:") || type.startsWith("reserved-ref:")){
                if(node.kind()!=JsonStructure.Kind.STRING){issue(path,pointer,"Resource reference is not a plain string.");return;}
                String[] policy=type.substring(type.indexOf(':')+1).split("\\|",-1);String value=string(document,node);
                if(value.equalsIgnoreCase(policy[1])){
                    if(type.startsWith("reserved-ref:") && affected(policy[0]))issue(path,pointer,"Reserved dimension sentinel is not a loadable stack reference.");
                    return;
                }
                if(source.plan.mode()!=Mode.DELETE && source.plan.source().equals(resolver.resolve(policy[0],value)) && key(source.plan.target()).equalsIgnoreCase(policy[1]))issue(path,pointer,"Replacement would become an inactive or reserved dimension sentinel.");
                reference(path,document,node,pointer,policy[0],value,false);return;
            }
            if(type.startsWith("trimmed-ref:")){
                if(node.kind()!=JsonStructure.Kind.STRING){issue(path,pointer,"Resource reference is not a plain string.");return;}
                String value=string(document,node),normalized=value.trim();int first=0,last=value.length();while(first<last && value.charAt(first)<=32)first++;while(last>first && value.charAt(last-1)<=32)last--;
                reference(path,document,node,pointer,type.substring(12),normalized,false,value.substring(0,first),value.substring(last));return;
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
        String exactPath(String path){return exact.get(path.toLowerCase(Locale.ROOT));}
        String resolve(String family,String key){return resolve(family,key,family.equals("blocks"));}
        String resolve(String family,String key,boolean quiet){if(key==null || (!quiet && key.equals("null")) || key.trim().isEmpty())return null;String extension=extension(family),wanted=family+"/"+key+extension;String path=exact.get(wanted.toLowerCase(Locale.ROOT));return path!=null?path:key.contains("/")?null:fallback.get(wanted);}
    }
    static String resolve(Set<String> paths,String family,String key){return new Resolver(paths,()->false).resolve(family,key);}
    static String key(String path){String family=ResourceTypes.fileFamily(path);return path.substring(family.length()+1,path.length()-ResourceTypes.extension(path).length());}
    static Prepared prepare(Source source,BooleanSupplier cancelled)throws Exception{
        Objects.requireNonNull(source);Objects.requireNonNull(cancelled);check(cancelled);var scan=new Scan(source,cancelled);
        if(source.plan.changes()){
            if(!targetFamily(ResourceTypes.fileFamily(source.plan.source())))scan.issue(source.plan.source(),"","This resource family has no certified mutation/reference policy yet.");
            for(String path:source.paths)if(ResourceTypes.fileFamily(path)!=null && !path.matches("[A-Za-z0-9_./-]+"))scan.issue(path,"","Resource path is outside certified ASCII Windows resource-key resolution.");
            for(var row:source.json.entrySet()){
                check(cancelled);String path=row.getKey(),descriptor=ownerDescriptor(path);
                if(descriptor==null){scan.issue(path,"","This JSON reference owner is outside the closed typed operation schema; operation blocked.");continue;}
                var document=JsonStructure.inspect(copy(row.getValue(),cancelled));scan.inspected++;if(!document.indexed() || document.status()!=JsonStructure.Status.READY){scan.issue(path,"","Malformed, duplicate-key or node/depth-limited JSON prevents a complete typed reference scan.");continue;}
                if(SCHEMA.getProperty("metadata."+path)!=null){
                    boolean valid=document.node(0).kind()==JsonStructure.Kind.ARRAY;
                    if(valid)for(int child:document.node(0).children()){check(cancelled);if(document.node(child).kind()!=JsonStructure.Kind.STRING){valid=false;break;}}
                    if(!valid){scan.issue(path,"","Known field-order metadata must be an array of strings; other root payloads remain uncertified.");continue;}
                }
                if(path.startsWith("snippet/") && document.node(0).kind()!=JsonStructure.Kind.OBJECT && document.node(0).kind()!=JsonStructure.Kind.NULL){scan.issue(path,"","Snippet payload root must be an object or null, not another indirection or container shape.");continue;}
                scan.documents.put(path,document);
            }
            for(var row:scan.documents.entrySet())scan.collectContexts(row.getKey(),row.getValue(),0,ownerDescriptor(row.getKey()));
            for(var row:scan.documents.entrySet()){
                check(cancelled);scan.node(row.getKey(),row.getValue(),0,ownerDescriptor(row.getKey()),"");
            }
            scan.checkSnippetGraph();scan.checkCategoricalComparisons();scan.checkExactComparisons();scan.checkDepositComparisons();
            var resulting=new TreeSet<>(source.paths);resulting.remove(source.plan.source());if(source.plan.target()!=null)resulting.add(source.plan.target());
            var nextResolver=new Resolver(resulting,cancelled);scan.checkReservedKeys(nextResolver);scan.checkCaveComparisons(nextResolver);if(source.plan.mode()!=Mode.DELETE)for(var reference:scan.refs){String expected=Objects.equals(reference.resolved(),source.plan.source())?source.plan.target():reference.resolved();String next=nextResolver.resolve(reference.family(),reference.replacement()==null?reference.key():reference.replacement());if(!Objects.equals(next,expected))scan.issue(reference.path(),reference.pointer(),"Operation would change another reference's resolution, including an unresolved or dotted-prefix alias.");}
        }
        var replacements=new TreeMap<String,byte[]>();if(scan.issues.isEmpty() && scan.omitted==0)for(var row:scan.rewrites.entrySet()){
            check(cancelled);var document=scan.documents.get(row.getKey());var text=new StringBuilder(document.source());var spans=new ArrayList<>(row.getValue());spans.sort(Comparator.comparingInt((Rewrite rewrite)->rewrite.node().start()).reversed());for(var rewrite:spans)text.replace(rewrite.node().start(),rewrite.node().end(),LosslessJson.quote(rewrite.value()));byte[] bytes=text.toString().getBytes(StandardCharsets.UTF_8);if(bytes.length>MAX_JSON_BYTES){scan.issue(row.getKey(),"","Rewritten JSON would exceed the 1 MB supported-source limit.");break;}new LosslessJson(bytes);replacements.put(row.getKey(),bytes);
        }
        check(cancelled);if(!scan.issues.isEmpty() || scan.omitted!=0)replacements.clear();var report=new Report(source.plan,scan.inspected,scan.refs,scan.issues,scan.omitted,replacements.size());return new Prepared(source,report,replacements);
    }
    private ResourceOperation(){}
}
