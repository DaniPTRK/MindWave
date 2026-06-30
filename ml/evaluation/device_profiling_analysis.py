"""Aggregate physical-phone profiling exports and generate thesis artifacts."""

from __future__ import annotations

from pathlib import Path

import matplotlib.pyplot as plt
import numpy as np
import pandas as pd
import seaborn as sns


ROOT = Path(__file__).resolve().parents[2]
RAW = ROOT / "ml" / "evaluation" / "profiling" / "raw"
RESULTS = ROOT / "ml" / "evaluation" / "results"
PLOTS = ROOT / "ml" / "evaluation" / "plots"
THESIS_PICS = ROOT / "thesis" / "pics"

TEAL = "#2a9d8f"
BLUE = "#457b9d"
LIGHT_BLUE = "#8ecae6"
ORANGE = "#e76f51"
GRAY = "#6c757d"

STAGES = [
    ("featureUs", "Feature extraction"),
    ("normalizeUs", "Normalization"),
    ("inferenceUs", "TFLite inference"),
    ("xaiUs", "XAI generation"),
    ("roomUs", "Room persistence"),
    ("alertUs", "Alert-policy evaluation"),
    ("totalUs", "Complete pipeline"),
]

EXPECTED_COLUMNS = {
    "windowId",
    "featureUs",
    "normalizeUs",
    "inferenceUs",
    "xaiUs",
    "roomUs",
    "alertUs",
    "totalUs",
    "isWarmup",
}


def configure_style() -> None:
    sns.set_theme(style="whitegrid", context="paper", font_scale=1.05)
    plt.rcParams.update(
        {"figure.dpi": 120, "savefig.bbox": "tight", "font.size": 10}
    )


def save_figure(fig: plt.Figure, filename: str) -> None:
    PLOTS.mkdir(parents=True, exist_ok=True)
    THESIS_PICS.mkdir(parents=True, exist_ok=True)
    for directory in (PLOTS, THESIS_PICS):
        fig.savefig(directory / filename, dpi=180, bbox_inches="tight")
    plt.close(fig)


def load_inference_runs() -> pd.DataFrame:
    files = sorted(RAW.glob("device_inference_run_*.csv"))
    if not files:
        raise FileNotFoundError(f"No inference runs found under {RAW}")

    frames: list[pd.DataFrame] = []
    for run_number, path in enumerate(files, start=1):
        frame = pd.read_csv(path)
        missing = EXPECTED_COLUMNS.difference(frame.columns)
        if missing:
            raise ValueError(f"{path.name} is missing columns: {sorted(missing)}")
        frame.insert(0, "run", run_number)
        frame.insert(1, "source_file", path.name)
        frames.append(frame)
    return pd.concat(frames, ignore_index=True)


def validate_inference_runs(data: pd.DataFrame) -> pd.DataFrame:
    counts = (
        data.assign(
            sample_type=np.where(data["isWarmup"].eq(1), "Warm-up", "Measured")
        )
        .groupby(["run", "sample_type"])
        .size()
        .unstack(fill_value=0)
        .reset_index()
    )
    counts.columns.name = None
    counts.to_csv(RESULTS / "device_inference_run_counts.csv", index=False)
    return counts


def summarize_inference(data: pd.DataFrame) -> tuple[pd.DataFrame, pd.DataFrame]:
    measured = data.loc[data["isWarmup"].eq(0)].copy()
    rows = []
    for column, label in STAGES:
        values = measured[column].astype(float).to_numpy() / 1000.0
        rows.append(
            {
                "stage": label,
                "count": len(values),
                "mean_ms": values.mean(),
                "median_ms": np.median(values),
                "p95_ms": np.percentile(values, 95),
                "p99_ms": np.percentile(values, 99),
                "max_ms": values.max(),
            }
        )
    summary = pd.DataFrame(rows)

    measured["total_ms"] = measured["totalUs"] / 1000.0
    run_rows = []
    for run_number, group in measured.groupby("run"):
        values = group["total_ms"].to_numpy()
        run_rows.append(
            {
                "run": run_number,
                "measured_windows": len(values),
                "mean_ms": values.mean(),
                "median_ms": np.median(values),
                "p95_ms": np.percentile(values, 95),
                "p99_ms": np.percentile(values, 99),
                "max_ms": values.max(),
            }
        )
    run_summary = pd.DataFrame(run_rows)

    RESULTS.mkdir(parents=True, exist_ok=True)
    summary.to_csv(RESULTS / "device_inference_profiling.csv", index=False)
    run_summary.to_csv(RESULTS / "device_inference_run_summary.csv", index=False)
    return summary, run_summary


