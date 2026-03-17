## End-to-End Workflows

This document summarizes the main workflows in the app: OBD connection and polling, local telemetry storage, and remote synchronization.

## 1. Connect to OBD2 Device

### Trigger

- User taps **"Connect to OBD Device"** button in `MainActivity`.

### Steps

1. **Permission check**:
   - `MainActivity.ensurePermissions()` ensures:
     - On Android 12+:
       - `BLUETOOTH_CONNECT`
       - `BLUETOOTH_SCAN`
     - On pre-Android 12:
       - `BLUETOOTH`
       - `BLUETOOTH_ADMIN`
     - Additionally:
       - `ACCESS_FINE_LOCATION`
   - If any are missing, it requests them using the Activity Result API and aborts the current connect attempt.

2. **Device selection**:
   - `MainActivity.showDevicePicker()`:
     - Fetches `BluetoothAdapter.bondedDevices`.
     - Shows a simple `AlertDialog` listing device names and addresses.
     - On selection, calls `connectToDevice(device)`.

3. **Start polling service**:
   - `connectToDevice(device)` logs and calls `viewModel.connectToDevice(device)`.
   - `ObdViewModel.connectToDevice` delegates to `ObdPollingService.connectAndStart(device)`.

4. **Bluetooth connect + OBD init**:
   - `ObdConnectionManager.connect(device)`:
     - Cancels discovery, opens RFCOMM socket with SPP UUID.
     - Connects and captures input/output streams.
   - `ObdCommandExecutor.initializeObd()` sends init AT commands and waits for responses.
   - `ObdPollingService` sets `ConnectionState.Connected` on success.

5. **UI state updates**:
   - `MainActivity` observes `viewModel.connectionState`:
     - `Disconnected` → shows "Status: Disconnected".
     - `Connecting` → shows "Status: Connecting...".
     - `Connected` → shows "Status: Connected".
     - `Error(message)` → shows "Status: Error - {message}".

## 2. Periodic OBD Polling & Local Storage

### Trigger

- After successful connection and OBD initialization.

### Steps

1. **Polling loop**:
   - `ObdPollingService` launches a coroutine in the provided `externalScope` (from `MainActivity`’s `lifecycleScope`), on the IO dispatcher.
   - While connected:
     - Sends PID `010C` (RPM) and parses via `ObdResponseParser.parseRpm`.
     - Sends PID `010D` (speed) and parses via `parseSpeed`.
     - Sends PID `0105` (coolant temp) and parses via `parseCoolantTemp`.

2. **Value normalization**:
   - Builds `VehicleData`:
     - `rpm`, `speed`, `coolantTemp` as parsed (nullable).
     - `timestamp = System.currentTimeMillis()`.

3. **Repository save**:
   - Calls `TelemetryRepository.saveReading(vehicleData)`:
     - Creates a `TelemetryRecord` with:
       - New UUID id.
       - `timestamp` from the reading.
       - Sensor values (nullable).
       - `syncStatus = PENDING`.
       - `source = "obd_android_app"`.
       - `createdAt` / `updatedAt = now`.
     - Converts to `TelemetryEntity` and inserts into Room.

4. **UI updates**:
   - `TelemetryDao.observeLatestTelemetry()` emits the latest record.
   - `TelemetryRepository.observeLatestTelemetry()` maps to domain.
   - `ObdViewModel.latestTelemetry` is a `StateFlow` wrapping this.
   - `MainActivity` collects `latestTelemetry` and updates:
     - RPM text.
     - Speed text.
     - Coolant temperature text.

5. **Error handling**:
   - Any exception in the polling loop:
     - Is logged with a clear tag.
     - Causes the loop to break.
     - Sets `ConnectionState.Error("Polling error")`.
     - Closes the connection via `ObdConnectionManager.disconnect()`.

## 3. Pending Records & Batch Selection

### Pending Definition

- A record is considered **pending** if:
  - `syncStatus` is `PENDING` (never uploaded), or
  - `syncStatus` is `FAILED` (previous upload attempt failed).

### Batch Selection Rules

- Implemented via `TelemetryRepository.getPendingBatch(limit = 100)`:

1. **Recover stale SYNCING records**:
   - Computes:
     - `now = System.currentTimeMillis()`.
     - `staleBefore = now - SYNCING_STALE_TIMEOUT_MS` (5 minutes).
   - Calls `TelemetryDao.resetStuckSyncing(now, staleBefore)` to convert any stale SYNCING records into FAILED.

2. **Query pending records**:
   - Calls `TelemetryDao.getPendingRecords(limit)`:
     - Filters `syncStatus IN ('PENDING', 'FAILED')`.
     - Ordered by `timestamp ASC` (oldest first).
     - Limited to 100.

3. **Wrap into TelemetryBatch**:
   - If empty, returns `null`.
   - Otherwise, returns:
     - `TelemetryBatch(batchId = UUID.randomUUID(), records = list, createdAt = now)`.

4. **Observing pending count**:
   - `TelemetryDao.observePendingCount()` counts PENDING + FAILED.
   - `TelemetryRepository.observePendingCount()` surfaces this to the UI.
   - `ObdViewModel.pendingRecordCount` is a `StateFlow` of the count.
   - `MainActivity` displays "Pending records: {count}".

