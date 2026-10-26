# Editor field coverage

Direct nonstatic/nontransient private fields in eight pinned source classes only. This is not full schema coverage. Unclassified or unimplemented fields remain read-only; this audit never gates Save/Export.

| Class | Declared | Classified | Implemented editable | Unclassified |
| --- | ---: | ---: | ---: | ---: |
| dimension | 82 | 18 | 12 | 64 |
| importedStructures | 6 | 4 | 4 | 2 |
| importedFeatures | 6 | 3 | 3 | 3 |
| biome | 42 | 12 | 6 | 30 |
| biomeGeneratorLink | 3 | 2 | 2 | 1 |
| generator | 16 | 11 | 11 | 5 |
| interpolator | 2 | 1 | 1 | 1 |
| region | 27 | 17 | 11 | 10 |

## dimension

Unclassified: `allObjectScaleFactor`, `biomeBoundsSamplingStep`, `biomeZoom`, `blockFallbacks`, `borrowedDimensionType`, `carving`, `caveBiomeStyle`, `caveDensitySamplingStep`, `caveLavaHeight`, `caveProfile`, `continentZoom`, `continentalStyle`, `coordFractureDistance`, `coordFractureZoom`, `datapackImports`, `debugChunkCrossSections`, `debugCrossSectionsMod`, `debugSmartBore`, `depositVariants`, `deposits`, `dimensionHeight`, `dimensionOptions`, `dimensionStack`, `disabledComponents`, `environment`, `explodeBiomePaletteSize`, `explodeBiomePalettes`, `fluidPalette`, `focus`, `focusRegion`, `fullbright`, `hideOresForHiddenOre`, `hydrology`, `imageMaps`, `importedFeatures`, `importedStructures`, `landBiomeStyle`, `landZoom`, `mode`, `ores`, `overlayNoise`, `preventLeafDecay`, `regionStyle`, `regionZoom`, `riverPolicy`, `rockPalette`, `seaBiomeStyle`, `seaZoom`, `shoreBiomeStyle`, `staticObjects`, `structures`, `studioMode`, `subterrainFeatures`, `surfaceLayerFallback`, `terrainSamplingStep`, `terrainTransform`, `treeSettings`, `upperDimension`, `upperDimensionCarving`, `upperDimensionGap`, `upperDimensionObjects`, `upperObjectsForcePlace`, `useMantle`, `worldBoundary`.

## importedStructures

Unclassified: `adjustments`, `frequencyOverrides`.

## importedFeatures

Unclassified: `biomeRules`, `disabledSteps`, `steps`.

## biome

Unclassified: `biomeScatter`, `biomeSkyScatter`, `biomeStyle`, `carvingBiome`, `caveCeilingLayers`, `caveProfile`, `childShrinkFactor`, `childStyle`, `children`, `customDerivitives`, `decorators`, `depositVariants`, `deposits`, `derivative`, `floatingChildBiomes`, `generators`, `layers`, `lockLayersMax`, `objects`, `ores`, `proceduralObjects`, `riverPolicy`, `seaLayers`, `slab`, `structures`, `surfaceLayerFallback`, `surfaceOreReplaceableBlocks`, `terrain3D`, `vanillaDerivative`, `wall`.

## biomeGeneratorLink

Unclassified: `generator`.

## generator

Unclassified: `cliffHeightGenerator`, `cliffHeightMax`, `cliffHeightMin`, `composite`, `interpolator`.

## interpolator

Unclassified: `function`.

## region

Unclassified: `caveProfile`, `depositVariants`, `deposits`, `objects`, `ores`, `proceduralObjects`, `riverPolicy`, `shoreHeightMax`, `shoreHeightMin`, `structures`.
