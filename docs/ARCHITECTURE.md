## Architecture Overview

This document describes the overall architecture of the Telemetry Android app as implemented in Steps 1–3.

### High-level Design

- **Language**: Kotlin
- **Min SDK**: 26
- **Architecture**: Clean + MVVM
- **Async**: Kotlin Coroutines + Flow
- **Persistence**: Room (local source of truth)
- **Background work**: WorkManager
- **Remote sync**: Firebase Cloud Firestore (with anonymous Firebase Auth)
- **Bluetooth**: Android Bluetooth Classic (RFCOMM) OBD2 dongle

### Module Structure

`app/`

- `ui/`
  - `MainActivity.kt` – Simple UI for connecting to an OBD2 device, displaying current sensor values, pending record count, and sync state.
- `viewmodel/`
  - `ObdViewModel.kt` – Orchestrates OBD polling, repository interaction, and exposes UI-facing state.
  - `ConnectionState.kt` – Sealed class for connection status.
  - `SyncState.kt` – Enum representing manual sync UI state.
- `model/`
  - `VehicleData.kt` – In-memory representation of a snapshot of sensor data from the vehicle.
- `obd/`
  - `ObdConnectionManager.kt` – Manages Bluetooth RFCOMM connection and streams.
  - `ObdCommandExecutor.kt` – Sends OBD/AT commands and reads raw responses.
  - `ObdResponseParser.kt` – Parses OBD hex responses into sensor values.
- `domain/`
  - `SyncStatus.kt` – Enum for local sync state (PENDING, SYNCING, SYNCED, FAILED).
  - `TelemetryRecord.kt` – Normalized domain model for telemetry events.
  - `TelemetryBatch.kt` – Domain model representing a batch of records for upload.
- `data/local/`
  - `TelemetryEntity.kt` – Room entity for telemetry records with indices on `timestamp` and `syncStatus`.
  - `TelemetryDao.kt` – DAO for CRUD operations and sync-related queries/updates.
  - `AppDatabase.kt` – Room `RoomDatabase` implementation.
- `data/repository/`
  - `TelemetryRepository.kt` – Repository that:
    - Saves `VehicleData` to Room as `TelemetryRecord`.
    - Provides flows for latest telemetry and pending count.
    - Produces pending `TelemetryBatch`es and marks them SYNCING/SYNCED/FAILED.
- `service/`
  - `ObdPollingService.kt` – Coroutine-based OBD polling service that runs independently of the UI.
- `firebase/`
  - `FirebaseSessionManager.kt` – Handles anonymous Firebase Auth sign-in.
  - `FirebaseTelemetryRemoteDataSource.kt` – Uploads telemetry batches to Firestore with batch writes.
- `worker/`
  - `TelemetrySyncWorker.kt` – WorkManager worker that syncs pending telemetry to Firestore.
- `sync/`
  - `TelemetrySyncScheduler.kt` – Schedules one-time and periodic sync work.
- `ObdTelemetryApplication.kt` – Custom `Application` that initializes Firebase and schedules periodic sync.

Resource and configuration files:

- `AndroidManifest.xml` – Declares `ObdTelemetryApplication`, Bluetooth permissions, and `MainActivity`.
- `res/layout/activity_main.xml` – Simple linear layout with connect button, sensor values, pending count, sync button, and sync state.
- `build.gradle.kts` – App-level Gradle configuration including Room, WorkManager, and Firebase dependencies.
- `gradle/libs.versions.toml` – Centralized dependency versions and aliases.

## Data Flow

### OBD Data Capture (Step 1)

1. `MainActivity` lists **paired Bluetooth devices** using `BluetoothAdapter.bondedDevices` and allows the user to select an OBD2 dongle.
2. `ObdViewModel.connectToDevice(device)` delegates to `ObdPollingService`:
   - `ObdConnectionManager` connects via RFCOMM using the SPP UUID `00001101-0000-1000-8000-00805F9B34FB`.
   - `ObdCommandExecutor.initializeObd()` sends the standard ELM327 init sequence:
     - `ATZ` (reset)
     - `ATE0` (echo off)
     - `ATL0` (linefeeds off)
     - `ATS0` (spaces off)
     - `ATH0` (headers off)
     - `ATSP0` (auto protocol)
3. `ObdPollingService` runs a loop (every 1 second while connected):
   - Sends PIDs:
     - `010C` – Engine RPM
     - `010D` – Vehicle speed
     - `0105` – Engine coolant temperature
   - `ObdResponseParser` converts raw hex responses into values:
     - RPM: `((A * 256) + B) / 4`
     - Speed: `A` (km/h)
     - Coolant temp: `A - 40` (°C)
   - Builds `VehicleData(rpm, speed, coolantTemp, timestamp)` and passes it to `TelemetryRepository.saveReading`.
