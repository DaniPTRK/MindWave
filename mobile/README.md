# MindWave — Mobile App (`mobile/`)

Android phone application — the computational heart of MindWave. Receives
60-second biometric windows from the paired Galaxy Watch, extracts 23 features,
runs on-device TFLite inference, stores results in the local Room database,
and participates in nightly Federated Learning rounds.

---

## Architecture

```
Galaxy Watch ──BLE/Wi-Fi──► MobileDataListenerService
                    ▲                  │
                    │        FeatureExtractor.extract()
              WatchFlushRequester     (23 features × 12 sub-windows)
              (60s MessageClient ping)         │
                                   ScalerNormalizer  ←── scaler_params.json (assets)
                                              │
                                   TFLite Interpreter
                                   ┌─── infer   → stress score + probabilities
                                   └─── explain → saliency map (XAI)
                                              │
                                   StressInferenceRepository
                                   ├── Room DB (mindwave.db)
                                   │   ├── StressReading
                                   │   ├── XaiExplanation
                                   │   ├── EmotionalJournal
                                   │   └── ContextEvent
                                   └── StressAlertWorker  ──► system notification
                                              │
                                   Jetpack Compose UI  ←── DashboardViewModel
                                   ├── Today (stress gauge + XAI cards)
                                   ├── Insights
                                   ├── History (30-day chart)
                                   ├── Journal (mood diary)
                                   ├── Breathing Exercise
                                   └── Profile / Settings

                                   FLTrainingWorker (~2 AM, WorkManager)
                                   ├── Download latest global model from server
                                   ├── Fine-tune via TFLite `train` signature
                                   └── Upload updated parameter tensors
```

---

## Module layout

```
mobile/
├── build.gradle.kts                 # compileSdk 36, minSdk 30, KSP, TFLite deps
├── schemas/                         # exported Room schema JSON (v1–v7)
└── src/main/
    ├── assets/
    │   ├── mindwave_stress.tflite             # inference-only model (serving_default signature)
    │   ├── mindwave_stress_trainable.tflite   # trainable model for FL on-device fine-tuning
    │   └── scaler_params.json                 # μ / σ from WESAD StandardScaler
    └── java/com/example/mindwave/
        ├── MainActivity.kt                    # Compose entry point + onResume auth sync
        ├── MindWaveApplication.kt             # App class, WorkManager bootstrap, WatchFlushRequester
        ├── data/
        │   ├── MindWaveDatabase.kt            # Room v7, local entities and migrations 1→7
        │   ├── StressReading.kt               # Core entity: features + prediction + userEmail
        │   ├── XaiExplanation.kt              # Per-feature saliency (23 values)
        │   ├── EmotionalJournal.kt            # User mood diary entry + userEmail + tags
        │   ├── ContextEvent.kt                # Calendar / weather context
        │   ├── StressDao.kt / XaiDao.kt / JournalDao.kt / ContextDao.kt
        │   ├── AuthRepository.kt              # JWT login/logout, offline mode, EncryptedSharedPrefs
        │   ├── SettingsRepository.kt          # DataStore: threshold, FL, quiet hours (per-email)
        │   ├── WeatherRepository.kt           # Open-Meteo API (context enrichment)
        │   └── ContextCorrelator.kt           # Links weather/calendar to stress peaks
        ├── inference/
        │   ├── FeatureExtractor.kt            # 23-feature extraction (mirrors features.py)
        │   ├── ScalerNormalizer.kt            # Z-score normalization from JSON params (±3σ clamp)
        │   └── StressInferenceRepository.kt   # Orchestrates extract → normalize → infer → persist
        ├── fl/
        │   └── FLTrainingWorker.kt            # Federated Learning CoroutineWorker (skipped offline)
        ├── sync/
        │   ├── MobileDataListenerService.kt   # Wear Data Layer listener (sensor windows + mood)
        │   ├── WatchStressSender.kt           # Pushes stress result back to watch
        │   ├── WatchAuthNotifier.kt           # Publishes phone auth state to watch
        │   ├── WatchFlushRequester.kt         # Sends 60s flush-request pings to watch
        │   ├── WatchConnectionListener.kt     # Detects watch connect/reconnect, republishes auth
        │   └── WatchConnectionState.kt        # StateFlow: connection status + last window summary
        ├── alert/                             # Notification builder + threshold check (per-email)
        ├── demo/                              # Demo data seeding helper
        └── ui/
            ├── navigation/
            │   ├── Screen.kt                  # Sealed route classes
            │   └── NavGraph.kt                # NavHost + bottom navigation bar
            ├── DashboardViewModel.kt          # Per-email data scoping, continuous currentEmail poll
            ├── TodayScreen.kt
            ├── InsightsScreen.kt
            ├── HistoryScreen.kt
            ├── JournalListScreen.kt
            ├── StressDetailScreen.kt
            ├── BreathingExerciseScreen.kt     # Guided 4-7-8 breathing exercise
            ├── SensorStatusScreen.kt          # Watch connection + buffer stats + simulate button
            ├── ProfileScreen.kt
            ├── ProfileViewModel.kt
            ├── XaiGrouping.kt                 # Groups 23 features into 4 sensor cards
            └── auth/
                ├── LoginScreen.kt             # Includes "Continue offline" button
                ├── RegisterScreen.kt
                └── AuthViewModel.kt           # Notifies watch on every auth state change
```

