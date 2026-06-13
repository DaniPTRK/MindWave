# MindWave — Mobile App (`mobile/`)

Android phone application — the computational heart of MindWave. Receives
60-second biometric windows from the paired Galaxy Watch, extracts 23 features,
runs on-device TFLite inference, stores results in an encrypted Room database,
and participates in nightly Federated Learning rounds.

---

## Architecture

```
Galaxy Watch ──BLE/Wi-Fi──► MobileDataListenerService
                                      │
                            FeatureExtractor.extract()
                           (23 features × 12 sub-windows)
                                      │
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
                            └── AlertNotificationWorker  ──► system notification
                                      │
                            Jetpack Compose UI  ←── DashboardViewModel
                            ├── Dashboard (stress gauge + XAI cards)
                            ├── History (30-day chart)
                            ├── Journal (mood diary)
                            ├── Stress Detail (per-reading breakdown)
                            ├── Breathing Exercise
                            └── Profile / Settings

                            FLTrainingWorker (~2 AM, WorkManager)
                            ├── Download latest global model from server
                            ├── Fine-tune via TFLite `train` signature
                            └── Upload round summary (weights only)
```

---

## Module layout

```
mobile/
├── build.gradle.kts                 # compileSdk 36, minSdk 30, KSP, TFLite deps
├── schemas/                         # exported Room schema JSON (v1, v2)
└── src/main/
    ├── assets/
    │   ├── mindwave_stress.tflite             # inference-only model (serving_default signature)
    │   ├── mindwave_stress_trainable.tflite   # trainable model for FL on-device fine-tuning
    │   └── scaler_params.json                 # μ / σ from WESAD StandardScaler
        ├── MindWaveApplication.kt             # App class, WorkManager bootstrap, watch auth sync
        │   ├── MindWaveDatabase.kt            # Room v4, 4 entities, migrations 1→2→3→4
        │   ├── StressReading.kt               # Core entity: features + prediction + userEmail
        ├── data/
        │   ├── EmotionalJournal.kt            # User mood diary entry + userEmail
        │   ├── StressReading.kt               # Core entity: features + prediction
        │   ├── XaiExplanation.kt              # Per-feature saliency (23 values)
        │   ├── AuthRepository.kt              # JWT login/logout, offline mode, EncryptedSharedPrefs
        │   ├── ContextEvent.kt                # Calendar / weather context
        │   ├── StressDao.kt / XaiDao.kt / JournalDao.kt / ContextDao.kt
        │   ├── AuthRepository.kt              # JWT login/logout, EncryptedSharedPrefs
        │   ├── SettingsRepository.kt          # DataStore: threshold, FL, quiet hours
        │   ├── WeatherRepository.kt           # Open-Meteo API (context enrichment)
        │   └── ContextCorrelator.kt           # Links weather/calendar to stress peaks
        ├── inference/
        │   └── FLTrainingWorker.kt            # Federated Learning CoroutineWorker (skipped in offline mode)
        │   ├── ScalerNormalizer.kt            # Z-score normalization from JSON params
        │   ├── WatchStressSender.kt           # Pushes stress result back to watch
        │   └── WatchAuthNotifier.kt           # Publishes phone auth state to watch
        ├── fl/
        │   └── FLTrainingWorker.kt            # Federated Learning CoroutineWorker
        ├── sync/
        │   ├── MobileDataListenerService.kt   # Wear Data Layer listener
        │   └── WatchStressSender.kt           # Pushes stress result back to watch
        ├── alert/                             # Notification builder + threshold check
        ├── demo/                              # Demo data seeding helper
        └── ui/
            ├── navigation/
            │   ├── Screen.kt                  # Sealed route classes
            │   └── NavGraph.kt                # NavHost + bottom navigation bar
            ├── DashboardScreen.kt             # Home tab: live gauge + XAI
            ├── ProfileScreen.kt               # Profile tab (settings + account + offline badge)
            ├── HistoryScreen.kt               # History tab
            ├── JournalListScreen.kt           # Journal tab
            ├── JournalDialog.kt
                └── AuthViewModel.kt           # Notifies watch on every auth state change
            ├── BreathingExerciseScreen.kt     # Guided breathing exercise
            ├── ProfileScreen.kt               # Profile tab (settings + account)
| `StressReading` | `stress_readings` | `id`, `timestamp`, `userEmail`, 7 aggregated features, `stressScore`, 3 class probs, `synced`, `featureTensor` (BLOB) |
| `EmotionalJournal` | `emotional_journals` | `id`, `timestamp`, `userEmail`, `userMood` (1–5), `note` |
            └── auth/
                ├── LoginScreen.kt
                ├── RegisterScreen.kt
                └── AuthViewModel.kt
```

