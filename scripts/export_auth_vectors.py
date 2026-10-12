"""Export only non-personal challenge/seed/output fields from an ARM64 replay."""
import argparse
import json
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("input", type=Path)
args = parser.parse_args()
data = json.loads(args.input.read_text())
assert data["status"] == "pass" or data["status"] == "PASS", data["status"]
rows = [f'{v["challenge"]}\t{v["seed"]}\t{v["response"]}' for v in data["vectors"]]
assert len(rows) == 516
target = Path(__file__).resolve().parents[1] / "app/src/test/resources/native-auth-vectors.tsv"
target.parent.mkdir(parents=True, exist_ok=True)
target.write_text("# Original ARM64 replay; challenge\tseed\tresponse\n"+"\n".join(rows)+"\n")
print(f"Exported {len(rows)} original-code vectors to {target}")
