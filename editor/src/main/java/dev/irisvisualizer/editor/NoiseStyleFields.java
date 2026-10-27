package dev.irisvisualizer.editor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;

/** Bounded controls on source-audited style objects; no factories or noise algorithms run. */
public final class NoiseStyleFields {
    public static final List<String> EDITABLE = List.of("style", "zoom", "exponent", "cellularFrequency", "cellularZoom");
    public static final List<String> OBJECT_FIELDS = List.of("style");
    public static final List<String> DIMENSION_FIELDS = List.of("regionStyle", "continentalStyle", "landBiomeStyle", "shoreBiomeStyle", "seaBiomeStyle", "caveBiomeStyle");
    public static final List<String> BIOME_FIELDS = List.of("childStyle");
    public static final List<String> TERRAIN_FIELDS = List.of("densityStyle", "crackStyle");
    public static final List<String> CAVE_FIELDS = List.of("baseDensityStyle", "detailDensityStyle", "warpStyle", "surfaceBreakStyle");
    public record Target(String resourceBase, String objectField, NoiseFields.Target noise, int moduleIndex) {
        public Target(String resourceBase, String objectField, NoiseFields.Target noise) { this(resourceBase, objectField, noise, -1); }
        public Target {
            if (resourceBase == null || objectField == null) throw new IllegalArgumentException("Unsupported style target");
            boolean valid = noise != null ? "generators".equals(resourceBase) && "style".equals(objectField)
                    : "dimensions".equals(resourceBase) && DIMENSION_FIELDS.contains(objectField)
                    || "biomes".equals(resourceBase) && (BIOME_FIELDS.contains(objectField)
                    || objectField.startsWith("terrain3D.") && TERRAIN_FIELDS.contains(objectField.substring("terrain3D.".length())));
            valid = valid || noise==null && CaveProfileFields.CONTEXTS.contains(resourceBase) && objectField.startsWith("caveProfile.")
                    && (CAVE_FIELDS.contains(objectField.substring("caveProfile.".length())) || objectField.equals("caveProfile.densityThreshold.style"));
            valid = valid || noise==null && "dimensions".equals(resourceBase) && objectField.equals("hydrology.rivers.geometry.banks3D.densityStyle");
            valid = valid || noise==null && "dimensions".equals(resourceBase) && (objectField.equals("hydrology.rivers.surface.channel.width.style") || objectField.equals("hydrology.rivers.surface.channel.depth.style"));
            valid = valid || noise==null && "dimensions".equals(resourceBase) && List.of("hydrology.rivers.underground.fluidLevel.style", "hydrology.rivers.underground.channelWidth.style", "hydrology.rivers.underground.depth.style", "hydrology.rivers.underground.headroom.style").contains(objectField);
            valid = moduleIndex == -1 ? valid : moduleIndex >= 0 && noise == null && CaveProfileFields.CONTEXTS.contains(resourceBase) && objectField.equals("caveProfile.modules.style");
            if (!valid) throw new IllegalArgumentException("Unsupported style target");
        }
        public static Target noise(NoiseFields.Target target) { return new Target("generators", "style", java.util.Objects.requireNonNull(target)); }
        public static Target dimension(String field) { return new Target("dimensions", field, null); }
        public static Target biome(String field) { return new Target("biomes", field, null); }
        public static Target terrain(String field) { return new Target("biomes","terrain3D."+field,null); }
        public static Target cave(String context, String field) {
            if (!CAVE_FIELDS.contains(field)) throw new IllegalArgumentException("Unsupported cave style");
            return new Target(context,"caveProfile."+field,null);
        }
        public static Target threshold(String context) { return new Target(context,"caveProfile.densityThreshold.style",null); }
        public static Target module(String context, int index) {
            if (index < 0) throw new IllegalArgumentException("Invalid cave module index");
            return new Target(context,"caveProfile.modules.style",null,index);
        }
        public static Target bank() { return new Target("dimensions","hydrology.rivers.geometry.banks3D.densityStyle",null); }
        public static Target channelRange(String field) {
            if (!RiverChannelRanges.OBJECT_FIELDS.contains(field)) throw new IllegalArgumentException("Unsupported channel range style");
            return new Target("dimensions","hydrology.rivers.surface.channel."+field+".style",null);
        }
        public static Target undergroundRange(String field) {
            if (!RiverChannelRanges.UNDERGROUND_FIELDS.contains(field)) throw new IllegalArgumentException("Unsupported underground range style");
            return new Target("dimensions","hydrology.rivers.underground."+field+".style",null);
        }
        public boolean undergroundRange() { return List.of("hydrology.rivers.underground.fluidLevel.style", "hydrology.rivers.underground.channelWidth.style", "hydrology.rivers.underground.depth.style", "hydrology.rivers.underground.headroom.style").contains(objectField); }
        public boolean riverRange() { return channelRange() || undergroundRange(); }
        public boolean channelRange() { return objectField.equals("hydrology.rivers.surface.channel.width.style") || objectField.equals("hydrology.rivers.surface.channel.depth.style"); }
        public String rangeField() { if (undergroundRange()) return "underground."+objectField.substring("hydrology.rivers.underground.".length(),objectField.length()-".style".length()); if (!channelRange()) throw new IllegalArgumentException("Not a channel range style"); return objectField.substring("hydrology.rivers.surface.channel.".length(),objectField.length()-".style".length()); }
        public boolean banks() { return objectField.equals("hydrology.rivers.geometry.banks3D.densityStyle"); }
        public boolean strictBounds() { return terrain() || banks(); }
        public boolean terrain() { return objectField.startsWith("terrain3D."); }
        public boolean cave() { return objectField.startsWith("caveProfile."); }
        public boolean threshold() { return objectField.equals("caveProfile.densityThreshold.style"); }
        public boolean module() { return moduleIndex >= 0; }
        public String member() { return riverRange() ? "style" : banks() ? "densityStyle" : module() || threshold() ? "style" : terrain() ? objectField.substring("terrain3D.".length()) : cave() ? objectField.substring("caveProfile.".length()) : objectField; }
        public String draftPrefix() { return riverRange() ? RiverChannelRanges.draftPrefix(rangeField())+"style." : banks() ? RiverBankFields.draftPrefix()+"densityStyle." : module() ? CaveModules.draftPrefix(resourceBase,moduleIndex)+"style." : threshold() ? CaveThresholdFields.draftPrefix(resourceBase)+"style." : cave() ? "caveProfile#"+resourceBase+"."+member()+"." : terrain() ? "terrain3D#"+member()+"." : noise == null ? "rootStyle#"+resourceBase+"."+objectField+"." : noise.draftPrefix()+"style."; }
    }
    private static final Properties DEFINITIONS = new Properties();
    private static final List<String> CHOICES;
    static {
        try (var input = NoiseStyleFields.class.getResourceAsStream("/style-fields.properties")) {
            if (input == null) throw new IOException("Missing pinned style definitions");
            DEFINITIONS.load(input);
            CHOICES = List.of(DEFINITIONS.getProperty("choices", "").split(","));
            if (CHOICES.stream().anyMatch(value -> !value.matches("[A-Z][A-Z0-9_]*"))
                    || CHOICES.stream().distinct().count() != CHOICES.size()
                    || !CHOICES.contains(noiseDefault()) || !CHOICES.contains(objectDefault()))
                throw new IOException("Invalid pinned noise style choices");
            for (String group : List.of("dimensions", "biomes"))
                for (String field : group.equals("dimensions") ? DIMENSION_FIELDS : BIOME_FIELDS)
                    if (!CHOICES.contains(DEFINITIONS.getProperty(group+"."+field+".default", "")))
                        throw new IOException("Missing pinned contextual style default");
            if (!"finiteNonnegative".equals(DEFINITIONS.getProperty("cellularFrequency.runtimeRule"))
                    || !"positiveFiniteReciprocalWhenActive".equals(DEFINITIONS.getProperty("cellularZoom.runtimeRule")))
                throw new IOException("Missing pinned cellular validation rules");
            if (!CHOICES.contains(RiverBankFields.styleDefault())) throw new IOException("Missing pinned bank style default");
            for (String field : TERRAIN_FIELDS)
                if (!CHOICES.contains(Terrain3DFields.styleDefault(field))) throw new IOException("Missing pinned terrain style default");
            for (String field : CAVE_FIELDS) {
                String value=CaveProfileFields.styleDefault(field),override=CaveProfileFields.styleZoomOverride(field);
                if (value==null || !CHOICES.contains(value)) throw new IOException("Missing pinned cave style default");
                if (override!=null) {
                    double zoom=Double.parseDouble(override);
                    if (!Double.isFinite(zoom) || zoom<Double.parseDouble(DEFINITIONS.getProperty("zoom.minimum"))) throw new IOException("Invalid pinned cave zoom override");
                }
            }
            if (!CHOICES.contains(CaveThresholdFields.defaultStyle(false)) || !CHOICES.contains(CaveThresholdFields.defaultStyle(true)))
                throw new IOException("Invalid pinned cave threshold styles");
            if (!CHOICES.contains(RiverChannelRanges.classStyle()) || RiverChannelRanges.rangeKeys().stream().anyMatch(field -> !CHOICES.contains(RiverChannelRanges.contextStyle(field)))) throw new IOException("Invalid pinned channel range styles");
            if (!CHOICES.contains(CaveModules.styleDefault())) throw new IOException("Invalid pinned cave module style");
        } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    public static List<String> choices() { return CHOICES; }
    public static String noiseDefault() { return DEFINITIONS.getProperty("noiseDefault"); }
    public static String objectDefault() { return DEFINITIONS.getProperty("objectDefault"); }
    public static String contextDefault(Target target) { return target.riverRange() ? RiverChannelRanges.classStyle() : target.banks() ? RiverBankFields.styleDefault() : target.module() ? CaveModules.styleDefault() : target.threshold() ? CaveThresholdFields.defaultStyle(false) : target.cave() ? CaveProfileFields.styleDefault(target.member()) : target.terrain() ? Terrain3DFields.styleDefault(target.member()) : target.noise() == null ? DEFINITIONS.getProperty(target.resourceBase()+"."+target.objectField()+".default") : noiseDefault(); }
    public static String contextDescription(Target target) { return target.riverRange() ? RiverChannelRanges.styleDescription() : target.banks() ? RiverBankFields.description("densityStyle") : target.module() ? CaveModules.help("style") : target.threshold() ? CaveThresholdFields.styleDescription() : target.cave() ? CaveProfileFields.help(target.member()) : target.terrain() ? Terrain3DFields.description(target.member()) : target.noise() == null ? DEFINITIONS.getProperty(target.resourceBase()+"."+target.objectField()+".description", "") : description("style"); }
    public static String contextDefaultLabel(Target target) {
        String override=target.cave() && !target.threshold() && !target.module() ? CaveProfileFields.styleZoomOverride(target.member()) : null;
        return contextDefault(target)+(override==null ? "" : ", zoom "+override);
    }
    public static String description(String field) { return DEFINITIONS.getProperty(field + ".description", ""); }
    public static String constraints(String field) {
        String min = DEFINITIONS.getProperty(field + ".minimum"), max = DEFINITIONS.getProperty(field + ".maximum");
        return "Finite number" + (min == null ? "" : ", minimum " + min) + (max == null ? "" : ", maximum " + max)
                + (field.equals("cellularZoom") ? "; positive zoom required while cellular frequency > 0" : "");
    }
    public static String constraints(Target target, String field) {
        if (!target.strictBounds()) return constraints(field);
        String maximum=bound(target,field,"maximum");
        return "Finite number, minimum "+bound(target,field,"minimum")
                + (Double.parseDouble(maximum)==Double.MAX_VALUE ? "" : ", maximum "+maximum);
    }
    private static String bound(Target target, String field, String kind) {
        return target.banks() ? RiverBankFields.styleBound(field,kind) : target.terrain() ? Terrain3DFields.styleBound(field,kind) : DEFINITIONS.getProperty(field+"."+kind);
    }
    public static String defaultToken(String field) {
        if (!EDITABLE.contains(field) || field.equals("style")) throw new IllegalArgumentException("Not a style scalar");
        return new java.math.BigDecimal(DEFINITIONS.getProperty(field + ".default")).stripTrailingZeros().toPlainString();
    }
    private static void authorize(String path, Target target, String field) {
        if (!target.resourceBase().equals(ResourceTypes.base(path)) || !EDITABLE.contains(field)) throw new IllegalArgumentException("Style field is read-only");
    }
    public static byte[] enable(String path, byte[] original, NoiseFields.Target target, String field) {
        return enable(path, original, Target.noise(target), field);
    }
    public static byte[] enable(String path, byte[] original, Target target, String field) {
        authorize(path, target, field);
        return editContainer(original, target, container -> {
            if (container.token(target.member()) == null) {
                byte[] seeded = container.enableStringPath(target.member()+".style", contextDefault(target));
                String override=target.cave() && !target.threshold() && !target.module() ? CaveProfileFields.styleZoomOverride(target.member()) : null;
                if (override!=null) seeded=new LosslessJson(seeded).enablePath(target.member()+".zoom",override);
                if (field.equals("style") || new LosslessJson(seeded).tokenPath(target.member()+"."+field)!=null) return seeded;
                container = new LosslessJson(seeded);
            }
            return container.objectEdit(target.member(), style -> {
                if (field.equals("style")) return style.enableStringPath("style",objectDefault());
                String token = defaultToken(field);
                validateNumber(target,field,Double.parseDouble(token));
                validateCellularChange(style,target,field,Double.parseDouble(token));
                return style.enable(field,token);
            }, false);
        }, true);
    }
    public static byte[] editStyle(String path, byte[] original, NoiseFields.Target target, String value) {
        return editStyle(path, original, Target.noise(target), value);
    }
    public static byte[] editStyle(String path, byte[] original, Target target, String value) {
        authorize(path, target, "style");
        if (value == null || !CHOICES.contains(value)) throw new IllegalArgumentException("Choose a pinned noise style");
        return editContainer(original, target, container -> container.objectEdit(target.member(), style -> style.stringValue("style", value), false), false);
    }
    public static byte[] editText(String path, byte[] original, NoiseFields.Target target, String field, String input) {
        return editText(path, original, Target.noise(target), field, input);
    }
    public static byte[] editText(String path, byte[] original, Target target, String field, String input) {
        authorize(path, target, field);
        if (field.equals("style")) throw new IllegalArgumentException("Use the noise style picker");
        double value = Double.parseDouble(input);
        validateNumber(target,field,value);
        return editContainer(original, target, container -> container.objectEdit(target.member(), style -> {
            validateCellularChange(style,target,field,value);
            return style.number(field,value);
        }, false), false);
    }
    private static void validateNumber(Target target, String field, double value) {
        String min=bound(target,field,"minimum"),max=bound(target,field,"maximum");
        if (!Double.isFinite(value) || (min != null && value < Double.parseDouble(min)) || (max != null && value > Double.parseDouble(max)))
            throw new IllegalArgumentException("Value violates pinned Iris style constraints");
    }
    private static double numericOrDefault(LosslessJson style, String field) {
        String token = style.token(field);
        try { return Double.parseDouble(token == null ? defaultToken(field) : token); }
        catch (NumberFormatException mismatch) { throw new IllegalArgumentException("Stored "+field+" must be numeric",mismatch); }
    }
    private static void validateCellularChange(LosslessJson style, Target target, String field, double value) {
        if (!field.equals("cellularFrequency") && !field.equals("cellularZoom")) return;
        double frequency = field.equals("cellularFrequency") ? value : numericOrDefault(style,"cellularFrequency");
        if (!Double.isFinite(frequency) || frequency < 0)
            throw new IllegalArgumentException("Cellular frequency must be finite and nonnegative");
        if (frequency > 0) {
            double zoom = field.equals("cellularZoom") ? value : numericOrDefault(style,"cellularZoom");
            if (!Double.isFinite(zoom) || zoom <= 0 || !Double.isFinite(1D/zoom))
                throw new IllegalArgumentException("Cellular zoom must be positive and large enough for a finite reciprocal while frequency > 0");
            if (target.strictBounds()) validateNumber(target,"cellularZoom",zoom);
        }
    }
    private static byte[] editContainer(byte[] original, Target target, java.util.function.Function<LosslessJson, byte[]> operation, boolean create) {
        if (target.riverRange()) return RiverChannelRanges.editRange(original,target.rangeField(),operation);
        if (target.banks()) return RiverBankFields.editObject(original,operation);
        if (target.module()) return CaveModules.editObject(original,target.moduleIndex(),operation);
        if (target.threshold()) return new LosslessJson(original).objectEdit("caveProfile", profile -> profile.objectEdit("densityThreshold",operation,false),false);
        if (target.cave()) return new LosslessJson(original).objectEdit("caveProfile",operation,false);
        if (target.terrain()) return new LosslessJson(original).objectEdit("terrain3D",operation,false);
        return target.noise() == null ? operation.apply(new LosslessJson(original)) : NoiseFields.editObject(original, target.noise(), operation, create);
    }
    static LosslessJson object(LosslessJson noise) {
        return objectAt(noise, "style");
    }
    static LosslessJson object(LosslessJson container, Target target) {
        return objectAt(container, target.member());
    }
    private static LosslessJson objectAt(LosslessJson container, String field) {
        String token = container.token(field);
        return token == null ? null : new LosslessJson(token.getBytes(StandardCharsets.UTF_8));
    }
}
