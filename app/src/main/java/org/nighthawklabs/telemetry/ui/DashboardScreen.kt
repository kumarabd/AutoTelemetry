package org.nighthawklabs.telemetry.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.auth.FirebaseAuth
import org.nighthawklabs.telemetry.domain.ConnectionState
import org.nighthawklabs.telemetry.domain.TelemetryRecord
import org.nighthawklabs.telemetry.viewmodel.ObdViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: ObdViewModel,
    onConnectClick: () -> Unit,
    onTripHistoryClick: () -> Unit,
    onSyncClick: () -> Unit,
    onIceDryRunClick: () -> Unit,
    onEvDryRunClick: () -> Unit,
    onLoginClick: () -> Unit,
    onLogoutClick: () -> Unit
) {
    val connectionState by viewModel.connectionState.collectAsState()
    val telemetry by viewModel.latestTelemetry.collectAsState()
    val pendingCount by viewModel.pendingRecordCount.collectAsState()
    
    val snackbarHostState = remember { SnackbarHostState() }
    
    // Minimal reactive state for auth
    var currentUser by remember { mutableStateOf(FirebaseAuth.getInstance().currentUser) }
    
    // Auth state listener
    DisposableEffect(Unit) {
        val listener = FirebaseAuth.AuthStateListener { auth ->
            currentUser = auth.currentUser
        }
        FirebaseAuth.getInstance().addAuthStateListener(listener)
        onDispose {
            FirebaseAuth.getInstance().removeAuthStateListener(listener)
        }
    }

    // UI Events listener (Toasts/Snackbars)
    LaunchedEffect(Unit) {
        viewModel.uiEvents.collect { event ->
            when (event) {
                is ObdViewModel.UiEvent.Message -> {
                    snackbarHostState.showSnackbar(event.text)
                }
                is ObdViewModel.UiEvent.Error -> {
                    snackbarHostState.showSnackbar(
                        message = event.text,
                        duration = SnackbarDuration.Long,
                        withDismissAction = true
                    )
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AutoTelemetry", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                ),
                actions = {
                    val user = currentUser
                    // Show Google Login if not logged in OR if only logged in anonymously
                    if (user == null || user.isAnonymous) {
                        TextButton(onClick = onLoginClick) {
                            Icon(Icons.AutoMirrored.Filled.Login, contentDescription = "Login")
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Google Login")
                        }
                    }
                    
                    // Show Logout if any session is active
                    if (user != null) {
                        TextButton(onClick = onLogoutClick) {
                            Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = "Logout")
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Logout")
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            StatusCard(connectionState)

            val vehicleType = (connectionState as? ConnectionState.Connected)?.vehicleType

            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.weight(1f)
            ) {
                // Speed is universal to both ICE and EV
                item {
                    TelemetryCard(
                        label = "Speed",
                        value = telemetry?.speed?.toString() ?: "---",
                        unit = "km/h",
                        icon = Icons.AutoMirrored.Filled.DirectionsRun,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }

                when (vehicleType) {
                    ConnectionState.VehicleType.ICE -> {
                        val iceData = telemetry as? TelemetryRecord.Ice
                        item {
                            TelemetryCard(
                                label = "RPM",
                                value = iceData?.rpm?.toString() ?: "---",
                                unit = "RPM",
                                icon = Icons.Default.Speed,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        item {
                            TelemetryCard(
                                label = "Coolant",
                                value = iceData?.coolantTemp?.toString() ?: "---",
                                unit = "°C",
                                icon = Icons.Default.DeviceThermostat,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                        }
                    }
                    ConnectionState.VehicleType.EV -> {
                        val evData = telemetry as? TelemetryRecord.Ev
                        item {
                            TelemetryCard(
                                label = "SoC",
                                value = evData?.soc?.toString() ?: "---",
                                unit = "%",
                                icon = Icons.Default.BatteryChargingFull,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        item {
                            TelemetryCard(
                                label = "Battery Temp",
                                value = evData?.batteryTemp?.toString() ?: "---",
                                unit = "°C",
                                icon = Icons.Default.ElectricCar,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                        }
                    }
                    else -> {
                        // Display nothing or generic placeholder when disconnected
                    }
                }

                item {
                    TelemetryCard(
                        label = "Pending",
                        value = pendingCount.toString(),
                        unit = "Records",
                        icon = Icons.Default.CloudUpload,
                        color = Color.Gray
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onConnectClick,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Bluetooth, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Connect to OBD Device")
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onTripHistoryClick,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.History, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("History")
                    }
                    OutlinedButton(
                        onClick = onSyncClick,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Sync, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Sync Now")
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    TextButton(
                        onClick = onIceDryRunClick,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Simulate ICE", color = MaterialTheme.colorScheme.secondary)
                    }
                    TextButton(
                        onClick = onEvDryRunClick,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Simulate EV", color = MaterialTheme.colorScheme.secondary)
                    }
                }
            }
        }
    }
}

@Composable
fun StatusCard(state: ConnectionState) {
    val (statusText, color) = when (state) {
        is ConnectionState.Disconnected -> "Disconnected" to Color.Gray
        is ConnectionState.Connecting -> "Connecting..." to MaterialTheme.colorScheme.primary
        is ConnectionState.Connected -> "Connected (${state.vehicleType})" to MaterialTheme.colorScheme.secondary
        is ConnectionState.Error -> "Error" to MaterialTheme.colorScheme.error
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .background(color, RoundedCornerShape(6.dp))
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text("System Status", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(statusText, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                    if (state is ConnectionState.Connected) {
                        val icon = when (state.vehicleType) {
                            ConnectionState.VehicleType.ICE -> Icons.Default.DirectionsCar
                            ConnectionState.VehicleType.EV -> Icons.Default.ElectricCar
                            ConnectionState.VehicleType.HYBRID -> Icons.Default.ElectricCar
                            else -> null
                        }
                        if (icon != null) {
                            Spacer(Modifier.width(8.dp))
                            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
            if (state is ConnectionState.Error) {
                Spacer(Modifier.weight(1f))
                Text(state.message, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
fun TelemetryCard(
    label: String,
    value: String,
    unit: String,
    icon: ImageVector,
    color: Color
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier.height(140.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(28.dp))
            Column {
                Text(value, fontSize = 28.sp, fontWeight = FontWeight.Black)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(label, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                    Spacer(Modifier.width(4.dp))
                    Text(unit, fontSize = 10.sp, color = color, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
