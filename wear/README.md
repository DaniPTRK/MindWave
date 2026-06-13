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
  ├── SWEAT_LOSS → edaBuffer              └── DataType.HEART_RATE → hrBuffer
  ├── SKIN_TEMPERATURE_CONTINUOUS → tempBuffer
  └── ACCELEROMETER_CONTINUOUS → accX/Y/ZBuffer
                │
                │  SensorForegroundService  (60s flush loop)
                ▼
         WearDataSender.sendWindow()
         DataMap → /mindwave/sensor_window
                │
         Wear Data Layer (Google encrypted BLE/Wi-Fi transport)
                │
                ▼
         Phone (MobileDataListenerService)
```

The watch also receives the stress result back from the phone via
`WatchStressSender` → `PhoneDataListenerService` and persists it in
`WatchStressStore` for the watch face UI, tile and complication.

---

## Module layout

```
wear/
├── build.gradle.kts                 # compileSdk 36, minSdk 30, Samsung AAR
└── src/main/java/com/example/mindwave/
    ├── sensor/
    │   └── SensorForegroundService.kt   # Dual-SDK sensor collection + flush loop
    ├── sync/
    │   ├── WearDataSender.kt            # Serializes buffers → DataMap → phone
    │   └── PhoneDataListenerService.kt  # Receives stress result from phone
    ├── data/
    │   └── WatchStressStore.kt          # Persists latest stress score for UI
    ├── presentation/
    │   └── MainActivity.kt             # StressRing gauge + mood quick-reply (1–5)
    ├── tile/
    │   └── MainTileService.kt           # Wear OS Tile: current stress + last update
    └── complication/
        └── MainComplicationService.kt   # Watch face complication data source
```

---

## Sensor collection

`SensorForegroundService` runs as a persistent foreground service.

### Sensor sources

| Signal | SDK | Tracker type | Sample rate | Buffer |
|--------|-----|-------------|-------------|--------|
| Heart Rate (BPM) | Jetpack Health Services | `DataType.HEART_RATE` | ~1 Hz | `hrBuffer` |
| Skin Temperature (°C) | Samsung Health SDK | `SKIN_TEMPERATURE_CONTINUOUS` | ~0.1 Hz | `tempBuffer` |
| EDA / Sweat Loss (µS proxy) | Samsung Health SDK | `SWEAT_LOSS` | ~4 Hz | `edaBuffer` |
| Accelerometer X/Y/Z (ADC int) | Samsung Health SDK | `ACCELEROMETER_CONTINUOUS` | ~25 Hz | `accX/Y/ZBuffer` |

All buffers are `CopyOnWriteArrayList<TimestampedValue>` (thread-safe, epoch ms + value).

Availability flags exposed as `StateFlow`:
- `SensorForegroundService.edaAvailable` — `true` only if the Samsung EDA tracker connected successfully
- `SensorForegroundService.accAvailable` — `true` only if the Samsung ACC tracker connected successfully

On devices that don't support EDA/ACC (non-Samsung hardware or emulator), the
flags remain `false` and the phone-side feature extractor receives empty arrays —
graceful degradation with zero-filled ACC/EDA features.

### Flush loop

Every 60 seconds, the service calls `WearDataSender.sendWindow()` which:

1. Snapshots and clears all six buffers atomically.
2. Serializes to a `DataMap` (see wire format below).
3. Puts the `PutDataMapRequest` via `Wearable.getDataClient().putDataItem()`.
4. If the send fails, backs off exponentially (max 3 retries).

---

## Wear Data Layer wire format

Path: `/mindwave/sensor_window`

| Key | Type | Description |
|-----|------|-------------|
| `timestamp` | `Long` | Epoch ms of the flush |
| `hr_times` | `LongArray` | Epoch ms for each HR sample |
| `hr_values` | `DoubleArray` | BPM values |
| `temp_times` | `LongArray` | Epoch ms for each temperature sample |
| `temp_values` | `DoubleArray` | °C values |
| `eda_times` | `LongArray` | Epoch ms for each EDA sample |
| `eda_values` | `DoubleArray` | Sweat-loss proxy µS |
| `acc_x_values` | `FloatArray` | Raw ADC accelerometer X (~25 Hz) |
| `acc_y_values` | `FloatArray` | Raw ADC accelerometer Y |
| `acc_z_values` | `FloatArray` | Raw ADC accelerometer Z |
| `eda_available` | `Boolean` | `true` if Samsung EDA tracker is active |
| `acc_available` | `Boolean` | `true` if Samsung ACC tracker is active |
| `mood` | `Int` | 0 = no input; 1–5 from quick mood selector |

---

## Watch UI

### MainActivity — `StressRing`

Circular stress gauge drawn via `Canvas`. Shows:
- Animated arc (0–1 fill, colour from green→red)
- Numeric stress percentage
- Last updated time

Mood quick-reply row: three icon buttons (😊 1-3 / 😐 3 / 😞 4-5) that set
`userMood` in `WatchStressStore` and include the value in the next window send.

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
| EDA (sweat loss) | Samsung Galaxy Watch 4 / 5 / 6 / 7 |
| Skin temperature | Samsung Galaxy Watch 4 / 5 / 6 / 7 |
| Accelerometer | Samsung Galaxy Watch 4 / 5 / 6 / 7 (via Samsung Health SDK) |

A Wear OS emulator can run the app but Samsung Health Sensor SDK trackers will
**not** produce data — `edaAvailable` and `accAvailable` will remain `false`.
HR data from Jetpack Health Services may also be unavailable on the emulator.

---

## Pairing with the phone

There is no in-app pairing button. Standard system pairing via the **Galaxy Wearable**
or **Wear OS** companion app must be completed first. Once the watch and phone
are paired at the OS level, the Wear Data Layer connects the two MindWave apps
automatically.

