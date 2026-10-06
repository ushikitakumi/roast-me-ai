#!/usr/bin/env python3
"""Recreate the fixed sample with a separately started local Nemo 0.24.0 engine."""
import json
from pathlib import Path
import urllib.request

root = Path(__file__).resolve().parents[1] / 'assets/avatar-prototype'
base = 'http://127.0.0.1:50129'
with urllib.request.urlopen(base + '/version', timeout=10) as response:
    assert json.load(response) == '0.24.0', 'Use the recorded Nemo version'
with urllib.request.urlopen(base + '/engine_manifest', timeout=10) as response:
    assert json.load(response)['uuid'] == '208cf94d-43d2-4cf5-abc0-9783cac36d29', 'Expected Nemo engine'
query = json.loads((root / 'audio-query.json').read_text())
request = urllib.request.Request(base + '/synthesis?speaker=10006',
    data=json.dumps(query).encode(), headers={'Content-Type': 'application/json'})
with urllib.request.urlopen(request, timeout=120) as response:
    audio = response.read()
assert audio.startswith(b'RIFF') and audio[8:12] == b'WAVE'
(root / 'rival-ja.wav').write_bytes(audio)
print('Saved assets/avatar-prototype/rival-ja.wav')
