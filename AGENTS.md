# AGENTS.md

Graduation project (DATN) workspace: a three-part robot. There is **no root manifest, no build config, and no CI**. The three directories are independent projects with their own toolchains. Nothing here is a monorepo — do not add shared config at the root and do not run a root-level `flutter`/`pio`/`pip` command; each must be run from its own subdirectory.

## Layout

| Directory | Stack | Git |
| --- | --- | --- |
| `raspberry_cam_lidar/` | Python 3, Picamera2/IMX500, RPLIDAR, YOLO + YuNet, stdlib HTTP | **only repo with git** (`main`, remote `hoang26724/raspberry_cam_lidar`) |
| `esp32/` | PlatformIO C++, Arduino framework, ESP32 DOIT DEVKIT V1 | not a repo |
| `app_control_robot/` | Flutter/Dart hold-to-move Bluetooth remote (Android only) | not a repo |

`raspberry_cam_lidar` and `esp32` have detailed, verified `AGENTS.md` files of their own. **Read the one for the directory you are editing before touching it** — they carry the pin mappings, model paths, hardware constraints, and command lists. `app_control_robot/AGENTS.md` covers the protocol contract. Do not restate or override their rules here.

## How the parts relate (and where they do not)

- The ESP32 handles motor actuation and is commanded over **Classic Bluetooth (RFCOMM/SPP) by the Flutter app**. `esp32/` runs a Bluetooth peripheral named `ESP32_ROBOT` (PIN `1234`); `app_control_robot/` is the client and opens the socket from a hand-written Kotlin host. Protocol is one ASCII char per command (`F`/`B`/`L`/`R`/`S`) plus `\n`. **There is no WiFi and no TCP in either project any more** — no IP, no port, no shared network. The two sides must be changed together; the details live in `esp32/AGENTS.md` and `app_control_robot/AGENTS.md`.
- The Pi (`raspberry_cam_lidar/`) is **still not connected to anything**. It handles perception and serves its own web view on port `8000`, with no link to the ESP32 and no link to the Flutter app.
- `app_control_robot` is no longer the stock counter demo — it is a working remote with a console/gamepad UI. It lists only devices the phone has already paired, because Android forbids pairing from inside an app.
- No SLAM, no training code, and no dataset tooling is checked in anywhere, despite the plan/report `.docx` files in `raspberry_cam_lidar`.

## Verification reality

- No project in this workspace has CI, a linter, a formatter, or a typecheck gate wired into a single command.
- There is no emulator or simulator path. Camera, LIDAR, motor direction, and motor timing can only be validated on the physical hardware. Do not report a project as "working" from a compile or syntax check alone; say which check you actually ran and what it did not cover.
- Language-specific checks, all run from the subdirectory:
  - `raspberry_cam_lidar`: `python3 -m py_compile pickleball.py optimize_pickleball.py`
  - `esp32`: `pio run`
  - `app_control_robot`: `flutter analyze` and `flutter test`; add `flutter build apk --debug` when `MainActivity.kt` changes, since that is the only check that covers the Kotlin Bluetooth host.

## Commits

Git exists only in `raspberry_cam_lidar`, so `git status` at `D:\DATN` fails. Commit inside that directory, on `main`, and stage only files you intentionally changed.

Tracked in that repo and worth respecting: both model files (`pickleball_Yolo11n.pt` at ~5 MB, `face_detection_yunet_2026may.onnx`) are committed and are required at runtime from the current working directory. Don't relocate, rename, or gitignore them without confirming the script's `PICKLEBALL_MODEL_PATH` / `FACE_MODEL_PATH` still resolve.
