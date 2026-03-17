## Local Storage & Telemetry Model

This document describes how telemetry data is normalized and stored locally using Room, and how it is prepared for synchronization.

## Domain Model

### TelemetryRecord

Located in `domain/TelemetryRecord.kt`, this is the normalized domain model for a telemetry event:

- `id: String`
  - UUID string used as a stable identifier across local DB and Firestore.
- `timestamp: Long`
  - Epoch millis when the reading was taken from the OBD layer.
- `rpm: Int?`
- `speed: Int?`
- `coolantTemp: Int?`
- `latitude: Double? = null`
- `longitude: Double? = null`
- `ignitionOn: Boolean? = null`
- `vehicleId: String? = null`
- `source: String = "obd_android_app"`
  - Identifies the source system for cross-platform telemetry analysis.
- `syncStatus: SyncStatus`
  - Local sync state (`PENDING`, `SYNCING`, `SYNCED`, `FAILED`).
- `createdAt: Long`
  - Epoch millis when the record was first created locally.
- `updatedAt: Long`
  - Epoch millis when the record was last updated locally (e.g., sync state change).

### SyncStatus

Defined in `domain/SyncStatus.kt`:

- `PENDING` – Newly created record, not yet uploaded.
- `SYNCING` – Currently being uploaded.
- `SYNCED` – Successfully uploaded.
- `FAILED` – Upload attempt failed; record is eligible for retry.

## Room Entity & DAO

### TelemetryEntity

Defined in `data/local/TelemetryEntity.kt`:

- Room entity mapping:
  - Table name: `telemetry`
  - Primary key: `id: String`
  - Indexed columns:
    - `timestamp`
    - `syncStatus`
- Fields:
  - Mirrors `TelemetryRecord` but stores `syncStatus` as a `String`.
- Converters:
  - `fun toDomain(): TelemetryRecord`
    - Converts `syncStatus` back to `SyncStatus` via `SyncStatus.valueOf(syncStatus)`.
  - `companion object fun fromDomain(record: TelemetryRecord): TelemetryEntity`
    - Serializes `SyncStatus` as `record.syncStatus.name`.

### TelemetryDao

Defined in `data/local/TelemetryDao.kt`. Responsibilities:

- **Insert operations**:
  - `suspend fun insert(record: TelemetryEntity)`
  - `suspend fun insertAll(records: List<TelemetryEntity>)`

- **Read operations**:
  - `suspend fun getLatestRecord(): TelemetryEntity?`
    - Latest record by `timestamp` (descending).
  - `suspend fun getPendingRecords(limit: Int): List<TelemetryEntity>`
    - Oldest records first (`ORDER BY timestamp ASC`).
    - Includes only `syncStatus IN ('PENDING', 'FAILED')`.
    - Limit 100 (or provided limit).
  - `fun observeLatestTelemetry(): Flow<TelemetryEntity?>`
  - `fun observePendingCount(): Flow<Int>`
    - Count of records where `syncStatus` is `PENDING` or `FAILED`.

- **Sync status updates**:
  - Underlying method:
    - `suspend fun updateSyncStatus(ids: List<String>, status: String, updatedAt: Long)`
  - Convenience wrappers:
    - `suspend fun markRecordsSyncing(ids: List<String>, updatedAt: Long)`
    - `suspend fun markRecordsSynced(ids: List<String>, updatedAt: Long)`
    - `suspend fun markRecordsFailed(ids: List<String>, updatedAt: Long)`
  - All update `updatedAt` to the provided timestamp.

- **Cleanup**:
  - `suspend fun deleteSyncedOlderThan(timestamp: Long)`
    - Optional maintenance API to remove very old, already-synced data if desired.

- **Recovery of stuck SYNCING records**:
  - `suspend fun resetStuckSyncing(now: Long, staleBefore: Long)`
    - Sets `syncStatus = 'FAILED'` and `updatedAt = now` for records that:
      - Currently have `syncStatus = 'SYNCING'`, and
      - `updatedAt < staleBefore`.
    - This is used to recover from crashes or unexpected terminations during sync so these records can be retried.

