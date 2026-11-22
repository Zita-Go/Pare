#!/usr/bin/env python3
"""Generate ignored Android build settings; never prints public config or tokens."""
import json, os
from pathlib import Path
root = Path(__file__).resolve().parent
config_path = root.parent / 'backend/supabase/project.local.json'
config = json.loads(config_path.read_text()) if config_path.exists() else {}
key = config.get('publishable_key', '')
if key and not key.startswith('sb_publishable_'):
    raise SystemExit('Expected a public Supabase publishable key; refusing to embed another key type')
sdk = Path(os.environ.get('ANDROID_HOME', root / '.toolchain/sdk')).resolve()
def escape(value):
    return str(value).replace('\\', '\\\\').replace('\n', '\\n').replace(':', '\\:').replace('=', '\\=')
values = {'sdk.dir': sdk, 'supabase.url': config.get('api_url', ''), 'supabase.publicKey': config.get('publishable_key', '')}
(root / 'local.properties').write_text('\n'.join(f'{k}={escape(v)}' for k, v in values.items()) + '\n')
os.chmod(root / 'local.properties', 0o600)
print('Generated ignored android/local.properties')
