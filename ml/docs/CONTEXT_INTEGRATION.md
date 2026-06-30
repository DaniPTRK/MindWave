# MindWave — Context Integration Architecture

This document describes how the app **correlates stress predictions** with
external context (calendar events, weather conditions) to generate proactive
recommendations — directly fulfilling the need of "Adrian" (relate stress
to meetings) and "Maria" (understand why she's exhausted at night).

---

## 1. Data flow (end-to-end)

```
Samsung Health Sensor SDK (BVP, EDA, TEMP, ACC)
          │
          ▼
┌─────────────────────────┐
│  FeatureExtractor       │  60 s windows → (1, 12, F) tensor
│  (Kotlin, on-device)    │
└────────────┬────────────┘
             │
             ▼
┌─────────────────────────┐
│  TFLite Interpreter     │
│  • infer → P(class)     │
│  • explain → importances│
└────────────┬────────────┘
             │
             ▼
┌─────────────────────────┐
│  Room: StressReading    │  persist prediction + top features
│       + XaiExplanation  │
└────────────┬────────────┘
             │  (trigger: new reading inserted)
             ▼
┌─────────────────────────┐
│  ContextCorrelator      │  queries Calendar & Weather APIs
│  (Kotlin coroutine)     │
└────────────┬────────────┘
             │
             ▼
┌─────────────────────────┐         ┌─────────────────────────┐
│  Room: ContextEvent     │────────►│  RecommendationEngine   │
│                         │         │  (rule-based, local)    │
└─────────────────────────┘         └────────────┬────────────┘
                                                 │
                                                 ▼
                                    Flow<Recommendation> → UI
```

Everything runs **on-device**. Calendar and weather data are fetched by the
phone's own network stack and stored in Room; they are never sent to the FL
server (only model weights leave the device).

---

## 2. ContextCorrelator — pseudo-code

```kotlin
class ContextCorrelator(
    private val calendarApi: GoogleCalendarClient,
    private val weatherApi: WeatherClient,
    private val contextDao: ContextDao,
    private val recommendationEngine: RecommendationEngine,
) {
    /**
     * Called right after a new [StressReading] is persisted.
     * Runs in a coroutine scope tied to the app lifecycle.
     */
    suspend fun onNewReading(reading: StressReading, xai: List<XaiExplanation>) {
        // 1. Calendar: events ±30 min from the reading.
        val events = calendarApi.getEvents(
            startMillis = reading.timestamp - 30 * 60_000,
            endMillis   = reading.timestamp + 30 * 60_000,
        )
        events.forEach { event ->
            contextDao.insert(ContextEvent(
                readingId = reading.id,
                timestamp = event.startMillis,
                source    = "calendar",
                title     = event.title,
                metadata  = """{"location":"${event.location}","duration":${event.durationMin}}""",
            ))
        }

        // 2. Weather: current conditions at the user's location.
        val weather = weatherApi.getCurrent()
        contextDao.insert(ContextEvent(
            readingId = reading.id,
            timestamp = reading.timestamp,
            source    = "weather",
            title     = weather.summary,  // e.g. "Cloudy, 8 °C"
            metadata  = """{"temp":${weather.tempC},"humidity":${weather.humidity},"uv":${weather.uvIndex}}""",
        ))

        // 3. Generate recommendations.
        recommendationEngine.evaluate(reading, xai, events, weather)
    }
}
```

---

## 3. RecommendationEngine — rule-based (v1)

The initial version uses a simple rule engine (no ML) that fires when a
pattern is detected. Rules are expressed declaratively so they can later be
replaced by a learned policy.

```kotlin
data class Recommendation(
    val timestamp: Long,
    val readingId: Long,
    val type: String,        // "breathing", "break", "hydrate", "sleep_tip"
    val message: String,     // user-facing localised string
    val priority: Int = 0,   // higher = more urgent
)

class RecommendationEngine(private val output: MutableSharedFlow<Recommendation>) {

    fun evaluate(
        reading: StressReading,
        xai: List<XaiExplanation>,
        calendarEvents: List<CalendarEvent>,
        weather: WeatherSnapshot,
    ) {
        val topFeature = xai.firstOrNull()?.featureName ?: return

        // RULE 1: Stress + HRV drop + upcoming meeting → breathing exercise.
        if (reading.stressScore == 1 /* stress */
            && topFeature.startsWith("hrv")
            && calendarEvents.any { it.startMillis > reading.timestamp }
        ) {
            val nextEvent = calendarEvents.first { it.startMillis > reading.timestamp }
            emit(Recommendation(
                timestamp = System.currentTimeMillis(),
                readingId = reading.id,
                type      = "breathing",
                message   = "Try a 2-min breathing exercise before '${nextEvent.title}'.",
                priority  = 2,
            ))
        }

        // RULE 2: Stress + EDA dominant + late night → sleep tip for Maria.
        if (reading.stressScore == 1
            && topFeature.startsWith("eda")
            && isLateNight(reading.timestamp)
        ) {
            emit(Recommendation(
                timestamp = System.currentTimeMillis(),
                readingId = reading.id,
                type      = "sleep_tip",
                message   = "Your EDA is elevated late at night — consider winding down.",
                priority  = 1,
            ))
        }

        // RULE 3: Stress + low temperature + cold weather → hydrate / warm up.
        if (reading.stressScore == 1
            && topFeature == "temp_mean"
            && weather.tempC < 10
        ) {
            emit(Recommendation(
                timestamp = System.currentTimeMillis(),
                readingId = reading.id,
                type      = "hydrate",
                message   = "Cold weather + low skin temp may amplify stress — warm up & hydrate.",
                priority  = 1,
            ))
        }
    }

    private fun emit(r: Recommendation) { output.tryEmit(r) }
    private fun isLateNight(ts: Long): Boolean {
        val hour = java.util.Calendar.getInstance().apply { timeInMillis = ts }.get(java.util.Calendar.HOUR_OF_DAY)
        return hour in 23..23 || hour in 0..5
    }
}
```

---

## 4. API integration notes

| API | Library | Auth | Key points |
|-----|---------|------|------------|
| Google Calendar | `com.google.api-client:google-api-services-calendar` | OAuth 2.0 (user consent) | Request `events.list` with `timeMin/timeMax` ±30 min; only read titles + times |
| OpenWeatherMap | Retrofit + Moshi | API key in `local.properties` | `/data/2.5/weather?lat=...&lon=...`; poll every 30 min or on new stress reading |

Both API calls are **read-only** and fire from the device. No user data
flows to these providers — we only pull context *in*.

---

## 5. Privacy recap

| Data | Stays on device? | Leaves device? |
|------|:---:|:---:|
| Raw BVP / EDA / TEMP / ACC | ✅ | ❌ |
| Engineered feature tensor (12, F) | ✅ | ❌ |
| `StressReading` row | ✅ | ❌ |
| `XaiExplanation` rows | ✅ | ❌ |
| `EmotionalJournal` entries | ✅ | ❌ |
| `ContextEvent` (calendar + weather) | ✅ | ❌ |
| Model **weights** (FL round) | — | ✅ (encrypted gRPC to server) |
| Aggregated org-level heatmap (Elena) | — | ✅ (differential privacy noise applied) |

---

## 6. Stage roadmap

| Stage | Status |
|---|---|
| 1. Baseline LSTM on WESAD | ✅ |
| 2. Flower FL simulation (Python) | ✅ |
| **3. TFLite + XAI + Room + Context architecture** | ✅ **this stage** |
| 4. FastAPI server, Docker, OAuth2, PostgreSQL | ⏭️ next |
| 5. Jetpack Compose UI + Wear Tile | ⏭️ later |

