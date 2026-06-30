"""Extract benchmark CSV blocks from a pasted MindWave profiling transcript."""

from __future__ import annotations

import argparse
import io
import re
from pathlib import Path

import pandas as pd


HEADER = (
    "windowId,featureUs,normalizeUs,inferenceUs,xaiUs,roomUs,"
    "alertUs,totalUs,isWarmup"
)
BLOCK_PATTERN = re.compile(
    rf"{re.escape(HEADER)}\r?\n((?:\d+(?:,\d+){{8}}\r?\n?)+)"
)


def extract_runs(transcript: Path, output_dir: Path) -> list[Path]:
    text = transcript.read_text(encoding="utf-8")
    blocks = BLOCK_PATTERN.findall(text)
    if not blocks:
        raise ValueError(f"No MindWave latency CSV blocks found in {transcript}")

    output_dir.mkdir(parents=True, exist_ok=True)
    written: list[Path] = []
    for run_number, block in enumerate(blocks, start=1):
        frame = pd.read_csv(io.StringIO(HEADER + "\n" + block.strip()))
        destination = output_dir / f"device_inference_run_{run_number}.csv"
        frame.to_csv(destination, index=False)
        written.append(destination)
        measured = int((frame["isWarmup"] == 0).sum())
        warmups = int((frame["isWarmup"] == 1).sum())
        print(
            f"run {run_number}: {len(frame)} rows "
            f"({measured} measured, {warmups} warm-up) -> {destination}"
        )
    return written


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("transcript", type=Path)
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=Path("ml/evaluation/profiling/raw"),
    )
    args = parser.parse_args()
    extract_runs(args.transcript, args.output_dir)


if __name__ == "__main__":
    main()
