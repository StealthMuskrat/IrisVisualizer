"""Audit placement density/scale defaults, annotations and their floating consumers."""
import hashlib
import json
import re
import sys
from pathlib import Path
from extract_river_material_fields import compact

def extract(placement,scale,interpolation,styled_range,mantle,style):
    sources=[placement,scale,interpolation,styled_range,mantle,style]
    p,s,i,r,m,g=[raw.decode('utf-8') for raw in sources]
    def require(text,*contracts):
        for contract in contracts:
            if compact(contract) not in compact(text): raise ValueError('Changed placement settings contract: '+contract)
    require(p,'private IrisObjectScale scale;','private IrisStyledRange densityStyle = null;',
            'return scale == null ? IrisObjectScale.getFixed(object, dimension == null ? 1D : dimension.getAllObjectScaleFactor()) : scale.get(random, object);',
            'if (densityStyle == null) { return density; }','return (int) Math.round(densityStyle.get(rng, x, z, data));','p.setDensityStyle(densityStyle);')
    require(s,'@NoArgsConstructor','public static final double MINIMUM_FACTOR = 0.01D;','public static final double MAXIMUM_FACTOR = 50D;',
            'if (!Double.isFinite(factor) || factor < MINIMUM_FACTOR || factor > MAXIMUM_FACTOR)',
            'requireValidFactor(size, "size");','requireValidFactor(minimumScale, "minimumScale");','requireValidFactor(maximumScale, "maximumScale");',
            'return request.size() != 1D ? request.size() : Math.max(request.minimumScale(), request.maximumScale());',
            'return variations > 0 && (minimumScale != 1D || maximumScale != 1D);','return Math.max(1, Math.min(variations, 32));',
            'if (size != 1D) { return size; }','return minimumScale + (((maximumScale - minimumScale) / variantCount()) * index);',
            'IrisObjectPlacementScaleInterpolator configuredInterpolation = interpolation == null ? IrisObjectPlacementScaleInterpolator.NONE : interpolation;')
    require(m,'int density = placement.getDensity(rng, minX, minZ, data);')
    if compact(m).count(compact('IrisObject obj0 = placement.scaleObject(rng, raw, getDimension());'))!=3 or compact(m).count(compact('if (entry != null && entry.hasObjectShrink())'))!=3: raise ValueError('Changed floating scale consumers')
    require(r,'@NoArgsConstructor','private double min = 16;','private double max = 32;',
            'private IrisGeneratorStyle style = new IrisGeneratorStyle(NoiseStyle.STATIC);','return style.create(rng, data).fitDouble(min, max, x, z);')
    require(g,'private NoiseStyle style = NoiseStyle.FLAT;')
    values={'irisRevision':'c3359e0a16438b1bb8468901c6171dcefdf63f7f'}
    for name,raw in zip(['placement','scale','interpolation','range','mantle','style'],sources): values[name+'SourceSha256']=hashlib.sha256(raw).hexdigest()
    for field,kind,default in [('size','double','1'),('variations','int','7'),('minimumScale','double','1'),('maximumScale','double','1')]:
        match=re.search(r'((?:    @[^\n]+\n)+)    private '+kind+' '+field+r' = '+default+';',s)
        if not match: raise ValueError('Changed scale declaration: '+field)
        annotations=match.group(1)
        for name,pattern in [('minimum',r'@MinNumber\(([^)]+)\)'),('maximum',r'@MaxNumber\(([^)]+)\)'),('description',r'@Description\(("(?:[^"\\]|\\.)*")\)')]:
            found=re.search(pattern,annotations)
            if not found: raise ValueError('Missing scale annotation')
            values['scale.'+field+'.'+name]=json.loads(found.group(1)) if name=='description' else found.group(1)
        values['scale.'+field+'.default']=default
    require(s,'private IrisObjectPlacementScaleInterpolator interpolation = IrisObjectPlacementScaleInterpolator.NONE;')
    body=i.split('public enum IrisObjectPlacementScaleInterpolator {',1)[1].rsplit('}',1)[0]
    pattern=r'\s*@Description\(("(?:[^"\\]|\\.)*")\)\s+([A-Z][A-Z_]*)(?:,|(?=\s*$))'
    matches=list(re.finditer(pattern,body))
    if not matches or re.sub(pattern,'',body).strip() or len({match.group(2) for match in matches})!=len(matches): raise ValueError('Changed scale interpolation enum')
    values['scale.interpolation.choices']=','.join(match.group(2) for match in matches);values['scale.interpolation.default']='NONE'
    for match in matches: values['scale.interpolation.'+match.group(2)+'.description']=json.loads(match.group(1))
    for name,default in [('min','16'),('max','32')]:values['densityStyle.'+name+'.default']=default
    values['densityStyle.style.default']='STATIC'
    for field,kind,source in [('densityStyle','IrisStyledRange',p),('scale','IrisObjectScale',p),('interpolation','IrisObjectPlacementScaleInterpolator',s)]:
        match=re.search(r'@Description\(("(?:[^"\\]|\\.)*")\)\s+private '+kind+' '+field+r'(?:\s*=\s*[^;]+)?;',source)
        if not match: raise ValueError('Missing placement setting description')
        values[('scale.' if field=='interpolation' else '')+field+'.description']=json.loads(match.group(1))
    values.update({'scale.nullPolicy':'dimensionFallback','densityStyle.nullPolicy':'fixedDensityFallback','range.policy':'orderedEditorSubset0ToIntMax','scale.rangePolicy':'orderedEditorSubset'})
    return ''.join(key+'='+json.dumps(value,ensure_ascii=True)[1:-1]+'\n' for key,value in values.items()).encode('ascii')

if __name__=='__main__': Path(sys.argv[7]).write_bytes(extract(*(Path(path).read_bytes() for path in sys.argv[1:7])))
