"""Pin image-map definitions, origins/enums and native authored-data validation without decoding pixels."""
import hashlib
import json
import re
import sys
from pathlib import Path
from extract_river_material_fields import compact

def extract(definition,origin,type_source,rotation,sampling,out_of_bounds,alpha,unknown,compiler):
    sources=[definition,origin,type_source,rotation,sampling,out_of_bounds,alpha,unknown,compiler];d,g,t,r,s,o,a,u,c=[raw.decode('utf-8') for raw in sources]
    values={'irisRevision':'c3359e0a16438b1bb8468901c6171dcefdf63f7f'}
    for name,raw in zip(['definition','origin','type','rotation','sampling','outOfBounds','alpha','unknownColor','compiler'],sources):values[name+'SourceSha256']=hashlib.sha256(raw).hexdigest()
    def require(text,*contracts):
        for contract in contracts:
            if compact(contract) not in compact(text):raise ValueError('Changed image-map definition contract: '+contract)
    require(d,'public static final double MINIMUM_SCALE = 0.000001D;','public static final double MAXIMUM_COLOR_TOLERANCE = 441.672956D;',
            '@RegistryListResource(IrisImage.class)','return "image-maps";')
    fields=[('source','String','""'),('type','IrisImageMapType','IrisImageMapType.GRAYSCALE_HEIGHT'),('blocksPerPixel','double','1D'),
            ('origin','IrisImageMapOrigin','new IrisImageMapOrigin()'),('sourceOrigin','IrisImageMapOrigin','new IrisImageMapOrigin()'),
            ('rotation','IrisImageMapRotation','IrisImageMapRotation.DEG_0'),('mirrorX','boolean','false'),('mirrorZ','boolean','false'),
            ('sampling','IrisImageMapSampling','IrisImageMapSampling.NEAREST'),('outOfBounds','IrisImageMapOutOfBounds','IrisImageMapOutOfBounds.FALLBACK'),
            ('fallbackValue','double','0D'),('fallbackTarget','String','""'),('alpha','IrisImageMapAlpha','IrisImageMapAlpha.IGNORE'),
            ('minimumHeight','double','-64D'),('maximumHeight','double','320D'),('verticalOffset','double','0D'),('clamp','boolean','true'),
            ('inverted','boolean','false'),('curveExponent','double','1D'),('smoothingRadius','int','0'),('threshold','double','0.5D'),
            ('falloff','double','0D'),('colorTolerance','double','0D'),('unknownColor','IrisImageMapUnknownColor','IrisImageMapUnknownColor.ERROR'),
            ('colors','KMap<String, String>','new KMap<>()')]
    constants={'MINIMUM_SCALE':'0.000001','MAXIMUM_COLOR_TOLERANCE':'441.672956'}
    for field,kind,default in fields:
        match=re.search(r'(?P<annotations>(?:@[A-Za-z]+\([^)]*\)\s*)*)@Description\((?P<description>"(?:[^"\\]|\\.)*")\)\s*private '+re.escape(kind)+' '+field+' = '+re.escape(default)+r';',d)
        if not match:raise ValueError('Changed image-map field '+field)
        token=default[1:-1] if kind=='String' else '{}' if kind.startswith('KMap') or kind=='IrisImageMapOrigin' else default.rsplit('.',1)[-1] if kind.startswith('IrisImageMap') else default.rstrip('D')
        values[field+'.description']=json.loads(match.group('description'));values[field+'.default']=token
        for annotation,key in [('MinNumber','minimum'),('MaxNumber','maximum')]:
            bound=re.search('@'+annotation+r'\(([^)]+)\)',match.group('annotations'))
            if bound:values[field+'.'+key]=constants.get(bound.group(1),bound.group(1))
    expected_bounds={'blocksPerPixel':('0.000001',None),'curveExponent':('0.000001',None),'fallbackValue':('0','1'),'smoothingRadius':('0','32'),'threshold':('0','1'),'falloff':('0','1'),'colorTolerance':('0','441.672956')}
    for field,(low,high) in expected_bounds.items():
        if values.get(field+'.minimum')!=low or values.get(field+'.maximum')!=high:raise ValueError('Changed annotation bounds '+field)
    for field in ['x','z']:
        match=re.search(r'@Description\(("(?:[^"\\]|\\.)*")\)\s*private double '+field+r' = 0D;',g)
        if not match:raise ValueError('Changed origin class '+field)
        values['originClass.'+field+'.default']='0';values['originClass.'+field+'.description']=json.loads(match.group(1))
    for field,text,expected in [('type',t,['GRAYSCALE_HEIGHT','RGB_HEIGHT','COLOR_MAP','BINARY_MASK','GRAYSCALE_MASK','ALPHA_MASK']),
                                ('rotation',r,['DEG_0','DEG_90','DEG_180','DEG_270']),('sampling',s,['NEAREST','BILINEAR','BICUBIC']),
                                ('outOfBounds',o,['FALLBACK','CLAMP','REPEAT','MIRROR','ERROR']),('alpha',a,['IGNORE','MASK','TRANSPARENT_IS_FALLBACK','ERROR']),
                                ('unknownColor',u,['ERROR','FALLBACK','IGNORE'])]:
        choices=re.findall(r'^\s*([A-Z][A-Z0-9_]*)\s*[,;]?\s*$',text.split('{',1)[1].rsplit('}',1)[0],re.MULTILINE)
        if choices!=expected:raise ValueError('Changed image-map enum '+field)
        values[field+'.choices']=','.join(choices)
    require(c,'private static final Pattern COLOR_PATTERN = Pattern.compile("#[0-9a-fA-F]{6}");','if (!"png".equals(image.getFormat()))','if (!finiteAtLeast(definition.getBlocksPerPixel(), IrisImageMap.MINIMUM_SCALE))',
            'if (origin == null || !Double.isFinite(origin.getX()) || !Double.isFinite(origin.getZ()))',
            'if (sourceOrigin == null || !Double.isFinite(sourceOrigin.getX()) || !Double.isFinite(sourceOrigin.getZ()))',
            'if (!Double.isFinite(definition.getMinimumHeight()) || !Double.isFinite(definition.getMaximumHeight()))',
            'definition.getMaximumHeight() < definition.getMinimumHeight()',
            'if (!Double.isFinite(definition.getVerticalOffset()))',
            'if (!finiteAtLeast(definition.getCurveExponent(), IrisImageMap.MINIMUM_SCALE))',
            'if (definition.getSmoothingRadius() < 0 || definition.getSmoothingRadius() > 32)',
            'if (!range(definition.getThreshold(), 0D, 1D))','if (!range(definition.getFalloff(), 0D, 1D))',
            'if (!range(definition.getFallbackValue(), 0D, 1D))',
            'if (!range(definition.getColorTolerance(), 0D, IrisImageMap.MAXIMUM_COLOR_TOLERANCE))',
            'if ((type == IrisImageMapType.COLOR_MAP || type == IrisImageMapType.BINARY_MASK) && definition.getSampling() != null && definition.getSampling() != IrisImageMapSampling.NEAREST)',
            'if (type == IrisImageMapType.COLOR_MAP && definition.getSmoothingRadius() != 0)',
            'definition.getAlpha() != IrisImageMapAlpha.IGNORE',
            'if (definition.getType() != IrisImageMapType.COLOR_MAP)', 'if (colors == null || colors.isEmpty())',
            'if (encoded == null || !COLOR_PATTERN.matcher(encoded).matches())','if (target == null || target.isBlank())',
            'int rgb = Integer.parseInt(encoded.substring(1), 16);','String previous = decoded.putIfAbsent(rgb, encoded);',
            'definition.getOutOfBounds() == IrisImageMapOutOfBounds.FALLBACK','definition.getUnknownColor() == IrisImageMapUnknownColor.FALLBACK',
            'definition.getAlpha() == IrisImageMapAlpha.TRANSPARENT_IS_FALLBACK','definition.getAlpha() == IrisImageMapAlpha.MASK',
            'if (fallbackRequired && (definition.getFallbackTarget() == null || definition.getFallbackTarget().isBlank()))')
    values.update({'source.policy':'pngOnly','height.policy':'orderedFiniteHeights','legend.policy':'exactRawRgbUnique'})
    return ''.join(key+'='+json.dumps(value,ensure_ascii=True)[1:-1]+'\n' for key,value in values.items()).encode('ascii')

if __name__=='__main__':Path(sys.argv[10]).write_bytes(extract(*(Path(path).read_bytes() for path in sys.argv[1:10])))
