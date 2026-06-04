"""Vendored Flower aggregator, copy of ml/src/fl/server.py"""
from __future__ import annotations
from typing import Callable
import flwr as fl

def weighted_average(metrics: list[tuple[int, dict]]) -> dict:
    """Sample-weighted aggregator for client-reported metric dicts."""
    if not metrics:
        return {}
    total = sum(n for n, _ in metrics)
    if total == 0:
        return {}
    out: dict[str, float] = {}
    for key in metrics[0][1].keys():
        out[key] = float(sum(n * m[key] for n, m in metrics) / total)
    return out

class FedAvgWithSnapshot(fl.server.strategy.FedAvg):
    """FedAvg that stashes the latest aggregated parameters server-side."""
    def __init__(self, *args, **kwargs) -> None:
        super().__init__(*args, **kwargs)
        self.final_parameters: fl.common.Parameters | None = None
        self.last_metrics: dict = {}

    def aggregate_fit(self, server_round, results, failures):
        aggregated, metrics = super().aggregate_fit(server_round, results, failures)
        if aggregated is not None:
            self.final_parameters = aggregated
        return aggregated, metrics

    def aggregate_evaluate(self, server_round, results, failures):
        loss, metrics = super().aggregate_evaluate(server_round, results, failures)
        self.last_metrics = {"loss": loss, **(metrics or {})}
        return loss, metrics


def build_strategy(
    initial_parameters: fl.common.Parameters,
    num_clients: int,
) -> FedAvgWithSnapshot:
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