4. `MainActivity` observes:
   - `ObdViewModel.connectionState` – shows connection status.
   - `ObdViewModel.latestTelemetry` – displays RPM, speed, and coolant temperature.

### Local Persistence & Telemetry Model (Step 2)

- **Domain model**:
  - `TelemetryRecord` includes:
    - `id: String` (UUID)
    - `timestamp: Long` (sensor read time)
    - `rpm: Int?`
    - `speed: Int?`
    - `coolantTemp: Int?`
    - `latitude: Double?`
    - `longitude: Double?`
    - `ignitionOn: Boolean?`
    - `vehicleId: String?`
    - `source: String = "obd_android_app"`
    - `syncStatus: SyncStatus`
    - `createdAt: Long`
    - `updatedAt: Long`

- **Room entity (`TelemetryEntity`)**:
  - Mirrors `TelemetryRecord`, with:
    - `@PrimaryKey val id: String`
    - Indices on `timestamp` and `syncStatus`.
  - Conversion helpers:
    - `toDomain(): TelemetryRecord`
    - `TelemetryEntity.fromDomain(record: TelemetryRecord)`

- **DAO (`TelemetryDao`)** supports:
  - Insert:
    - `insert(record: TelemetryEntity)`
    - `insertAll(records: List<TelemetryEntity>)`
  - Queries:
    - `getLatestRecord()`
    - `getPendingRecords(limit: Int)` – oldest first, `syncStatus IN ('PENDING', 'FAILED')`.
    - `observeLatestTelemetry()` – Flow of latest record.
    - `observePendingCount()` – Flow of count of PENDING + FAILED records.
    - `deleteSyncedOlderThan(timestamp: Long)` – optional cleanup.
  - Sync status updates:
    - `updateSyncStatus(ids: List<String>, status: String, updatedAt: Long)`
    - `markRecordsSyncing(ids, updatedAt)`
    - `markRecordsSynced(ids, updatedAt)`
    - `markRecordsFailed(ids, updatedAt)`
  - Stuck SYNCING recovery:
    - `resetStuckSyncing(now: Long, staleBefore: Long)` – sets stale SYNCING records back to FAILED.

- **Repository (`TelemetryRepository`)**:
  - `saveReading(vehicleData: VehicleData)`:
    - Creates a new `TelemetryRecord` with:
      - `id = UUID.randomUUID().toString()`
      - `timestamp = vehicleData.timestamp`
      - `rpm/speed/coolantTemp` from `VehicleData`
      - Remaining fields (GPS/ignition/vehicleId) currently null
      - `source = "obd_android_app"`
      - `syncStatus = SyncStatus.PENDING`
      - `createdAt` / `updatedAt = System.currentTimeMillis()`
    - Persists as `TelemetryEntity`.
  - `observeLatestTelemetry(): Flow<TelemetryRecord?>`
  - `observePendingCount(): Flow<Int>`
  - `getPendingBatch(limit: Int = 100): TelemetryBatch?`:
    - First calls `resetStuckSyncing` to recover stale SYNCING records (5-minute timeout).
    - Fetches up to 100 PENDING or FAILED records ordered by oldest timestamp.
    - Wraps them in a `TelemetryBatch(batchId, records, createdAt)`.
  - `markBatchSyncing(batch)` / `markBatchSynced(batch)` / `markBatchFailed(batch)`:
    - Update `syncStatus` to SYNCING/SYNCED/FAILED.
    - Update `updatedAt` to current time.

### OBD Polling Service (Step 2)

- `ObdPollingService`:
  - Holds `connectionState: StateFlow<ConnectionState>`.
  - `connectAndStart(device: BluetoothDevice)`:
    - Connects via `ObdConnectionManager`.
    - Runs OBD init sequence.
    - Enters polling loop in a coroutine (IO dispatcher).
  - Each loop iteration:
    - Sends PIDs `010C`, `010D`, `0105`.
    - Parses values, builds `VehicleData`.
    - Calls `TelemetryRepository.saveReading(vehicleData)`.
  - On any error:
    - Logs the error.
    - Sets `ConnectionState.Error`.
    - Disconnects and ends the loop.
  - `stop()`:
    - Cancels the polling job.
    - Disconnects Bluetooth.
    - Sets `ConnectionState.Disconnected`.

### ViewModel & UI (Step 2)

- `ObdViewModel`:
  - Depends on `ObdPollingService` and `ITelemetryRepository`.
  - Exposes:
    - `connectionState: StateFlow<ConnectionState>` – from `ObdPollingService`.
    - `latestTelemetry: StateFlow<TelemetryRecord?>` – via repository + `stateIn`.
    - `pendingRecordCount: StateFlow<Int>` – via repository + `stateIn`.
  - Methods:
    - `connectToDevice(device: BluetoothDevice)` → starts polling service.
    - `disconnect()` → stops polling service.