def plot_inference_latency(summary: pd.DataFrame, measured_count: int) -> None:
    stage_rows = summary.iloc[:5].copy()
    alert = summary.loc[summary["stage"].eq("Alert-policy evaluation")].iloc[0]
    total = summary.loc[summary["stage"].eq("Complete pipeline")].iloc[0]
    metrics = [
        ("median_ms", "Median", TEAL),
        ("p95_ms", "P95", LIGHT_BLUE),
        ("p99_ms", "P99", ORANGE),
    ]

    fig, axes = plt.subplots(
        1,
        3,
        figsize=(12.4, 4.5),
        gridspec_kw={"width_ratios": [1.8, 0.85, 0.85]},
    )
    y = np.arange(len(stage_rows))
    height = 0.22
    for offset, (column, label, color) in zip((-height, 0, height), metrics):
        axes[0].barh(
            y + offset,
            stage_rows[column],
            height=height,
            label=label,
            color=color,
        )
    axes[0].set_yticks(y, stage_rows["stage"])
    axes[0].invert_yaxis()
    axes[0].set_xlabel("Latency (ms)")
    axes[0].set_title("Compute and persistence stages")
    axes[0].legend(frameon=False, loc="upper right")

    for axis, row, title in [
        (axes[1], alert, "Alert policy"),
        (axes[2], total, "Complete pipeline"),
    ]:
        values = [row[column] for column, _, _ in metrics]
        bars = axis.bar(
            [label for _, label, _ in metrics],
            values,
            color=[color for _, _, color in metrics],
            width=0.68,
        )
        axis.set_title(title)
        axis.set_ylabel("Latency (ms)")
        axis.tick_params(axis="x", rotation=20)
        axis.bar_label(bars, fmt="%.1f", padding=3, fontsize=8)
        axis.set_ylim(0, max(values) * 1.25)

    fig.suptitle(
        f"Physical-phone latency across {measured_count} measured windows"
    )
    fig.tight_layout()
    save_figure(fig, "device_inference_latency.png")


def plot_inference_run_stability(data: pd.DataFrame) -> None:
    measured = data.loc[data["isWarmup"].eq(0)].copy()
    measured["total_ms"] = measured["totalUs"] / 1000.0

    fig, axes = plt.subplots(1, 2, figsize=(10.4, 4.2))
    sns.boxplot(
        data=measured,
        x="run",
        y="total_ms",
        color=LIGHT_BLUE,
        width=0.55,
        ax=axes[0],
    )
    sns.stripplot(
        data=measured,
        x="run",
        y="total_ms",
        color=BLUE,
        alpha=0.35,
        size=2.5,
        ax=axes[0],
    )
    axes[0].set(
        title="Complete-pipeline distribution by run",
        xlabel="Run",
        ylabel="Latency (ms)",
    )

    stage_long = measured.melt(
        id_vars="run",
        value_vars=["featureUs", "inferenceUs", "xaiUs", "roomUs", "alertUs"],
        var_name="stage",
        value_name="latency_us",
    )
    stage_long["latency_ms"] = stage_long["latency_us"] / 1000.0
    stage_long["stage"] = stage_long["stage"].map(
        {
            "featureUs": "Features",
            "inferenceUs": "Inference",
            "xaiUs": "XAI",
            "roomUs": "Room",
            "alertUs": "Alert",
        }
    )
    sns.pointplot(
        data=stage_long,
        x="run",
        y="latency_ms",
        hue="stage",
        errorbar=None,
        palette=[TEAL, BLUE, ORANGE, GRAY, "#9b5de5"],
        ax=axes[1],
    )
    axes[1].set(
        title="Mean stage latency by run",
        xlabel="Run",
        ylabel="Latency (ms)",
    )
    axes[1].legend(title="", frameon=False, fontsize=8)
    fig.tight_layout()
    save_figure(fig, "device_inference_run_stability.png")


