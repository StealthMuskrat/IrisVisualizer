"""Pin the reviewed consumer evidence behind seven non-generation classifications.

The hashes require deliberate re-auditing when the Iris pin changes. They do not
infer generation semantics or claim that all Iris schemas/consumers are covered.
"""
import hashlib
import json
import sys
from pathlib import Path

from field_coverage import SOURCES, declared_fields


def audit(source_dir, policy_file, manifest_file, lock_file):
    manifest = json.loads(manifest_file.read_text())
    policy = json.loads(policy_file.read_text())
    revision = json.loads(lock_file.read_text())['iris']['revision']
    if manifest['irisRevision'] != revision:
        raise ValueError('Read-only consumer audit requires the current Iris revision')
    sources = manifest['sources']
    declarations = {}
    for relative, expected in sources.items():
        path = source_dir / 'core/src/main/java/art/arcane/iris' / relative
        if not path.is_file():
            path = source_dir / Path(relative).name
        raw = path.read_bytes()
        if hashlib.sha256(raw).hexdigest() != expected:
            raise ValueError('Read-only consumer source differs; re-audit required: ' + relative)
        declarations[relative] = declared_fields(raw.decode('utf-8'))
    count = 0
    for group, fields in manifest['fields'].items():
        for field, relative in fields.items():
            if SOURCES.get(group) != relative or field not in declarations.get(relative, {}):
                raise ValueError('Audited field missing from its declared source: ' + group + '.' + field)
            rule = policy.get(group, {}).get(field, {})
            if rule.get('classification') != 'read-only' or not rule.get('reason', '').strip():
                raise ValueError('Audited non-generation field must remain read-only: ' + group + '.' + field)
            count += 1
    if count != 7:
        raise ValueError('Consumer audit must account for all seven reviewed fields')
    return count


if __name__ == '__main__':
    count = audit(*(Path(value) for value in sys.argv[1:]))
    print(f'PASS: {count} read-only classifications bound to pinned declaration and consumer source hashes')