- `MainActivity`:
  - Uses an inline `ViewModelProvider.Factory` to build:
    - Room `AppDatabase` and `TelemetryRepository`.
    - `ObdConnectionManager`, `ObdCommandExecutor`, `ObdResponseParser`.
    - `ObdPollingService` using `lifecycleScope`.
    - `ObdViewModel`.
  - UI:
    - Connect button – triggers Bluetooth device picker and connection.
    - Text views for connection status, RPM, speed, coolant temperature.
    - Text view showing pending telemetry record count.

### Remote Sync & Firebase (Step 3)

#### Firebase & Auth

- **FirebaseSessionManager**:
  - `ensureSignedIn(): FirebaseUser?`:
    - If already signed-in, returns current user.
    - Otherwise, signs in anonymously via `auth.signInAnonymously().await()`.
    - Logs and returns null on failure.
  - `currentUserId: String?`:
    - Returns the current Firebase user UID, or null.

- **Firebase initialization**:
  - `ObdTelemetryApplication`:
    - Calls `FirebaseApp.initializeApp(this)` in `onCreate()` with error logging.
    - Schedules periodic sync via `TelemetrySyncScheduler.enqueuePeriodicSync(this)`.
  - Manifest:
    - Sets `android:name=".ObdTelemetryApplication"` on the `<application>` tag.
  - **Note**: You must add `google-services.json` and apply the Google Services Gradle plugin when wiring up the Firebase project.

#### Firestore Remote Data Source

- **Schema**:
  - Collection path: `telemetry_vehicles/{vehicleId}/records/{recordId}`
    - `vehicleId` fallback: `"unknown_vehicle"` when `TelemetryRecord.vehicleId` is null.

- **FirebaseTelemetryRemoteDataSource**:
  - Depends on `FirebaseFirestore` and `FirebaseSessionManager`.
  - `uploadBatch(batch: TelemetryBatch): Boolean`:
    - Ensures anonymous auth via `sessionManager.ensureSignedIn()`.
    - Builds a Firestore `WriteBatch`:
      - For each `TelemetryRecord`:
        - Computes `vehicleId = record.vehicleId ?: "unknown_vehicle"`.
        - Targets `telemetry_vehicles/{vehicleId}/records/{record.id}`.
        - Writes a document with fields:
          - `id`, `timestamp`, `rpm`, `speed`, `coolantTemp`
          - `latitude`, `longitude`, `ignitionOn`
          - `vehicleId`, `source`
          - `syncStatus` (string)
          - `createdAt`, `updatedAt`
          - `uploadedAt` (set at upload time)
          - `uploaderUserId` (Firebase UID)
    - Commits the batch via `writeBatch.commit().await()`.
    - Returns:
      - `true` if commit succeeds.
      - `false` if commit throws, logging the error.

Batch writes are atomic from the app’s perspective: either all documents in the batch are written or none, and only on success are local records marked SYNCED.

#### WorkManager-based Sync

- **TelemetrySyncWorker** (WorkManager `CoroutineWorker`):
  - Builds dependencies on each run:
    - Room `AppDatabase` + `TelemetryRepository`.
    - `FirebaseSessionManager`.
    - `FirebaseTelemetryRemoteDataSource`.
  - `doWork()`:
    1. Logs start with `id` and `runAttemptCount`.
    2. Attempts to load a pending batch via `repository.getPendingBatch(limit = 100)`:
       - If loading fails: logs and returns `Result.failure()`.
       - If no batch: logs "No pending telemetry records to sync" and returns `Result.success()`.
    3. If a batch is present:
       - Logs batch id and record count.
       - Calls `repository.markBatchSyncing(batch)` to move records to SYNCING and update `updatedAt`.
       - Calls `remoteDataSource.uploadBatch(batch)`:
         - On `true`:
           - `repository.markBatchSynced(batch)`.
           - Logs success and returns `Result.success()`.
         - On `false`:
           - `repository.markBatchFailed(batch)`.
           - Logs warning and returns `Result.retry()` to let WorkManager back off and reattempt.
    4. On exception:
       - Logs the exception with batch id.
       - Classifies error as transient if:
         - `e is FirebaseNetworkException`, or
         - `e is FirebaseFirestoreException` with `code == UNAVAILABLE`.
       - Attempts to `repository.markBatchFailed(batch)` to keep local state consistent.
       - Returns:
         - `Result.retry()` for transient errors.
         - `Result.failure()` otherwise.

This ensures:

- Records are not lost even if the app or worker crashes mid-sync:
  - Batch is only marked SYNCED after Firestore commit succeeds.
  - SYNCING records that get stuck are later recovered via `resetStuckSyncing`.