def summarize_fl() -> tuple[pd.DataFrame, pd.DataFrame]:
    data = pd.read_csv(RAW / "device_fl_phase_runs.csv")
    local_phase_columns = ["load_ms", "restore_ms", "train_ms", "save_ms"]
    data["local_phase_sum_ms"] = data[local_phase_columns].sum(axis=1)
    data["estimated_end_to_end_ms"] = data["local_wall_ms"] + data["upload_ms"]
    data.to_csv(RESULTS / "device_fl_profiling.csv", index=False)

    value_columns = [
        "load_ms",
        "restore_ms",
        "train_ms",
        "save_ms",
        "upload_ms",
        "local_wall_ms",
        "estimated_end_to_end_ms",
    ]
    cold = data.loc[data["run_type"].eq("Cold")].iloc[0]
    warm = data.loc[data["run_type"].eq("Warm")]
    summary = pd.DataFrame(
        [
            {"run_type": "Cold", **cold[value_columns].to_dict()},
            {"run_type": "Warm mean", **warm[value_columns].mean().to_dict()},
        ]
    )
    summary.to_csv(RESULTS / "device_fl_summary.csv", index=False)
    return data, summary


def plot_fl_latency(data: pd.DataFrame, summary: pd.DataFrame) -> None:
    cold = summary.loc[summary["run_type"].eq("Cold")].iloc[0]
    warm = summary.loc[summary["run_type"].eq("Warm mean")].iloc[0]
    phases = ["load_ms", "restore_ms", "train_ms", "save_ms"]
    labels = ["Model load", "Weight restore", "Local training", "Weight save"]
    y = np.arange(len(phases))
    height = 0.34

    fig, axes = plt.subplots(
        1,
        2,
        figsize=(10.6, 4.5),
        gridspec_kw={"width_ratios": [1.65, 1]},
    )
    cold_bars = axes[0].barh(
        y - height / 2,
        [cold[p] for p in phases],
        height,
        label="Cold execution",
        color=ORANGE,
    )
    warm_bars = axes[0].barh(
        y + height / 2,
        [warm[p] for p in phases],
        height,
        label="Warm mean",
        color=TEAL,
    )
    axes[0].set_yticks(y, labels)
    axes[0].invert_yaxis()
    axes[0].set_xlabel("Latency (ms)")
    axes[0].set_title("Federated-learning phases")
    axes[0].legend(frameon=False)
    axes[0].bar_label(cold_bars, fmt="%.1f", padding=3, fontsize=8)
    axes[0].bar_label(warm_bars, fmt="%.1f", padding=3, fontsize=8)
    axes[0].set_xlim(0, max(cold[phases]) * 1.28)

    totals = data[["run", "local_wall_ms"]].melt(
        id_vars="run", var_name="scope", value_name="latency_ms"
    )
    totals["scope"] = totals["scope"].map(
        {
            "local_wall_ms": "Local processing",
        }
    )
    sns.barplot(
        data=totals,
        x="run",
        y="latency_ms",
        hue="scope",
        palette=[BLUE],
        ax=axes[1],
    )
    axes[1].set(
        title="FL latency by execution",
        xlabel="Run (1 is cold)",
        ylabel="Latency (ms)",
    )
    axes[1].legend(title="", frameon=False, fontsize=8)
    fig.tight_layout()
    save_figure(fig, "device_fl_phase_latency.png")