---

## Room database

Database file: `mindwave.db` (SQLite, not uploaded to server)

| Entity | Table | Key columns |
|--------|-------|-------------|
| `StressReading` | `stress_readings` | `id`, `timestamp`, `userEmail`, 7 aggregated features, `stressScore`, 3 class probs, `synced`, `featureTensor` (BLOB) |
| `XaiExplanation` | `xai_explanations` | `readingId` (FK), 23 saliency floats |
| `EmotionalJournal` | `emotional_journals` | `id`, `timestamp`, `userEmail`, `userMood` (1–5), `note`, `tags`, `entryType` (`JOURNAL`, `FEEDBACK`, `WATCH_MOOD`) |
| `ContextEvent` | `context_events` | `id`, `timestamp`, `type` (CALENDAR/WEATHER), `label` |

**Schema migrations:**
- v1 → v2: `ADD COLUMN featureTensor BLOB` on `stress_readings`
- v2 → v3: `ADD COLUMN userEmail TEXT` on `emotional_journals` (per-account journal scoping)
- v3 → v4: `ADD COLUMN userEmail TEXT` on `stress_readings` (per-account reading scoping)
- v4 → v5: create `latency_records` for profiling
- v5 → v6: rebuild `latency_records` with duration fields
- v6 → v7: add `entryType` to `emotional_journals` so feedback, journal notes and watch mood replies are explicit

> Offline mode uses the sentinel email `offline@local` as `userEmail`. All data created while offline stays permanently under that key unless erased manually.

**Per-email isolation:** every DAO query filters by `userEmail`. `DashboardViewModel`
continuously polls `AuthRepository` for the current email and re-scopes all flows
when the account changes — supporting account-switching without restart.

---

## Offline mode

MindWave can run **without a server** using an anonymous local account.

### How to enter offline mode

On the **Login** screen tap **"Continue offline (no account needed)"**.
No network call is made. A synthetic session is created with email `offline@local`.

### What works offline

| Feature | Status |
|---------|--------|
| TFLite inference (stress detection) | ✅ Full — bundled model |
| Room DB (all readings, journals, XAI) | ✅ Full — stored under `offline@local` |
| Wear watch sensor streaming | ✅ Full — watch sends data normally |
| Stress alerts & notifications | ✅ Full |
| Breathing exercise / History / Journal | ✅ Full |
| Demo data seeder | ✅ Full |
| Federated Learning training round | ❌ Skipped — no server |
| Global model download | ❌ Skipped — no server |
| Account registration / JWT | ❌ Not applicable |

---

## Phone ↔ Watch data flow

### Phone-driven flush polling

The phone controls when the watch sends data:

| Component | Role |
|-----------|------|
| `WatchFlushRequester` | Sends a zero-byte `MessageClient` ping to `/mindwave/request_flush` every **60 s** after the first interval. Foregrounding the phone only refreshes auth state, so normal sensor windows stay evenly spaced. It first targets the declared `mindwave_wear_app` capability, then falls back to all connected Wear nodes if capability discovery returns nothing. |
| `PhoneDataListenerService.onMessageReceived()` (watch) | Receives the ping and immediately calls `WearDataSender.sendWindow()`. |
| `SensorForegroundService` fallback timer (watch) | Checks every **120 s** and fires only if no recent phone-driven flush request was received. |

