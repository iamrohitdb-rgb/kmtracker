# KM Tracker Pro — Setup Instructions

## Project Structure
```
KMTrackerPro/
├── build.gradle                          ← project-level Gradle
├── settings.gradle
└── app/
    ├── build.gradle                      ← app-level Gradle (dependencies)
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/example/kmtrackerpro/
        │   ├── MainActivity.kt           ← main screen, map, buttons
        │   ├── TrackingService.kt        ← background GPS + Haversine
        │   ├── HistoryActivity.kt        ← run history list
        │   └── Database.kt              ← Room entity, DAO, database
        └── res/
            ├── layout/
            │   ├── activity_main.xml
            │   ├── activity_history.xml
            │   └── item_run.xml
            ├── raw/
            │   └── map_style_dark.json   ← dark map theme
            └── values/
                ├── strings.xml
                └── themes.xml
```

---

## Step-by-Step Setup

### Step 1 — Install Android Studio
Download **Android Studio Hedgehog (2023.1.1)** or newer from:
https://developer.android.com/studio

During installation, make sure the following are checked:
- Android SDK
- Android SDK Platform
- Android Virtual Device (emulator)

---

### Step 2 — Get a Google Maps API Key

1. Go to https://console.cloud.google.com
2. Create a new project (e.g. "KMTrackerPro")
3. Navigate to **APIs & Services → Library**
4. Search for and **Enable**:
   - Maps SDK for Android
5. Go to **APIs & Services → Credentials**
6. Click **+ Create Credentials → API Key**
7. Copy the key

8. Open `AndroidManifest.xml` and replace:
   ```xml
   android:value="YOUR_GOOGLE_MAPS_API_KEY"
   ```
   with your actual key:
   ```xml
   android:value="AIzaSy..."
   ```

> ⚠️ Never commit your real API key to a public repository.
> Use `local.properties` + Gradle secrets plugin for production.

---

### Step 3 — Open the Project in Android Studio

1. Launch Android Studio
2. Click **Open** (not "New Project")
3. Navigate to the `KMTrackerPro/` folder and click **OK**
4. Wait for Gradle sync to complete (first time takes 2–5 minutes)

---

### Step 4 — Create a Virtual Device (Emulator)

> Skip this step if you have a physical Android phone.

1. In Android Studio, click **Device Manager** (right sidebar)
2. Click **Create Device**
3. Choose **Pixel 6** → Next
4. Download **API 34 (Android 14)** image → Next → Finish
5. Click the ▶ Play button next to your new device to start it

---

### Step 5 — Enable GPS on Emulator (for testing)

1. Start the emulator
2. In the emulator toolbar, click the **⋮** (three-dot menu) → **Location**
3. Set a latitude/longitude (e.g. 51.5074, -0.1278 for London)
4. Click **Send** — this simulates GPS movement

To simulate a moving GPS route:
- Use the **Routes** tab in the emulator's extended controls
- Import a GPX file or set multiple waypoints

---

### Step 6 — Run the App

1. Select your device/emulator in the toolbar
2. Press **Run ▶** (Shift+F10)
3. The app will install and launch automatically

---

### Step 7 — Grant Permissions on First Launch

When the app opens:
1. Tap **Start** — it will ask for **Location permission**
2. Choose **"While using the app"** or **"Allow all the time"**
3. On Android 10+, you'll be asked for **Background Location** separately
   - Tap **"Allow all the time"** for tracking to work in the background

---

## How to Use the App

| Button  | Action                                      |
|---------|---------------------------------------------|
| Start   | Begins a new run, starts GPS + timer         |
| Pause   | Freezes timer + GPS, retains distance        |
| Resume  | Resumes a paused run                         |
| Stop    | Ends run, saves to history, resets display   |
| History | Opens the saved run list                     |

- The **cyan polyline** on the map shows your path in real time
- **Voice alert** fires every full kilometre ("You completed 1 kilometer")
- **Long-press** a history entry to delete it

---

## Key Technical Decisions

### Haversine Formula (in TrackingService.kt)
Used to calculate the great-circle distance between two GPS coordinates.
More accurate than simple Euclidean distance because it accounts for Earth's curvature.

### GPS Noise Filter
Any GPS reading less than 2 metres from the previous point is ignored.
This prevents stationary GPS drift from adding phantom distance.

### Foreground Service
`TrackingService` runs as a Foreground Service (with a persistent notification).
This tells Android "this process is important — don't kill it."
Without this, tracking would stop when the phone screen turns off.

### Room Database
Room wraps SQLite. The `@Entity`, `@Dao`, and `@Database` annotations
generate all SQL at compile time — no manual SQL strings needed.

### LiveData
`MutableLiveData` in `TrackingService` allows `MainActivity` to observe
data changes reactively. When the service updates a value, the UI
automatically refreshes without polling.

---

## Troubleshooting

| Problem | Solution |
|---------|----------|
| Map is blank | Check your API key in `AndroidManifest.xml` |
| Distance doesn't increase | Check location permissions are "Allow all the time" |
| App crashes on start | Check Logcat for errors; ensure Gradle sync succeeded |
| Voice alert not working | Check that Text-to-Speech engine is installed on device |
| Build fails | Make sure Google Play Services is installed in your SDK |

---

## Dependencies Reference

| Library | Version | Purpose |
|---------|---------|---------|
| Google Maps SDK | 18.2.0 | Map display + polylines |
| FusedLocationProvider | 21.1.0 | Accurate GPS updates |
| Room | 2.6.1 | SQLite database |
| Lifecycle (LiveData) | 2.7.0 | Reactive UI updates |
| Coroutines | 1.7.3 | Background database ops |
| Material Components | 1.11.0 | Dark mode buttons/cards |