---

## Room database

**Schema migrations:**
- v1 → v2: `ADD COLUMN featureTensor BLOB` on `stress_readings`
- v2 → v3: `ADD COLUMN userEmail TEXT` on `emotional_journals` (per-account journal scoping)
- v3 → v4: `ADD COLUMN userEmail TEXT` on `stress_readings` (per-account reading scoping)

> Offline mode uses the sentinel email `offline@local` as `userEmail`. All data created while offline stays permanently under that key unless erased manually.

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
Asset: `mindwave_stress.tflite` — inference-only model (~compact size, no Flex ops)
| Federated Learning training round | ❌ Skipped — no server |
| `serving_default` | `features: [1, 12, 23]` float32 | `output_0: [1, 2]` softmax probs | Inference (non-stress / stress) |

### Watch auth sync

Every auth state change (login / logout / offline) is published to the watch via
`WatchAuthNotifier` at `/mindwave/phone_auth`. The watch's `PhoneAuthListenerService`
stores this in `PhoneAuthState`. `SensorForegroundService` checks `isPhoneLoggedIn()`
> **Important:** The bundled asset is the **inference-only** model (no resource variables, no FlexOps).
> The trainable model (`mindwave_stress_trainable.tflite`) is kept separately in `ml/models/` and is
> used exclusively by `FLTrainingWorker` for on-device fine-tuning.  Loading the trainable model in
> the inference path caused `read_variable.cc: variable != nullptr` errors — fixed by always loading
before passing it to inference.

`StressInferenceRepository` tries signature keys in this order:
1. `serving_default` / `features` → `output_0` (current export format)
2. `infer` / `x` → `logits` (legacy export — kept for backward compatibility)
3. Flat `Interpreter.run()` fallback
| `XaiExplanation` | `xai_explanations` | `readingId` (FK), 23 saliency floats |
| `EmotionalJournal` | `emotional_journals` | `id`, `timestamp`, `userMood` (1–5), `note` |
| `ContextEvent` | `context_events` | `id`, `timestamp`, `type` (CALENDAR/WEATHER), `label` |

**Schema migration:**  
- v1 → v2: `ALTER TABLE stress_readings ADD COLUMN featureTensor BLOB`  
  (stores the raw `[12 × 23]` float tensor for FL training)

---

## Feature extraction pipeline

`FeatureExtractor.extract()` replicates `ml/src/features.py` exactly:

| Index | Group | Features (per sub-window) |
|-------|-------|--------------------------|
| 0–3 | HRV time | `hrv_meanNN`, `hrv_SDNN`, `hrv_RMSSD`, `hrv_pNN50` |
| 4–6 | HRV freq | `hrv_LF`, `hrv_HF`, `hrv_LFHF` (broadcast from 60s window) |
| 7–13 | EDA | `eda_scl_mean`, `eda_scl_slope`, `eda_scr_mean`, `eda_scr_std`, `eda_scr_auc`, `eda_scr_peaks`, `eda_scr_amp_mean` |
| 14–18 | TEMP | `temp_mean`, `temp_std`, `temp_slope`, `temp_min`, `temp_max` |
| 19–22 | ACC | `acc_mag_mean`, `acc_mag_std`, `acc_mag_energy`, `acc_mag_zcr` |

