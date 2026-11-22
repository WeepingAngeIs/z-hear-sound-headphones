"""Assert independent outcomes for synthesized words in each sentence position and for near words."""
import csv
from pathlib import Path
import sys

root = Path(sys.argv[1])
with (root / "mentions.tsv").open(encoding="utf-8") as handle:
    rows = list(csv.DictReader(handle, delimiter="\t"))
assert rows
wrong = []
for row in rows:
    print("MENTION_CASE", row["fixture"], row["mode"], "expected=" + row["expected"], "actual=" + row["actual"],
          "segments=" + row["segments"], "ms=" + row["maxMillis"], flush=True)
    if row["phrase"].startswith("sentence-") and row["actual"] != row["expected"]:
        wrong.append((row["fixture"], row["mode"], row["actual"]))
if wrong:
    from trace_mentions import show
    show(root)
    raise AssertionError(wrong)
