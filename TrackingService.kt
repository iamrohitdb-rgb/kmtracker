package com.example.kmtrackerpro

// ─────────────────────────────────────────────────────────────
//  TrackingService.kt
//
//  A Foreground Service that:
//   • Requests GPS updates from FusedLocationProviderClient
//   • Calculates distance using the Haversine formula
//   • Filters out GPS noise (jumps < 2 metres)
//   • Fires a voice alert every full kilometre
//   • Broadcasts live stats to MainActivity via LiveData objects
//   • Shows a persistent notification so Android won't kill it
// ─────────────────────────────────────────────────────────────

import android.app.*
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.*
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import androidx.lifecycle.MutableLiveData
import com.google.android.gms.location.*
import java.util.Locale
import kotlin.math.*

class TrackingService : Service() {

    // ── Companion: channel id + LiveData shared with MainActivity ──
    companion object {
        const val CHANNEL_ID = "tracking_channel"
        const val NOTIFICATION_ID = 1

        // These are observed in MainActivity to update the UI in real time
        val distanceLiveData   = MutableLiveData(0.0)   // km
        val timeLiveData       = MutableLiveData(0L)     // seconds
        val speedLiveData      = MutableLiveData(0.0)    // km/h
        val pathLiveData       = MutableLiveData<MutableList<android.location.Location>>(mutableListOf())
        val isTracking         = MutableLiveData(false)
    }

    // ── GPS client provided by Google Play Services ──
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback

    // ── Text-to-Speech for voice alerts ──
    private lateinit var tts: TextToSpeech

    // ── State ──
    private var lastLocation: Location? = null
    private var totalDistanceMetres = 0.0
    private var lastKmAlertAt = 0        // last whole km at which we spoke
    private var startTimeMs = 0L
    private var timer: CountDownTimer? = null
    private var elapsedSeconds = 0L
    private var isPaused = false

    // ─────────────────────────────────────────────────────────
    //  Service lifecycle
    // ─────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()
        initTts()
        setupLocationCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "START"  -> startTracking()
            "PAUSE"  -> pauseTracking()
            "RESUME" -> resumeTracking()
            "STOP"   -> stopTracking()
        }
        return START_STICKY  // Restart service if killed by system
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        tts.shutdown()
        timer?.cancel()
    }

    // ─────────────────────────────────────────────────────────
    //  Tracking control
    // ─────────────────────────────────────────────────────────

    private fun startTracking() {
        // Reset all state for a fresh run
        totalDistanceMetres = 0.0
        lastKmAlertAt = 0
        lastLocation = null
        elapsedSeconds = 0L
        isPaused = false

        distanceLiveData.postValue(0.0)
        timeLiveData.postValue(0L)
        speedLiveData.postValue(0.0)
        pathLiveData.postValue(mutableListOf())
        isTracking.postValue(true)

        startForeground(NOTIFICATION_ID, buildNotification("Tracking…"))
        requestLocationUpdates()
        startTimer()
    }

    private fun pauseTracking() {
        isPaused = true
        timer?.cancel()
        fusedLocationClient.removeLocationUpdates(locationCallback)
        updateNotification("Paused")
    }

    private fun resumeTracking() {
        isPaused = false
        requestLocationUpdates()
        startTimer()
        updateNotification("Tracking…")
    }

    private fun stopTracking() {
        isPaused = false
        timer?.cancel()
        fusedLocationClient.removeLocationUpdates(locationCallback)
        isTracking.postValue(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ─────────────────────────────────────────────────────────
    //  Timer (counts elapsed seconds)
    // ─────────────────────────────────────────────────────────

    private fun startTimer() {
        // CountDownTimer counts DOWN, so we use a huge number and
        // just track elapsed time ourselves.
        timer = object : CountDownTimer(Long.MAX_VALUE, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                elapsedSeconds++
                timeLiveData.postValue(elapsedSeconds)
            }
            override fun onFinish() {}
        }.start()
    }

    // ─────────────────────────────────────────────────────────
    //  Location updates
    // ─────────────────────────────────────────────────────────

    private fun setupLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                if (isPaused) return
                result.lastLocation?.let { newLoc ->
                    processNewLocation(newLoc)
                }
            }
        }
    }

    @Suppress("MissingPermission") // Permission is checked in MainActivity before starting this service
    private fun requestLocationUpdates() {
        val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            2000L  // request update every 2 seconds
        ).setMinUpdateDistanceMeters(2f) // ignore if moved < 2 metres (GPS noise filter)
            .build()

        fusedLocationClient.requestLocationUpdates(
            request,
            locationCallback,
            Looper.getMainLooper()
        )
    }

    // ─────────────────────────────────────────────────────────
    //  Core logic: process each new GPS point
    // ─────────────────────────────────────────────────────────

    private fun processNewLocation(newLocation: Location) {
        val prev = lastLocation
        if (prev != null) {
            val deltaMetres = haversineMetres(
                lat1 = prev.latitude,  lon1 = prev.longitude,
                lat2 = newLocation.latitude, lon2 = newLocation.longitude
            )

            // ── GPS noise filter: ignore jumps smaller than 2 metres ──
            if (deltaMetres < 2.0) return

            totalDistanceMetres += deltaMetres
            val totalKm = totalDistanceMetres / 1000.0

            // ── Update live stats ──
            distanceLiveData.postValue(totalKm)

            // Speed = distance / time  (in km/h)
            val speedKmh = if (elapsedSeconds > 0)
                totalKm / (elapsedSeconds / 3600.0) else 0.0
            speedLiveData.postValue(speedKmh)

            // ── Add point to path (for the map polyline) ──
            val currentPath = pathLiveData.value ?: mutableListOf()
            currentPath.add(newLocation)
            pathLiveData.postValue(currentPath)

            // ── Voice alert every full kilometre ──
            val completedKm = totalKm.toInt()
            if (completedKm > lastKmAlertAt) {
                lastKmAlertAt = completedKm
                speakKmAlert(completedKm)
            }

            // ── Update notification with current stats ──
            updateNotification("%.2f km  |  %s".format(totalKm, formatTime(elapsedSeconds)))
        }

        lastLocation = newLocation
    }

    // ─────────────────────────────────────────────────────────
    //  Haversine formula
    //  Calculates the great-circle distance between two GPS
    //  coordinates in metres.
    // ─────────────────────────────────────────────────────────

    private fun haversineMetres(lat1: Double, lon1: Double,
                                lat2: Double, lon2: Double): Double {
        val R = 6_371_000.0 // Earth's radius in metres
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return R * c
    }

    // ─────────────────────────────────────────────────────────
    //  Text-to-Speech
    // ─────────────────────────────────────────────────────────

    private fun initTts() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts.language = Locale.getDefault()
            }
        }
    }

    private fun speakKmAlert(km: Int) {
        val message = "You completed $km kilometer${if (km > 1) "s" else ""}"
        // QUEUE_FLUSH replaces any queued speech; QUEUE_ADD appends
        tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, "km_alert_$km")
    }

    // ─────────────────────────────────────────────────────────
    //  Foreground notification
    // ─────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "KM Tracker",
                NotificationManager.IMPORTANCE_LOW  // LOW = no sound, still visible
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        // Tap the notification → open MainActivity
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("KM Tracker Pro")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pendingIntent)
            .setOngoing(true)   // user cannot swipe it away
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    // ─────────────────────────────────────────────────────────
    //  Helpers
    // ─────────────────────────────────────────────────────────

    private fun formatTime(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s)
        else             "%02d:%02d".format(m, s)
    }
}
