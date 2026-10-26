"""Extract the official four-file Studio starter's literal JSON (not generation code)."""
import hashlib
import re
import sys
import textwrap
from pathlib import Path

source = Path(sys.argv[1]).read_bytes()
text = source.decode('utf-8')
start = text.index('    static void createStarterProject(')
end = text.index('    private static boolean hasLoadableDimensionFile', start)
method = text[start:end]
patterns = {
    'dimension.json': r'Files.writeString\(stage.resolve\("dimensions/" \+ safeName \+ "\.json"\), """\n(.*?)"""\.formatted\(safeName\), StandardCharsets.UTF_8\);',
    'region.json': r'Files.writeString\(stage.resolve\("regions/starter.json"\), """\n(.*?)""", StandardCharsets.UTF_8\);',
    'biome.json': r'Files.writeString\(stage.resolve\("biomes/starter.json"\), """\n(.*?)""", StandardCharsets.UTF_8\);',
    'generator.json': r'Files.writeString\(stage.resolve\("generators/flat.json"\), """\n(.*?)""", StandardCharsets.UTF_8\);',
}
output = Path(sys.argv[2])
output.mkdir(parents=True, exist_ok=True)
for name, pattern in patterns.items():
    match = re.search(pattern, method, re.DOTALL)
    if not match:
        raise ValueError('Official starter template changed: ' + name)
    template = textwrap.dedent(match.group(1))
    # Fail rather than approximating Java text-block escaping or interpolation changes.
    if '\\' in template or ('%' in template and (name != 'dimension.json' or template.count('%s') != 1)):
        raise ValueError('Unsupported template encoding: ' + name)
    (output / name).write_bytes(template.encode('utf-8'))
(output / 'source.properties').write_bytes(('irisRevision=c3359e0a16438b1bb8468901c6171dcefdf63f7f\n'
    + 'sourceSha256=' + hashlib.sha256(source).hexdigest() + '\n').encode('ascii'))
