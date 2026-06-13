import sys, io, struct, os
from pathlib import Path
import numpy as np
os.environ["TF_ENABLE_ONEDNN_OPTS"] = "0"
os.environ["TF_CPP_MIN_LOG_LEVEL"] = "3"
import tensorflow as tf
MODELS_DIR = Path("ml/models")
N_STEPS = 12
N_FEATURES = 23
print("TF:", tf.__version__, " NumPy:", np.__version__)
from tensorflow.keras import layers as L, models as M, regularizers as R
def build_export_model(input_shape, n_classes):
    reg = R.L2(1e-4)
    inp = L.Input(shape=input_shape, name="features")
    x = L.Masking(mask_value=0.0)(inp)
    x = L.LSTM(64, return_sequences=True, unroll=True, kernel_regularizer=reg, recurrent_regularizer=reg)(x)
    x = L.LSTM(32, return_sequences=False, unroll=True, kernel_regularizer=reg, recurrent_regularizer=reg)(x)
    x = L.Dense(32, activation="relu", kernel_regularizer=reg)(x)
    out = L.Dense(n_classes, activation="softmax")(x)
    return M.Model(inp, out)
km = tf.keras.models.load_model(str(MODELS_DIR / "mindwave_stress.keras"), compile=False)
n_classes = km.output_shape[-1]
print("Keras input:", km.input_shape, "output:", km.output_shape, "n_classes:", n_classes)
em = build_export_model(km.input_shape[1:], n_classes)
em.set_weights(km.get_weights())
class Mod(tf.Module):
    def __init__(self, model):
        super().__init__()
        self.model = model
        self.opt = tf.keras.optimizers.SGD(1e-3)
    @tf.function(input_signature=[tf.TensorSpec([None,N_STEPS,N_FEATURES],tf.float32),tf.TensorSpec([None],tf.int64)])
    def train(self, x, y):
        with tf.GradientTape() as tape:
            logits = self.model(x, training=True)
            loss = tf.reduce_mean(tf.keras.losses.sparse_categorical_crossentropy(y, logits))
        self.opt.apply_gradients(zip(tape.gradient(loss,self.model.trainable_variables), self.model.trainable_variables))
        return {"loss": loss}
    @tf.function(input_signature=[tf.TensorSpec([None,N_STEPS,N_FEATURES],tf.float32)])
    def infer(self, x):
        return {"logits": self.model(x, training=False)}
    @tf.function(input_signature=[])
    def parameters(self):
        return {f"var_{i}": tf.identity(v) for i,v in enumerate(self.model.trainable_variables)}
    def _restore_sig(self):
        return [tf.TensorSpec(v.shape,v.dtype) for v in self.model.trainable_variables]
    def restore(self, *vals):
        for v,nv in zip(self.model.trainable_variables, vals): v.assign(nv)
        return {"status": tf.constant(1)}
    @tf.function(input_signature=[tf.TensorSpec([1,N_STEPS,N_FEATURES],tf.float32)])
    def explain(self, x):
        xv = tf.identity(x)
        with tf.GradientTape() as tape:
            tape.watch(xv)
            logits = self.model(xv, training=False)
            pc = tf.argmax(logits, axis=-1)
            prob = tf.reduce_sum(logits * tf.one_hot(pc, depth=logits.shape[-1]), axis=-1)
        grad = tape.gradient(prob, xv)
        return {"importances": tf.reduce_mean(tf.abs(grad), axis=1)}
mod = Mod(em)
rng = np.random.default_rng(42)
print("\n=== infer ===")
x = rng.standard_normal((1,N_STEPS,N_FEATURES)).astype(np.float32)
logits = mod.infer(x=tf.constant(x))["logits"].numpy()
print("  shape:", logits.shape, " sum:", round(float(logits.sum()),4), " values:", logits)
is_probs = 0.99 < float(logits.sum()) < 1.01
print("  -> probabilities:", is_probs, "(softmax output from Keras Dense activation)")
print("\n=== explain ===")
x_e = rng.standard_normal((1,N_STEPS,N_FEATURES)).astype(np.float32)
imp = mod.explain(x=tf.constant(x_e))["importances"].numpy()
print("  shape:", imp.shape, " dtype:", imp.dtype, " size:", imp.size)
print("  min:", round(float(imp.min()),6), " max:", round(float(imp.max()),6))
print("  first 5:", imp.flatten()[:5].tolist())
print("  Android ByteBuffer =", N_FEATURES, "floats  model_output =", imp.size, "floats  match:", N_FEATURES == imp.size)
if N_FEATURES != imp.size:
    print("  *** SIZE MISMATCH ***")
