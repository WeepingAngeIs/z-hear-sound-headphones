"""Make fixed low-level and room-noise versions of known synthetic speech, never real microphones."""
from array import array
from pathlib import Path
import math
import random
import sys

def main(root):
    rows = []
    seed = 7254
    for line in (root / 'sources.tsv').read_text(encoding='utf-8-sig').splitlines():
        name, voice, phrase, rate, expected = line.split('\t')
        source = array('h')
        source.frombytes((root / (name + '.raw')).read_bytes())
        peak = max(map(abs, source))
        if not peak:
            raise ValueError('Empty synthesized speech')
        profiles = ['clean', 'quiet', 'room', 'tight-release'] if phrase in ('enderman', 'lopunny') and rate == '0' else ['clean']
        for profile in profiles:
            rng = random.Random(seed)
            seed += 1
            # Identical clean peak levels across installed voices. Quiet peaks at -23 dBFS.
            gain = (2300 if profile == 'quiet' else 18000) / peak
            samples = [0] * 3840 + list(source) + [0] * 11200
            if profile == 'tight-release':
                # Drop only leading/trailing synthesized silence. Keep both real word edges.
                first = next(i for i, n in enumerate(source) if abs(n) > 12)
                last = len(source) - next(i for i, n in enumerate(reversed(source)) if abs(n) > 12)
                samples = source[max(0, first - 160):min(len(source), last + 160)]
            result = array('h')
            for i, value in enumerate(samples):
                noise = 0
                if profile == 'room':
                    noise = rng.gauss(0, 110) + 260 * math.sin(2 * math.pi * 120 * i / 16000)
                result.append(max(-32768, min(32767, round(value * gain + noise))))
            key = name + '-' + profile
            (root / (key + '.pcm')).write_bytes(result.tobytes())
            rows.append((key, voice, phrase, 'fast' if rate == '2' else profile, expected))
    for kind in ('silence', 'fan', 'typing'):
        result = array('h')
        rng = random.Random(504)
        for i in range(35200):
            noise = 0
            if kind == 'fan':
                noise = 360 * math.sin(2 * math.pi * 100 * i / 16000) + rng.gauss(0, 96)
            if kind == 'typing' and i % 2700 < 75:
                noise = rng.gauss(0, 2200) * (1 - i % 2700 / 75)
            result.append(round(noise))
        result.extend([0] * 11200)
        (root / (kind + '.pcm')).write_bytes(result.tobytes())
        rows.append((kind, 'generated', kind, 'control', 'neutral'))
    (root / 'manifest.tsv').write_text(''.join('\t'.join(row) + '\n' for row in rows), encoding='utf-8')

if __name__ == '__main__':
    main(Path(sys.argv[1]))
