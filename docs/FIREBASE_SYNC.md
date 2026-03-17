## Firebase Sync & Background Upload

This document describes how Firebase Cloud Firestore and WorkManager are used to synchronize locally stored telemetry records.

## Overview

- **Remote store**: Firebase Cloud Firestore.
- **Authentication**: Anonymous Firebase Auth.
- **Sync semantics**:
  - Local Room DB is the source of truth.
  - Records flow through sync states: `PENDING → SYNCING → SYNCED` or `FAILED`.
  - Sync uses batched Firestore writes.
  - WorkManager coordinates background sync with periodic and manual triggers.

## Firestore Schema

### Document Path

Records are stored under the following path:

```text
telemetry_vehicles/{vehicleId}/records/{recordId}
```

- `vehicleId`:
  - `TelemetryRecord.vehicleId` when non-null.
  - `"unknown_vehicle"` when `vehicleId` is null.
- `recordId`:
  - `TelemetryRecord.id` (UUID).

### Document Fields

For each `TelemetryRecord`, Firestore document contains:

- `id: String` – matches `TelemetryRecord.id`.
- `timestamp: Long` – OBD sensor read time.
- `rpm: Int?`
- `speed: Int?`
- `coolantTemp: Int?`
- `latitude: Double?`
- `longitude: Double?`
- `ignitionOn: Boolean?`
- `vehicleId: String` – actual `vehicleId` or `"unknown_vehicle"`.
- `source: String` – `"obd_android_app"`.
- `syncStatus: String` – the local sync status at the time of upload (optional in backend semantics).
- `createdAt: Long` – local record creation time.
- `updatedAt: Long` – local record last update time.
- `uploadedAt: Long` – timestamp of successful upload.
- `uploaderUserId: String` – Firebase Auth UID used to upload the record.

All timestamps are stored as epoch millis (`Long`) for consistency and simplicity.

## FirebaseSessionManager

File: `firebase/FirebaseSessionManager.kt`

- Wraps `FirebaseAuth` and centralizes anonymous sign-in.
- Public API:
  - `suspend fun ensureSignedIn(): FirebaseUser?`
    - Returns the current user if already signed in.
    - Otherwise attempts `signInAnonymously().await()`.
    - Logs and returns null on failure; callers must handle the null case.
  - `val currentUserId: String?`
    - Returns the Firebase UID of the current user, or null if not signed in.

This manager is used by the remote data source and the sync worker to ensure that upload occurs under a valid anonymous user context.

## FirebaseTelemetryRemoteDataSource

File: `firebase/FirebaseTelemetryRemoteDataSource.kt`

### Responsibilities

- Implements communication with Firestore.
- Performs batched writes to upload telemetry records.
- Maps domain `TelemetryRecord` objects to Firestore document data.

### Key Method: uploadBatch

```kotlin
suspend fun uploadBatch(batch: TelemetryBatch): Boolean
```

Behavior:

1. Calls `sessionManager.ensureSignedIn()`:
   - If this returns null, logs an error and returns `false`.
2. Retrieves `uploaderUserId = currentUser.uid`.
3. Captures `uploadedAt = System.currentTimeMillis()`.
4. Builds a Firestore `WriteBatch`:
   - Iterates over `batch.records`.
   - Determines `vehicleId = record.vehicleId ?: "unknown_vehicle"`.
   - Creates document reference:
     - `telemetry_vehicles/{vehicleId}/records/{record.id}`.
   - Calls `writeBatch.set(docRef, mapRecordToDocument(record, uploadedAt, uploaderUserId))`.
5. Commits the batch with `writeBatch.commit().await()`.

Return values:

- Returns `true` if the batch commit succeeds.
- Returns `false` and logs an error if:
  - Authentication fails.
  - Firestore commit throws any exception.

### Atomicity Considerations

- From the app’s perspective, Firestore batch writes are atomic:
  - Either all documents in the batch are written, or none are.
  - Local records are only marked SYNCED after a successful batch commit.
  - On any failure, all records in the batch are marked FAILED and remain eligible for future retries.

## TelemetrySyncWorker (WorkManager)

File: `worker/TelemetrySyncWorker.kt`

### Responsibilities

- Runs in the background (even when the UI is not active).
- Coordinates the end-to-end sync operation:
  - Selects a pending batch from Room.
  - Marks it as SYNCING.
  - Uploads it to Firestore.
  - Marks it as SYNCED or FAILED based on upload outcome.
  - Signals WorkManager about whether to retry later or not.

### Dependency Construction

On each `doWork()` call:

1. Builds Room database:

```kotlin
val db = Room.databaseBuilder(
    applicationContext,
    AppDatabase::class.java,
    "telemetry.db"
).build()
```

2. Creates `TelemetryRepository(dao)`.
3. Instantiates `FirebaseSessionManager`.
4. Instantiates `FirebaseTelemetryRemoteDataSource`:
   - Uses `FirebaseFirestore.getInstance()` and the session manager.

### doWork Logic

1. Logs the start:

```kotlin
Log.d(TAG, "Starting telemetry sync work. id=$id runAttemptCount=$runAttemptCount")
```

2. Attempts to get a pending batch:

```kotlin
val batch = try {
    repository.getPendingBatch(limit = 100)
} catch (e: Exception) {
    Log.e(TAG, "Failed to load pending batch", e)
    return Result.failure()
}
```

3. If `batch == null`:
   - Logs and returns `Result.success()` (nothing to sync).
4. If a batch is present:
   - Logs batch metadata (id and size).
   - Calls `repository.markBatchSyncing(batch)` to move records to SYNCING and update `updatedAt`.
   - Calls `remoteDataSource.uploadBatch(batch)`:
     - On success (`true`):
       - Calls `repository.markBatchSynced(batch)`.
       - Logs and returns `Result.success()`.
     - On failure (`false`):
       - Calls `repository.markBatchFailed(batch)`.
       - Logs and returns `Result.retry()` to allow WorkManager to reattempt later.

