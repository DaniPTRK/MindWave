package com.example.mindwave.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches current weather from Open-Meteo API.
 *
 * Weather is fetched on-device only and stored as a context event.
 * Used by context correlator to enrich stress recommendations.
 */
class WeatherRepository {

    data class Weather(
        val temperatureC: Double,
        val weatherCode: Int,
        val summary: String,
    )

    /**
     * @param latitude/longitude location to query. Defaults to Bucharest if the
     *        device location is unavailable.
     */
    suspend fun current(
        latitude: Double = 44.4268,
        longitude: Double = 26.1025,
    ): Result<Weather> = withContext(Dispatchers.IO) {
        runCatching {
            val url = URL(
                "https://api.open-meteo.com/v1/forecast" +
                    "?latitude=$latitude&longitude=$longitude&current=temperature_2m,weather_code"
            )
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8_000
                readTimeout = 8_000
            }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                throw Exception("Weather API returned ${conn.responseCode}")
            }
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val current = json.getJSONObject("current")
            val temp = current.getDouble("temperature_2m")
            val code = current.optInt("weather_code", 0)
            Weather(temperatureC = temp, weatherCode = code, summary = describe(code))
        }.onFailure { Log.w("WeatherRepository", "Weather fetch failed: ${it.message}") }
    }

    /** Maps a WMO weather code to a short human summary. */
    private fun describe(code: Int): String = when (code) {
        0 -> "Clear sky"
        1, 2, 3 -> "Partly cloudy"
        45, 48 -> "Fog"
        in 51..57 -> "Drizzle"
        in 61..67 -> "Rain"
        in 71..77 -> "Snow"
        in 80..82 -> "Rain showers"
        in 95..99 -> "Thunderstorm"
        else -> "Unknown"
    }
}