## 4. Remote Sync (Periodic)

### Trigger

- `ObdTelemetryApplication` calls `TelemetrySyncScheduler.enqueuePeriodicSync(this)` in `onCreate()`.
- WorkManager ensures that a periodic `TelemetrySyncWorker` runs approximately every 15 minutes, subject to constraints.

### Steps

1. **Constraints**:
   - Network: `NetworkType.CONNECTED`.
   - Battery: not low.

2. **Worker execution**:
   - `TelemetrySyncWorker` is invoked by WorkManager.
   - It:
     - Builds DB, repository, session manager, and remote data source.
     - Attempts to obtain a pending batch from the repository.
     - Marks the batch as SYNCING.
     - Uploads to Firestore using `FirebaseTelemetryRemoteDataSource.uploadBatch`.
     - On success:
       - Marks records as SYNCED.
       - Returns `Result.success()`.
     - On failure:
       - Marks records as FAILED.
       - Returns `Result.retry()` on transient errors or `Result.failure()` on fatal errors.

3. **Record safety**:
   - Records are only marked SYNCED after a successful Firestore batch commit.
   - FAILED records remain in the DB and will be included in future batches.
   - Stale SYNCING records are periodically reverted to FAILED to avoid permanent "stuck" states.

## 5. Manual Sync ("Sync Now" Button)

### Trigger

- User taps **"Sync Now"** button in `MainActivity`.

### Steps

1. **Sync button click**:
   - `btnSyncNow.setOnClickListener { viewModel.syncNow(applicationContext) }`.

2. **ViewModel trigger**:
   - `ObdViewModel.syncNow(context)`:
     - Sets `syncState` to `SyncState.SYNCING`.
     - Calls `TelemetrySyncScheduler.enqueueOneTimeSync(context)`.
     - Optimistically sets `syncState` to `SyncState.SUCCESS` (UI-level signal; real sync result is managed by WorkManager).

3. **Scheduler behavior**:
   - `TelemetrySyncScheduler.enqueueOneTimeSync(context)`:
     - Inspects existing unique work with name `UNIQUE_ONE_TIME_WORK_NAME`.
     - If any work in `ENQUEUED` or `RUNNING` state exists, it returns early (no-op).
     - Otherwise:
       - Enqueues a unique one-time `TelemetrySyncWorker` with constraints.

4. **Worker execution**:
   - Same as periodic sync: runs `TelemetrySyncWorker`, which performs batch selection, upload, and status updates.

5. **UI state**:
   - `MainActivity` observes `viewModel.syncState`:
     - Displays "Sync state: Idle/Syncing/Success/Failed" accordingly.
   - `pendingRecordCount` remains accurate as Room reflects changes to `syncStatus`.

## 6. Firebase Initialization & Auth

### Application Startup

1. **Firebase initialization**:
   - `ObdTelemetryApplication` calls `FirebaseApp.initializeApp(this)` inside a try/catch block.
   - Errors are logged but do not crash the app.

2. **Periodic sync scheduling**:
   - Immediately schedules periodic telemetry sync on app start.

### Authentication Flow

1. **Anonymous Auth**:
   - Before uploads, `FirebaseTelemetryRemoteDataSource.uploadBatch` calls `FirebaseSessionManager.ensureSignedIn()`.
   - If there is no current user:
     - Attempts `signInAnonymously().await()`.
     - Logs and returns null if sign-in fails.
2. **Usage**:
   - If `ensureSignedIn()` returns null:
     - Remote data source returns `false` from `uploadBatch`.
     - Worker marks the batch FAILED and returns `Result.retry()` or `Result.failure()` depending on error type.

## 7. Failure Modes & Recovery

### Network/Firestore Failures

- On upload failure:
  - Affected records are marked FAILED.
  - Worker returns `Result.retry()` on transient errors such as:
    - `FirebaseNetworkException`.
    - `FirebaseFirestoreException` with `UNAVAILABLE`.
  - Returns `Result.failure()` on non-transient errors.

### App/Worker Crash During Sync

- If the app or worker terminates while a batch is in SYNCING:
  - Those records remain in SYNCING state with `updatedAt` from the time of the last state change.
  - On the next sync attempt:
    - `TelemetryRepository.getPendingBatch` calls `resetStuckSyncing` to revert old SYNCING records (older than the 5-minute threshold) back to FAILED.
    - They then become eligible for selection by `getPendingRecords`.

### Duplicate/Concurrent Syncs

- Manual "Sync Now" and periodic sync:
  - Both use unique work names and constraints to prevent duplicated one-time workers.
  - The repository and DAO logic ensure that records already in SYNCING or SYNCED are not re-selected as PENDING.

## 8. Summary

- Telemetry flows from OBD polling → `VehicleData` → `TelemetryRecord` (Room) → `TelemetryBatch` → Firestore.
- Local database remains the canonical source of telemetry on-device.
- Sync is resilient to transient faults, network issues, and process restarts.
- Both periodic and manual sync mechanisms coexist cleanly and safely.

