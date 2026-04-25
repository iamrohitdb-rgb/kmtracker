package com.example.kmtrackerpro

// ─────────────────────────────────────────────────────────────
//  MainActivity.kt
//
//  Responsibilities:
//   • Request runtime permissions (fine + background location)
//   • Display Google Map with live polyline path
//   • Show distance / time / speed cards
//   • Start / Pause / Stop buttons that control TrackingService
//   • Save completed run to Room database
//   • Navigate to HistoryActivity
// ─────────────────────────────────────────────────────────────

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.kmtrackerpro.databinding.ActivityMainBinding
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity(), OnMapReadyCallback {

    // ViewBinding gives us type-safe access to every view in the layout
    private lateinit var binding: ActivityMainBinding

    // Google Map reference
    private var googleMap: GoogleMap? = null

    // The polyline drawn on the map as the user runs
    private var routePolyline: Polyline? = null

    // Database access
    private lateinit var db: AppDatabase

    // Track whether we are currently in a run
    private var isRunning = false
    private var isPaused  = false

    // ─────────────────────────────────────────────────────────
    //  Permission launchers
    //  We use the modern Activity Result API instead of the
    //  deprecated onRequestPermissionsResult.
    // ─────────────────────────────────────────────────────────

    // Step 1: ask for Fine Location
    private val fineLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            // Fine location granted → now ask for Background on Android 10+
            askBackgroundLocationIfNeeded()
        } else {
            Toast.makeText(this,
                "Location permission is required to track runs.", Toast.LENGTH_LONG).show()
        }
    }

    // Step 2: ask for Background Location (Android 10+ only)
    private val backgroundLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(this,
                "Background location denied. Tracking may stop when screen turns off.",
                Toast.LENGTH_LONG).show()
        }
    }

    // ─────────────────────────────────────────────────────────
    //  onCreate
    // ─────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Inflate layout using ViewBinding
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        db = AppDatabase.getDatabase(this)

        // Attach the map fragment and register the callback
        val mapFragment = supportFragmentManager
            .findFragmentById(R.id.mapFragment) as SupportMapFragment
        mapFragment.getMapAsync(this)

        setupButtons()
        observeLiveData()
        checkAndRequestPermissions()
    }

    // ─────────────────────────────────────────────────────────
    //  Button setup
    // ─────────────────────────────────────────────────────────

    private fun setupButtons() {

        // ── START button ──
        binding.btnStart.setOnClickListener {
            if (!hasLocationPermission()) {
                Toast.makeText(this, "Please grant location permission first.", Toast.LENGTH_SHORT).show()
                checkAndRequestPermissions()
                return@setOnClickListener
            }
            isRunning = true
            isPaused  = false
            updateButtonStates()
            sendServiceCommand("START")
        }

        // ── PAUSE / RESUME button ──
        binding.btnPause.setOnClickListener {
            if (!isPaused) {
                // Currently running → pause
                isPaused = true
                binding.btnPause.text = "Resume"
                sendServiceCommand("PAUSE")
            } else {
                // Currently paused → resume
                isPaused = false
                binding.btnPause.text = "Pause"
                sendServiceCommand("RESUME")
            }
        }

        // ── STOP button ──
        binding.btnStop.setOnClickListener {
            isRunning = false
            isPaused  = false
            saveRunToDatabase()
            sendServiceCommand("STOP")
            updateButtonStates()
            resetDisplayValues()
        }

        // ── HISTORY button ──
        binding.btnHistory.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }
    }

    // Sends a command string to TrackingService
    private fun sendServiceCommand(action: String) {
        Intent(this, TrackingService::class.java).also {
            it.action = action
            ContextCompat.startForegroundService(this, it)
        }
    }

    // ─────────────────────────────────────────────────────────
    //  Observe LiveData from TrackingService
    // ─────────────────────────────────────────────────────────

    private fun observeLiveData() {

        // Distance
        TrackingService.distanceLiveData.observe(this) { km ->
            binding.tvDistance.text = "%.2f km".format(km)
        }

        // Time
        TrackingService.timeLiveData.observe(this) { seconds ->
            binding.tvTime.text = formatTime(seconds)
        }

        // Speed
        TrackingService.speedLiveData.observe(this) { kmh ->
            binding.tvSpeed.text = "%.1f km/h".format(kmh)
        }

        // Path → redraw polyline on map
        TrackingService.pathLiveData.observe(this) { locations ->
            if (locations.isNullOrEmpty()) {
                routePolyline?.remove()
                routePolyline = null
                return@observe
            }

            val latLngs = locations.map { LatLng(it.latitude, it.longitude) }

            // If polyline doesn't exist yet, create it
            if (routePolyline == null) {
                routePolyline = googleMap?.addPolyline(
                    PolylineOptions()
                        .addAll(latLngs)
                        .width(12f)
                        .color(0xFF00E5FF.toInt()) // Cyan accent
                        .geodesic(true)
                )
            } else {
                // Otherwise just update the existing polyline's points
                routePolyline?.points = latLngs
            }

            // Pan camera to follow the runner
            val last = latLngs.last()
            googleMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(last, 16f))
        }
    }

    // ─────────────────────────────────────────────────────────
    //  Map ready callback
    // ─────────────────────────────────────────────────────────

    override fun onMapReady(map: GoogleMap) {
        googleMap = map

        // Dark map style to match the app theme
        map.setMapStyle(
            MapStyleOptions.loadRawResourceStyle(this, R.raw.map_style_dark)
        )

        // Show the blue dot for the user's position if permission granted
        if (hasLocationPermission()) {
            try {
                @Suppress("MissingPermission")
                map.isMyLocationEnabled = true
            } catch (e: SecurityException) { /* handled */ }
        }

        map.uiSettings.isZoomControlsEnabled = true
        map.uiSettings.isCompassEnabled      = true
    }

    // ─────────────────────────────────────────────────────────
    //  Save run to database
    // ─────────────────────────────────────────────────────────

    private fun saveRunToDatabase() {
        val distanceKm = TrackingService.distanceLiveData.value ?: 0.0
        val durationSec = TrackingService.timeLiveData.value ?: 0L

        // Don't save trivially short runs (< 10 metres)
        if (distanceKm < 0.01) return

        val avgSpeed = if (durationSec > 0)
            distanceKm / (durationSec / 3600.0) else 0.0

        val dateStr = SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault())
            .format(Date())

        val run = RunEntity(
            date = dateStr,
            distanceKm = distanceKm,
            durationSeconds = durationSec,
            avgSpeedKmh = avgSpeed
        )

        // Room operations must run off the main thread
        lifecycleScope.launch(Dispatchers.IO) {
            db.runDao().insertRun(run)
        }

        Toast.makeText(this, "Run saved!", Toast.LENGTH_SHORT).show()
    }

    // ─────────────────────────────────────────────────────────
    //  UI helpers
    // ─────────────────────────────────────────────────────────

    private fun updateButtonStates() {
        binding.btnStart.isEnabled  = !isRunning
        binding.btnPause.isEnabled  = isRunning
        binding.btnStop.isEnabled   = isRunning
        binding.btnPause.text       = "Pause"
    }

    private fun resetDisplayValues() {
        binding.tvDistance.text = "0.00 km"
        binding.tvTime.text     = "00:00"
        binding.tvSpeed.text    = "0.0 km/h"
        routePolyline?.remove()
        routePolyline = null
    }

    private fun formatTime(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s)
        else             "%02d:%02d".format(m, s)
    }

    // ─────────────────────────────────────────────────────────
    //  Permission helpers
    // ─────────────────────────────────────────────────────────

    private fun hasLocationPermission() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    private fun checkAndRequestPermissions() {
        if (!hasLocationPermission()) {
            fineLocationLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            askBackgroundLocationIfNeeded()
        }
    }

    private fun askBackgroundLocationIfNeeded() {
        // Background location is only a separate permission on Android 10 (API 29)+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.ACCESS_BACKGROUND_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                backgroundLocationLauncher.launch(
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION
                )
            }
        }
    }
}
