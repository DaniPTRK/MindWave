# MindWave — Wear OS App (`wear/`)

Samsung Galaxy Watch application that collects raw biometric signals and
streams them to the paired phone. The watch **never runs the ML model** —
its sole job is sensor capture, buffering, and transmission.

---

## Architecture

```
Samsung Health Sensor SDK v1.4.1        Jetpack Health Services
────────────────────────────────        ───────────────────────
 HealthTrackingService                   HealthServices.getMeasureClient()
  ├── EDA_CONTINUOUS  → edaBuffer         └── DataType.HEART_RATE → hrBuffer
  │   (fallback: SWEAT_LOSS)                  (non-Samsung or SDK fallback)
  ├── SKIN_TEMPERATURE_CONTINUOUS → tempBuffer
  └── ACCELEROMETER_CONTINUOUS → accX/Y/ZBuffer
                │
                │  SensorForegroundService  (2-min fallback flush loop)
                ▼
         WearDataSender.sendWindow()         ◄── PhoneDataListenerService.onMessageReceived()
         DataMap → /mindwave/sensor_window        (phone-driven 60s flush request)
                │
         Wear Data Layer (Google encrypted BLE/Wi-Fi transport)
                │
                ▼
         Phone (MobileDataListenerService)
```

The watch also receives the stress result back from the phone via
`WatchStressSender` → `PhoneDataListenerService.onDataChanged()` and persists
it in `WatchStressStore` for the watch face UI, tile and complication. The pushed payload also carries the latest `StressReading` id so a quick watch reply can be tied to the prediction it evaluates.

### Flush cadence — phone-driven polling

The phone owns the 60-second inference cadence:

1. **`WatchFlushRequester`** (phone) sends a zero-byte `MessageClient` message
   to `/mindwave/request_flush` after the first 60-second interval and then every 60 s.
2. **`PhoneDataListenerService.onMessageReceived()`** (watch) receives the ping
   and immediately calls `WearDataSender.sendWindow()`.
3. The watch also has a **2-minute fallback timer** in `SensorForegroundService`
   that fires only if no recent phone-driven flush request was received, for example after a lost Bluetooth message or a killed phone process.

`MessageClient` is used (not `DataClient`) because it is fire-and-forget,
sub-second delivery, and the payload is empty — just a trigger.
The phone declares the `mindwave_phone_app` capability and the watch declares
`mindwave_wear_app`; if capability discovery is stale on the phone, the flush
requester falls back to all connected Wear nodes.

---

## Module layout

```
wear/
├── build.gradle.kts                 # compileSdk 36, minSdk 30, Samsung AAR
└── src/main/java/com/example/mindwave/
    ├── sensor/
    │   └── SensorForegroundService.kt   # Dual-SDK sensor collection + 2-min fallback flush
    ├── sync/
    │   ├── WearDataSender.kt            # Serializes buffers → DataMap → phone
    │   ├── PhoneDataListenerService.kt  # Receives stress result + flush requests from phone
    │   └── PhoneAuthListenerService.kt  # Receives auth state from phone, updates PhoneAuthState
    ├── data/
    │   ├── WatchStressStore.kt          # Persists latest stress score for UI
    │   ├── PhoneAuthState.kt            # SharedPrefs: is_logged_in + user_email from phone
    │   └── SensorDebugState.kt          # Live StateFlows read by DebugScreen
    ├── presentation/
    │   └── MainActivity.kt             # FlowerGaugeScreen + WearBreathingScreen + mood quick-reply
    ├── tile/
    │   └── MainTileService.kt           # Wear OS Tile: current stress + last update
    └── complication/
        └── MainComplicationService.kt   # Watch face complication data source
```

---

## Sensor collection

`SensorForegroundService` runs as a persistent foreground service. It is automatically
started when `MainActivity` launches and will appear as an ongoing notification.

### Required permissions

The following permissions are declared in `AndroidManifest.xml`:

