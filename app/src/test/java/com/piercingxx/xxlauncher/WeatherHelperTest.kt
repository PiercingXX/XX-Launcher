package com.piercingxx.xxlauncher

import com.piercingxx.xxlauncher.weather.parseWeatherSummary
import com.piercingxx.xxlauncher.weather.toWeatherLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WeatherHelperTest {

    @Test
    fun `parses temperature and condition`() {
        val payload = """{"current_weather":{"temperature":71.6,"weathercode":0}}"""
        assertEquals("72°F Clear", parseWeatherSummary(payload, useFahrenheit = true))
    }

    @Test
    fun `celsius unit label`() {
        val payload = """{"current_weather":{"temperature":21.2,"weathercode":61}}"""
        assertEquals("21°C Rain", parseWeatherSummary(payload, useFahrenheit = false))
    }

    @Test
    fun `missing current_weather returns null`() {
        assertNull(parseWeatherSummary("{}", useFahrenheit = true))
    }

    @Test
    fun `missing temperature returns null`() {
        assertNull(parseWeatherSummary("""{"current_weather":{}}""", useFahrenheit = true))
    }

    @Test
    fun `weather codes map to words`() {
        assertEquals("Clear", 0.toWeatherLabel())
        assertEquals("Cloudy", 2.toWeatherLabel())
        assertEquals("Fog", 45.toWeatherLabel())
        assertEquals("Snow", 75.toWeatherLabel())
        assertEquals("Storm", 95.toWeatherLabel())
        assertEquals("Weather", 42.toWeatherLabel())
    }
}