### Auth state synchronisation

Every auth state change (login / logout / offline / `onResume`) is published to the
watch via `WatchAuthNotifier` at `/mindwave/phone_auth`. The watch's
`PhoneAuthListenerService` stores this in `PhoneAuthState`. Both flush paths check
`isPhoneLoggedIn()` — it only stops sending when the phone is **fully logged out**.

---

## Feature extraction pipeline

`FeatureExtractor.extract()` replicates `ml/src/features.py` exactly:

| Index | Group | Features (per sub-window) |
|-------|-------|--------------------------|
| 0–3 | HRV time | `hrv_meanNN`, `hrv_SDNN`, `hrv_RMSSD`, `hrv_pNN50` |
| 4–6 | HRV freq | `hrv_LF`, `hrv_HF`, `hrv_LFHF` (zero-filled — 1 Hz HR insufficient) |
| 7–13 | EDA | `eda_scl_mean`, `eda_scl_slope`, `eda_scr_mean`, `eda_scr_std`, `eda_scr_auc`, `eda_scr_peaks`, `eda_scr_amp_mean` |
| 14–18 | TEMP | `temp_mean`, `temp_std`, `temp_slope`, `temp_min`, `temp_max` |
| 19–22 | ACC | `acc_mag_mean`, `acc_mag_std`, `acc_mag_energy`, `acc_mag_zcr` |

**Important EDA pre-processing:** each EDA sub-window is **z-scored** before feature
computation (`zscoreWindow()`) to match the `nk.standardize()` call in the Python
training pipeline. This makes SCL/SCR features scale-invariant. Raw Samsung skin
conductance values (~20 µS at rest) would otherwise produce +30σ outliers and
saturate the LSTM.

**Display vs. model input:** `StressReading.edaScl` stores the **raw mean µS** from
`window.edaValues.average()` — not the z-scored value — so the phone UI shows a
meaningful skin-conductance reading. The z-scored tensor is only used internally
for the TFLite model.

**ACC scaling:** Samsung SDK values are in mg (milligravity). `WearDataSender`
divides by 1000 to convert to g before sending, matching the WESAD training scale.

**ACC band-pass filter:** 4th-order Butterworth 0.5–10 Hz on tri-axial ACC magnitude,
implemented as Direct Form II Transposed biquad cascade (matches `preprocessing.py:filter_acc_mag`).
Residual DC (gravity) is removed by per-window mean subtraction after filtering.

Input: 60s window split into **12 sub-windows of 5s each** → output: `FloatArray[12 × 23 = 276]`.

---

## TFLite model

Assets: `mindwave_stress.tflite` (inference-only) and
`mindwave_stress_trainable.tflite` (FL fine-tuning only).

### Model loading priority

`StressInferenceRepository` tries models in this order:

1. **FL-updated local file** (`filesDir/mindwave_fl_model.tflite`) — trainable + FlexDelegate
2. **Bundled trainable asset** (`mindwave_stress_trainable.tflite`) — smoke-tested; discarded if Flex ops unavailable (x86 emulator)
3. **Bundled plain asset** (`mindwave_stress.tflite`) — no Flex ops, works everywhere

### Inference signature priority

| Order | Signature | Input key | Output key | Model |
|-------|-----------|-----------|------------|-------|
| 1 | `infer` | `x: [1,12,23]` | `logits: [1,2]` | Trainable |
| 2 | `serving_default` | `features: [1,12,23]` | `output_0: [1,2]` | Plain |
| 3 | `Interpreter.run()` | raw `ByteBuffer` | raw `ByteBuffer` | Any |

Output is softmax'd if the sum is outside [0.99, 1.01].

`ScalerNormalizer` loads `scaler_params.json` from assets (contains `mean[]` and
`scale[]` per feature, fit on the WESAD training set), z-scores the tensor, and
clamps to ±3σ before passing to inference.

### XAI

