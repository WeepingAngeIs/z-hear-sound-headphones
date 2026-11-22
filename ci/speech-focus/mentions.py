"""Assert independent outcomes for synthesized words in each sentence position and for near words."""
import csv
from pathlib import Path
import sys
from sentence_results import failures

root = Path(sys.argv[1])
with (root / "mentions.tsv").open(encoding="utf-8") as handle:
    rows = list(csv.DictReader(handle, delimiter="\t"))
assert rows
for row in rows:
    print("MENTION_CASE", row["fixture"], row["mode"], "expected=" + row["expected"], "actual=" + row["actual"],
          "segments=" + row["segments"], "ms=" + row["maxMillis"], flush=True)
wrong = failures(rows)
if wrong:
    from trace_mentions import show
    show(root)
    raise AssertionError(wrong)
