package com.piercingxx.xxlauncher.widgets

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.piercingxx.xxlauncher.R
import com.piercingxx.xxlauncher.data.SettingsRepository
import com.piercingxx.xxlauncher.theme.ThemeManager
import com.piercingxx.xxlauncher.theme.applyLauncherFont
import com.piercingxx.xxlauncher.util.openAlarmApp
import com.piercingxx.xxlauncher.util.openCalendarApp
import com.piercingxx.xxlauncher.util.openWeatherApp
import com.piercingxx.xxlauncher.weather.WeatherResult
import com.piercingxx.xxlauncher.weather.fetchWeatherSummary
import com.piercingxx.xxlauncher.weather.hasWeatherLocationPermission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class WidgetContainer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    lateinit var settings: SettingsRepository
    lateinit var themeManager: ThemeManager

    /** Called when the weather widget is tapped without location permission. */
    var onWeatherPermissionNeeded: (() -> Unit)? = null

    /** Launches a "pkg|activity|user" tap-action override; returns success. */
    var launchTapAction: ((String) -> Boolean)? = null

    private var clockView: TextView? = null
    private var dateView: TextView? = null
    private var batteryView: TextView? = null
    private var weatherView: TextView? = null

    private var timeTickRegistered = false
    private var batteryRegistered = false
    private var weatherLoading = false
    private var weatherJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val timeTickReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            clockView?.text = currentTime()
            dateView?.text = currentDate()
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            batteryView?.text = batteryText()
        }
    }

    init {
        orientation = VERTICAL
    }

    /** (Re)builds the widget stack from settings. Call after settings change. */
    fun rebuild() {
        removeAllViews()
        clockView = null; dateView = null; batteryView = null; weatherView = null

        val colors = themeManager.getCurrentColors()
        val scale = settings.textSizeScale
        val dateTimeMode = settings.dateTimeMode
        val gravity = when (settings.textAlignment) {
            "left" -> Gravity.START
            "right" -> Gravity.END
            else -> Gravity.CENTER_HORIZONTAL
        }

        // Widgets render in the user's persisted order.
        settings.widgetsOrder.forEach { type ->
            when (type) {
                "clock" -> if (dateTimeMode == "date_time") {
                    clockView = addWidget(currentTime(), 53f * scale, colors.textColor, gravity) {
                        if (!runTapOverride("clock")) openAlarmApp(context)
                    }
                }

                "date" -> if (dateTimeMode != "off") {
                    dateView = addWidget(currentDate(), 14f * scale, colors.textColor, gravity) {
                        if (!runTapOverride("date")) openCalendarApp(context)
                    }
                }

                "battery" -> {
                    batteryView = addWidget(
                        batteryText(), 14f * scale, colors.textColor, gravity, topMarginDp = 8,
                    ) {
                        runTapOverride("battery")
                    }
                }

                "weather" -> {
                    weatherView = addWidget(
                        weatherText(), 14f * scale, colors.textColor, gravity, topMarginDp = 8,
                    ) {
                        when {
                            runTapOverride("weather") -> Unit
                            !context.hasWeatherLocationPermission() ->
                                onWeatherPermissionNeeded?.invoke()
                            else -> {
                                // Refresh quietly behind the scenes; the tap itself
                                // belongs to the weather app.
                                refreshWeather(force = true)
                                openWeatherApp(context)
                            }
                        }
                    }
                    refreshWeather()
                }
            }
        }

        applyLauncherFont(settings.fontFamily)
        syncReceivers()
    }

    /** Runs a configured "pkg|activity|user" tap override; false = use default. */
    private fun runTapOverride(widget: String): Boolean {
        val action = settings.getWidgetTapAction(widget)
        if (action.isBlank()) return false
        return launchTapAction?.invoke(action) ?: false
    }

    private fun addWidget(
        text: String,
        textSizeSp: Float,
        color: Int,
        widgetGravity: Int,
        topMarginDp: Int = 0,
        onTap: (() -> Unit)?,
    ): TextView {
        val view = TextView(context).apply {
            this.text = text
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
            gravity = widgetGravity
            if (onTap != null) {
                isClickable = true
                setOnClickListener { onTap() }
            }
        }
        addView(
            view,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = (topMarginDp * resources.displayMetrics.density).toInt()
            },
        )
        return view
    }

    private fun currentTime(): String {
        val pattern = if (android.text.format.DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm"
        return SimpleDateFormat(pattern, Locale.getDefault()).format(Date())
    }

    private fun currentDate(): String =
        SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(Date()).replace(".,", ",")

    private fun batteryText(): String {
        val battery = (context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return if (battery in 1..100) "$battery%" else ""
    }

    // A cached reading always wins over "Loading…" so refreshes stay invisible;
    // the loading text only appears when there is nothing to show yet.
    private fun weatherText(): String = when {
        !context.hasWeatherLocationPermission() ->
            context.getString(R.string.weather_permission_required)
        settings.weatherCachedSummary.isNotBlank() -> settings.weatherCachedSummary
        weatherLoading -> context.getString(R.string.weather_loading)
        else -> context.getString(R.string.weather_unavailable)
    }

    fun refreshWeather(force: Boolean = false) {
        if (weatherView == null) return
        if (!context.hasWeatherLocationPermission()) {
            weatherView?.text = weatherText()
            return
        }
        val now = System.currentTimeMillis()
        if (!force && settings.weatherCachedSummary.isNotBlank() &&
            now - settings.weatherCachedAt < WEATHER_REFRESH_INTERVAL_MS
        ) {
            weatherView?.text = weatherText()
            return
        }
        if (weatherLoading) return

        weatherLoading = true
        weatherView?.text = weatherText()
        weatherJob = scope.launch {
            try {
                val result = fetchWeatherSummary(context, settings.weatherTempUnit == "fahrenheit")
                if (result is WeatherResult.Success) {
                    settings.weatherCachedSummary = result.summary
                    settings.weatherCachedAt = System.currentTimeMillis()
                }
            } finally {
                // Must clear even when the job is cancelled on detach —
                // otherwise the flag latches and every later refresh no-ops.
                weatherLoading = false
            }
            // On failure the stale cached reading stays visible.
            weatherView?.text = weatherText()
        }
    }

    // Receivers follow the widgets: registered when their widget appears,
    // dropped when it is turned off, so a disabled widget stops waking the app.
    private fun syncReceivers() {
        val wantTimeTick = clockView != null || dateView != null
        if (wantTimeTick && !timeTickRegistered) {
            context.registerReceiver(timeTickReceiver, IntentFilter(Intent.ACTION_TIME_TICK))
            timeTickRegistered = true
        } else if (!wantTimeTick) {
            unregisterTimeTick()
        }

        val wantBattery = batteryView != null
        if (wantBattery && !batteryRegistered) {
            context.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            batteryRegistered = true
        } else if (!wantBattery) {
            unregisterBattery()
        }
    }

    private fun unregisterTimeTick() {
        if (!timeTickRegistered) return
        runCatching { context.unregisterReceiver(timeTickReceiver) }
        timeTickRegistered = false
    }

    private fun unregisterBattery() {
        if (!batteryRegistered) return
        runCatching { context.unregisterReceiver(batteryReceiver) }
        batteryRegistered = false
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        unregisterTimeTick()
        unregisterBattery()
        weatherJob?.cancel()
    }

    companion object {
        private const val WEATHER_REFRESH_INTERVAL_MS = 15 * 60 * 1000L
    }
}
