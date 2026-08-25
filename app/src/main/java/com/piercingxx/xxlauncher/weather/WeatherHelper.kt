package com.piercingxx.xxlauncher.weather

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Open-Meteo (keyless) current-weather lookup. This is the only outbound
 * network request the launcher ever makes.
 */
sealed interface WeatherResult {
    data class Success(val summary: String) : WeatherResult
    data object PermissionRequired : WeatherResult
    data object LocationUnavailable : WeatherResult
    data object NetworkUnavailable : WeatherResult
}

fun Context.hasWeatherLocationPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

suspend fun fetchWeatherSummary(context: Context, useFahrenheit: Boolean): WeatherResult =
    withContext(Dispatchers.IO) {
        if (!context.hasWeatherLocationPermission()) return@withContext WeatherResult.PermissionRequired
        val location = context.bestLastKnownLocation()
            ?: return@withContext WeatherResult.LocationUnavailable

        runCatching { fetchFromOpenMeteo(location, useFahrenheit) }.fold(
            onSuccess = { summary ->
                if (summary.isNullOrBlank()) WeatherResult.NetworkUnavailable
                else WeatherResult.Success(summary)
            },
            onFailure = { WeatherResult.NetworkUnavailable },
        )
    }

private fun fetchFromOpenMeteo(location: Location, useFahrenheit: Boolean): String? {
    val unit = if (useFahrenheit) "fahrenheit" else "celsius"
    val endpoint = URL(
        "https://api.open-meteo.com/v1/forecast?latitude=${location.latitude}" +
            "&longitude=${location.longitude}&current_weather=true&timezone=auto" +
            "&temperature_unit=$unit"
    )
    val connection = (endpoint.openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 5000
        readTimeout = 5000
    }
    try {
        if (connection.responseCode !in 200..299) return null
        connection.inputStream.bufferedReader().use { reader ->
            return parseWeatherSummary(reader.readText(), useFahrenheit)
        }
    } finally {
        connection.disconnect()
    }
}

internal fun parseWeatherSummary(payload: String, useFahrenheit: Boolean): String? {
    val currentWeather = JSONObject(payload).optJSONObject("current_weather") ?: return null
    val temperature = currentWeather.optDouble("temperature", Double.NaN)
    if (temperature.isNaN()) return null
    val weatherCode = currentWeather.optInt("weathercode", -1)
    return String.format(
        Locale.getDefault(),
        "%d°%s %s",
        temperature.roundToInt(),
        if (useFahrenheit) "F" else "C",
        weatherCode.toWeatherLabel(),
    )
}

// Callers gate on hasWeatherLocationPermission(); reads are also try-wrapped.
@android.annotation.SuppressLint("MissingPermission")
private fun Context.bestLastKnownLocation(): Location? {
    val locationManager =
        getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
    return listOf(
        LocationManager.NETWORK_PROVIDER,
        LocationManager.PASSIVE_PROVIDER,
        LocationManager.GPS_PROVIDER,
    )
        .mapNotNull { provider ->
            runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull()
        }
        .maxByOrNull { it.time }
}

internal fun Int.toWeatherLabel(): String = when (this) {
    0 -> "Clear"
    1, 2, 3 -> "Cloudy"
    45, 48 -> "Fog"
    51, 53, 55, 56, 57 -> "Drizzle"
    61, 63, 65, 66, 67, 80, 81, 82 -> "Rain"
    71, 73, 75, 77, 85, 86 -> "Snow"
    95, 96, 99 -> "Storm"
    else -> "Weather"
}
