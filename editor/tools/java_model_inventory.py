"""Lexical inventory of serialized Java fields; method bodies and nested classes are excluded.
This inventories source declarations, not Java execution or a general-purpose JSON schema.
"""
from dataclasses import dataclass
import re

@dataclass(frozen=True)
class Token:
    value: str
    start: int
    end: int

@dataclass(frozen=True)
class Field:
    name: str
    type: str
    default: str | None
    annotations: str

@dataclass(frozen=True)
class Model:
    name: str
    kind: str
    parent: str | None
    fields: tuple[Field, ...]
    custom_noarg: bool
    annotations: str

TOKEN = re.compile(r'//[^\n]*|/\*.*?\*/|""".*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|[A-Za-z_$][\w$]*|\d+(?:[\w.]*\d)?|[^\s]', re.S)
MODIFIERS = {'public','protected','private','static','transient','final','volatile','synchronized','abstract','native','strictfp','default'}

def tokens(text):
    return [Token(m[0],m.start(),m.end()) for m in TOKEN.finditer(text) if not m[0].startswith(('//','/*'))]

def matching(items,start,left='(',right=')'):
    depth=0
    for i in range(start,len(items)):
        if items[i].value==left:depth+=1
        elif items[i].value==right:
            depth-=1
            if depth==0:return i
    raise ValueError('Unclosed Java delimiter')

def strip_annotations(items):
    i=0;annotations=[]
    while i<len(items):
        if items[i].value!='@':break
        start=i;i+=1
        if i>=len(items):raise ValueError('Incomplete Java annotation')
        i+=1
        while i+1<len(items) and items[i].value=='.':i+=2
        if i<len(items) and items[i].value=='(':i=matching(items,i)+1
        annotations.append(''.join(t.value for t in items[start:i]))
    return items[i:],''.join(annotations)

def annotation_arguments(source,wanted):
    """Read one top-level annotation without matching annotation-like text inside a literal."""
    items=tokens(source);i=0;found=None
    while i<len(items):
        if items[i].value!='@':raise ValueError('Invalid annotation inventory')
        i+=1
        if i>=len(items):raise ValueError('Incomplete annotation type')
        name=items[i].value;i+=1
        while i+1<len(items) and items[i].value=='.':name+='.'+items[i+1].value;i+=2
        args=()
        if i<len(items) and items[i].value=='(':
            end=matching(items,i);args=tuple(t.value for t in items[i+1:end]);i=end+1
        if name.split('.')[-1]==wanted:
            if found is not None:raise ValueError('Duplicate annotation: '+wanted)
            found=args
    return found

def preamble(items):
    modifiers=[];annotations=''
    while items:
        if items[0].value=='@':
            items,found=strip_annotations(items);annotations+=found
        elif items[0].value in MODIFIERS:
            modifiers.append(items[0].value);items=items[1:]
        else:break
    return items,annotations,modifiers

def declaration(items,text):
    items,annotations,modifiers=preamble(items)
    if not items:return None
    if any(t.value in ['class','interface','enum','record'] for t in items[:2]):return None
    equal=next((i for i,t in enumerate(items) if t.value=='='),len(items))
    head=items[:equal]
    if any(t.value=='(' for t in head):return None
    if len(head)<2:return None
    if 'static' in modifiers or 'transient' in modifiers:return None
    angle=0
    for t in head:
        if t.value=='<':angle+=1
        elif t.value=='>':angle-=1
        elif t.value==',' and angle==0:raise ValueError('Multiple Java fields in one declaration require explicit review')
    if angle!=0:raise ValueError('Unsupported field generic declaration')
    nesting=[]
    for t in items[equal+1:]:
        if t.value in ['(','[','{','<']:nesting.append(t.value)
        elif t.value in [')',']','}','>'] and nesting:nesting.pop()
        elif t.value==',' and not nesting:raise ValueError('Multiple initialized Java fields require explicit review')
    if not re.fullmatch(r'[A-Za-z_$][\w$]*',head[-1].value):raise ValueError('Unsupported Java field name or post-name array syntax')
    kind=''.join(t.value for t in head[:-1]);name=head[-1].value
    default=text[items[equal].end:items[-1].end].strip() if equal<len(items) else None
    return Field(name,kind,default,annotations)

def inventory(text,name):
    items=tokens(text)
    pos=next((i for i,t in enumerate(items[:-1]) if t.value in ['class','enum','record','interface'] and items[i+1].value==name and (i==0 or items[i-1].value!='.')),None)
    if pos is None:raise ValueError('Primary Java declaration not found: '+name)
    kind=items[pos].value
    boundary=max((i for i in range(pos) if items[i].value==';'),default=-1)
    _,annotations,_=preamble(items[boundary+1:pos])
    opening=next(i for i in range(pos+2,len(items)) if items[i].value=='{')
    header=items[pos+2:opening];parent=None
    for i,t in enumerate(header[:-1]):
        if t.value=='extends':parent=header[i+1].value;break
    if header and header[0].value=='<':raise ValueError('Generic model needs an explicit serialization policy: '+name)
    if kind=='record':raise ValueError('Record serialization needs an explicit adapter policy: '+name)
    if kind in ['enum','interface']:return Model(name,kind,parent,(),False,annotations)
    fields=[];custom_noarg=False;depth=1;start=opening+1;i=start
    while i<len(items) and depth:
        value=items[i].value
        if value=='{' and depth==1 and sum(t.value=='(' for t in items[start:i])==sum(t.value==')' for t in items[start:i]):
            prefix,_,modifiers=preamble(items[start:i]);static_initializer=not prefix and 'static' in modifiers
            if not prefix and not static_initializer:raise ValueError('Instance initializer needs an explicit default policy: '+name)
            field_head=any(t.value=='=' for t in prefix)
            if not field_head:
                if len(prefix)>=3 and prefix[0].value==name and prefix[1].value=='(' and prefix[2].value==')':custom_noarg=True
                close=matching(items,i,'{','}');i=close+1;start=i;continue
        if value=='{':depth+=1
        elif value=='}':
            if depth==1 and items[start:i]:raise ValueError('Unterminated Java member declaration: '+name)
            depth-=1
        elif value==';' and depth==1:
            field=declaration(items[start:i],text)
            if field:fields.append(field)
            start=i+1
        i+=1
    if depth:raise ValueError('Incomplete primary Java class: '+name)
    if len({f.name for f in fields})!=len(fields):raise ValueError('Duplicate serialized Java field: '+name)
    return Model(name,kind,parent,tuple(fields),custom_noarg,annotations)
