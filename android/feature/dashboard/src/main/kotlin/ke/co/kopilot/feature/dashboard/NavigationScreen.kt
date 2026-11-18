package ke.co.kopilot.feature.dashboard

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ke.co.kopilot.core.designsystem.theme.KoPilotClusterTokens
import ke.co.kopilot.feature.navigation.KoPilotNavigationMap

private data class KenyaPlacePreset(
    val name: String,
    val lat: Double,
    val lng: Double,
)

private val KENYA_PLACES = listOf(
    KenyaPlacePreset("Nairobi CBD", -1.286389, 36.817223),
    KenyaPlacePreset("JKIA Airport", -1.319167, 36.927778),
    KenyaPlacePreset("Westlands", -1.2676, 36.8108),
    KenyaPlacePreset("Thika", -1.0332, 37.0693),
    KenyaPlacePreset("Mombasa", -4.043477, 39.668206),
    KenyaPlacePreset("Kisumu", -0.091702, 34.767956),
    KenyaPlacePreset("Nakuru", -0.303099, 36.080026),
    KenyaPlacePreset("Eldoret", 0.514277, 35.269780),
    KenyaPlacePreset("Kiambu", -1.1714, 36.8356),
    KenyaPlacePreset("Machakos", -1.5177, 37.2634),
    KenyaPlacePreset("Naivasha", -0.7172, 36.4310),
    KenyaPlacePreset("Nyeri", -0.4167, 36.9500),
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NavigationScreen(
    onBack: () -> Unit,
    onStartRouteOnDashboard: () -> Unit,
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var query by remember { mutableStateOf("") }
    val fix = state.locationState.currentFix
    val nav = state.navigationState

    val openGoogleMaps: (String, Double, Double) -> Unit = { name, lat, lng ->
        val navUri = Uri.parse("google.navigation:q=$lat,$lng&mode=d")
        val intent = Intent(Intent.ACTION_VIEW, navUri).apply {
            setPackage("com.google.android.apps.maps")
        }
        runCatching { ctx.startActivity(intent) }.onFailure {
            val geoUri = Uri.parse("geo:$lat,$lng?q=$lat,$lng(${Uri.encode(name)})")
            ctx.startActivity(Intent(Intent.ACTION_VIEW, geoUri))
        }
    }

    Scaffold(
        containerColor = KoPilotClusterTokens.Background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = KoPilotClusterTokens.Background,
                    titleContentColor = KoPilotClusterTokens.PrimaryText,
                    navigationIconContentColor = KoPilotClusterTokens.PrimaryText,
                ),
                title = {
                    Column {
                        Text("KoPilot Navigation", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text(
                            text = if (fix != null) {
                                "GPS: ${"%.4f".format(fix.latitude)}, ${"%.4f".format(fix.longitude)}"
                            } else {
                                "Acquiring current GPS location..."
                            },
                            fontSize = 11.sp,
                            color = KoPilotClusterTokens.SecondaryText,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // Live Map showing current location & active route
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.95f)
                    .heightIn(min = 210.dp),
            ) {
                KoPilotNavigationMap(
                    currentLat = fix?.latitude,
                    currentLng = fix?.longitude,
                    routePoints = nav.decodedPoints,
                    destinationLatLng = nav.destinationLatLng,
                    destinationName = nav.destinationName,
                    hasLocationPermission = state.locationState.permissionGranted,
                )
                Surface(
                    shape = RoundedCornerShape(50),
                    color = Color(0xCC071019),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(10.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.MyLocation,
                            contentDescription = null,
                            tint = KoPilotClusterTokens.PrimaryAccent,
                            modifier = Modifier.size(15.dp),
                        )
                        Text(
                            text = nav.destinationName?.let { "To: $it" } ?: "Current Location",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            // Search, Route Summary & Places List
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1.05f)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("Search place, town, or road") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        onClick = { viewModel.searchDestinations(query) },
                        modifier = Modifier.height(54.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = KoPilotClusterTokens.PrimaryAccent,
                            contentColor = KoPilotClusterTokens.Background,
                        ),
                    ) {
                        Icon(Icons.Default.Search, contentDescription = "Search")
                    }
                }

                val activeRoute = nav.activeRoute
                val destLatLng = nav.destinationLatLng
                if (activeRoute != null && destLatLng != null) {
                    val mins = (activeRoute.durationSeconds / 60).coerceAtLeast(1)
                    val km = "%.1f".format(activeRoute.distanceMeters / 1000.0)
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = KoPilotClusterTokens.CardSurface,
                        border = BorderStroke(1.dp, KoPilotClusterTokens.PrimaryAccent.copy(alpha = 0.6f)),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = "Route to ${nav.destinationName ?: "Destination"}",
                                color = KoPilotClusterTokens.PrimaryText,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                            )
                            Text(
                                text = "$mins min • $km km via ${activeRoute.summary}",
                                color = KoPilotClusterTokens.PrimaryAccent,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Button(
                                    onClick = onStartRouteOnDashboard,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = KoPilotClusterTokens.PrimaryAccent,
                                        contentColor = KoPilotClusterTokens.Background,
                                    ),
                                ) {
                                    Icon(Icons.Default.Navigation, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Drive with Route")
                                }
                                OutlinedButton(
                                    onClick = {
                                        openGoogleMaps(
                                            nav.destinationName ?: "Destination",
                                            destLatLng.latitude,
                                            destLatLng.longitude,
                                        )
                                    },
                                ) {
                                    Text("Open Google Maps")
                                }
                            }
                        }
                    }
                }

                if (nav.searchResults.isNotEmpty()) {
                    Text(
                        text = "Search Results (${nav.searchResults.size})",
                        color = KoPilotClusterTokens.PrimaryText,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                    )
                    nav.searchResults.forEach { place ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = KoPilotClusterTokens.CardSurface,
                            border = BorderStroke(1.dp, KoPilotClusterTokens.Border),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.navigateToCoordinates(place.name, place.latitude, place.longitude) },
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(place.name, color = KoPilotClusterTokens.PrimaryText, fontWeight = FontWeight.SemiBold)
                                    Text(place.formattedAddress, color = KoPilotClusterTokens.SecondaryText, fontSize = 12.sp)
                                }
                                Text(
                                    text = "Route",
                                    color = KoPilotClusterTokens.PrimaryAccent,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                )
                            }
                        }
                    }
                }

                Text(
                    text = "Places in Kenya",
                    color = KoPilotClusterTokens.PrimaryText,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    KENYA_PLACES.forEach { preset ->
                        FilterChip(
                            selected = nav.destinationName == preset.name,
                            onClick = { viewModel.navigateToCoordinates(preset.name, preset.lat, preset.lng) },
                            label = { Text(preset.name) },
                        )
                    }
                }
            }
        }
    }
}
