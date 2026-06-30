# MindWave — Federated Learning Architecture (Stage 2 → Stage 3 bridge)

This document explains how the Python Flower simulation we built in Stage 2
will translate to the real on-device cycle running on Android (mobile + Wear
OS) in Stage 3.

---

## 1. Mapping the Python client to the Android client

| Concern | Stage 2 (Python, this folder) | Stage 3 (Android, future) |
|---|---|---|
| Client wrapper | `MindWaveClient(fl.client.NumPyClient)` in `src/fl/client.py` | Kotlin Flower client (the [`flwr-android`](https://github.com/adap/flower) sample, or a custom gRPC client built on top of `io.grpc:grpc-okhttp`) |
| Model carrier | `tf.keras.Model` (full TensorFlow) | A single **`.tflite`** file shipped in `mobile/src/main/assets/` |
| Training engine | `model.fit(...)` (Keras) | TFLite **on-device training** signatures (see §3) |
| Local data | `X_train, y_train` numpy arrays in RAM | A **Room** table populated from the Samsung Health Sensor SDK; one row per 60 s window of features |
| Server | `fl.server.ServerApp` in this process | The same `flwr` Python server, deployed behind FastAPI in Stage 4 |
| Transport | In-process simulation backend (Ray) | Real **gRPC** over TLS to the FastAPI-fronted Flower SuperLink |
| Privacy property | Only weights cross | **Identical** — only weights cross |

The key insight is that the privacy contract is enforced by the *protocol*, not
by the language. As long as the Android side only ever transmits the result of
`get_parameters` and the metrics dict from `fit` / `evaluate`, the guarantee
holds.

---

## 2. The federated round, end-to-end on a watch

```text
   ┌──────────────────────────┐                       ┌──────────────────────┐
   │  FastAPI + Flower server │ ─── round start ────► │  Wear OS / Mobile    │
   │  (Stage 4)               │   (push weights via   │  Flower client       │
   └──────────────────────────┘    gRPC, ~30–60 KB)   └─────────┬────────────┘
            ▲                                                    │
            │                                                    │ 1. restore(weights)
            │                                                    │ 2. for n local epochs:
            │                                                    │      train(X_batch, y_batch)
            │                                                    │ 3. new_weights = parameters()
            │                                                    │ 4. metrics = evaluate(...)
            │                                                    │
            │       ◄─── new_weights, num_examples, metrics ─────┘
            │
   ┌──────────────────────────┐
   │  FedAvg aggregation      │
   │  (server-side only)      │
   └──────────────────────────┘
```

Steps 1–4 happen entirely on the device. Sensor data read by the Samsung
Health Sensor SDK is featurised locally (the same `features.py` logic ported
to Kotlin / native lib), stored in Room and only ever consumed inside the
TFLite interpreter.

---

## 3. TensorFlow Lite + the four training signatures

The `mindwave_stress.tflite` produced in Stage 1 is *inference-only*. To
participate in Federated Learning on-device the model must be re-exported
with **four `tf.function` signatures** (the standard pattern from Google's
[on-device personalization guide](https://www.tensorflow.org/lite/examples/on_device_training/overview)):

| Signature | Purpose | Mapped Flower call |
|---|---|---|
| `train(x, y) -> {loss}` | One mini-batch of SGD on local data | `MindWaveClient.fit` inner loop |
| `infer(x) -> {logits}` | Forward pass for predictions / XAI | normal stress scoring |
| `parameters() -> {var_0, var_1, ...}` | Read all trainable weights as flat tensors | `get_parameters` |
| `restore(var_0, var_1, ...) -> {ok}` | Overwrite all trainable weights | `set_weights` (called at the start of `fit` / `evaluate`) |

Sketch of the re-export code (Stage 3 will land in `src/tflite_export.py` as
`convert_for_on_device_training`):

```python
class TrainableLSTM(tf.Module):
    def __init__(self, model):
        self.model = model
        self.opt = tf.keras.optimizers.SGD(1e-2)

    @tf.function(input_signature=[
        tf.TensorSpec([None, 12, N_FEATURES], tf.float32),
        tf.TensorSpec([None], tf.int64),
    ])
    def train(self, x, y):
        with tf.GradientTape() as tape:
            logits = self.model(x, training=True)
            loss = tf.keras.losses.sparse_categorical_crossentropy(y, logits)
        grads = tape.gradient(loss, self.model.trainable_variables)
        self.opt.apply_gradients(zip(grads, self.model.trainable_variables))
        return {"loss": tf.reduce_mean(loss)}

    @tf.function(input_signature=[tf.TensorSpec([None, 12, N_FEATURES], tf.float32)])
    def infer(self, x):
        return {"logits": self.model(x, training=False)}

    @tf.function(input_signature=[])
    def parameters(self):
        return {f"var_{i}": v.read_value()
                for i, v in enumerate(self.model.trainable_variables)}

    @tf.function(input_signature=[
        tf.TensorSpec(v.shape, v.dtype) for v in model.trainable_variables
    ])
    def restore(self, *new_values):
        for v, nv in zip(self.model.trainable_variables, new_values):
            v.assign(nv)
        return {"ok": tf.constant(1)}

converter = tf.lite.TFLiteConverter.from_concrete_functions(
    [m.train.get_concrete_function(),
     m.infer.get_concrete_function(),
     m.parameters.get_concrete_function(),
     m.restore.get_concrete_function()],
    m,
)
converter.target_spec.supported_ops = [
    tf.lite.OpsSet.TFLITE_BUILTINS, tf.lite.OpsSet.SELECT_TF_OPS,
]  # SELECT_TF_OPS is needed for the gradient ops
```

On Android, each signature is reachable via
`Interpreter.getSignatureRunner("train" | "infer" | "parameters" | "restore")`.

---

## 4. Stage roadmap recap

| Stage | Status | Output |
|---|---|---|
| 1. Baseline LSTM on WESAD | ✅ done | `models/mindwave_stress.{keras,tflite}` |
| **2. Flower FL simulation in Python** | ✅ **this stage** | `models/fl_global.keras`, validated client↔server loop |
| 3. Android client + on-device TFLite training | ⏭️ next | Kotlin Flower client wired into `mobile/` + `wear/` |
| 4. FastAPI-backed Flower server, PostgreSQL, Docker | ⏭️ later | Production server, deployable container |
| 5. XAI (SHAP/LIME) + context (Calendar, Weather) | ⏭️ later | UI explanations + correlations |

