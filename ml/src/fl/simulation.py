"""End-to-end Flower simulation"""
from __future__ import annotations

import argparse
import json
from dataclasses import dataclass
from pathlib import Path

import flwr as fl
import numpy as np
import tensorflow as tf

from ..config import MODELS_DIR, PROCESSED_DIR, set_global_seed
from ..model import build_lstm
from .client import make_client_fn
from .data_partition import partition_by_subject, summarize_partitions
from .server import make_server_fn, parameters_to_weights


# Run config
@dataclass
class FLRunConfig:
    data_path: Path = PROCESSED_DIR / "wesad_wrist.npz"
    init_from: Path | None = MODELS_DIR / "mindwave_stress.keras"
    output: Path = MODELS_DIR / "fl_global.keras"
    rounds: int = 3
    local_epochs: int = 1
    batch_size: int = 32
    subjects: list[str] | None = None
    warm_start: bool = True


# Helpers
def _initial_weights(
    cfg: FLRunConfig, input_shape, n_classes: int
) -> tuple[list[np.ndarray], tf.keras.Model]:
    """Return (initial_weights, template_model)."""
    template = build_lstm(input_shape=input_shape, n_classes=n_classes)

    if cfg.warm_start and cfg.init_from is not None and Path(cfg.init_from).exists():
        print(f"Warm-starting from {cfg.init_from}")
        loaded = tf.keras.models.load_model(cfg.init_from, compile=False)
        try:
            template.set_weights(loaded.get_weights())
        except ValueError as exc:
            print(f"  Could not copy weights ({exc}); using fresh init.")
    else:
        print("Cold-starting from a freshly initialised model.")

    return template.get_weights(), template


# Public api
@dataclass
class SimHistory:
    """Aggregated metrics recorded during the simulation"""
    losses_distributed: list[tuple[int, float]]
    metrics_distributed: dict[str, list[tuple[int, float]]]
    metrics_distributed_fit: dict[str, list[tuple[int, float]]]


def run(cfg: FLRunConfig):
    """Run the full simulation and return the history & final weights."""
    set_global_seed()

    partitions = partition_by_subject(cfg.data_path, subjects=cfg.subjects)
    if not partitions:
        raise RuntimeError("No client partitions built")
    print("Client partitions:")
    print(summarize_partitions(partitions))

    num_clients = len(partitions)
    sample = next(iter(partitions.values()))
    n_classes = int(max(p.y_train.max() for p in partitions.values()) + 1)

    init_weights, template = _initial_weights(
        cfg, input_shape=sample.X_train.shape[1:], n_classes=n_classes,
    )
    initial_parameters = fl.common.ndarrays_to_parameters(init_weights)

    client_fn = make_client_fn(
        partitions=partitions,
        init_weights=init_weights,
        local_epochs=cfg.local_epochs,
        batch_size=cfg.batch_size,
    )
    server_fn = make_server_fn(
        initial_parameters=initial_parameters,
        num_clients=num_clients,
        num_rounds=cfg.rounds,
    )

    client_app = fl.client.ClientApp(client_fn=client_fn)
    server_app = fl.server.ServerApp(server_fn=server_fn)

    print(f"\n   Starting Flower simulation: "
          f"{num_clients} clients × {cfg.rounds} rounds × "
          f"{cfg.local_epochs} local epochs \n")
    fl.simulation.run_simulation(
        server_app=server_app,
        client_app=client_app,
        num_supernodes=num_clients,
        backend_config={"client_resources": {"num_cpus": 1, "num_gpus": 0}},
    )

    # Recover final weights and build history from the strategy.
    strategy = server_fn.strategy 
    history = SimHistory(
        losses_distributed=strategy.losses_distributed,
        metrics_distributed=strategy.metrics_distributed,
        metrics_distributed_fit=strategy.metrics_distributed_fit,
    )
    final_weights: list[np.ndarray] | None = None
    if strategy.final_parameters is not None:
        final_weights = parameters_to_weights(strategy.final_parameters)
        template.set_weights(final_weights)
        cfg.output.parent.mkdir(parents=True, exist_ok=True)
        template.save(cfg.output)
        print(f"\nFinal global model saved at {cfg.output}")
    else:
        print("\nNo aggregated parameters were captured.")

    _print_history(history)
    _save_history_json(history, cfg.output.with_suffix(".history.json"))

    return history, final_weights


# History helpers
def _print_history(history: SimHistory) -> None:
    print("\n=== Aggregated metrics per round ===")
    if history.losses_distributed:
        print("  losses_distributed:")
        for r, loss in history.losses_distributed:
            print(f"    round {r}: loss={loss:.4f}")
    if history.metrics_distributed_fit:
        print("  fit metrics (weighted avg over clients):")
        for k, series in history.metrics_distributed_fit.items():
            for r, v in series:
                print(f"    round {r} {k}={v:.4f}")
    if history.metrics_distributed:
        print("  evaluate metrics (weighted avg over clients):")
        for k, series in history.metrics_distributed.items():
            for r, v in series:
                print(f"    round {r} {k}={v:.4f}")


def _save_history_json(history: SimHistory, out: Path) -> None:
    payload = {
        "losses_distributed": history.losses_distributed,
        "metrics_distributed_fit": {
            k: list(v) for k, v in history.metrics_distributed_fit.items()
        },
        "metrics_distributed": {
            k: list(v) for k, v in history.metrics_distributed.items()
        },
    }
    out.write_text(json.dumps(payload, indent=2, default=float))
    print(f"History → {out}")


# CLI
def _parse_args() -> FLRunConfig:
    p = argparse.ArgumentParser(description="Run the MindWave FL simulation")
    p.add_argument("--data", type=Path,
                   default=PROCESSED_DIR / "wesad_wrist.npz")
    p.add_argument("--init-from", type=Path,
                   default=MODELS_DIR / "mindwave_stress.keras")
    p.add_argument("--output", type=Path, default=MODELS_DIR / "fl_global.keras")
    p.add_argument("--rounds", type=int, default=3)
    p.add_argument("--local-epochs", type=int, default=2)
    p.add_argument("--batch-size", type=int, default=32)
    p.add_argument("--subjects", nargs="*", default=None,
                   help="Optional subset of subjects (e.g. S2 S3 S4).")
    p.add_argument("--no-warm-start", dest="warm_start", action="store_false",
                   help="Ignore the Stage-1 model and start from a fresh init.")
    args = p.parse_args()
    return FLRunConfig(
        data_path=args.data,
        init_from=args.init_from,
        output=args.output,
        rounds=args.rounds,
        local_epochs=args.local_epochs,
        batch_size=args.batch_size,
        subjects=args.subjects,
        warm_start=args.warm_start,
    )


def main() -> None:
    cfg = _parse_args()
    run(cfg)


if __name__ == "__main__":
    main()

