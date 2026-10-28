"""Verify classification and source drift cannot silently bypass this audit."""
import json
import sys
import tempfile
from pathlib import Path

from audit_read_only_consumers import audit


def rejects(action):
    try:
        action()
    except (ValueError, FileNotFoundError):
        return
    raise AssertionError('Expected rejected read-only evidence drift')


source = Path(sys.argv[1])
root = Path(__file__).resolve().parents[2]
policy = root / 'editor/policy/field-policy.json'
manifest = root / 'editor/policy/read-only-consumers.json'
lock = root / 'iris.lock.json'
assert audit(source, policy, manifest, lock) == 7
with tempfile.TemporaryDirectory() as folder:
    temp = Path(folder)
    changed = temp / 'policy.json'
    original = json.loads(policy.read_text())
    for group, fields in json.loads(manifest.read_text())['fields'].items():
        for field in fields:
            for invalid in ({'classification': 'generation', 'reason': 'incorrect'},
                            {'classification': 'read-only', 'reason': ' '}):
                data = json.loads(json.dumps(original))
                data[group][field] = invalid
                changed.write_text(json.dumps(data))
                rejects(lambda: audit(source, changed, manifest, lock))
    altered = json.loads(manifest.read_text())
    altered['irisRevision'] = '0' * 40
    changed.write_text(json.dumps(altered))
    rejects(lambda: audit(source, policy, changed, lock))
    altered = json.loads(manifest.read_text())
    first = next(iter(altered['sources']))
    altered['sources'][first] = '0' * 64
    changed.write_text(json.dumps(altered))
    rejects(lambda: audit(source, policy, changed, lock))
    altered = json.loads(manifest.read_text())
    altered['fields']['dimension']['missingField'] = altered['fields']['dimension'].pop('treeSettings')
    changed.write_text(json.dumps(altered))
    rejects(lambda: audit(source, policy, changed, lock))
    altered = json.loads(manifest.read_text())
    del altered['fields']['dimension']['treeSettings']
    changed.write_text(json.dumps(altered))
    rejects(lambda: audit(source, policy, changed, lock))
    rejects(lambda: audit(temp / 'absent', policy, manifest, lock))
print('PASS: rejected consumer hash, Iris revision, missing declaration, incomplete audit and policy drift')
