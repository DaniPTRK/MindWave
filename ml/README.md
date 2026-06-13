# MindWave Stress-Detection Model (`ml/`)

Python pipeline that trains the global baseline LSTM on the WESAD wrist-sensor
dataset, exports a 5-signature on-device TFLite model, and runs a Flower
Federated Learning simulation. The exported model and scaler parameters are
shipped in the Android app's assets.

We use **only the wrist sensors** of WESAD (Empatica E4: BVP, EDA, TEMP, ACC),
because the deployment target is a Samsung Galaxy Watch.

---

## Modeling decisions

| Choice | Value |
|--------|-------|
| Classes | 2 (baseline, stress) — WESAD labels {1, 2} |
| Window length | 60 s, stride 15 s (75% overlap) |
| Sub-window (LSTM step) | 5 s, no overlap → 12 steps per window |
| Features per step | 23 (HRV-time×4, HRV-freq×3, EDA×7, TEMP×5, ACC×4) |
| Model | LSTM(64) → LSTM(32) → Dense(32) → Dense(3, softmax) |
| Evaluation | Leave-One-Subject-Out (LOSO) + hold-out (S13, S16) |
| LOSO accuracy | **93.66% ± 6.01%** (mean ± std across 14 subjects) |
| Best / worst fold | 100.0% (S2) / 77.7% (S14) |

---

## Feature set (23 features per sub-window)

| Index | Group | Feature names |
|-------|-------|---------------|
| 0–3 | HRV time | `hrv_meanNN`, `hrv_SDNN`, `hrv_RMSSD`, `hrv_pNN50` |
| 4–6 | HRV freq | `hrv_LF`, `hrv_HF`, `hrv_LFHF` (broadcast from 60s window) |
| 7–13 | EDA | `eda_scl_mean`, `eda_scl_slope`, `eda_scr_mean`, `eda_scr_std`, `eda_scr_auc`, `eda_scr_peaks`, `eda_scr_amp_mean` |
| 14–18 | TEMP | `temp_mean`, `temp_std`, `temp_slope`, `temp_min`, `temp_max` |
| 19–22 | ACC | `acc_mag_mean`, `acc_mag_std`, `acc_mag_energy`, `acc_mag_zcr` |

These names match `mobile/FeatureExtractor.kt` exactly, enabling correct XAI
saliency attribution in `XaiGrouping.kt`.

---

## Folder layout

```
ml/
├── README.md
├── requirements.txt             # pinned dependencies (Python 3.11)
├── src/
│   ├── config.py                # paths, constants, label modes, random seeds
│   ├── wesad_loader.py          # load wrist signals from WESAD .pkl files
│   ├── preprocessing.py         # filtering / resampling (incl. filter_acc_mag)
│   ├── features.py              # HRV / EDA / TEMP / ACC feature extractors
│   ├── windowing.py             # sliding-window assembly + label majority vote
│   ├── model.py                 # Keras LSTM factory (TFLite-friendly)
│   ├── train.py                 # CLI training script (LOSO + final model)
│   ├── evaluate.py              # metrics + confusion matrix + Keras-vs-TFLite parity
│   ├── tflite_export.py         # Inference-only TFLite conversion (float + int8)
│   ├── tflite_training_export.py# 5-signature trainable TFLite (TrainableModule)
│   ├── xai.py                   # Vanilla-gradient saliency maps (standalone script)
│   └── fl/
│       ├── client.py            # MindWaveClient (Flower NumPyClient, 1 client = 1 subject)
│       └── simulation.py        # CLI simulation runner (FedAvg, configurable rounds)
├── notebooks/
│   ├── 01_data_exploration.ipynb
│   ├── 02_preprocessing_features.ipynb
│   ├── 03_model_training.ipynb
│   ├── 04_federated_learning.ipynb   # FL simulation walkthrough
│   └── 05_tflite_training_export.ipynb # 5-signature export walkthrough
├── data/processed/              # generated .npz cache (gitignored)
└── models/                      # all generated artifacts (see below)
```

---

## Generated artifacts (`ml/models/`)

