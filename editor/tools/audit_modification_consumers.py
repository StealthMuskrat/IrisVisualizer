"""Reproduce the pinned production-Java consumer audit behind read-only modification recipes.

This is source evidence, never a pack validator or an authorization to edit recipes.
The optional downloader is for development/CI source caches only.
"""
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path, PurePosixPath
import argparse, hashlib, json, re
from urllib.request import urlopen
from java_model_inventory import tokens

ROOT = Path(__file__).resolve().parents[2]
MANIFEST = ROOT/'editor/policy/modification-consumer-audit.json'

def index_hash(files):
    return hashlib.sha256(json.dumps(sorted(files.items()),separators=(',',':')).encode()).hexdigest()

def source_path(root,relative):
    path=PurePosixPath(relative)
    if path.is_absolute() or '..' in path.parts or not relative.endswith('.java') or '/src/' not in relative:
        raise ValueError('Invalid production source path: '+relative)
    destination=root/path
    if not destination.resolve().is_relative_to(root.resolve()) or destination.is_symlink():
        raise ValueError('Source cache path escapes its root: '+relative)
    return destination

def production_path(relative):
    return relative.endswith('.java') and '/src/' in relative and not re.search(r'/(?:test|offline|testFixtures|jmh|gameplay)/',relative)

def git_blob(raw):
    return hashlib.sha1(b'blob '+str(len(raw)).encode()+b'\0'+raw).hexdigest()

def reference_sites(text,symbols):
    # Conservative Unicode escape decoding also exposes escaped identifiers/literal names.
    text=re.sub(r'\\u+([0-9a-fA-F]{4})',lambda m:chr(int(m[1],16)),text)
    counts={}
    literal=re.compile(r'(?<![\w$])('+ '|'.join(re.escape(s) for s in sorted(symbols,key=len,reverse=True))+r')(?![\w$])')
    for token in tokens(text):
        if token.value in symbols:counts[token.value]=counts.get(token.value,0)+1
        elif token.value.startswith(('"',"'")):
            for match in literal.finditer(token.value):counts[match[1]]=counts.get(match[1],0)+1
    return dict(sorted(counts.items()))

def verify_policy(policy,manifest=None,lock_file=None):
    manifest=json.loads(MANIFEST.read_text()) if manifest is None else manifest
    lock=json.loads((ROOT/'iris.lock.json' if lock_file is None else lock_file).read_text())
    if manifest['irisRevision']!=lock['iris']['revision'] or index_hash(manifest['sources'])!=manifest['sourceIndexSha256']:
        raise ValueError('Modification consumer evidence requires a complete re-audit for this Iris pin')
    count=0
    for group in manifest['recipeGroups']:
        fields=policy.get(group)
        if not isinstance(fields,dict) or not fields:raise ValueError('Missing audited recipe policy: '+group)
        for field,rule in fields.items():
            if not isinstance(rule,dict) or rule.get('classification')!='read-only' or not isinstance(rule.get('reason'),str) or not rule['reason'].strip():
                raise ValueError('Pinned schema/compatibility recipe must remain read-only: '+group+'.'+field)
            count+=1
    if count!=manifest['classifiedFields']:raise ValueError('Modification field inventory changed; re-audit required')
    return count

def audit(source_dir,policy_file,manifest_file=MANIFEST,lock_file=ROOT/'iris.lock.json'):
    manifest=json.loads(manifest_file.read_text());policy=json.loads(policy_file.read_text())
    fields=verify_policy(policy,manifest,lock_file);sites={};symbols=set(manifest['symbols'])
    actual={path.relative_to(source_dir).as_posix() for path in source_dir.rglob('*.java') if production_path(path.relative_to(source_dir).as_posix())}
    if actual!=set(manifest['sources']):raise ValueError('Production source scope is incomplete or has extra Java files; re-audit required')
    for relative,expected in manifest['sources'].items():
        raw=source_path(source_dir,relative).read_bytes()
        if git_blob(raw)!=expected:raise ValueError('Pinned production source changed: '+relative)
        found=reference_sites(raw.decode('utf-8'),symbols)
        if found:sites[relative]=found
    if sites!=manifest['referenceSites']:raise ValueError('Modification consumer sites changed; re-audit required')
    return len(manifest['sources']),fields

def download(source_dir,manifest_file=MANIFEST):
    manifest=json.loads(manifest_file.read_text());revision=manifest['irisRevision']
    if not re.fullmatch('[0-9a-f]{40}',revision):raise ValueError('Invalid pinned source revision')
    def fetch(row):
        relative,expected=row;destination=source_path(source_dir,relative)
        if destination.is_file() and git_blob(destination.read_bytes())==expected:return
        url='https://raw.githubusercontent.com/VolmitSoftware/Iris/'+revision+'/'+relative
        with urlopen(url,timeout=30) as response:raw=response.read(2_000_001)
        if len(raw)>2_000_000 or git_blob(raw)!=expected:raise ValueError('Downloaded production source differs: '+relative)
        destination.parent.mkdir(parents=True,exist_ok=True);destination.write_bytes(raw)
    with ThreadPoolExecutor(max_workers=16) as workers:list(workers.map(fetch,manifest['sources'].items()))

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('source_dir',type=Path);parser.add_argument('--download',action='store_true');args=parser.parse_args()
    verify_policy(json.loads((ROOT/'editor/policy/field-policy.json').read_text()))
    if args.download:download(args.source_dir)
    files,fields=audit(args.source_dir,ROOT/'editor/policy/field-policy.json')
    print(f'Audited {files} pinned production Java files; {fields} schema/compatibility recipe fields stay read-only.')