print("\n=== train ===")
xb = rng.standard_normal((4,N_STEPS,N_FEATURES)).astype(np.float32)
yb = np.array([0,1,0,1],dtype=np.int64)
loss = mod.train(x=tf.constant(xb), y=tf.constant(yb))["loss"].numpy()
print("  loss:", round(float(loss),6))
print("\n=== parameters ===")
params = mod.parameters()
total = sum(v.numpy().size for v in params.values())
print("  tensors:", len(params), " total_params:", total)
for k,v in params.items():
    print("   ", k, "shape:", tuple(v.shape), "size:", v.numpy().size)
print("\n=== restore ===")
rfn = tf.function(mod.restore, input_signature=mod._restore_sig())
st = rfn(**{k:v for k,v in params.items()})["status"].numpy()
print("  status:", st)
print("\n=== saveWeights/restoreWeights round-trip ===")
tensors = {k: v.numpy() for k,v in params.items()}
buf = io.BytesIO()
buf.write(struct.pack(">i", len(tensors)))
for arr in tensors.values():
    flat = arr.flatten().astype("<f4")
    buf.write(struct.pack(">i", len(flat)))
    buf.write(flat.tobytes())
nbytes = len(buf.getvalue())
print("  Saved", len(tensors), "tensors ->", nbytes, "bytes")
buf.seek(0)
n = struct.unpack(">i", buf.read(4))[0]
restored = {}
for i in range(n):
    sz = struct.unpack(">i", buf.read(4))[0]
    restored[f"var_{i}"] = np.frombuffer(buf.read(sz*4), dtype="<f4")
ok = all(np.allclose(restored[f"var_{i}"].reshape(v.shape),v,atol=1e-5) for i,(_,v) in enumerate(tensors.items()))
print("  Round-trip match:", ok)
if not ok:
    for i,(_,v) in enumerate(tensors.items()):
        if not np.allclose(restored[f"var_{i}"].reshape(v.shape),v,atol=1e-5):
            print("    MISMATCH at var_" + str(i))
print("\n=== TFLite file ===")
tp = MODELS_DIR / "mindwave_stress_trainable.tflite"
if tp.exists():
    content = tp.read_bytes()
    print("  Size:", round(len(content)/1024, 1), "KB")
    try:
        ri = tf.lite.Interpreter(model_content=content)
        ri.allocate_tensors()
        sigs = ri.get_signature_list()
        print("  Signatures:", list(sigs.keys()))
        for nm,sg in sigs.items():
            print("   ", nm, "in:", list(sg["inputs"].keys()), " out:", list(sg["outputs"].keys()))
    except Exception as e:
        print("  [Cannot run without Flex runtime]:", str(e)[:200])
else:
    print("  NOT FOUND")
print("\n=== VERDICT ===")
issues = []
if not is_probs:
    issues.append("infer: outputs RAW LOGITS - Android softmax branch IS needed")
if N_FEATURES != imp.size:
    issues.append("explain: ByteBuffer mismatch " + str(N_FEATURES) + " vs " + str(imp.size))
if imp.shape not in [(1,N_FEATURES),(N_FEATURES,)]:
    issues.append("explain: unexpected output shape " + str(imp.shape))
if not ok:
    issues.append("saveWeights/restoreWeights: CORRUPTS weights on round-trip")
if issues:
    print("BUGS FOUND:")
    for iss in issues:
        print("  -", iss)
else:
    print("All checks passed. Pipeline is consistent.")