- FAILED records remain eligible for future batches because `getPendingRecords` includes both PENDING and FAILED.

#### Sync Scheduling & Orchestration

- **TelemetrySyncScheduler**:
  - Default WorkManager constraints:
    - Network: `NetworkType.CONNECTED`.
    - Battery: `requiresBatteryNotLow = true`.
  - **One-time sync**:
    - `enqueueOneTimeSync(context)`:
      - Uses `WorkManager.getWorkInfosForUniqueWork(UNIQUE_ONE_TIME_WORK_NAME).get()` to detect if a one-time sync is already ENQUEUED or RUNNING.
      - If one is active, it returns early to avoid racing multiple workers over the same pending batch.
      - Otherwise, enqueues a unique work request with policy `ExistingWorkPolicy.KEEP`.
  - **Periodic sync**:
    - `enqueuePeriodicSync(context)`:
      - Schedules `TelemetrySyncWorker` every 15 minutes (`PeriodicWorkRequestBuilder`).
      - Uses `enqueueUniquePeriodicWork` with `ExistingPeriodicWorkPolicy.KEEP` to avoid duplicate periodic jobs.

This coordination minimizes races between manual and periodic sync by:

- Ensuring at most one one-time sync work is active at a time.
- Relying on the repository’s selection rules (PENDING/FAILED only) and status transitions to keep batch boundaries clear.

### ViewModel & UI Sync Integration (Step 3)

- **SyncState ViewModel state**:
  - `SyncState` enum: `IDLE`, `SYNCING`, `SUCCESS`, `FAILED`.
  - `ObdViewModel`:
    - Maintains `syncState: StateFlow<SyncState>` (backed by a `MutableStateFlow`).
    - `syncNow(appContext: Context)`:
      - Sets `syncState` to `SYNCING`.
      - Calls `TelemetrySyncScheduler.enqueueOneTimeSync(appContext)`.
      - Optimistically sets `syncState` to `SUCCESS` (in a production app you might observe WorkManager for more accurate UI feedback).

- **MainActivity**:
  - Layout additions:
    - `TextView` `txtPendingCount` – shows "Pending records: X".
    - `Button` `btnSyncNow` – triggers manual sync.
    - `TextView` `txtSyncState` – shows "Sync state: Idle/Syncing/Success/Failed".
  - Behavior:
    - `btnSyncNow.setOnClickListener { viewModel.syncNow(applicationContext) }`.
    - Collects `viewModel.pendingRecordCount` and updates `txtPendingCount`.
    - Collects `viewModel.syncState` and updates `txtSyncState` with a simple formatted string.

## Correctness & Robustness Summary

- **No data loss on crash**:
  - Records move through `PENDING → SYNCING → SYNCED` only when remote success is confirmed.
  - If a crash occurs mid-sync:
    - Records remain as SYNCING.
    - Later, `TelemetryRepository.getPendingBatch` calls `resetStuckSyncing` to revert stale SYNCING records to FAILED, making them eligible for future batches.

- **FAILED records retried**:
  - DAO’s `getPendingRecords` filters on `syncStatus IN ('PENDING', 'FAILED')`.
  - Repository’s `getPendingBatch` uses this query, so FAILED records are repeatedly included until a future sync succeeds.

- **SYNCING records do not remain stuck**:
  - `resetStuckSyncing(now, staleBefore)` in DAO marks SYNCING records whose `updatedAt` is older than a 5-minute timeout as FAILED.
  - This is invoked every time a pending batch is built.

- **Atomic Firestore writes from app perspective**:
  - Firestore `WriteBatch` is used to commit the entire batch; local status is only updated to SYNCED after batch commit succeeds.
  - On failure or exception, records are marked FAILED and later retried.

- **Manual vs periodic sync races**:
  - `TelemetrySyncScheduler.enqueueOneTimeSync` checks existing WorkManager work state and avoids starting a new one-time sync if one is already ENQUEUED or RUNNING.
  - Unique work names and `ExistingWorkPolicy.KEEP` reduce duplication.
  - Repository selection logic and status transitions prevent re-processing already SYNCING/SYNCED records.

- **Logging & error handling**:
  - All critical operations (batch load, mark SYNCING/SYNCED/FAILED, upload, exceptions) log with clear tags:
    - `TelemetryRepository`, `TelemetrySyncWorker`, `FirebaseTelemetryRemote`, `FirebaseSessionManager`, `ObdConnectionManager`, etc.
  - Worker classifies errors as transient vs fatal and chooses `Result.retry()` vs `Result.failure()` accordingly.
  - The app does not crash due to sync errors; failures are logged and retried later per WorkManager’s policy.  

For further details on any specific layer (OBD, Room, Firebase, WorkManager), see the respective module files referenced above.