5. Exception handling:
   - Catches all exceptions from sync logic.
   - Classifies error as transient if:
     - `e is FirebaseNetworkException`, or
     - `e is FirebaseFirestoreException` with `code == UNAVAILABLE`.
   - Attempts to `repository.markBatchFailed(batch)` to keep local state consistent.
   - Returns:
     - `Result.retry()` if error is transient.
     - `Result.failure()` otherwise.

### Crash Safety & Record Integrity

- **No data loss**:
  - Records are never deleted by the sync worker.
  - `TelemetryRepository` is the only component that updates `syncStatus` and timestamps.
  - SYNCED status is only applied after Firestore commit succeeds.
- **FAILED records**:
  - Remain eligible for retries because DAO uses `syncStatus IN ('PENDING', 'FAILED')` when selecting pending records.
- **SYNCING records**:
  - If the app or worker crashes mid-sync, some records may be left in SYNCING.
  - `TelemetryRepository.getPendingBatch` calls `resetStuckSyncing` before selecting a new batch:
    - Any SYNCING records whose `updatedAt` is older than a 5-minute timeout are set to FAILED and retried.

## TelemetrySyncScheduler

File: `sync/TelemetrySyncScheduler.kt`

### Responsibilities

- Provides a simple API to schedule:
  - One-time sync (e.g., from a "Sync Now" button).
  - Periodic background sync (configured at app startup).

### Constraints

- `defaultConstraints()`:
  - Requires network connectivity (`NetworkType.CONNECTED`).
  - Requires battery not low (`setRequiresBatteryNotLow(true)`).

### One-Time Sync

```kotlin
fun enqueueOneTimeSync(context: Context)
```

Behavior:

1. Uses `WorkManager.getWorkInfosForUniqueWork(UNIQUE_ONE_TIME_WORK_NAME).get()` to inspect existing work with the same unique name.
2. If any existing work is `ENQUEUED` or `RUNNING`, it returns early:
   - Prevents overlapping one-time sync jobs from manual button presses or repeated triggers.
3. Otherwise, builds a `OneTimeWorkRequest` for `TelemetrySyncWorker` with default constraints.
4. Enqueues unique work with:
   - Name: `UNIQUE_ONE_TIME_WORK_NAME`.
   - Policy: `ExistingWorkPolicy.KEEP`.

### Periodic Sync

```kotlin
fun enqueuePeriodicSync(context: Context)
```

Behavior:

1. Builds a `PeriodicWorkRequest` for `TelemetrySyncWorker`:
   - Interval: 15 minutes (minimum allowed).
   - Constraints: default constraints (connected network, battery not low).
2. Enqueues unique periodic work with:
   - Name: `UNIQUE_PERIODIC_WORK_NAME`.
   - Policy: `ExistingPeriodicWorkPolicy.KEEP`.

This setup:

- Ensures only one periodic sync schedule is active.
- Prevents redundant or concurrent workers from overloading the system or contending for the same batch.

## Application Startup

File: `ObdTelemetryApplication.kt`

- Custom `Application` class used to:
  - Initialize Firebase:

    ```kotlin
    try {
        FirebaseApp.initializeApp(this)
    } catch (e: Exception) {
        Log.e(TAG, "Failed to initialize Firebase", e)
    }
    ```

  - Schedule periodic sync:

    ```kotlin
    TelemetrySyncScheduler.enqueuePeriodicSync(this)
    ```

> Note: The Firebase project and `google-services.json` must be correctly configured and the Google Services Gradle plugin applied for Firebase initialization to succeed.

## Manual Sync from the UI

### ViewModel

- `ObdViewModel` exposes:
  - `syncState: StateFlow<SyncState>` – simple UI representation of the last manual sync attempt.
  - `fun syncNow(appContext: Context)` – triggers a one-time sync via `TelemetrySyncScheduler.enqueueOneTimeSync`.
    - Sets `syncState` to `SYNCING`.
    - Calls enqueue method.
    - Optimistically sets `syncState` to `SUCCESS` (future refinement could use WorkManager’s `WorkInfo` to reflect real outcome).

### UI

- `MainActivity`:
  - Adds a "Sync Now" button (`btnSyncNow`).
  - Adds a "Sync state" text view (`txtSyncState`).
  - On button click:

    ```kotlin
    btnSyncNow.setOnClickListener {
        viewModel.syncNow(applicationContext)
    }
    ```

  - Observes `viewModel.syncState` and updates `txtSyncState` accordingly.

## Failure Handling Summary

- **Network unavailable / Firestore unavailable**:
  - Upload returns false or throws `FirebaseNetworkException` / `UNAVAILABLE`.
  - Records are marked FAILED.
  - Worker returns `Result.retry()` to allow incremental backoff and reattempts.

- **Authentication failures**:
  - Anonymous sign-in failure causes `ensureSignedIn()` to return null.
  - Remote data source logs and returns `false`.
  - Worker marks the batch FAILED and retries.

- **Serialization / mapping issues**:
  - These manifest as exceptions during mapping or commit.
  - Worker logs full details, marks batch FAILED, and returns:
    - `Result.failure()` for non-transient errors.

- **Worker or app crash mid-sync**:
  - Records may be left in SYNCING.
  - `resetStuckSyncing` in the repository is used to recover such records by setting them to FAILED after a timeout.
  - Future sync invocations then pick them up as part of a new batch.

This design emphasizes durability and retriability: records are never silently dropped; they remain in the local database with a clear sync state and are retried until successfully uploaded or investigated.

