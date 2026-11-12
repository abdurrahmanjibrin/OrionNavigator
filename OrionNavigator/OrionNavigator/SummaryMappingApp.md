# Helios — Orion Survey & Mapping Helper

Helios is the companion survey and ground-truth mapping application for the **Orion Indoor Navigation System**. Designed for facility administrators and surveyors, Helios simplifies the process of creating topological indoor maps, capturing pedometer step counts, recording high-precision orientation vectors (Compass and Gyroscope), validating graph connectivity, and visualizing indoor dead-reckoning layouts.

---

## Architecture Overview

```
                      ┌──────────────────────────────────────────┐
                      │              Helios Dashboard            │
                      └─────────────────────┬────────────────────┘
                                            │
         ┌──────────────────┬───────────────┴──────────────┬──────────────────┐
         ▼                  ▼                              ▼                  ▼
┌─────────────────┐ ┌───────────────┐              ┌───────────────┐  ┌───────────────┐
│  Walk & Record  │ │Map Visualizer │              │Graph Inspector│  │Realtime GPS   │
│  Survey Engine  │ │2D Dead Reckon │              │& Calibration  │  │Coordinate Tool│
└────────┬────────┘ └───────┬───────┘              └───────┬───────┘  └───────────────┘
         │                  │                              │
         ▼                  ▼                              ▼
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                 OrientationManager (Dual Sensor Engine: Compass + Gyro)             │
│                 StepManager (Hardware Step Detector + Manual Fallback)              │
│                 MapRepository (JSON Serialization & Dijkstra Shortest Path)         │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

---

## Core Features

### 1. Walk & Record Surveying
- **Dual Sensor Telemetry**: Streams both Compass (magnetometer + accelerometer rotation vector) and Gyroscope heading in real time.
- **Departure Angle Locking**: When tapping **"Start Walking to Next Node"**, the initial departure heading is locked to ensure the recorded corridor direction matches the true line of departure.
- **Arrival Angle Recording**: Captures the arrival heading when the surveyor arrives at the destination node.
- **Sensor Calibration**: Supports zero-point gyroscope calibration and compass-alignment calibration (`alignGyroToCompass()`).
- **Hardware Pedometer Integration**: Uses Android's `TYPE_STEP_DETECTOR` with manual correction buttons (+/-).

### 2. 2D JSON Map Visualizer
- **Dead-Reckoning Layout**: Computes 2D Cartesian coordinates of nodes from polar survey vectors ($\theta_{\text{departure}}$, $\text{steps} \times \text{stepLength}$).
- **Interactive Canvas**: Pan, drag, and pinch-to-zoom gestures.
- **Corridor-Aligned Text**: Edge labels are automatically rotated to lie parallel to corridor lines (normalized within $[-90^\circ, 90^\circ]$ for readability).
- **Directional Color Matching**:
  - **Gold**: Forward edge labels and directional arrows.
  - **Cyan**: Reverse edge labels and directional arrows.
- **Perpendicular Offsets**: Offsets forward and reverse labels to opposite sides of the corridor line to eliminate text overlap.
- **Dynamic Font Scaling**: Floating controls (+ / -) to adjust label sizes between $5\text{sp}$ and $18\text{sp}$.
- **Angle Source Toggle**: Switch between **Departure Angle** (recommended) and **Arrival Angle** dead reckoning to inspect layout effects.

### 3. Realtime GPS Coordinates Utility
- **Snapshot Mode**: Single-tap capture of current GPS latitude, longitude, and accuracy.
- **Continuous Live Mode**: Realtime streaming location updates.
- **Precision Selector**: Configurable decimal precision (2 to 8 decimal places).
- **Clipboard Utility**: One-tap copy of formatted coordinates for documentation.

### 4. Graph Inspector & Route Simulation
- **Graph Inspector**: Audit node definitions, examine incoming/outgoing edges, and calibrate headings by aiming the device down corridors.
- **Dijkstra Route Testing**: Verifies that the exported graph is fully connected and simulates turn-by-turn navigation instructions before deployment to Orion.

---

## Mathematical Models & Coordinate Systems

### 1. 2D Dead-Reckoning Projection
Given an origin node $(x_0, y_0)$, a step count $n$, a calibrated step length $L_{\text{step}}$, and a departure heading $\theta_{\text{departure}}$:

$$\text{Distance } d = n \times L_{\text{step}}$$

$$\Delta x = \sin(\theta_{\text{departure}}) \times d$$

$$\Delta y = -\cos(\theta_{\text{departure}}) \times d$$

$$x_{\text{dest}} = x_0 + \Delta x, \quad y_{\text{dest}} = y_0 + \Delta y$$

### 2. Crossroads & Dynamic Turn Calculation
Corridors maintain a single fixed orientation in the global frame ($0^\circ = \text{Magnetic North}$). At any intersection $X$, Orion calculates the turn angle dynamically:

$$\Delta \theta_{\text{turn}} = \theta_{\text{target\_edge}}(X \to B) - \theta_{\text{current\_heading}}$$

$$\text{Turn Angle} = ((\Delta \theta_{\text{turn}} + 180^\circ) \pmod{360^\circ}) - 180^\circ$$

- $\Delta \theta \approx 0^\circ \implies$ Straight
- $\Delta \theta > 0^\circ \implies$ Turn Right $\Delta \theta^\circ$
- $\Delta \theta < 0^\circ \implies$ Turn Left $|\Delta \theta|^\circ$

---

## JSON Map Format Specification

Helios produces JSON files formatted for the Orion navigation engine. The structure supports both legacy fields (`degree`, `gyro`) and detailed directional fields (`departureDegree`, `arrivalDegree`, etc.):

```json
{
  "buildingName": "Gedung Rektorat Lantai 1",
  "stepLengthCm": 75,
  "nodes": [
    {
      "id": 1,
      "name": "Lobby Utama",
      "isDestination": true
    },
    {
      "id": 2,
      "name": "Pertigaan Koridor",
      "isDestination": false
    }
  ],
  "edges": [
    {
      "from": 1,
      "to": 2,
      "stepCount": 12,
      "departureDegree": 90.0,
      "departureGyro": 90.0,
      "arrivalDegree": 92.0,
      "arrivalGyro": 92.0,
      "degree": 90.0,
      "gyro": 90.0
    },
    {
      "from": 2,
      "to": 1,
      "stepCount": 12,
      "departureDegree": 272.0,
      "departureGyro": 272.0,
      "arrivalDegree": 270.0,
      "arrivalGyro": 270.0,
      "degree": 272.0,
      "gyro": 272.0
    }
  ]
}
```

---

## Version History

| Version | Release File | Highlights |
| :--- | :--- | :--- |
| **v1.0.0** | `Helios.apk` | Initial release. Pedometer step recording, compass heading capture, manual graph editing, Dijkstra test route engine, JSON export/import. |
| **v2.0.0** | `Helios_v2.apk` | Dual orientation system (simultaneous Compass and Gyroscope recording). Added 2D JSON Map Visualizer with pinch/pan navigation. Added Realtime GPS Coordinates tool with selectable decimal precision. |
| **v3.0.0** | `Helios_v3.apk` | Departure direction locking at origin node. Arrival heading recording. Visualizer dead-reckoning using departure vectors. Parallel corridor text rotation, color-coded forward/reverse edge labels, and dynamic label font scaling. |

---

## Project Structure

```
Helios - Orion Helper/
├── app/
│   ├── src/main/java/com/orion/helios/
│   │   ├── MainActivity.kt               # Navigation host & sensor dispatch
│   │   ├── model/
│   │   │   ├── BuildingMap.kt            # Root map container
│   │   │   ├── MapNode.kt                # Node definition
│   │   │   └── MapEdge.kt                # Edge definition with dual bearings
│   │   ├── navigation/
│   │   │   ├── Dijkstra.kt               # Pathfinding algorithm
│   │   │   └── GraphValidator.kt         # Graph connectivity checks
│   │   ├── repository/
│   │   │   └── MapRepository.kt          # JSON parser & persistence
│   │   ├── sensor/
│   │   │   ├── OrientationManager.kt     # Compass & Gyroscope sensor fusion
│   │   │   └── StepManager.kt            # Hardware pedometer listener
│   │   └── ui/
│   │       ├── screens/
│   │       │   ├── DashboardScreen.kt    # Home hub
│   │       │   ├── WalkRecordScreen.kt   # Survey recording interface
│   │       │   ├── VisualizerScreen.kt   # 2D graph visualizer
│   │       │   ├── GpsCoordinateScreen.kt# Realtime GPS utility
│   │       │   ├── GraphInspectorScreen.kt # Topology inspection
│   │       │   └── RouteTestScreen.kt    # Route simulation
│   │       └── theme/                    # Color palette & Material3 theme
│   └── build.gradle                      # Module configuration (SDK 35, Compose)
├── DemoMap.json                          # Sample survey dataset
├── Map.json                              # Default active map
├── Helios_v3.apk                         # Current release binary
└── README.md                             # Documentation
```

---

## Building the APK

To build the debug APK from the command line:

```powershell
Set-Location "Helios - Orion Helper"
.\gradlew.bat assembleDebug
```

The output binary is placed in:
`app/build/outputs/apk/debug/Helios_v3.apk`

