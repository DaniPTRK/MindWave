"""Flower server: orchestration + aggregation strategy"""
from __future__ import annotations

from typing import Callable

import flwr as fl
import numpy as np

# Metric aggregation
def weighted_average(metrics: list[tuple[int, dict]]) -> dict:
    """Sample-weighted average of scalar client metrics"""
    if not metrics:
        return {}
    total = sum(n for n, _ in metrics)
    if total == 0:
        return {}
    out: dict[str, float] = {}
    for key in metrics[0][1].keys():
        out[key] = float(sum(n * m[key] for n, m in metrics) / total)
    return out


# Custom FedAvg that remembers the final aggregated parameters.
class FedAvgWithSnapshot(fl.server.strategy.FedAvg):
    """FedAvg that stashes the latest aggregated parameters server-side
    and records per-round loss / metrics
    """

    def __init__(self, *args, **kwargs) -> None:
        super().__init__(*args, **kwargs)
        self.final_parameters: fl.common.Parameters | None = None

        # Manual history
        self.losses_distributed: list[tuple[int, float]] = []
        self.metrics_distributed: dict[str, list[tuple[int, float]]] = {}
        self.metrics_distributed_fit: dict[str, list[tuple[int, float]]] = {}

    def aggregate_fit(self, server_round, results, failures):
        aggregated, metrics = super().aggregate_fit(server_round, results, failures)
        if aggregated is not None:
            self.final_parameters = aggregated
        for k, v in (metrics or {}).items():
            self.metrics_distributed_fit.setdefault(k, []).append((server_round, v))
        return aggregated, metrics

    def aggregate_evaluate(self, server_round, results, failures):
        loss, metrics = super().aggregate_evaluate(server_round, results, failures)
        if loss is not None:
            self.losses_distributed.append((server_round, loss))
        for k, v in (metrics or {}).items():
            self.metrics_distributed.setdefault(k, []).append((server_round, v))
        return loss, metrics


# Strategy factory
def build_strategy(
    initial_parameters: fl.common.Parameters,
    num_clients: int,
) -> FedAvgWithSnapshot:
    """FedAvg with everyone participating in every round"""
    return FedAvgWithSnapshot(
        fraction_fit=1.0,
        fraction_evaluate=1.0,
        min_fit_clients=num_clients,
        min_evaluate_clients=num_clients,
        min_available_clients=num_clients,
        initial_parameters=initial_parameters,
        fit_metrics_aggregation_fn=weighted_average,
        evaluate_metrics_aggregation_fn=weighted_average,
    )


# server_fn factory used by ServerApp
def make_server_fn(
    initial_parameters: fl.common.Parameters,
    num_clients: int,
    num_rounds: int,
) -> Callable[[fl.common.Context], fl.server.ServerAppComponents]:
    """Build a server_fn bound to the given run config"""
    strategy = build_strategy(initial_parameters, num_clients)

    def server_fn(context: fl.common.Context) -> fl.server.ServerAppComponents:
        return fl.server.ServerAppComponents(
            strategy=strategy,
            config=fl.server.ServerConfig(num_rounds=num_rounds),
        )

    # Expose the strategy so the simulation runner can read final_parameters
    server_fn.strategy = strategy
    return server_fn


def parameters_to_weights(parameters: fl.common.Parameters) -> list[np.ndarray]:
    """Convenience wrapper to convert aggregated params back into Keras-compatible weights"""
    return fl.common.parameters_to_ndarrays(parameters)


__all__ = [
    "FedAvgWithSnapshot",
    "build_strategy",
    "make_server_fn",
    "parameters_to_weights",
    "weighted_average",
]