| Permission | Purpose |
|-----------|---------|
| `BODY_SENSORS` | Heart rate and Samsung Health SDK sensors |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_HEALTH` | Continuous monitoring |
| `POST_NOTIFICATIONS` | Persistent foreground notification |
| `ACTIVITY_RECOGNITION` | Some Samsung Health trackers |
| `android.permission.health.READ_HEART_RATE` | Jetpack Health Services HR |
| `android.permission.health.READ_SKIN_TEMPERATURE` | Samsung skin temp |
| `android.permission.health.READ_OXYGEN_SATURATION` | Samsung SDK |
| **`com.samsung.android.hardware.sensormanager.permission.READ_ADDITIONAL_HEALTH_DATA`** | **Samsung EDA (skin conductance) and accelerometer** |

> The `READ_ADDITIONAL_HEALTH_DATA` manifest declaration is required for EDA
> access. Without it, `getHealthTracker(EDA_CONTINUOUS)` silently fails regardless
> of the runtime permission grant.

### Sensor sources

| Signal | SDK | Tracker type | Sample rate | Buffer |
|--------|-----|-------------|-------------|--------|
| Heart Rate (BPM) | Samsung Health SDK | `HEART_RATE_CONTINUOUS` | ~1 Hz | `hrBuffer` |
| Heart Rate (BPM) | Jetpack Health Services | `DataType.HEART_RATE` | ~1 Hz | `hrBuffer` (non-Samsung fallback) |
| Skin Temperature (°C) | Samsung Health SDK | `SKIN_TEMPERATURE_CONTINUOUS` | ~0.1 Hz | `tempBuffer` |
| EDA / Skin Conductance (µS) | Samsung Health SDK | `EDA_CONTINUOUS` → `SWEAT_LOSS` fallback | ~4 Hz | `edaBuffer` |
| Accelerometer X/Y/Z (mg) | Samsung Health SDK | `ACCELEROMETER_CONTINUOUS` | ~25 Hz | `accX/Y/ZBuffer` |

All buffers are `CopyOnWriteArrayList<TimestampedValue>` (thread-safe, epoch ms + value).

**EDA tracker registration** tries two tracker types in order:
1. `EDA_CONTINUOUS` with `ValueKey.EdaSet.SKIN_CONDUCTANCE` (Galaxy Watch 5+)
2. `SWEAT_LOSS` with `ValueKey.SweatLossSet.SWEAT_LOSS` (legacy fallback)

If `onError` fires on the active tracker, `edaAvailable` is set to `false` and
`SensorDebugState.sensorStatus` is updated so the debug screen reflects the failure.

Availability flags exposed as `StateFlow`:
- `SensorForegroundService.edaAvailable` — `true` only if the Samsung EDA tracker connected successfully
- `SensorForegroundService.accAvailable` — `true` only if the Samsung ACC tracker connected successfully

On devices that don't support EDA/ACC (non-Samsung hardware or emulator), the
flags remain `false` and the phone-side feature extractor receives empty arrays —
graceful degradation with zero-filled ACC/EDA features.

### Flush mechanism

Data flushing is now **phone-driven**. The watch has two flush paths:

| Path | Trigger | Interval |
|------|---------|----------|
| **Phone-requested flush** (primary) | `MessageClient` message from `WatchFlushRequester` | 60 s (controlled by phone) |
| **Fallback flush** (safety net) | Watch-side timer in `SensorForegroundService`; skipped when a phone request arrived recently | 120 s check |

Both paths check `PhoneAuthState.isPhoneLoggedIn()` before calling
`WearDataSender.sendWindow()`. If the phone is fully logged out the buffers
accumulate locally. Offline mode does **not** stop the flush.

When `sendWindow()` proceeds it:

1. Snapshots and clears all six buffers atomically.
2. Injects the last-known temperature if the temp buffer is empty (sensor slow to fire).
3. Serializes to a `DataMap` (see wire format below).
4. Puts the `PutDataMapRequest` via `Wearable.getDataClient().putDataItem()`.
5. If the send fails, backs off exponentially (max 5 retries, up to 30 s).

---

## Wear Data Layer wire format

Path: `/mindwave/sensor_window`

| Key | Type | Description |
|-----|------|-------------|
| `timestamp` | `Long` | Epoch ms of the flush |
| `hr_times` | `LongArray` | Epoch ms for each HR sample |
| `hr_values` | `FloatArray` | BPM values |
| `temp_times` | `LongArray` | Epoch ms for each temperature sample |
| `temp_values` | `FloatArray` | °C values |
| `eda_times` | `LongArray` | Epoch ms for each EDA sample |
| `eda_values` | `FloatArray` | Skin conductance µS |
| `acc_x_values` | `FloatArray` | ACC X in **g** (Samsung mg ÷ 1000, matches WESAD scale) |
| `acc_y_values` | `FloatArray` | ACC Y in g |
| `acc_z_values` | `FloatArray` | ACC Z in g |
| `eda_available` | `Boolean` | `true` if Samsung EDA tracker is active |
| `acc_available` | `Boolean` | `true` if Samsung ACC tracker is active |
| `mood` | `Int` | 0 = no input; 1/2 = stressed, 3 = not sure, 4/5 = not stressed |
| `mood_only` | `Boolean` | `true` for quick-reply payloads that contain feedback but no sensor window |
| `reading_id` | `Long` | Stress reading id returned by the phone, used to attach quick replies to the evaluated prediction |
| `_nonce` | `Long` | `System.nanoTime()` — forces Data Layer to deliver even if payload unchanged |

---

## Watch UI

### MainActivity — `FlowerGaugeScreen` (page 0)

Flower-gauge watch face with:
- Animated circular stress arc (colour green→yellow→orange→red, updated when phone pushes a result)
- Central stress percentage + label
- Four sensor petal bubbles (HR, EDA, Temp, Acc) pinned to side positions.
- Bottom action row with compact icon buttons:
  **Breathe** launches `WearBreathingScreen`; **Rate** opens the mood quick-reply popup.
- Feedback popup is **not dismissed** when a new stress score arrives from the phone
  while the user is mid-interaction (guarded by `showFeedback` / `showBreathing` state)

### WearBreathingScreen

Full-screen 4-7-8 breathing exercise that runs entirely on the watch — no phone
connection required:

| Phase | Duration |
|-------|---------|
| Breathe in | 4 s |
| Hold | 7 s |
| Breathe out | 8 s |

Runs 3 cycles, then shows a "Done" completion screen. An animated circle expands
on inhale/hold and contracts on exhale. The user can stop early at any time.

### DebugScreen (page 1 — swipe right)

Live sensor debug view: SDK connection status, live HR / Temp / EDA / ACC values,
per-buffer sample counts, and a scrollable send log (success/skip/error).

### SdkCheckScreen (test-only — not in swipe flow)

Step-by-step Samsung Health SDK diagnostic tool. Kept in the codebase for
developer/debug use but **removed from the swipe pager** so end users never
encounter it.

The pager has **2 pages** (FlowerGauge + Debug). Page indicator shows 2 dots.

### Tile — `MainTileService`

Single-column Wear OS Tile:
- Current stress score badge
- Time since last measurement
- "Start sensor" launch action

### Complication — `MainComplicationService`

Provides a `SHORT_TEXT` complication data source with the current stress
percentage for use on any Wear OS watch face.

---

## Build

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'

# Install on a connected Galaxy Watch or Wear OS emulator
.\gradlew.bat :wear:installDebug
```