## Repository

### ITelemetryRepository

Defined in `data/repository/TelemetryRepository.kt` (interface):

- `suspend fun saveReading(vehicleData: VehicleData)`
- `fun observeLatestTelemetry(): Flow<TelemetryRecord?>`
- `fun observePendingCount(): Flow<Int>`
- `suspend fun getPendingBatch(limit: Int = 100): TelemetryBatch?`
- `suspend fun markBatchSyncing(batch: TelemetryBatch)`
- `suspend fun markBatchSynced(batch: TelemetryBatch)`
- `suspend fun markBatchFailed(batch: TelemetryBatch)`

### TelemetryRepository Implementation

Constructor:

- `TelemetryRepository(private val dao: TelemetryDao)`

Additional implementation detail:

- `SYNCING_STALE_TIMEOUT_MS = 5 minutes`
  - Used to determine when SYNCING records are considered “stuck”.

#### saveReading(vehicleData: VehicleData)

1. Creates a new `TelemetryRecord`:
   - `id = UUID.randomUUID().toString()`
   - `timestamp = vehicleData.timestamp`
   - `rpm`, `speed`, `coolantTemp` from `VehicleData` (nullable).
   - `latitude`, `longitude`, `ignitionOn`, `vehicleId` initially null.
   - `source = "obd_android_app"`.
   - `syncStatus = SyncStatus.PENDING`.
   - `createdAt = now`, `updatedAt = now`.
2. Converts to `TelemetryEntity` and inserts via DAO.
3. Catches and logs any exceptions without crashing the app.

#### observeLatestTelemetry()

- Maps `dao.observeLatestTelemetry()` from `TelemetryEntity?` to `TelemetryRecord?` using `toDomain()`.
- Used by `ObdViewModel` to feed the UI with the latest reading.

#### observePendingCount()

- Forwards `dao.observePendingCount()` to the UI for pending record count display.

#### getPendingBatch(limit: Int)

1. Calculates `now = System.currentTimeMillis()` and `staleBefore = now - SYNCING_STALE_TIMEOUT_MS`.
2. Calls `dao.resetStuckSyncing(now, staleBefore)`:
   - Ensures any records stuck in SYNCING longer than the timeout are moved to FAILED.
3. Calls `dao.getPendingRecords(limit)`:
   - Oldest first.
   - Only PENDING and FAILED.
4. If the result is empty, returns `null`.
5. Otherwise:
   - Converts all entities to domain records.
   - Wraps them in a `TelemetryBatch` with:
     - `batchId = UUID.randomUUID().toString()`.
     - `records = list`.
     - `createdAt = now`.

This design guarantees:

- FAILED records remain eligible for future retries.
- SYNCING records will not be permanently stuck; they eventually become FAILED and get retried.

#### markBatchSyncing / markBatchSynced / markBatchFailed

Each method:

1. Extracts IDs: `ids = batch.records.map { it.id }`.
2. Obtains a fresh `now = System.currentTimeMillis()`.
3. Calls the appropriate DAO method:
   - `dao.markRecordsSyncing(ids, now)`.
   - `dao.markRecordsSynced(ids, now)`.
   - `dao.markRecordsFailed(ids, now)`.
4. Catches and logs any exceptions so that sync logic does not crash the app.

### Data Normalization Rules

When converting `VehicleData` to `TelemetryRecord`:

- Null sensor values are preserved as null; no imputed or default values are introduced.
- Timestamps:
  - `timestamp` preserves the sensor read time.
  - `createdAt` / `updatedAt` describe local storage lifecycle.
- `syncStatus` is initialized as `PENDING`.
- `source` is set to `"obd_android_app"` for downstream analysis.

## Source of Truth

- The **Room database** is the **source of truth** for telemetry on the device:
  - The UI reads current values and pending counts from Room via repository flows.
  - Remote Firestore is treated as a synchronization target, not the authoritative store.
  - Records are never removed solely due to remote upload; optional cleanup can be implemented via `deleteSyncedOlderThan`.

