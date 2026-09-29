#!/usr/bin/env python3
"""Rebuild the bundled piano. Requires numpy, scipy and soundfile; no runtime downloads."""
import concurrent.futures
import hashlib
import json
from pathlib import Path
import re
import struct
import urllib.parse
import urllib.request

import numpy as np
from scipy.signal import resample_poly
import soundfile as sf

ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT / '.tools/piano-source'
OUT = ROOT / 'app/src/main/assets'
REPO = 'https://raw.githubusercontent.com/sfzinstruments/SalamanderGrandPiano/'
REVISION = '3382bf9496bba2486f5ab0de55a264d1dfc38404'

def fetch(path, revision):
    dest = CACHE / path
    if not dest.exists():
        dest.parent.mkdir(parents=True, exist_ok=True)
        url = REPO + revision + '/' + urllib.parse.quote(path)
        with urllib.request.urlopen(url, timeout=120) as response:
            data = response.read()
        dest.write_bytes(data)
    return dest

def main():
    OUT.mkdir(parents=True, exist_ok=True)
    CACHE.mkdir(parents=True, exist_ok=True)
    revision = REVISION
    roots = list(range(21, 109, 3))
    names = ['C','C#','D','D#','E','F','F#','G','G#','A','A#','B']
    offsets = {}
    for layer in (4, 9, 14):
        source = fetch(f'Data/vel_{layer:02}.txt', revision).read_text()
        offsets[layer] = [int(x) for x in re.findall(r'#define \$OFF\d+ (\d+)', source)]
    entries = [(note, layer, f'Samples/{names[note % 12]}{note // 12 - 1}v{layer}.flac')
               for note in roots for layer in (4, 9, 14)]
    with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
        list(pool.map(lambda e: fetch(e[2], revision), entries))
    manifest = []
    with (OUT / 'grand.pno').open('wb') as output:
        output.write(struct.pack('<4sI', b'PNO1', len(entries)))
        for note, layer, name in entries:
            path = fetch(name, revision)
            samples, rate = sf.read(path, dtype='float32', always_2d=True)
            assert rate == 48000 and samples.shape[1] == 2
            offset = offsets[layer][roots.index(note)]
            samples = samples[offset:offset + rate * 12]
            samples = resample_poly(samples, 1, 2, axis=0)
            # Fade the sample's end, retaining the original stereo balance and dynamics.
            fade = min(6000, len(samples))
            samples[-fade:] *= np.linspace(1, 0, fade, dtype=np.float32)[:, None]
            pcm = np.rint(np.clip(samples, -1, 32767 / 32768) * 32768).astype('<i2')
            output.write(struct.pack('<IIII', note, (4, 9, 14).index(layer), len(pcm), 24000))
            output.write(pcm.tobytes())
            manifest.append({'file': name, 'sha256': hashlib.sha256(path.read_bytes()).hexdigest(),
                             'root': note, 'layer': layer, 'offset': offset, 'frames': len(pcm)})
    license_text = fetch('LICENSE', revision).read_text()
    (OUT / 'SALAMANDER_LICENSE.txt').write_text(license_text)
    (OUT / 'NOTICE.txt').write_text(
        'Salamander Grand Piano v3 — Alexander Holm\n'
        'Creative Commons Attribution 3.0 Unported\nhttps://creativecommons.org/licenses/by/3.0/\n'
        'Source: https://github.com/sfzinstruments/SalamanderGrandPiano\n'
        f'Revision: {revision}\n'
        'Piano Sounds adaptation: 30 root notes, velocity layers 4/9/14, attack offsets, '
        '24 kHz stereo PCM16, maximum 12-second samples with a 250ms end fade. '
        'The SFZ mapping is not included. No endorsement by the original author is implied.\n\n'
        'Oboe — Copyright Google LLC. Apache License 2.0.\nhttps://github.com/google/oboe\n')
    metadata = {'revision': revision, 'bankSha256': hashlib.sha256((OUT / 'grand.pno').read_bytes()).hexdigest(),
                'samples': manifest}
    (ROOT / 'docs/PIANO_SOURCES.json').write_text(json.dumps(metadata, indent=2) + '\n')
    print(f'Built {len(entries)} samples, {(OUT / "grand.pno").stat().st_size / 1048576:.1f} MiB')

if __name__ == '__main__':
    main()
