#!/usr/bin/env python3
"""Verify the complete isolated artifact against pinned originals and explicit isolation manifests."""
from pathlib import Path
import hashlib,io,json,zipfile
root=Path(__file__).resolve().parents[1]
forbidden=('com/tcl/ff/component/adsdkbi/','com/tcl/ff/component/overseabase/database/','a/a/a/a/a/')
def classes(path):
 result={}
 with zipfile.ZipFile(path) as aar:
  for entry in aar.namelist():
   if entry.endswith('.jar'):
    with zipfile.ZipFile(io.BytesIO(aar.read(entry))) as jar:
     for name in jar.namelist():
      if name.endswith('.class'):
       if name in result:raise SystemExit('Duplicate class: '+name)
       result[name]=jar.read(name)
 return result
summary=[]
for component in ('base','media','player'):
 original=classes(root/f'vendor/tcl-{component}-2.8.02.aar')
 packaged=classes(root/f'runtime-{component}/build/outputs/aar/runtime-{component}-release.aar')
 policy=json.loads((root/f'runtime-{component}/build/original/isolation-manifest.json').read_text())
 removed=set(policy['removedClasses']);changed={x['className']:x for x in policy['changedClasses']}
 for name,data in original.items():
  if name in removed:
   assert name not in packaged,name
   assert name.startswith(forbidden),name
  elif name in changed:
   assert hashlib.sha256(data).hexdigest()==changed[name]['originalSha256'],name
   assert hashlib.sha256(packaged[name]).hexdigest()==changed[name]['isolatedSha256'],name
  else:assert packaged.get(name)==data,name
 namespaces={'base':'com/tcl/ff/component/overseabasebusiness','media':'com/tcl/ff/component/vastad','player':'com/tcl/ff/component/oversea/uniplayer'}
 generated={namespaces[component]+'/logging/PropertyLog'+suffix+'.class' for suffix in ['', '$1', '$2', '$Reader', '$Streams']}
 assert set(packaged)-set(original)==generated,'Unexpected runtime classes'
 assert not any(name.startswith(forbidden) for name in packaged)
 summary.append({'component':component,'original':len(original),'removed':len(removed),'boundary_changes':len(changed),'unchanged':len(original)-len(removed)-len(changed),'project_local_logging_helpers':len(generated)})
print(json.dumps(summary,indent=2))
