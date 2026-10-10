#!/usr/bin/env python3
"""Verify all original R-field references resolve in the packaged vendor namespaces."""
from pathlib import Path
import io,json,struct,zipfile,xml.etree.ElementTree as E
root=Path(__file__).resolve().parents[1]
components={'base':'com/tcl/ff/component/overseabasebusiness','player':'com/tcl/ff/component/oversea/uniplayer','media':'com/tcl/ff/component/vastad'}
def pool(blob):
 pos=8;n=struct.unpack_from('>H',blob,pos)[0];pos+=2;cp=[None]*n;i=1
 while i<n:
  tag=blob[pos];pos+=1
  if tag==1:
   size=struct.unpack_from('>H',blob,pos)[0];pos+=2;cp[i]=(tag,blob[pos:pos+size].decode('utf-8','replace'));pos+=size
  elif tag in (7,8,16,19,20):cp[i]=(tag,struct.unpack_from('>H',blob,pos)[0]);pos+=2
  elif tag in (9,10,11,12,17,18):cp[i]=(tag,*struct.unpack_from('>HH',blob,pos));pos+=4
  elif tag in (3,4):pos+=4
  elif tag in (5,6):pos+=8;i+=1
  elif tag==15:pos+=3
  else:raise ValueError(tag)
  i+=1
 return cp
symbols={};refs={k:set() for k in components}
for component in components:
 with zipfile.ZipFile(root/f'runtime-{component}/build/outputs/aar/runtime-{component}-release.aar') as aar:
  symbols[component]={tuple(line.split()[1:3]) for line in aar.read('R.txt').decode().splitlines() if len(line.split())>=3}
 with zipfile.ZipFile(root/f'vendor/tcl-{component}-2.8.02.aar') as aar, zipfile.ZipFile(io.BytesIO(aar.read('classes.jar'))) as jar:
  for name in jar.namelist():
   if not name.endswith('.class'):continue
   cp=pool(jar.read(name))
   for item in cp:
    if item and item[0]==9:
     owner=cp[cp[item[1]][1]][1];field=cp[cp[item[2]][1]][1]
     for target,namespace in components.items():
      if owner.startswith(namespace+'/R$'):refs[target].add((owner.split('$',1)[1],field))
summary={}
for component,values in refs.items():
 missing=values-symbols[component]
 if missing:raise SystemExit(f'{component} missing R symbols: {sorted(missing)}')
 summary[component]={'original_referenced_fields':len(values),'missing':0}
# Verify base resource values copied into the media namespace without editing any qualifier.
with zipfile.ZipFile(root/'vendor/tcl-base-2.8.02.aar') as z:
 expected={}
 for name in z.namelist():
  if name.startswith('res/values') and name.endswith('.xml'):
   qualifier=name.split('/')[1]
   for value in E.fromstring(z.read(name)):
    expected[(qualifier,value.tag,value.get('name'))]=E.tostring(value,encoding='unicode').strip()
with zipfile.ZipFile(root/'runtime-media/build/outputs/aar/runtime-media-release.aar') as z:
 actual={}
 for name in z.namelist():
  if name.startswith('res/values') and name.endswith('.xml'):
   qualifier=name.split('/')[1]
   for value in E.fromstring(z.read(name)):
    actual[(qualifier,value.tag,value.get('name'))]=E.tostring(value,encoding='unicode').strip()
for key,value in expected.items():
 if actual.get(key)!=value:raise SystemExit(f'Original base resource changed in media namespace: {key}')
summary['transitive_base_values']={'qualifier_values_verified':len(expected),'changed':0}
print(json.dumps(summary,indent=2))
