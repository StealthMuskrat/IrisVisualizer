package dev.irisvisualizer.editor;

import java.util.Properties;

/** Deferred UI-stage contracts. No ImGui native context or generated preview is needed. */
final class NumericBoundsTests {
    private static void check(boolean value, String detail) {
        if (!value) throw new AssertionError(detail);
    }

    static void run() {
        var probability = NumericBounds.of("double", "0", "1");
        String precise = "0.123456789012345678901234567890";
        check(probability.slider() && !probability.integer(), "small decimal ranges use double sliders");
        check(probability.draft(precise).orElseThrow() == Double.parseDouble(precise), "drafts retain double precision");
        check(probability.draft("1e-1").orElseThrow() == 0.1, "valid JSON exponent drafts remain supported");
        for (String token : new String[]{null, "", "null", "true", "\"0.5\"", "NaN", "Infinity", "0x1p0", "01", ".5", "-0.1", "1.1", "1e999"})
            check(probability.draft(token).isEmpty(), "unsupported or out-of-range drafts never clamp: " + token);
        check(Double.doubleToRawLongBits(probability.draft("-0.0").orElseThrow()) == Double.doubleToRawLongBits(-0.0), "signed zero survives inspection");
        var integer = NumericBounds.of("Integer", "-360.0", "360");
        check(integer.slider() && integer.integer(), "native integer metadata selects an integer slider");
        check(integer.draft("3.0").orElseThrow() == 3 && integer.draft("3e1").orElseThrow() == 30, "exact integral spellings remain valid");
        for (String token : new String[]{"3.1", "2147483648", "9007199254740993", "-361", "361"})
            check(integer.draft(token).isEmpty(), "integer drafts require exact native width and bounds");
        for (String[] spec : new String[][]{{"long", "0", "1"}, {"String", "0", "1"}, {"double", null, "1"},
                {"double", "0", null}, {"double", "NaN", "1"}, {"double", "0", "Infinity"},
                {"double", "1", "1"}, {"double", "2", "1"}, {"double", "0", "1025"},
                {"double", "0", "1e-300"}, {"double", "1e20", "1e20"}, {"int", "0.5", "10"}})
            check(!NumericBounds.of(spec[0], spec[1], spec[2]).slider(), "wide, unknown and unusable ranges keep text entry");
        check(NumericBounds.of("double", "0", "1024").slider(), "small-range boundary is inclusive");
        var properties = new Properties();
        properties.setProperty("x.type", "double"); properties.setProperty("x.minimum", "0"); properties.setProperty("x.maximum", "1");
        check(NumericBounds.from(properties, "x").slider() && !NumericBounds.from(properties, "absent").slider(), "metadata lookup fails closed");
        check(DimensionFields.numericBounds("landChance").slider(), "dimension probability bounds are connected");
        check(!DimensionFields.numericBounds("terrainSamplingStep").slider(), "discrete sampling options retain their exact validation");
        check(NoiseFields.numericBounds("opacity").slider() && NoiseFields.numericBounds("octaves").integer(), "noise opacity and octave kinds remain distinct");
        check(!NoiseFields.numericBounds("seed").slider() && !Terrain3DFields.numericBounds("seed").slider(), "signed 64-bit seeds never enter sliders");
        check(Terrain3DFields.numericBounds("minimumSlope").slider(), "terrain limits use their existing pinned metadata");
        check(DimensionTypeFields.numericBounds("ambientLight").draft("-1").isEmpty(), "inheritance stays separate from an explicit numeric value");
        check(DimensionTypeFields.numericBounds("ambientLight").draft("0").isPresent(), "explicit zero does not acquire inheritance meaning");
        check(!DimensionTypeFields.numericBounds("fixedTime").slider(), "nullable exact time remains text");
        check(FloatingObjectRotations.numericBounds("interval").slider(), "angle intervals use audited degree bounds");
        properties.remove("x.type");
        check(!NumericBounds.from(properties, "x").slider() && NumericBounds.from(properties, "x", "int").integer(), "bounds-only contracts require their owner's explicit native type");
        check(FloatingPlacementEffects.numericBounds("boreExtendMinY").integer()
                && FloatingPlacementEffects.numericBounds("boreExtendMinY").draft("-1").isPresent(), "bounds-only bore integers preserve the native negative sentinel");
        check(FloatingPlacementConstraints.numericBounds("surfaceSupportBuffer").slider()
                && FloatingPlacementConstraints.numericBounds("surfaceSupportBuffer").draft("0.5").isEmpty(), "support controls retain exact integer semantics");
        check(FloatingPlacementConstraints.boundBounds("slopeCondition", "minimumSlope").slider()
                && !FloatingPlacementConstraints.boundBounds("clamp", "minimumHeight").slider(), "bounded slopes and wide world-height clamps remain distinct");
        check(DimensionPalettes.weightBounds().maximum() == 1000 && HydrologyFluidPalettes.weightBounds().maximum() == 64, "general and hydrology weights retain their distinct source limits");
        check(RiverMaterialFields.numericBounds("depth").maximum() == 8, "material depth does not borrow palette weight limits");
        check(StructureWorkcells.numericBounds("width").draft("-").isEmpty(), "an incomplete workcell draft remains correctable without integer parsing");
        check(StructureDefinitions.numericBounds("maxDepth").slider() && DimensionStacks.numericBounds("spacer").integer(), "structure and dimension-stack integer controls use pinned metadata");
        check(NativeJigsawOverrides.numericBounds("maxDepth").slider()
                && !NativeJigsawOverrides.numericBounds("maxDistanceVertical").slider(), "native jigsaw wide overrides stay exact fields");
        check(StaticObjects.numericBounds("rotation").slider() && StaticObjects.numericBounds("scale").slider()
                && !StaticObjects.numericBounds("position").slider(), "fixed rotations/scale do not change world-coordinate presentation");
        System.out.println("PASS: numeric presentation bounds, double precision, signed zero, exact integer width, malformed/out-of-range drafts, inheritance and signed64 text controls");
    }
}
