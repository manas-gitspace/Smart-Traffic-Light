# Smart Traffic Light Simulation 🚦

A smart, priority-based traffic signal simulation with an intelligent decision engine, glassmorphism UI, and dual-mode operation (Java Desktop + Web Browser).

![Java](https://img.shields.io/badge/Java-23+-orange?logo=openjdk&logoColor=white)
![HTML5](https://img.shields.io/badge/HTML5-Canvas-blue?logo=html5&logoColor=white)
![License](https://img.shields.io/badge/License-MIT-green)

---

## ✨ Features

- **Smart Priority Scoring Engine** — Each road gets a dynamic score:
  ```
  Score = waiting cars + 0.5 × (first car wait time) + 1000 (Ambulance boost)
  ```
  The highest-scoring road receives GREEN next.

- **Realistic 2D Traffic Signals** — Steel mounting poles, safety-yellow contrast backplates, metallic housings, arched visor hoods, and multi-layer radiant glow for active lamps.

- **Glassmorphism UI** — Frosted translucent dark-glass panels, specular highlight borders, glowing lane status indicators, and modern interactive controls.

- **Emergency Vehicle Priority** — Ambulances trigger an immediate +1000 score boost, overriding normal scheduling and forcing a fast GREEN transition.

- **Dual-Mode Operation**:
  - **Java Desktop App** — Full Swing GUI with animated 2D canvas.
  - **Web Browser (HTML5)** — Opens in-browser via an embedded Java web server with real-time bidirectional sync. Also works standalone if opened without Java.

---

## 📸 Screenshots

### Java Desktop Application
> Launch the Java app to see the full glassmorphism desktop simulation with live traffic signals, vehicle queues, priority scoring, and interactive controls.

### Web Browser Simulation
> Click "🌐 Open Web Simulation" in the Java app, or open `Smart Traffic Light.html` directly in any browser for the standalone web version.

---

## 🚀 Getting Started

### Prerequisites

- **Java 17+** (tested with Java 23)
- Any modern web browser (for the HTML simulation)

### Compile & Run

```bash
# Clone the repository
git clone https://github.com/manas-gitspace/Smart-Traffic-Light.git
cd Smart-Traffic-Light

# Compile
javac SmartTrafficLight.java

# Run the simulation
java SmartTrafficLight
```

The Java app will:
1. Open the desktop GUI window.
2. Start an embedded web server on `http://localhost:8080`.
3. You can click **"🌐 Open Web Simulation"** to launch the HTML version in your browser — both stay in sync.

### Standalone Web Mode

You can also open `Smart Traffic Light.html` directly in any browser. It will run fully client-side with its own simulation engine (no Java required).

---

## 🏗️ Project Structure

```
Smart-Traffic-Light/
├── SmartTrafficLight.java       # Java Desktop App (Swing GUI + embedded web server)
├── Smart Traffic Light.html     # Web Browser Simulation (HTML5 Canvas + JS)
├── Smart Traffic Light.md       # Original project brief
├── README.md                    # This file
├── LICENSE                      # MIT License
└── .gitignore                   # Git ignore rules
```

---

## 🧠 How the Decision Algorithm Works

```
┌─────────────────────────────────────────────────┐
│          Smart Traffic Priority Engine           │
├─────────────────────────────────────────────────┤
│                                                  │
│  For each road (N, E, S, W):                     │
│    score = queue_size                             │
│          + 0.5 × first_car_wait_time              │
│          + 1000  (if ambulance present)            │
│                                                  │
│  Road with highest score → gets GREEN next        │
│                                                  │
│  Signal transition: GREEN → YELLOW → RED          │
│  (always goes through YELLOW before switching)    │
│                                                  │
│  Starvation prevention: a road holding GREEN      │
│  for 6+ steps is deprioritized.                  │
│                                                  │
└─────────────────────────────────────────────────┘
```

---

## 🔗 Java ↔ HTML Connection

When both are running together:

| Endpoint         | Method | Description                        |
|------------------|--------|------------------------------------|
| `/`              | GET    | Serves `Smart Traffic Light.html`  |
| `/api/state`     | GET    | Returns full simulation state JSON |
| `/api/action`    | POST   | Sends actions (step, auto, reset, add vehicle) |

The HTML page polls `/api/state` every 400ms. Actions from either the Java GUI or the web browser update **both** interfaces in real time.

---

## 🛠️ Technologies Used

- **Java Swing** — Desktop GUI with custom-painted glassmorphism panels and 2D vehicle/signal rendering.
- **com.sun.net.httpserver** — Zero-dependency embedded HTTP server for Java ↔ HTML bridge.
- **HTML5 Canvas** — Hardware-accelerated 2D rendering of the intersection, vehicles, and traffic signals.
- **Vanilla JavaScript** — Client-side simulation engine and real-time API sync (no frameworks or dependencies).

---

## 📄 License

This project is licensed under the **MIT License** — see the [LICENSE](LICENSE) file for details.

---

## 👤 Author

**Manas Patil**
- GitHub: [@manas-gitspace](https://github.com/manas-gitspace)

---

> Built as a Data Structures mini-project demonstrating **Queue**, **Priority Queue (Max-Heap)**, and **Greedy Scheduling** algorithms in a real-world traffic management simulation.
