"""Audit disabled contextual palettes versus inline class defaults and their actual consumers."""
import hashlib
import json
import re
import sys
from pathlib import Path
from extract_river_material_fields import compact


def extract(biome,layer,post,carve,subterrain):
    b,l,p,c,s=(raw.decode('utf-8') for raw in [biome,layer,post,carve,subterrain])
    def require(text,*contracts):
        for contract in contracts:
            if compact(contract) not in compact(text): raise ValueError('Changed slab/wall contract: '+contract)
    require(b,'@NoArgsConstructor');require(l,'@NoArgsConstructor')
    values={'irisRevision':'c3359e0a16438b1bb8468901c6171dcefdf63f7f'}
    for name,raw in [('biome',biome),('layer',layer),('post',post),('carve',carve),('subterrain',subterrain)]: values[name+'SourceSha256']=hashlib.sha256(raw).hexdigest()
    for field in ['slab','wall']:
        match=re.search(r'@Description\(("(?:[^"\\]|\\.)*")\)\s+private IrisBiomePaletteLayer '+field+r' = new IrisBiomePaletteLayer\(\).zero\(\);',b)
        if not match: raise ValueError('Changed slab/wall contextual default')
        values[field+'.description']=json.loads(match.group(1))
    require(l,'public IrisBiomePaletteLayer zero() { palette.clear(); return this; }',
            'private KList<IrisBlockData> palette = new KList<IrisBlockData>().qadd(new IrisBlockData("GRASS_BLOCK"));',
            'if (localBlockData.isEmpty()) { return null; }')
    require(p,'boolean walls = dimension.isPostProcessingWalls();','boolean slabs = dimension.isPostProcessingSlabs();',
            'if (walls && !river && !biome.isShore())','if (!biome.getWall().getPalette().isEmpty())',
            'NativeBlockState d = biome.getWall().get(rng, x + i, i + h, z + i, getData());',
            'if (slabs && !river && isSolid(x, h, z, originX, originZ, currentData))',
            'IrisSlopeClip sc = biome.getSlab().getSlopeCondition();',
            'NativeBlockState d = sc.isValid(getComplex().getSlopeStream().get(x, z)) ? biome.getSlab().get(rng, x, h, z, getData()) : null;')
    require(c,'NativeBlockState data = biome.getWall().get(rng, worldX, yy, worldZ, wallData);')
    require(s,'candidate = biome.getWall().get(random, x, worldY - minimumY, z, data);')
    # No thickness/slope settings are consumed by these material-selection paths, except slab slope.
    for text in [p,c,s]:
        for getter in ['getMinHeight()','getMaxHeight()']:
            if re.search(r'get(?:Slab|Wall)\(\)\.'+re.escape(getter),text): raise ValueError('Changed material thickness consumer')
        if 'getWall().getSlopeCondition()' in compact(text): raise ValueError('Changed wall slope consumer')
    values.update({'palette.contextDefault':'[]','palette.classBlock':'GRASS_BLOCK','palette.minimum':'0','slope.context':'slabOnly','thickness.context':'readOnly'})
    return ''.join(key+'='+value.replace('\\','\\\\').replace('\n','\\n').replace('\r','\\r')+'\n' for key,value in values.items()).encode('ascii')

if __name__=='__main__': Path(sys.argv[6]).write_bytes(extract(*(Path(path).read_bytes() for path in sys.argv[1:6])))
