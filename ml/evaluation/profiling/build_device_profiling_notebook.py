"""Build the self-contained Chapter 6 device-profiling notebook."""

from __future__ import annotations

import json
from pathlib import Path
from textwrap import dedent


ROOT = Path(__file__).resolve().parents[3]
OUTPUT = ROOT / "ml" / "notebooks" / "08_device_profiling.ipynb"


def markdown(source: str) -> dict:
    return {"cell_type": "markdown", "metadata": {}, "source": dedent(source).lstrip()}


def code(source: str) -> dict:
    return {
        "cell_type": "code",
        "execution_count": None,
        "metadata": {},
        "outputs": [],
        "source": dedent(source).lstrip(),
    }


cells = [
    markdown(
        """
        # Physical-device Profiling

        This notebook aggregates the profiling exports collected on the Redmi Note 10 Pro and generates the tables and figures used in Chapter 6. It does not run the Android benchmark itself. The raw CSV exports are stored under `ml/evaluation/profiling/raw`.

        The inference experiment contains three runs. Each run contains 10 warm-up windows and 100 measured windows. Warm-up rows are validated and excluded from every reported latency statistic. The FL experiment uses 20 balanced feedback examples, two local epochs, and a batch size of eight.
        """
    ),
    code(
        """
        from pathlib import Path
        import sys

        import pandas as pd
        from IPython.display import Image, display

        def find_repo_root(start: Path) -> Path:
            for candidate in [start, *start.parents]:
                if (candidate / "ml" / "evaluation" / "profiling" / "raw").exists():
                    return candidate
            raise FileNotFoundError("Could not locate the MindWave repository root")

        ROOT = find_repo_root(Path.cwd().resolve())
        if str(ROOT) not in sys.path:
            sys.path.insert(0, str(ROOT))

        from ml.evaluation.device_profiling_analysis import (
            PLOTS,
            RAW,
            configure_style,
            load_inference_runs,
            plot_fl_latency,
            plot_inference_latency,
            plot_inference_run_stability,
            plot_resource_summary,
            summarize_fl,
            summarize_inference,
            summarize_resources,
            validate_inference_runs,
        )

        pd.set_option("display.max_columns", None)
        configure_style()
        print(f"Repository root: {ROOT}")
        """
    ),
    markdown(
        """
        ## Device and Test Conditions

        The build was profiled while connected over USB, with the screen on and synthetic windows injected back-to-back. These conditions isolate phone-side processing after a complete sensor window is available. They do not measure watch sensing, Wear Data Layer transfer, normal idle utilization, or battery drain.
        """
    ),
    code(
        """
        metadata = pd.read_csv(RAW / "device_profile_metadata.csv")
        display(metadata)
        """
    ),
    markdown(
        """
        ## Inference-pipeline Validation

        Every raw run is checked before aggregation. The ten warm-up rows in each run are retained for provenance but excluded from the reported results.
        """
    ),
    code(
        """
        inference_runs = load_inference_runs()
        run_counts = validate_inference_runs(inference_runs)
        display(run_counts)

        assert len(run_counts) == 3
        assert (run_counts["Measured"] == 100).all()
        assert (run_counts["Warm-up"] == 10).all()
        assert int(inference_runs["isWarmup"].eq(0).sum()) == 300
        """
    ),
    markdown(
        """
        ## Phone-side Latency

        Latency is measured with `SystemClock.elapsedRealtimeNanos()` around feature extraction, normalization, TFLite inference, explanation generation, Room persistence, and immediate alert-policy evaluation. Percentiles are calculated over the 300 measured windows.
        """
    ),
    code(
        """
        inference_summary, inference_run_summary = summarize_inference(inference_runs)
        display(inference_summary.round(3))
        display(inference_run_summary.round(3))
        """
    ),
    code(
        """
        measured_count = int(inference_runs["isWarmup"].eq(0).sum())
        plot_inference_latency(inference_summary, measured_count)
        plot_inference_run_stability(inference_runs)
        display(Image(filename=str(PLOTS / "device_inference_latency.png")))
        display(Image(filename=str(PLOTS / "device_inference_run_stability.png")))
        """
    ),
    markdown(
        """
        The `MW.alert` Perfetto slices are not used for the latency table because the measured function is suspending and Android trace sections are thread-local. Coroutine suspension or thread migration can therefore inflate individual slice durations. The monotonic wall-time records persisted by the application are used instead.
        """
    ),
    markdown(
        """
        ## Federated-learning Profiling

        Four local-training executions were captured. The first execution is cold and includes initial model/delegate setup. The other three reuse initialized state. `local_wall_ms` covers load, restore, train, and weight save. Upload is reported separately, and the estimated end-to-end value adds the measured upload duration to the local wall time.
        """
    ),
    code(
        """
        fl_runs, fl_summary = summarize_fl()
        display(fl_runs.round(3))
        display(fl_summary.round(3))

        plot_fl_latency(fl_runs, fl_summary)
        display(Image(filename=str(PLOTS / "device_fl_phase_latency.png")))
        """
    ),
    markdown(
        """
        ## CPU and Memory Counters

        CPU percentages are normalized to one fully occupied core. Values above 100% indicate parallel work across more than one core, not utilization above the phone's total CPU capacity. The memory values are the Java heap and HWUI counters available in the captured interval; they are not equivalent to the complete process RSS.
        """
    ),
    code(
        """
        resource_summary = summarize_resources()
        display(resource_summary.round(3))

        plot_resource_summary(resource_summary)
        display(Image(filename=str(PLOTS / "device_resource_summary.png")))
        """
    ),
    markdown(
        """
        ## Thesis-facing Interpretation

        The phone-side pipeline can be compared with the latency requirement because timing starts after a complete sensor window is available. The benchmark cannot support a battery-life claim because the phone was charging over USB and the screen remained on. It also cannot be used as a watch-to-phone communication benchmark because all sensor windows were generated locally.
        """
    ),
    code(
        """
        total = inference_summary.loc[
            inference_summary["stage"].eq("Complete pipeline")
        ].iloc[0]
        alert = inference_summary.loc[
            inference_summary["stage"].eq("Alert-policy evaluation")
        ].iloc[0]
        warm_fl = fl_summary.loc[fl_summary["run_type"].eq("Warm mean")].iloc[0]

        print(f"Complete pipeline median: {total['median_ms']:.2f} ms")
        print(f"Complete pipeline p95:    {total['p95_ms']:.2f} ms")
        print(f"Complete pipeline p99:    {total['p99_ms']:.2f} ms")
        print(f"Alert-policy median:      {alert['median_ms']:.2f} ms")
        print(f"Warm FL local mean:       {warm_fl['local_wall_ms']:.2f} ms")
        print(f"Warm FL incl. upload:     {warm_fl['estimated_end_to_end_ms']:.2f} ms")
        """
    ),
]

notebook = {
    "cells": cells,
    "metadata": {
        "kernelspec": {
            "display_name": "Python 3",
            "language": "python",
            "name": "python3",
        },
        "language_info": {"name": "python", "version": "3.12"},
    },
    "nbformat": 4,
    "nbformat_minor": 5,
}

OUTPUT.parent.mkdir(parents=True, exist_ok=True)
OUTPUT.write_text(json.dumps(notebook, indent=1) + "\n", encoding="utf-8")
print(f"Wrote {OUTPUT}")
