#!/usr/bin/env python3
"""Create local-only credentials once; never print secrets."""
from pathlib import Path
import secrets
root=Path(__file__).resolve().parent.parent
path=root/'.env'
if path.exists():
    print('.env already exists; left unchanged.')
else:
    db=secrets.token_urlsafe(24)
    path.write_text(f'POSTGRES_PASSWORD={db}\nDATABASE_URL=jdbc:postgresql://localhost:54329/roastme\nDATABASE_USER=roastme\nDATABASE_PASSWORD={db}\nBACKEND_USER=backend\nBACKEND_PASSWORD={secrets.token_urlsafe(24)}\nFRONTEND_USER=owner\nFRONTEND_PASSWORD={secrets.token_urlsafe(24)}\nBACKEND_URL=http://127.0.0.1:8080\nAPP_ORIGIN=http://localhost:3000\n')
    path.chmod(0o600)
    print('Created private .env (ignored by Git).')
