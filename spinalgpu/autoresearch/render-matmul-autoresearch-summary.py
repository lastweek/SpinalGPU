#!/usr/bin/env python3

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path


def load_rounds(path: Path) -> list[dict]:
    rows: list[dict] = []
    for line in path.read_text().splitlines():
        line = line.strip()
        if not line:
            continue
        rows.append(json.loads(line))
    rows.sort(key=lambda row: int(row["round"]))
    return rows


def fmt_round(row: dict | None, cycle_key: str) -> str:
    if row is None:
        return "n/a"
    return f"round {row['round']} ({row[cycle_key]} cycles)"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--session", required=True)
    args = parser.parse_args()

    repo_root = Path(__file__).resolve().parents[2]
    log_root = Path(os.environ.get("SPINALGPU_AUTORESEARCH_LOG_ROOT", str(repo_root / "spinalgpu" / "autoresearch-logs")))
    session_dir = log_root / "sessions" / args.session
    rounds_path = session_dir / "rounds.jsonl"
    summary_path = session_dir / "summary.md"

    rows = load_rounds(rounds_path)
    accepted = [row for row in rows if row.get("decision") == "accepted"]
    latest_accepted = accepted[-1] if accepted else None
    best_tcgen = min(accepted, key=lambda row: int(row["tcgen05_cycles"])) if accepted else None
    best_cuda = min(accepted, key=lambda row: int(row["cuda_core_cycles"])) if accepted else None
    last_five = rows[-5:]
    next_focus = latest_accepted.get("next_focus", "n/a") if latest_accepted else "n/a"

    lines = [
        "# Matmul AutoResearch Summary",
        "",
        f"Session: `{args.session}`",
        "",
        "## Current Accepted Baseline",
        "",
        f"- tcgen05: {fmt_round(latest_accepted, 'tcgen05_cycles')}",
        f"- CUDA core: {fmt_round(latest_accepted, 'cuda_core_cycles')}",
        "",
        "## Best Results",
        "",
        f"- Best tcgen05: {fmt_round(best_tcgen, 'tcgen05_cycles')}",
        f"- Best CUDA core: {fmt_round(best_cuda, 'cuda_core_cycles')}",
        "",
        "## Last 5 Rounds",
        "",
        "| Round | Decision | CUDA cycles | tcgen05 cycles | Correctness | Fault | Next focus |",
        "| ---: | --- | ---: | ---: | --- | --- | --- |",
    ]

    for row in last_five:
        lines.append(
            f"| {row['round']} | {row['decision']} | {row['cuda_core_cycles']} | {row['tcgen05_cycles']} | "
            f"{row['correctness_passed']} | {row['fault']} | {row['next_focus']} |"
        )

    lines.extend(
        [
            "",
            "## Next Suggested Focus",
            "",
            f"- `{next_focus}`",
            "",
        ]
    )

    summary_path.write_text("\n".join(lines))
    print(f"wrote {summary_path}")


if __name__ == "__main__":
    main()