| File | Description |
|------|-------------|
| `mindwave_stress.keras` | Final Keras model (all subjects) |
| `mindwave_stress.tflite` | Float32 inference-only TFLite |
| `mindwave_stress_int8.tflite` | Int8 quantized TFLite (smaller / faster) |
| `mindwave_stress_trainable.tflite` | **5-signature trainable TFLite** (shipped in Android assets) |
| `scaler.pkl` | `StandardScaler` fit on training subjects |
| `scaler_params.json` | Mean + scale arrays (used by `ScalerNormalizer.kt` in Android) |
| `feature_names.json` | Ordered list of 23 feature names |
| `label_map.json` | `{0: "baseline", 1: "stress", 2: "amusement"}` |
| `loso_metrics.json` | Per-subject accuracy / F1 + mean ± std |
| `holdout_metrics.json` | Hold-out set metrics (S13, S16) |
| `fl_global_smoke.history.json` | FL simulation training history (3 rounds) |

---

## How to run

```powershell
cd ml
python -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -r requirements.txt

# 1. Explore the data interactively
jupyter lab notebooks/01_data_exploration.ipynb

# 2. Build the preprocessed dataset
python -m src.windowing --wesad ..\WESAD --out data\processed\wesad_wrist.npz

# 3. Train (hold-out for speed)
python -m src.train --data data\processed\wesad_wrist.npz --mode holdout

# 4. Train with Leave-One-Subject-Out (slow — headline metric)
python -m src.train --data data\processed\wesad_wrist.npz --mode loso

# 5. Export inference-only TFLite (float32 + int8)
python -m src.tflite_export

# 6. Export 5-signature trainable TFLite + scaler_params.json
python -c "from src.tflite_training_export import export_trainable_tflite; export_trainable_tflite()"

# 7. Run Federated Learning simulation
python -m src.fl.simulation --rounds 3 --local-epochs 2

# 8. (Optional) Standalone XAI saliency maps
python -m src.xai
```

---

## 5-signature trainable TFLite (`tflite_training_export.py`)

`TrainableModule` wraps the Keras LSTM in a `tf.Module` and exports these
concrete `tf.function` signatures:

| Signature | Input | Output | Purpose |
|-----------|-------|--------|---------|
| `infer` | `x: [N, 12, 23]` float32 | `output: [N, 3]` float32 | Forward pass (inference) |
| `explain` | `x: [1, 12, 23]` float32 | `saliency: [1, 12, 23]` float32 | Vanilla-gradient XAI |
| `train` | `x: [N, 12, 23]`, `y: [N]` int64 | `loss: []` float32 | SGD mini-batch training |
| `parameters` | — | weight tensors | Export weights for FL aggregation |
| `restore` | weight tensors | — | Import aggregated global weights |

> **Initialization required:** The trainable model contains LSTM optimizer state
> variables. Call `interpreter.runSignature("initialize")` immediately after
> creating the `Interpreter` on Android before any other signature call.

`export_scaler_params()` is called alongside the TFLite export and writes
`scaler_params.json` (mean + scale per feature), which `ScalerNormalizer.kt`
reads from Android assets to z-score inputs before inference.

---

## Federated Learning simulation (`fl/`)

Each WESAD subject = one simulated device. Implements `MindWaveClient`
(Flower `NumPyClient`):

```python
# Run 3 FL rounds, 2 local epochs per client, all 14 subjects
python -m src.fl.simulation --rounds 3 --local-epochs 2

# Subset of subjects, no warm-start (random init)
python -m src.fl.simulation --rounds 5 --subjects S2 S3 S5 --no-warm-start
```

Simulation results (`fl_global_smoke.history.json`) show convergence from
~22.7% → 78.2% train accuracy over 3 rounds from a randomly initialized model.

---

## Notes

- **Frequency-domain HRV** (LF, HF, LF/HF) is computed once per 60 s window
  and broadcast to all 12 sub-windows — 5 s is too short for reliable LF
  estimation.
- **EDA decomposition** uses NeuroKit2's fast `'highpass'` method by default.
  Pass `--eda-method cvxEDA` to `windowing.py` for the slower but more accurate
  cvxEDA decomposition.
- **ACC band-pass filter:** `preprocessing.py:filter_acc_mag` applies a 4th-order
  Butterworth 0.5–10 Hz filter to the ACC magnitude. The same filter is ported
  to `FeatureExtractor.kt` as a Direct Form II Transposed biquad cascade.
- **Subject S12** is absent from WESAD; the loader skips it silently.
- **Label config:** `config.py` defines `binary_strict` (labels {1, 2} only)
  and `binary_with_amusement` modes. The 3-class softmax head is present in the
  model, but the recommended deployment is binary. The third output
  (`stressProbAmusement` in `StressReading.kt`) is a legacy artifact.
- **TF version pinned to 2.15.x** — keeps the LSTM → TFLite conversion path on
  the well-tested `UNIDIRECTIONAL_SEQUENCE_LSTM` op supported by the Android
  TFLite runtime.
