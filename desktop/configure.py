"""Generate only public sync configuration; never copy a local config wholesale."""
from pathlib import Path
from urllib.parse import urlsplit
import argparse, json, os
parser=argparse.ArgumentParser();parser.add_argument('--required',action='store_true');args=parser.parse_args()
root=Path(__file__).resolve().parent
local=root.parent/'backend/supabase/project.local.json'
values=json.loads(local.read_text()) if local.exists() else {}
url=os.environ.get('STEP_SYNC_URL',values.get('api_url','')).strip()
key=os.environ.get('STEP_SYNC_PUBLIC_KEY',values.get('publishable_key','')).strip()
destination=root/'app/src/main/resources/step-sync-config.json'
if not url and not key:
    destination.unlink(missing_ok=True)
    if args.required:raise SystemExit('Public sync configuration is required for a distributable build')
    print('Desktop cloud configuration absent: offline test build')
else:
    u=urlsplit(url)
    if u.scheme!='https' or not u.hostname or u.username or u.password or u.query or u.fragment:raise SystemExit('Invalid public sync URL')
    if not key.startswith('sb_publishable_') or len(key)>512 or any(c.isspace() for c in key):raise SystemExit('Only a publishable client key may enter the desktop app')
    destination.parent.mkdir(parents=True,exist_ok=True)
    destination.write_text(json.dumps({'api_url':url.rstrip('/'),'publishable_key':key}))
    print('Desktop public client configuration prepared (values hidden)')
