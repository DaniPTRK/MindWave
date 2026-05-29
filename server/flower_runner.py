"""Production Flower SuperLink server.

Loads the latest baseline model from the shared volume, starts
the Flower server on port 9092, and POSTs a round summary to the
FastAPI service at the end of the run.
"""
from __future__ import annotations

import logging
import os
import time
from pathlib import Path

import flwr as fl
import numpy as np
import requests
import tensorflow as tf

from flower_app import FedAvgWithSnapshot, build_strategy
from pydantic import BaseSettings, Field

logging.basicConfig(level=logging.INFO, format="[FL] %(message)s")
log = logging.getLogger(__name__)


class FlowerSettings(BaseSettings):
    models_dir: str = Field(default="/models")
    api_base_url: str = Field(default="http://api:8000", alias="API_BASE_URL")
    fl_service_token: str = Field(..., alias="FL_SERVICE_TOKEN")  # REQUIRED
    fl_num_rounds: int = Field(default=3, alias="FL_NUM_ROUNDS")
    fl_num_clients: int = Field(default=1, alias="FL_NUM_CLIENTS")
    flower_server_address: str = Field(default="0.0.0.0:9092", alias="FLOWER_SERVER_ADDRESS")

def load_initial_parameters(models_dir: Path) -> fl.common.Parameters:
    """Pick the latest .keras model on disk; fall back to a dummy zero-init."""
    candidates = sorted(models_dir.glob("*.keras"))
    if not candidates:
        log.warning("No .keras files in %s — starting from empty parameters", models_dir)
        return fl.common.ndarrays_to_parameters([])
    chosen = candidates[-1]
    log.info("Loading initial weights from %s", chosen)
    model = tf.keras.models.load_model(chosen, compile=False)
    return fl.common.ndarrays_to_parameters(model.get_weights())


def post_round_summary(
    api_base_url: str,
    service_token: str,
    payload: dict,
) -> None:
    url = f"{api_base_url.rstrip('/')}/model/round_summary"
    try:
        r = requests.post(
            url,
            json=payload,
            headers={"X-FL-Service-Token": service_token},
            timeout=10,
        )
        r.raise_for_status()
        log.info("Round summary accepted by API: %s", r.json())
    except Exception as exc:
        log.error("Failed to post round summary to %s: %s", url, exc)


def main() -> None:
    settings = FlowerSettings()
    models_dir = Path(settings.models_dir)

    api_base_url = settings.api_base_url
    service_token = settings.fl_service_token
    num_rounds = settings.fl_num_rounds
    num_clients = settings.fl_num_clients
    listen_addr = settings.flower_server_address

    initial = load_initial_parameters(models_dir)
    strategy = build_strategy(initial_parameters=initial, num_clients=num_clients)

    log.info("Starting Flower server on %s for %d rounds", listen_addr, num_rounds)
    history = fl.server.start_server(
        server_address=listen_addr,
        strategy=strategy,
        config=fl.server.ServerConfig(num_rounds=num_rounds),
    )

    # Persist the final aggregated weights to the shared volume.
    if strategy.final_parameters is not None:
        weights = fl.common.parameters_to_ndarrays(strategy.final_parameters)
        out_path = models_dir / f"fl_global_round_{num_rounds}.npz"
        np.savez(out_path, *weights)
        log.info("Saved aggregated weights → %s", out_path)
    else:
        out_path = None

    # Report to FastAPI.
    last_acc = strategy.last_metrics.get("accuracy")
    last_loss = strategy.last_metrics.get("loss")
    payload = {
        "round": num_rounds,
        "num_clients": num_clients,
        "mean_loss": float(last_loss) if last_loss is not None else None,
        "mean_accuracy": float(last_acc) if last_acc is not None else None,
        "weights_filename": out_path.name if out_path else "unknown.npz",
        "notes": f"Auto-generated at {int(time.time())}",
    }
    post_round_summary(api_base_url, service_token, payload)


if __name__ == "__main__":
    main()