The `explain` TFLite signature provides gradient-based feature importances.
If unavailable, `StressInferenceRepository` falls back to **ablation importance**:
for each of the 23 features, zero it out and measure the drop in P(stress).

---

## Federated Learning

`FLTrainingWorker` is a `CoroutineWorker` scheduled by `WorkManager` at ~2 AM daily:

```
Constraints: NetworkType.CONNECTED + BatteryNotLow
             + custom check: battery > 80% OR charging
```

Round steps:
1. `GET /model/latest` → compare remote version with local version
2. If newer: `GET /model/{version}/download`, write to `filesDir/mindwave_fl_model.tflite`
3. Load unsynced readings that have binary `FEEDBACK` entries linked to them
4. Normalize stored feature tensors with the same scaler used for inference
5. Call TFLite `train` signature for N mini-batches
6. Export updated parameter tensors via `parameters` and upload them with `sample_count` and `base_model_version`
7. Mark readings as `synced = true` and clear their feature BLOBs only after local training and upload complete successfully

Only updated model parameter tensors and the local sample count leave the device.
Raw biometrics, feature tensors, predictions, explanations and journal entries are
never uploaded. Skipped entirely in offline mode.

---

## Settings

`SettingsRepository` (Jetpack DataStore, **scoped per email**) persists:

| Key | Default | Description |
|-----|---------|-------------|
| `alertThreshold` | 0.85 | Stress score threshold for push notifications |
| `flEnabled` | `true` | Opt in/out of Federated Learning |
| `calendarEnabled` | `false` | Correlate stress with Google Calendar events |
| `quietHoursEnabled` | `true` | Suppress alerts overnight |
| `quietStartHour` | 22 | Quiet hours start (22:00) |
| `quietEndHour` | 7 | Quiet hours end (07:00) |
| `notificationIntervalMinutes` | 30 | Cooldown between stress notifications (15 / 30 / 60 min) |

All settings are exposed via `ProfileScreen` (the **Profile** tab).

Stress notifications are evaluated immediately after each new reading is saved.
The trigger uses a **3-minute rolling mean** of `stressProbStress` against the
configured threshold; the notification interval only controls cooldown.

---

## Navigation

5-tab bottom navigation bar (`Screen.kt` / `NavGraph.kt`):

| Tab | Route | Purpose |
|-----|-------|---------|
| Today | `today` | Live stress gauge + sensor status strip + XAI summary + quick Breathe action |
| Insights | `insights` | Sensor contributions, artifact warnings, weekly pattern, technical XAI |
| History | `history` | 7×24 heatmap + D/W/M signal charts (Stress / HRV / Temp / EDA) |
| Journal | `journal` | Mood diary + feedback; entries scoped per account |
| Profile | `profile` | Settings + Privacy & FL explainer + Developer/Demo section |

Secondary screens (pushed onto back-stack):

| Route | Screen | Trigger |
|-------|--------|---------|
| `stress_detail/{id}` | `StressDetailScreen` | Tap gauge on Today, tap a peak on Insights, notification deep-link |
| `breathing` | `BreathingExerciseScreen` | "Breathe" button on Today / StressDetail |
| `sensor_status` | `SensorStatusScreen` | Sensor strip on Today tab; includes "Simulate Watch Window" developer button |

---

## Build

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'

# Install on a connected device / emulator
.\gradlew.bat :mobile:installDebug
```

### Key build settings

| Property | Value |
|----------|-------|
| `minSdk` | 30 |
| `compileSdk` | 36 (release) |
| `noCompress` | `tflite` (models already compressed) |
| Room schema location | `mobile/schemas/` |
| Annotation processor | KSP |

### Server URL

Configured via `SERVER_BASE_URL` in `local.properties` (injected into `BuildConfig`):

| Where | Value |
|-------|-------|
| Android Emulator | `http://10.0.2.2:8000` *(default)* |
| Physical phone (same LAN) | `http://<your-PC-IP>:8000` |
| Production | `https://<your-domain>` |

---

## Known issues / TODOs

- `stressProbAmusement` in `StressReading` should be renamed to `stressProbOther`
  (the 3-class head exists in the model, but "amusement" class was dropped from
  the binary deployment label set).
- HR dashboard (`stats/organization`) role is wired up server-side but there is
  no dedicated HR view in the phone UI yet.

