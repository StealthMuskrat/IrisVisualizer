package dev.irisvisualizer.editor;

import java.util.*;

/** UI-only preview choices. Never loads a model, starts generation, or installs overrides in a pack. */
final class PreviewSession {
    enum Mode {TWO_D,THREE_D}
    enum Kind {
        DIMENSION("Dimension","Terrain / biome map","Generated world",true,true,true),
        REGION("Region","Biome distribution","Region terrain",true,true,true),
        BIOME("Biome","Terrain heightmap","Biome terrain",true,true,true),
        GENERATOR("Generator","Noise map","Terrain shape",true,true,true),
        EXPRESSION("Expression","Noise map","3D noise",true,true,true),
        STRUCTURE("Structure",null,"Structure",false,true,true),
        OBJECT("Object",null,"Original object",false,true,false),
        IMAGE("Image","Original image",null,true,false,false),
        OTHER("Resource",null,null,false,false,false);
        final String label,twoD,threeD;final boolean has2D,has3D,context;
        Kind(String label,String twoD,String threeD,boolean has2D,boolean has3D,boolean context){this.label=label;this.twoD=twoD;this.threeD=threeD;this.has2D=has2D;this.has3D=has3D;this.context=context;}
        boolean meaningful(Mode mode){return mode==Mode.TWO_D?has2D:has3D;}
        String title(Mode mode){return mode==Mode.TWO_D?twoD:threeD;}
    }
    record Coordinates(long seed,int x,int z){}
    record Identity(String resourceType,String resourceKey,String sourcePath){}
    record View(String path,Kind kind,Mode mode,String dimension,Coordinates coordinates,PreviewFraming framing,PreviewIsolation.Placement placement,ReferenceChoices.Catalog dimensions,String problem){
        boolean meaningful(Mode candidate){return kind.meaningful(candidate);}
        boolean image(){return kind==Kind.IMAGE && mode==Mode.TWO_D;}
        boolean contextual(){return kind.context;}
        boolean isolationAvailable(){return kind==Kind.BIOME || kind==Kind.REGION;}
        PreviewIsolation isolationRequest(){return isolationAvailable() && dimension!=null?PreviewIsolation.request(placement,dimension,identity()):null;}
        String title(){return isolationAvailable() && placement==PreviewIsolation.Placement.DIMENSION_SETTINGS?Kind.DIMENSION.title(mode):kind.title(mode);}
        boolean framingAvailable(){return contextual() && kind!=Kind.STRUCTURE && meaningful(mode);}
        String framingProblem(){return !framingAvailable() || dimension==null?null:mode==Mode.TWO_D?framing.mapProblem(coordinates.x(),coordinates.z()):framing.terrainProblem(coordinates.x(),coordinates.z());}
        Identity identity(){
            String family=kind==Kind.OTHER?null:ResourceTypes.fileFamily(path);
            return family==null?null:new Identity(family,path.substring(family.length()+1,path.length()-ResourceTypes.extension(path).length()),path);
        }
        String unavailable(){if(kind.context && problem!=null)return problem;if(!kind.meaningful(mode))return "No preview is defined for this resource type.";if(kind.context && dimension==null)return "Choose a dimension context for this preview.";String geometry=framingProblem();return geometry!=null?geometry:"Generation and rendered previews are unavailable while native preview support is incomplete.";}
    }
    private static final Coordinates DEFAULTS=new Coordinates(0,0,0);
    private final int maximumFiles;private final long maximumCharacters;private String problem;private Set<String> dimensionPaths=Set.of();
    PreviewSession(){this(ReferenceChoices.MAX_KEYS,ReferenceChoices.MAX_CHARACTERS);}
    PreviewSession(int files,long characters){if(files<1 || files>ReferenceChoices.MAX_KEYS || characters<1 || characters>ReferenceChoices.MAX_CHARACTERS)throw new IllegalArgumentException("Invalid preview context metadata budget");maximumFiles=files;maximumCharacters=characters;}
    private PackWorkspace owner;private long resources=-1;private String selected,dimension;private Kind kind=Kind.OTHER;private Mode mode=Mode.TWO_D;
    private ReferenceChoices.Catalog dimensions=new ReferenceChoices.Catalog(List.of());private final Map<String,Coordinates> coordinates=new HashMap<>();
    private final Map<String,PreviewFraming> framings=new HashMap<>();
    private final Map<String,PreviewIsolation.Placement> placements=new HashMap<>();
    static Kind kind(String path){String family=ResourceTypes.fileFamily(path);if(family==null)return Kind.OTHER;return switch(family){case "dimensions"->Kind.DIMENSION;case "regions"->Kind.REGION;case "biomes"->Kind.BIOME;case "generators"->Kind.GENERATOR;case "expressions"->Kind.EXPRESSION;case "structures","jigsaw-pools","jigsaw-pieces"->Kind.STRUCTURE;case "objects"->Kind.OBJECT;case "images"->Kind.IMAGE;default->Kind.OTHER;};}
    View select(PackWorkspace current,String path){
        Objects.requireNonNull(current);if(owner!=current){clear();owner=current;}
        if(resources!=current.resourceRevision()){
            placements.keySet().retainAll(current.paths());
            var paths=new ArrayList<String>();long characters=0;problem=null;
            for(String candidate:current.paths())if(DimensionFields.dimension(candidate)){characters+=candidate.length();if(paths.size()>=maximumFiles || characters>maximumCharacters){problem="Dimension context catalog exceeds its file/path metadata budget; preview context is unavailable.";break;}paths.add(candidate);}
            if(problem!=null){dimensions=new ReferenceChoices.Catalog(List.of());dimensionPaths=Set.of();dimension=null;}
            else{dimensions=new ReferenceChoices.Catalog(paths);dimensionPaths=Set.copyOf(paths);coordinates.keySet().retainAll(dimensionPaths);framings.keySet().retainAll(dimensionPaths);if(dimension!=null && !dimensionPaths.contains(dimension))dimension=null;}resources=current.resourceRevision();
        }
        Kind next=path!=null && current.paths().contains(path)?kind(path):Kind.OTHER;if(!Objects.equals(selected,path) || kind!=next){selected=path;kind=next;mode=next.has2D?Mode.TWO_D:next.has3D?Mode.THREE_D:Mode.TWO_D;}
        if(next==Kind.DIMENSION && dimensionPaths.contains(path))dimension=path;
        else if(dimension==null && dimensions.keys().size()==1)dimension=dimensions.keys().getFirst();
        return view();
    }
    private View view(){return new View(selected,kind,mode,dimension,dimension==null?DEFAULTS:coordinates.getOrDefault(dimension,DEFAULTS),dimension==null?PreviewFraming.DEFAULT:framings.getOrDefault(dimension,PreviewFraming.DEFAULT),placements.getOrDefault(selected,kind==Kind.BIOME || kind==Kind.REGION?PreviewIsolation.Placement.ISOLATED:PreviewIsolation.Placement.DIMENSION_SETTINGS),dimensions,problem);}
    View mode(Mode next){Objects.requireNonNull(next);if(!kind.meaningful(next))throw new IllegalArgumentException("No preview in that mode for this resource");mode=next;return view();}
    View dimension(String selected){if(!kind.context)throw new IllegalArgumentException("This resource has no dimension preview context");if(kind==Kind.DIMENSION && !Objects.equals(selected,this.selected))throw new IllegalArgumentException("The selected dimension is its own context");if(selected==null || !dimensionPaths.contains(selected))throw new IllegalArgumentException("Choose an existing top-level dimension JSON");dimension=selected;return view();}
    View configure(String seed,String x,String z){
        if(!kind.context || dimension==null)throw new IllegalArgumentException("Choose a dimension context first");var next=new Coordinates(integer64(seed,"seed"),integer32(x,"X coordinate"),integer32(z,"Z coordinate"));coordinates.put(dimension,next);return view();
    }
    View placement(PreviewIsolation.Placement next){
        Objects.requireNonNull(next);if(!view().isolationAvailable() || dimension==null)throw new IllegalArgumentException("Choose a dimension and a biome or region preview first.");
        placements.put(selected,next);return view();
    }
    private void requireFraming(Mode required){
        if(dimension==null || !view().framingAvailable() || mode!=required)throw new IllegalArgumentException("Choose a dimension and an applicable "+(required==Mode.TWO_D?"2D map":"3D terrain/noise")+" preview first.");
    }
    View mapScale(String input){requireFraming(Mode.TWO_D);var next=view().framing().mapScale(input);framings.put(dimension,next);return view();}
    View terrainArea(String width,String depth){requireFraming(Mode.THREE_D);var next=view().framing().terrainArea(width,depth);framings.put(dimension,next);return view();}
    View resetFraming(){
        requireFraming(mode);var current=view().framing();framings.put(dimension,mode==Mode.TWO_D?current.resetMap():current.resetTerrain());return view();
    }
    /** Observe an already-applied file operation; this method cannot mutate or authorize pack data. */
    void resourceApplied(PackWorkspace current,String source,String target){
        if(owner!=current || source==null || Objects.equals(source,target))return;
        if(current.paths().contains(source) || target!=null && (!Objects.equals(ResourceTypes.fileFamily(source),ResourceTypes.fileFamily(target)) || !current.paths().contains(target)))return;
        var placement=placements.remove(source);if(target!=null && placement!=null)placements.put(target,placement);
        if(!DimensionFields.dimension(source))return;
        var location=coordinates.remove(source);var framing=framings.remove(source);
        if(target!=null){if(location!=null)coordinates.put(target,location);if(framing!=null)framings.put(target,framing);}
        if(Objects.equals(dimension,source))dimension=target;
        resources=-1;
    }
    private static String number(String value,String label){if(value==null)throw new IllegalArgumentException("Enter a whole "+label);String text=value.strip();if(text.isEmpty() || text.length()>20 || !text.matches("-?[0-9]+"))throw new IllegalArgumentException("Use a signed whole "+label);return text;}
    private static long integer64(String value,String label){try{return Long.parseLong(number(value,label));}catch(NumberFormatException invalid){throw new IllegalArgumentException("Use a signed 64-bit "+label);}}
    private static int integer32(String value,String label){long parsed=integer64(value,label);if(parsed<Integer.MIN_VALUE || parsed>Integer.MAX_VALUE)throw new IllegalArgumentException("Use a signed 32-bit "+label);return (int)parsed;}
    void clear(){owner=null;resources=-1;selected=dimension=null;kind=Kind.OTHER;mode=Mode.TWO_D;dimensions=new ReferenceChoices.Catalog(List.of());dimensionPaths=Set.of();problem=null;coordinates.clear();framings.clear();placements.clear();}
}
