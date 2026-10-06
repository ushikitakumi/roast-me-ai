#!/usr/bin/env python3
"""Inspect the selected offline prototype files; no network or app dependencies."""
import hashlib
import json
from pathlib import Path
import struct
import wave

root = Path(__file__).resolve().parents[1] / 'assets/avatar-prototype'
blob = (root / 'rival-mpfb.glb').read_bytes()
magic, version, length = struct.unpack_from('<4sII', blob)
assert (magic, version, length) == (b'glTF', 2, len(blob))
size, kind = struct.unpack_from('<II', blob, 12)
assert kind == 0x4E4F534A
model = json.loads(blob[20:20 + size])
binary = 20 + size + 8
assert struct.unpack_from('<I', blob, 20 + size + 4)[0] == 0x004E4942
assert not [x for group in ('images', 'buffers') for x in model.get(group, []) if 'uri' in x]
checks = []
for mesh in model['meshes']:
    names = mesh.get('extras', {}).get('targetNames', [])
    for name in ('jawOpen', 'eyeBlinkLeft', 'eyeBlinkRight'):
        if name not in names:
            continue
        for primitive in mesh['primitives']:
            accessor = model['accessors'][primitive['targets'][names.index(name)]['POSITION']]
            assert accessor['componentType'] == 5126 and accessor['type'] == 'VEC3'
            sparse = accessor.get('sparse')
            data = sparse['values'] if sparse else accessor
            count = sparse['count'] if sparse else accessor['count']
            view = model['bufferViews'][data['bufferView']]
            start = binary + view.get('byteOffset', 0) + data.get('byteOffset', 0)
            assert not view.get('byteStride'), 'Add strided accessor handling before changing model'
            values = struct.unpack_from('<' + str(count * 3) + 'f', blob, start)
            delta = max(abs(x) for x in values)
            assert delta > 0
            checks.append({'mesh': mesh['name'], 'target': name, 'max_delta': delta})
assert {x['target'] for x in checks if x['mesh'] == 'base'} == {'jawOpen', 'eyeBlinkLeft', 'eyeBlinkRight'}
assert {'Head', 'Neck'} <= {n.get('name') for n in model['nodes']}
with wave.open(str(root / 'rival-ja.wav')) as wav:
    assert wav.getnchannels() == 1 and wav.getsampwidth() == 2 and wav.getframerate() == 24000
    duration = wav.getnframes() / wav.getframerate()
    assert 9 <= duration <= 11
    samples = struct.unpack('<' + str(wav.getnframes()) + 'h', wav.readframes(wav.getnframes()))
    peak = max(abs(x) for x in samples)
    assert 0 < peak < 32767
report = {
    'model_bytes': len(blob), 'model_format': 'glTF 2.0 binary (not VRM)',
    'meshes': len(model['meshes']), 'embedded_resources_only': True,
    'morph_checks': checks, 'head_bones': ['Head', 'Neck'],
    'audio_seconds': duration, 'sample_rate': 24000, 'channels': 1, 'pcm_bits': 16,
    'audio_peak': peak,
    'sha256': {f: hashlib.sha256((root / f).read_bytes()).hexdigest()
               for f in ('rival-mpfb.glb', 'rival-ja.wav', 'audio-query.json', 'line.ja.txt')},
    'limitations': 'Structural checks only; rendering, perceived age, expression and listening evaluation remain for A1/A2.'
}
print(json.dumps(report, ensure_ascii=False, indent=2))
