## OBD2 Integration

This document describes how the app connects to the OBD2 Bluetooth dongle and how it reads and parses vehicle data.

### Connection Layer

#### Bluetooth Transport

- **Technology**: Android Bluetooth Classic (not BLE).
- **UUID**: Standard Serial Port Profile (SPP) UUID is used for RFCOMM:
  - `00001101-0000-1000-8000-00805F9B34FB`
- **Class**: `obd/ObdConnectionManager.kt`
  - Responsibilities:
    - Obtain `BluetoothAdapter` from `BluetoothManager`.
    - Create RFCOMM socket via `BluetoothDevice.createRfcommSocketToServiceRecord`.
    - Connect to the selected paired OBD2 dongle.
    - Expose `InputStream` and `OutputStream` for higher-level components.
    - Implement `connect(device)`, `disconnect()`, and `isConnected()` with logging and proper resource cleanup.

### OBD Initialization

#### ELM327 Init Sequence

After a successful Bluetooth connection, the app sends a standard ELM327 initialization sequence via `ObdCommandExecutor.initializeObd()`:

1. `ATZ` – Reset device.
2. `ATE0` – Echo off.
3. `ATL0` – Linefeeds off.
4. `ATS0` – Spaces off.
5. `ATH0` – Headers off.
6. `ATSP0` – Automatic protocol detection.

Each command is written as `<COMMAND>\r` over the RFCOMM output stream.

Responses:

- The executor waits for the `>` prompt in the input stream or times out.
- It logs raw responses and continues even if the responses are not strictly `OK`, to better tolerate device variations.

### OBD Command Execution

#### Class: `obd/ObdCommandExecutor.kt`

- **Responsibilities**:
  - Send OBD/AT commands (e.g., `"010C"`, `"010D"`, `"0105"`, `"ATZ"`, etc.).
  - Clear any buffered data in the input stream before sending.
  - Read responses via a `BufferedReader` until:
    - The `>` prompt is found, or
    - A configurable timeout elapses.
  - Return a cleaned string response for parsing and log raw responses for debugging.

- **Key methods**:
  - `suspend fun initializeObd(): Boolean` – Runs the init sequence.
  - `suspend fun sendCommand(command: String, timeoutMs: Long = 2000L): String` – Sends the given command and returns the raw response string (spaces and line breaks normalized, `>` removed).

### OBD Response Parsing

#### Class: `obd/ObdResponseParser.kt`

The parser converts raw hex responses to numeric values. It supports:

- **Engine RPM (PID 010C)**:
  - Example response: `"41 0C 1A F8"` or `"410C1AF8"`.
  - Bytes:
    - Mode + PID: `41 0C`
    - Data bytes: `1A`, `F8` → `A`, `B`
  - Formula:
    - `RPM = ((A * 256) + B) / 4`
  - Method:
    - `fun parseRpm(response: String): Int?`

- **Vehicle Speed (PID 010D)**:
  - Example response: `"41 0D 3C"` or `"410D3C"`.
  - Data byte: `A`
  - Formula:
    - `Speed (km/h) = A`
  - Method:
    - `fun parseSpeed(response: String): Int?`

- **Engine Coolant Temperature (PID 0105)**:
  - Example response: `"41 05 7B"` or `"41057B"`.
  - Data byte: `A`
  - Formula:
    - `CoolantTemp (°C) = A - 40`
  - Method:
    - `fun parseCoolantTemp(response: String): Int?`

#### Parsing Strategy

- The parser:
  - Normalizes whitespace and removes `\r`, `\n`, and `>` characters.
  - Accepts both space-separated (`"41 0C 1A F8"`) and compact (`"410C1AF8"`) response formats.
  - Splits into hex tokens and:
    - Skips the first two bytes (mode + PID).
    - Converts remaining tokens into integers with `toInt(16)`.
  - Handles parsing failures by:
    - Logging a descriptive error.
    - Returning `null` to indicate an invalid or missing value.

### Vehicle Data Model

#### `model/VehicleData.kt`

Represents a snapshot of parsed OBD sensor readings at a point in time:

- `rpm: Int?`
- `speed: Int?`
- `coolantTemp: Int?`
- `timestamp: Long` – System time when the reading was taken.

Notes:

- All sensor fields are nullable:
  - If a PID is not supported, malformed, or fails to parse, the corresponding field is `null`.
  - This null-aware design is preserved through domain and persistence layers to avoid data loss or false values.

### Behavior on EVs and Unsupported PIDs

- Many EVs do not support traditional combustion-engine PIDs such as RPM (010C) and coolant temperature (0105).
- In such cases:
  - The OBD adapter may return “NO DATA” or non-standard responses.
  - The parser safely returns `null` for unsupported or malformed responses.
  - `VehicleData` and subsequent `TelemetryRecord` instances will have `rpm` / `coolantTemp` set to `null`, while other fields like `speed` may still be present if the PID is supported.

### Error Handling & Logging

- All OBD-related classes log errors and warnings with clear tags:
  - Connection failures, IO errors, timeouts, and parsing failures are recorded in Logcat.
- The polling loop in `ObdPollingService`:
  - Stops gracefully on repeated errors.
  - Marks the connection state as `Error(...)`.
  - Ensures resources are cleaned up via `disconnect()`.

