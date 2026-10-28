"""Pin dimension carving authoring and native root/child resolution contracts."""
import hashlib
import json
import re
import sys
from pathlib import Path
from extract_river_material_fields import compact

def extract(dimension,entry,resolver,range_source,consumer):
    d,e,r,g,m=[raw.decode('utf-8') for raw in [dimension,entry,resolver,range_source,consumer]]
    values={'irisRevision':'c3359e0a16438b1bb8468901c6171dcefdf63f7f'}
    for name,raw in zip(['dimension','entry','resolver','range','consumer'],[dimension,entry,resolver,range_source,consumer]):values[name+'SourceSha256']=hashlib.sha256(raw).hexdigest()
    def require(text,*contracts):
        for contract in contracts:
            if compact(contract) not in compact(text):raise ValueError('Changed carving contract: '+contract)
    require(d,'@ArrayType(type = IrisDimensionCarvingEntry.class, min = 1)', 'private KList<IrisDimensionCarvingEntry> carving = new KList<>();',
            'index.put(entryId.trim(), entry);','if (entry != null && entry.isEnabled()) { addReachableBiomeKey(pending, entry.getBiome()); }')
    fields=[('id','String','""'),('enabled','boolean','true'),('biome','String','""'),('worldYRange','IrisRange','new IrisRange(-64, 320)'),
            ('childShrinkFactor','double','1.5'),('childStyle','IrisGeneratorStyle','NoiseStyle.CELLULAR_IRIS_DOUBLE.style()'),('children','KList<String>','new KList<>()'),('childRecursionDepth','int','3')]
    for field,kind,default in fields:
        match=re.search(r'@Description\(("(?:[^"\\]|\\.)*")\)\s*private '+re.escape(kind)+' '+field+' = '+re.escape(default)+r';',e)
        if not match:raise ValueError('Changed carving field '+field)
        values[field+'.description']=json.loads(match.group(1));values[field+'.default']=default[1:-1] if kind=='String' else 'CELLULAR_IRIS_DOUBLE' if field=='childStyle' else default
    require(e,'@RegistryListResource(IrisBiome.class)','private static final double MINIMUM_CHILD_SHRINK_FACTOR = 0.0001D;',
            'IrisBiome loaded = data.getBiomeLoader().load(biomeKey.trim());','if (loaded != null && loaded.isCompatExcluded())',
            'return Math.max(MINIMUM_CHILD_SHRINK_FACTOR, getChildShrinkFactor());',
            'return getChildStyle().createScaledGenerator(deriveChildStyleSeed(childrenGeneratorSeed), data, childShrinkFactor());')
    require(r,'private static final int MAX_CHILD_DEPTH = 32;',
            'IrisDimensionCarvingEntry resolved = null; for (IrisDimensionCarvingEntry entry : entries) { if (!isRootCandidate(data, entry, worldY, resolvedState)) { continue; } resolved = entry; }',
            'if (entry == null || !entry.isEnabled()) { return false; }',
            'IrisRange worldYRange = entry.getWorldYRange(); if (worldYRange != null && !worldYRange.contains(worldY)) { return false; }',
            'return resolveBoundEntryBiome(data, entry, state) != null;',
            'IrisDimensionCarvingEntry child = entryIndex.get(childId.trim());',
            'if (child == null || !child.isEnabled()) { continue; }',
            'options.add(new CarvingChoice(child, rarity(childBiome)));','options.add(new CarvingChoice(parent, rarity(parentBiome)));',
            'if (depth <= 0) { return 0; } return Math.min(depth, MAX_CHILD_DEPTH);',
            'int childDepthLimit = clampDepth(selected.getChildRecursionDepth());',
            'if (childDepthLimit < depth) { depth = childDepthLimit; }')
    selection=re.search(r'private static IrisRaritySelection<CarvingChoice> buildSelectionPlan\(.*?\n    \}',r,re.DOTALL)
    if not selection or 'getWorldYRange' in selection.group(0):raise ValueError('Child selection range semantics changed')
    require(m,'private List<WeightedProfile> resolveDimensionCarvingProfiles(',
            'for (IrisDimensionCarvingEntry entry : entries)',
            'if (entry == null || !entry.isEnabled()) { continue; }',
            'IrisDimensionCarvingEntry resolvedEntry = IrisDimensionCarvingResolver.resolveFromRoot( engine, entry, baseX + (columnIndex >> 4), previous, baseZ + (columnIndex & 15), resolverState);',
            'IrisRange worldYRange = new IrisRange(previous, boundary - 1);',
            'weightedProfiles.add(new WeightedProfile(profile, rootProfileColumnWeights.get(profile), -1D, worldYRange, weightedProfiles.size()));')
    require(g,'private double min = 16;','private double max = 32;','return v >= min && v <= max;')
    values.update({'consumer.policy':'multipleWeightedProfiles','root.policy':'lastUsableMatchingRoot','range.policy':'inclusiveAbsoluteWorldY','children.policy':'parentPlusChildrenIgnoreChildY',
                   'range.context.min':'-64','range.context.max':'320','range.class.min':'16','range.class.max':'32','depth.maximum':'32','shrink.minimum':'0.0001'})
    return ''.join(key+'='+json.dumps(value,ensure_ascii=True)[1:-1]+'\n' for key,value in values.items()).encode('ascii')

if __name__=='__main__':Path(sys.argv[6]).write_bytes(extract(*(Path(path).read_bytes() for path in sys.argv[1:6])))