Or use the **wear** run configuration in Android Studio.

### Key build settings

| Property | Value |
|----------|-------|
| `minSdk` | 30 |
| `compileSdk` | 36 (release) |
| `useLibrary` | `"wear-sdk"` |
| Samsung SDK | Local AAR — `samsung-health-sensor-api-1.4.1@aar` |

---

## Hardware requirements

| Feature | Requirement |
|---------|-------------|
| Heart rate | Any Wear OS 3+ device |
| EDA (skin conductance) | Samsung Galaxy Watch 4 / 5 / 6 / 7 / 8 |
| Skin temperature | Samsung Galaxy Watch 4 / 5 / 6 / 7 / 8 |
| Accelerometer | Samsung Galaxy Watch 4 / 5 / 6 / 7 / 8 (via Samsung Health SDK) |

A Wear OS emulator can run the app but Samsung Health Sensor SDK trackers will
**not** produce data — `edaAvailable` and `accAvailable` will remain `false`.
HR data from Jetpack Health Services may also be unavailable on the emulator.

---

## Phone auth sync

The watch automatically adapts to changes in the phone's login state:

| Phone state | Watch behaviour |
|-------------|----------------|
| Logged in (real account) | ✅ Flushes buffers on every phone request (~60s) |
| Offline mode (`offline@local`) | ✅ Flushes buffers on every phone request (~60s) |
| Fully logged out | ⛔ Skips flush — buffers accumulate |
| App restarted / relaunched | ✅ Phone re-publishes auth state on startup + `onResume` |
| Watch connects/reconnects | ✅ Phone auto-detects and publishes auth state |

### How it works

1. The phone publishes `{ is_logged_in, user_email }` to `/mindwave/phone_auth` via
   the Wear Data Layer whenever the auth state changes (login, logout, offline, and
   every `MainActivity.onResume`).
2. `PhoneAuthListenerService` on the watch receives the update and writes it to
   `PhoneAuthState` (SharedPreferences). Stores `last_update` timestamp.
3. Both flush paths (phone-requested and fallback timer) check
   `PhoneAuthState.isPhoneLoggedIn()` before sending. The skip log includes how
   old the stored auth state is for diagnosability.
4. `WatchConnectionListener` on the phone detects when the watch connects
   (via `CapabilityClient`) and automatically publishes the current auth state.

> The watch defaults to `is_logged_in = true` if no update has been received yet,
> so sensor data flows from the first boot without requiring a manual re-login.

---

## Pairing with the phone

There is no in-app pairing button. Standard system pairing via the **Galaxy Wearable**
or **Wear OS** companion app must be completed first. Once the watch and phone
are paired at the OS level, the Wear Data Layer connects the two MindWave apps
automatically.