def summarize_resources() -> pd.DataFrame:
    perfetto = pd.read_csv(RAW / "device_perfetto_summary.csv")
    memory = pd.read_csv(RAW / "device_memory_counters.csv")
    inference_cores = perfetto.loc[
        perfetto["scope"].eq("Inference benchmark")
        & perfetto["metric"].eq("Average one-core utilization"),
        "value",
    ].iloc[0] / 100.0
    fl_cores = perfetto.loc[
        perfetto["scope"].eq("Cold FL run")
        & perfetto["metric"].eq("Average one-core utilization"),
        "value",
    ].iloc[0] / 100.0
    heap = memory.loc[memory["counter"].eq("Heap size")].iloc[0]
    hwui = memory.loc[memory["counter"].eq("HWUI All Memory")].iloc[0]
    summary = pd.DataFrame(
        [
            {
                "scope": "Inference benchmark",
                "metric": "Equivalent occupied CPU cores",
                "average": inference_cores,
                "peak": np.nan,
                "unit": "cores",
            },
            {
                "scope": "Cold FL run",
                "metric": "Equivalent occupied CPU cores",
                "average": fl_cores,
                "peak": np.nan,
                "unit": "cores",
            },
            {
                "scope": "Cold FL run",
                "metric": "Java heap size counter",
                "average": heap["average"] / 1024.0,
                "peak": heap["peak"] / 1024.0,
                "unit": "MiB",
            },
            {
                "scope": "Cold FL run",
                "metric": "HWUI All Memory counter",
                "average": hwui["average"] / (1024.0**2),
                "peak": hwui["peak"] / (1024.0**2),
                "unit": "MiB",
            },
        ]
    )
    summary.to_csv(RESULTS / "device_resource_summary.csv", index=False)
    return summary


def plot_resource_summary(summary: pd.DataFrame) -> None:
    cpu = summary.loc[summary["unit"].eq("cores")]
    memory = summary.loc[summary["unit"].eq("MiB")]
    fig, axes = plt.subplots(1, 2, figsize=(9.8, 4.1))
    cpu_bars = axes[0].bar(
        ["Inference\nbenchmark", "Cold FL\nrun"],
        cpu["average"],
        color=[TEAL, ORANGE],
        width=0.58,
    )
    axes[0].bar_label(cpu_bars, fmt="%.2f", padding=3)
    axes[0].set(
        title="Average CPU occupancy",
        ylabel="Equivalent fully occupied cores",
    )
    axes[0].set_ylim(0, max(cpu["average"]) * 1.25)

    memory_bars = axes[1].bar(
        ["Java heap", "HWUI memory"],
        memory["average"],
        color=[BLUE, LIGHT_BLUE],
        width=0.58,
    )
    axes[1].bar_label(memory_bars, fmt="%.2f", padding=3)
    axes[1].set(title="Observed memory counters", ylabel="MiB")
    axes[1].set_ylim(0, max(memory["average"]) * 1.25)
    fig.tight_layout()
    save_figure(fig, "device_resource_summary.png")


def run_analysis() -> dict[str, pd.DataFrame]:
    configure_style()
    inference = load_inference_runs()
    counts = validate_inference_runs(inference)
    inference_summary, run_summary = summarize_inference(inference)
    measured_count = int(inference["isWarmup"].eq(0).sum())
    plot_inference_latency(inference_summary, measured_count)
    plot_inference_run_stability(inference)

    fl_runs, fl_summary = summarize_fl()
    plot_fl_latency(fl_runs, fl_summary)
    resource_summary = summarize_resources()
    plot_resource_summary(resource_summary)
    return {
        "run_counts": counts,
        "inference_summary": inference_summary,
        "inference_run_summary": run_summary,
        "fl_runs": fl_runs,
        "fl_summary": fl_summary,
        "resource_summary": resource_summary,
    }


def main() -> None:
    artifacts = run_analysis()
    for name, frame in artifacts.items():
        print(f"\n{name}")
        print(frame.to_string(index=False))


if __name__ == "__main__":
    main()
