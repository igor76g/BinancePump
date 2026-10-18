package com.example.binancepump

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Foreground service that continuously monitors all Binance USDT-M futures:
 *   1. keeps a list of symbols whose 24h quote volume >= minimum volume,
 *   2. polls prices and tracks them over a rolling window,
 *   3. sends a phone notification when growth within the window >= threshold.
 *
 * Settings are re-read on every loop iteration, so changes saved in the app apply
 * without restarting the service.
 */
class MonitorService : Service() {

    companion object {
        private const val TAG = "MonitorService"

        /** Human-readable status shown in the app UI. */
        val status = MutableStateFlow("Stopped")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val tracker = PriceTracker()
    private var allSymbols: Set<String> = emptySet()
    private var qualifying: Set<String> = emptySet()
    private var lastVolumeRefreshMs = 0L
    private val lastAlertMs = HashMap<String, Long>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Notifier.ensureChannels(this)
        ServiceCompat.startForeground(
            this,
            Notifier.FOREGROUND_ID,
            Notifier.buildServiceNotification(this, "Starting..."),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        publishStatus("Starting...")

        if (job?.isActive != true) {
            job = scope.launch {
                while (isActive) {
                    val settings = SettingsStore.load(applicationContext)
                    try {
                        tick(settings)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Check failed", e)
                        publishStatus("Error: ${e.message ?: e.javaClass.simpleName} (retrying)")
                    }
                    delay(settings.checkIntervalSec.coerceAtLeast(1) * 1000L)
                }
            }
        }
        return START_STICKY
    }

    /** One monitoring iteration (blocking network calls, runs on Dispatchers.IO). */
    private fun tick(settings: Settings) {
        val nowMs = System.currentTimeMillis()

        // 1) Periodically refresh which symbols pass the 24h volume filter
        val refreshMs = settings.volumeRefreshSec.coerceAtLeast(1) * 1000L
        if (qualifying.isEmpty() || nowMs - lastVolumeRefreshMs >= refreshMs) {
            allSymbols = BinanceApi.fetchUsdtPerpetualSymbols()
            val volumes = BinanceApi.fetchQuoteVolumes()
            qualifying = allSymbols
                .filter { (volumes[it] ?: 0.0) >= settings.minVolumeUsdt }
                .toSet()
            tracker.retainOnly(qualifying)
            lastVolumeRefreshMs = nowMs
        }

        // 2) Poll prices and detect pumps
        val prices = BinanceApi.fetchPrices()
        val windowMs = settings.windowMinutes.coerceAtLeast(1) * 60_000L
        val cooldownMs = settings.cooldownMinutes.coerceAtLeast(0) * 60_000L

        for (symbol in qualifying) {
            val price = prices[symbol] ?: continue
            tracker.add(symbol, price, nowMs, windowMs)
            val change = tracker.changePercent(symbol) ?: continue

            if (change >= settings.priceChangePercent) {
                val last = lastAlertMs[symbol]
                if (last != null && nowMs - last < cooldownMs) continue

                Log.i(TAG, "ALERT $symbol +${"%.2f".format(change)}%")
                Notifier.sendPumpAlert(this, symbol, change, price, settings.windowMinutes)
                lastAlertMs[symbol] = nowMs
            }
        }

        publishStatus(
            "Watching ${qualifying.size} coins (24h volume >= ${settings.minVolumeMillionUsdt} M USDT). " +
                "Last check: ${timeFormat.format(Date())}"
        )
    }

    private fun publishStatus(text: String) {
        status.value = text
        Notifier.updateServiceNotification(this, text)
    }

    override fun onDestroy() {
        job?.cancel()
        scope.cancel()
        status.value = "Stopped"
        super.onDestroy()
    }
}
