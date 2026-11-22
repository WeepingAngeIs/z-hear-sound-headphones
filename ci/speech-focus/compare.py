"""Print only generated fixture labels and the resolved registry decision from each revision."""
import csv
from collections import Counter
from pathlib import Path
import sys

root = Path(sys.argv[1])
def read(name):
    with (root / (name + '.tsv')).open(encoding='utf-8', newline='') as handle:
        return list(csv.DictReader(handle, delimiter='\t'))

before, after = read('baseline'), read('candidate')
assert len(before) == len(after) > 30
for label, rows in (('baseline', before), ('candidate', after)):
    times = sorted(int(row['maxMillis']) for row in rows if int(row['segments']) > 0)
    p95 = times[(len(times)-1) * 95 // 100]
    print('FOCUS_LATENCY', label, 'p95_ms='+str(p95), 'max_ms='+str(times[-1]))
    if label == 'candidate':
        assert p95 < 1000 and times[-1] < 4000, 'Recognition exceeded the player-facing timing budget'
counts = Counter()
wrong = []
for b, a in zip(before, after):
    assert all(b[k] == a[k] for k in ('fixture', 'voice', 'phrase', 'profile', 'mode', 'expected'))
    for revision, row in [('baseline', b), ('candidate', a)]:
        outcome = 'exact' if row['actual'] == row['expected'] else 'miss' if row['actual'] == 'neutral' else 'wrong'
        group = row['phrase'] if row['expected'] != 'neutral' else 'negative'
        counts[revision, group, outcome] += 1
        if revision == 'candidate' and outcome == 'wrong':
            wrong.append((row['fixture'], row['mode'], row['actual']))
    print('FOCUS_CASE', a['voice'], a['phrase'], a['profile'], a['mode'], 'baseline='+b['actual'],
          'candidate='+a['actual'], 'segments='+b['segments']+'/'+a['segments'])
for key, count in sorted(counts.items()):
    print('FOCUS_TOTAL', *key, count)
if wrong:
    raise SystemExit('Wrong registered cue for generated fixtures: ' + str(wrong))

for row in after:
    if row['phrase'].startswith('sentence-'):
        assert row['actual'] == row['expected'], ('Sentence decision', row['fixture'], row['mode'], row['actual'])

for target in ('ENDERMAN', 'cobblemon:lopunny'):
    old = sum(b['expected'] == b['actual'] == target for b in before)
    new = sum(a['expected'] == a['actual'] == target for a in after)
    print('FOCUS_ACCEPTANCE', target, 'baseline='+str(old), 'candidate='+str(new))
    assert new >= old, 'Previously reliable name regressed: ' + target
    if target == 'cobblemon:lopunny' and any(b['expected'] == target != b['actual'] for b in before):
        assert new > old, 'No measurable pronunciation improvement for Lopunny'
    for mode in ('ptt', 'activation'):
        rows = [a for a in after if a['expected'] == target and a['mode'] == mode]
        assert sum(a['actual'] == target for a in rows) >= len(rows) // 2, (target, mode)
    for phrase in (('ender-stressed', 'ender-unstressed') if target == 'ENDERMAN' else ('lop-schwa', 'low-punny')):
        assert counts['candidate', phrase, 'exact'] >= 2, 'Pronunciation lost: ' + phrase
for row in after:
    if row['phrase'] in ('lop-bunny', 'lop-schwa', 'low-punny'):
        assert row['actual'] == row['expected'], ('Explicit Lopunny pronunciation lost', row['fixture'], row['mode'])
for neighbor in ('buneary', 'endermite', 'poponee', 'hoglin', 'zoglin'):
    assert counts['candidate', neighbor, 'exact'] >= max(1, counts['baseline', neighbor, 'exact'] - 1), neighbor
