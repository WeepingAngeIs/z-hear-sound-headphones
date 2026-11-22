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
