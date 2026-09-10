#!/usr/bin/env python3
"""Export the running backend contract using local credentials (never printed)."""
from pathlib import Path
import os, base64, json, urllib.request
root=Path(__file__).resolve().parent.parent
config=dict(os.environ)
if (root/'.env').exists():
    config.update(line.split('=',1) for line in (root/'.env').read_text().splitlines() if line and not line.startswith('#'))
token=base64.b64encode(f'{config["BACKEND_USER"]}:{config["BACKEND_PASSWORD"]}'.encode()).decode()
request=urllib.request.Request(config.get('BACKEND_URL','http://127.0.0.1:8080')+'/v3/api-docs',headers={'Authorization':'Basic '+token})
with urllib.request.urlopen(request) as response: contract=json.load(response)
contract['servers']=[{'url':'/'}]
(root/'backend/openapi.json').write_text(json.dumps(contract,ensure_ascii=False,indent=2,sort_keys=True)+'\n')
print('Exported backend/openapi.json')