| Tab | Route | Screen | Purpose |
|-----|-------|--------|---------|
| Today | `today` | `TodayScreen` | Live stress gauge (non-tappable) + sensor status strip + XAI summary + quick actions |
| Insights | `insights` | `InsightsScreen` | Sensor contributions, artifact warnings, weekly pattern (confirmed events from user feedback), expandable technical XAI |
| History | `history` | `HistoryScreen` | 7×24 heatmap + D/W/M signal charts (Stress / HRV / Temp / EDA) |
| Route | Screen | Trigger |
|-------|--------|---------|
| `stress_detail/{id}` | `StressDetailScreen` | Tap gauge on Today, tap a peak on Insights, notification deep-link |
| `breathing` | `BreathingExerciseScreen` | "Breathe" button on Today / StressDetail |
| `sensor_status` | `SensorStatusScreen` | Sensor strip on Today tab |

**Removed:** The `Stress` bottom tab that was a duplicate of StressDetail is gone. `SettingsScreen.kt` (orphan file) deleted.

Secondary (pushed onto back-stack, not in bottom bar):
| `explain` | `[1, 12, 23]` float32 | `saliency: [1, 12, 23]` | Vanilla-gradient XAI |
| `train` | `x: [N, 12, 23]`, `y: [N]` int64 | `loss: []` float32 | On-device fine-tuning (SGD) |
| `parameters` | — | weight tensors | Export weights for FL |
| `restore` | weight tensors | — | Import global model weights |

> **Important:** The trainable model contains LSTM optimizer state variables
> that must be initialized before first use. Call
> `interpreter.runSignature(emptyMap(), emptyMap(), "initialize")` immediately
> after creating the `Interpreter` instance.

`ScalerNormalizer` loads `scaler_params.json` from assets (contains `mean[]` and
`scale[]` per feature, fit on the WESAD training set) and z-scores the tensor
before passing it to `infer`.

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
3. Load `Room.stressDao().getUnsynced()` (readings with user feedback)
4. Build `[N, 12, 23]` tensor from stored `featureTensor` BLOB
5. Call TFLite `train` signature for N mini-batches
6. Mark readings as `synced = true`
7. Export weights via `parameters` signature → upload to server

Only model weight tensors leave the device. Raw biometrics, features, and
predictions are never uploaded.

---

## Settings

`SettingsRepository` (Jetpack DataStore) persists:

| Key | Default | Description |
|-----|---------|-------------|
| `alertThreshold` | 0.85 | Stress score threshold for push notifications |
| `flEnabled` | `true` | Opt in/out of Federated Learning |
| `calendarEnabled` | `false` | Correlate stress with Google Calendar events |
| `quietHoursEnabled` | `true` | Suppress alerts overnight |
| `quietStartHour` | 22 | Quiet hours start (22:00) |
| `quietEndHour` | 7 | Quiet hours end (07:00) |

All settings are exposed via `ProfileScreen` (the **Profile** tab in the bottom navbar).

---

## Navigation

5-tab bottom navigation bar (`Screen.kt` / `NavGraph.kt`):

| Tab | Route | Screen |
|-----|-------|--------|
| Home | `home` | `DashboardScreen` — live stress gauge + XAI cards |
| Stress | `stress` | `StressDetailScreen` — most recent reading detail |
| History | `history` | `HistoryScreen` — 30-day chart |
| Journal | `journal` | `JournalListScreen` — mood diary |
| Profile | `profile` | `ProfileScreen` — settings + account |

Secondary routes: `breathing` (guided exercise), `stress_detail/{readingId}`
(deep-link from notifications).

---

## Build

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'

# Install on a connected device / emulator
.\gradlew.bat :mobile:installDebug
```

Or open the project in Android Studio and run the **mobile** configuration.

### Key build settings

| Property | Value |
|----------|-------|
| `minSdk` | 30 |
| `compileSdk` | 36 (release) |
| `noCompress` | `tflite` (models already compressed) |
| Room schema location | `mobile/schemas/` |
| Annotation processor | KSP |

### Server URL

Edit `BASE_URL` in `AuthRepository.kt`:

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
- `SettingsScreen.kt` is a redundant orphan file — all settings are implemented
  in `ProfileScreen.kt`, which is the active screen. The orphan should be deleted.
- HR dashboard (`stats/organization`) role is wired up server-side but there is
  no dedicated HR view in the phone UI yet.
