package org.ethereumphone.andyclaw.skills.builtin

import android.content.Context
import android.content.Intent
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import org.ethereumphone.andyclaw.skills.AndyClawSkill
import org.ethereumphone.andyclaw.skills.SkillManifest
import org.ethereumphone.andyclaw.skills.SkillResult
import org.ethereumphone.andyclaw.skills.Tier
import org.ethereumphone.andyclaw.skills.ToolDefinition
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

class LocationSkill(private val context: Context) : AndyClawSkill {
    override val id = "location"
    override val name = "Location"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val json = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
    }

    override val baseManifest = SkillManifest(
        description = "Get the device's current location, search for nearby places, " +
                "view locations on a map, and start navigation routes via Google Maps.",
        tools = listOf(
            ToolDefinition(
                name = "get_current_location",
                description = "Get the device's current GPS location including coordinates " +
                        "and a reverse-geocoded human-readable address. Use this to answer " +
                        "'where am I?' or to get coordinates before searching nearby.",
                inputSchema = JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("object"),
                        "properties" to JsonObject(emptyMap()),
                    )
                ),
                requiredPermissions = listOf(
                    "android.permission.ACCESS_FINE_LOCATION",
                ),
            ),
            ToolDefinition(
                name = "search_nearby",
                description = "Search for places near a location. Uses OpenStreetMap data " +
                        "to find restaurants, shops, ATMs, gas stations, pharmacies, or " +
                        "any other type of place. Call get_current_location first to " +
                        "obtain the device coordinates, then pass them here.",
                inputSchema = JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("object"),
                        "properties" to JsonObject(
                            mapOf(
                                "query" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("string"),
                                        "description" to JsonPrimitive(
                                            "What to search for (e.g. 'restaurant', 'pharmacy', " +
                                                    "'ATM', 'gas station', 'supermarket', 'cafe')"
                                        ),
                                    )
                                ),
                                "latitude" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("number"),
                                        "description" to JsonPrimitive(
                                            "Latitude of the center point to search around"
                                        ),
                                    )
                                ),
                                "longitude" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("number"),
                                        "description" to JsonPrimitive(
                                            "Longitude of the center point to search around"
                                        ),
                                    )
                                ),
                                "radius_meters" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("integer"),
                                        "description" to JsonPrimitive(
                                            "Search radius in meters (default: 1000, max: 50000)"
                                        ),
                                    )
                                ),
                                "max_results" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("integer"),
                                        "description" to JsonPrimitive(
                                            "Maximum number of results to return (default: 10, max: 25)"
                                        ),
                                    )
                                ),
                            )
                        ),
                        "required" to JsonArray(
                            listOf(
                                JsonPrimitive("query"),
                                JsonPrimitive("latitude"),
                                JsonPrimitive("longitude"),
                            )
                        ),
                    )
                ),
            ),
            ToolDefinition(
                name = "open_in_maps",
                description = "Open a location in Google Maps for viewing. You can specify " +
                        "coordinates, an address, or a place name. The map app will open " +
                        "and display that location with a pin.",
                inputSchema = JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("object"),
                        "properties" to JsonObject(
                            mapOf(
                                "latitude" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("number"),
                                        "description" to JsonPrimitive(
                                            "Latitude of the location to show (use with longitude)"
                                        ),
                                    )
                                ),
                                "longitude" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("number"),
                                        "description" to JsonPrimitive(
                                            "Longitude of the location to show (use with latitude)"
                                        ),
                                    )
                                ),
                                "address" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("string"),
                                        "description" to JsonPrimitive(
                                            "Address or place name to show on the map " +
                                                    "(alternative to lat/lon)"
                                        ),
                                    )
                                ),
                                "label" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("string"),
                                        "description" to JsonPrimitive(
                                            "Optional label for the map pin when using coordinates"
                                        ),
                                    )
                                ),
                                "zoom" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("integer"),
                                        "description" to JsonPrimitive(
                                            "Zoom level 1-21 (default: 15). " +
                                                    "1=world, 10=city, 15=streets, 20=buildings"
                                        ),
                                    )
                                ),
                            )
                        ),
                    )
                ),
            ),
            ToolDefinition(
                name = "start_navigation",
                description = "Start turn-by-turn navigation in Google Maps to a destination. " +
                        "You can provide coordinates or an address. Supports driving, walking, " +
                        "bicycling, and two-wheeler transport modes.",
                inputSchema = JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("object"),
                        "properties" to JsonObject(
                            mapOf(
                                "destination_latitude" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("number"),
                                        "description" to JsonPrimitive(
                                            "Latitude of the destination (use with destination_longitude)"
                                        ),
                                    )
                                ),
                                "destination_longitude" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("number"),
                                        "description" to JsonPrimitive(
                                            "Longitude of the destination (use with destination_latitude)"
                                        ),
                                    )
                                ),
                                "destination_address" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("string"),
                                        "description" to JsonPrimitive(
                                            "Address or place name to navigate to " +
                                                    "(alternative to lat/lon)"
                                        ),
                                    )
                                ),
                                "mode" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("string"),
                                        "description" to JsonPrimitive(
                                            "Transport mode: 'driving' (default), 'walking', " +
                                                    "'bicycling', or 'two-wheeler'"
                                        ),
                                        "enum" to JsonArray(
                                            listOf(
                                                JsonPrimitive("driving"),
                                                JsonPrimitive("walking"),
                                                JsonPrimitive("bicycling"),
                                                JsonPrimitive("two-wheeler"),
                                            )
                                        ),
                                    )
                                ),
                                "avoid" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("string"),
                                        "description" to JsonPrimitive(
                                            "Comma-separated things to avoid: " +
                                                    "'tolls', 'highways', 'ferries'"
                                        ),
                                    )
                                ),
                            )
                        ),
                    )
                ),
            ),
        ),
        permissions = listOf(
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.ACCESS_COARSE_LOCATION",
        ),
    )

    override val privilegedManifest: SkillManifest? = null

    override suspend fun execute(tool: String, params: JsonObject, tier: Tier): SkillResult {
        return when (tool) {
            "get_current_location" -> getCurrentLocation()
            "search_nearby" -> searchNearby(params)
            "open_in_maps" -> openInMaps(params)
            "start_navigation" -> startNavigation(params)
            else -> SkillResult.Error("Unknown tool: $tool")
        }
    }

    // ── get_current_location ────────────────────────────────────────────

    private suspend fun getCurrentLocation(): SkillResult = withContext(Dispatchers.IO) {
        try {
            val locationManager =
                context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

            val isGpsEnabled =
                locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
            val isNetworkEnabled =
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

            if (!isGpsEnabled && !isNetworkEnabled) {
                return@withContext SkillResult.Error(
                    "Location services are disabled. Please enable GPS or network location " +
                            "in device settings."
                )
            }

            // Try to get a fresh location; fall back to last known
            val location = getFreshLocation(locationManager)
                ?: getLastKnownLocation(locationManager)
                ?: return@withContext SkillResult.Error(
                    "Unable to determine current location. Make sure location services " +
                            "are enabled and the device has a GPS fix."
                )

            val result = buildJsonObject {
                put("latitude", location.latitude)
                put("longitude", location.longitude)
                put("accuracy_meters", location.accuracy.toDouble())
                put("altitude_meters", if (location.hasAltitude()) location.altitude else null)
                put("speed_mps", if (location.hasSpeed()) location.speed.toDouble() else null)
                put("bearing", if (location.hasBearing()) location.bearing.toDouble() else null)
                put("provider", location.provider ?: "unknown")
                put(
                    "timestamp",
                    java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                        .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                        .format(java.util.Date(location.time))
                )

                // Reverse geocode to get a human-readable address
                val address = reverseGeocode(location.latitude, location.longitude)
                if (address != null) {
                    put("address", address)
                }
            }
            SkillResult.Success(result.toString())
        } catch (e: SecurityException) {
            SkillResult.Error(
                "Location permission not granted. Please allow location access."
            )
        } catch (e: Exception) {
            SkillResult.Error("Failed to get current location: ${e.message}")
        }
    }

    @Suppress("MissingPermission")
    private suspend fun getFreshLocation(
        locationManager: LocationManager,
    ): Location? = suspendCancellableCoroutine { cont ->
        val cancellationSignal = CancellationSignal()
        cont.invokeOnCancellation { cancellationSignal.cancel() }

        // Pick the best available provider
        val provider = when {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ->
                LocationManager.GPS_PROVIDER
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ->
                LocationManager.NETWORK_PROVIDER
            else -> {
                cont.resume(null)
                return@suspendCancellableCoroutine
            }
        }

        try {
            locationManager.getCurrentLocation(
                provider,
                cancellationSignal,
                context.mainExecutor,
            ) { location ->
                cont.resume(location)
            }
        } catch (e: Exception) {
            cont.resume(null)
        }

        // Timeout after 10 seconds via the cancellation signal
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        handler.postDelayed({
            if (cont.isActive) {
                cancellationSignal.cancel()
                cont.resume(null)
            }
        }, 10_000L)
    }

    @Suppress("MissingPermission")
    private fun getLastKnownLocation(locationManager: LocationManager): Location? {
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        )
        return providers
            .mapNotNull { provider ->
                try {
                    locationManager.getLastKnownLocation(provider)
                } catch (_: Exception) {
                    null
                }
            }
            .maxByOrNull { it.time }
    }

    private fun reverseGeocode(latitude: Double, longitude: Double): String? {
        return try {
            val geocoder = Geocoder(context, Locale.getDefault())
            @Suppress("DEPRECATION")
            val addresses = geocoder.getFromLocation(latitude, longitude, 1)
            if (!addresses.isNullOrEmpty()) {
                val addr = addresses[0]
                buildString {
                    // Build a readable address from components
                    addr.thoroughfare?.let { append(it) }
                    addr.subThoroughfare?.let {
                        if (isNotEmpty()) append(" ")
                        append(it)
                    }
                    addr.locality?.let {
                        if (isNotEmpty()) append(", ")
                        append(it)
                    }
                    addr.adminArea?.let {
                        if (isNotEmpty()) append(", ")
                        append(it)
                    }
                    addr.postalCode?.let {
                        if (isNotEmpty()) append(" ")
                        append(it)
                    }
                    addr.countryName?.let {
                        if (isNotEmpty()) append(", ")
                        append(it)
                    }
                }.ifEmpty { null }
            } else null
        } catch (_: Exception) {
            null
        }
    }

    // ── search_nearby ───────────────────────────────────────────────────

    private suspend fun searchNearby(params: JsonObject): SkillResult =
        withContext(Dispatchers.IO) {
            val query = params["query"]?.jsonPrimitive?.contentOrNull
                ?: return@withContext SkillResult.Error("Missing required parameter: query")
            val lat = params["latitude"]?.jsonPrimitive?.doubleOrNull
                ?: return@withContext SkillResult.Error("Missing required parameter: latitude")
            val lon = params["longitude"]?.jsonPrimitive?.doubleOrNull
                ?: return@withContext SkillResult.Error("Missing required parameter: longitude")
            val radiusMeters =
                (params["radius_meters"]?.jsonPrimitive?.intOrNull ?: 1000).coerceIn(100, 50000)
            val maxResults =
                (params["max_results"]?.jsonPrimitive?.intOrNull ?: 10).coerceIn(1, 25)

            try {
                val results = searchNominatim(query, lat, lon, radiusMeters, maxResults)

                val response = buildJsonObject {
                    put("query", query)
                    put("center_latitude", lat)
                    put("center_longitude", lon)
                    put("radius_meters", radiusMeters)
                    put("result_count", results.size)
                    put("results", results)
                }
                SkillResult.Success(response.toString())
            } catch (e: Exception) {
                SkillResult.Error("Nearby search failed: ${e.message}")
            }
        }

    /**
     * Searches for nearby places using the Nominatim (OpenStreetMap) search API.
     * Uses a viewbox around the center point to restrict results to the area.
     */
    private fun searchNominatim(
        query: String,
        lat: Double,
        lon: Double,
        radiusMeters: Int,
        maxResults: Int,
    ): JsonArray {
        // Convert radius to approximate degree offset for viewbox
        val degOffset = radiusMeters / 111_320.0

        val viewboxLeft = lon - degOffset
        val viewboxRight = lon + degOffset
        val viewboxTop = lat + degOffset
        val viewboxBottom = lat - degOffset

        val url = buildString {
            append("https://nominatim.openstreetmap.org/search?")
            append("q=").append(java.net.URLEncoder.encode(query, "UTF-8"))
            append("&format=jsonv2")
            append("&limit=").append(maxResults)
            append("&viewbox=$viewboxLeft,$viewboxTop,$viewboxRight,$viewboxBottom")
            append("&bounded=1")
            append("&addressdetails=1")
            append("&extratags=1")
        }

        val request = Request.Builder()
            .url(url)
            .addHeader("User-Agent", "AndyClaw/1.0 (Android AI Assistant)")
            .addHeader("Accept", "application/json")
            .get()
            .build()

        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            throw Exception("Nominatim returned HTTP ${response.code}")
        }

        val body = response.body?.string()
            ?: throw Exception("Empty response from Nominatim")

        val rawResults = json.decodeFromString<List<NominatimResult>>(body)

        return buildJsonArray {
            for (result in rawResults) {
                add(buildJsonObject {
                    put("name", result.displayName)
                    put("type", result.type)
                    put("category", result.category)
                    put("latitude", result.lat.toDoubleOrNull() ?: 0.0)
                    put("longitude", result.lon.toDoubleOrNull() ?: 0.0)

                    // Compute distance from center
                    val resultLat = result.lat.toDoubleOrNull() ?: 0.0
                    val resultLon = result.lon.toDoubleOrNull() ?: 0.0
                    val distance = distanceBetween(lat, lon, resultLat, resultLon)
                    put("distance_meters", distance.toInt())

                    // Extract useful details from address
                    result.address?.let { addr ->
                        val street = buildString {
                            addr.road?.let { append(it) }
                            addr.houseNumber?.let {
                                if (isNotEmpty()) append(" ")
                                append(it)
                            }
                        }
                        if (street.isNotEmpty()) put("street", street)
                        addr.city?.let { put("city", it) }
                            ?: addr.town?.let { put("city", it) }
                            ?: addr.village?.let { put("city", it) }
                        addr.postcode?.let { put("postcode", it) }
                    }

                    // Extract useful extra tags (phone, website, opening hours)
                    result.extratags?.let { tags ->
                        tags.phone?.let { put("phone", it) }
                        tags.website?.let { put("website", it) }
                        tags.openingHours?.let { put("opening_hours", it) }
                        tags.cuisine?.let { put("cuisine", it) }
                    }
                })
            }
        }
    }

    // ── open_in_maps ────────────────────────────────────────────────────

    private fun openInMaps(params: JsonObject): SkillResult {
        val lat = params["latitude"]?.jsonPrimitive?.doubleOrNull
        val lon = params["longitude"]?.jsonPrimitive?.doubleOrNull
        val address = params["address"]?.jsonPrimitive?.contentOrNull
        val label = params["label"]?.jsonPrimitive?.contentOrNull
        val zoom = (params["zoom"]?.jsonPrimitive?.intOrNull ?: 15).coerceIn(1, 21)

        val uri = when {
            lat != null && lon != null -> {
                if (label != null) {
                    // geo: URI with a labeled pin
                    Uri.parse("geo:$lat,$lon?z=$zoom&q=$lat,$lon(${Uri.encode(label)})")
                } else {
                    Uri.parse("geo:$lat,$lon?z=$zoom")
                }
            }
            address != null -> {
                Uri.parse("geo:0,0?q=${Uri.encode(address)}")
            }
            else -> {
                return SkillResult.Error(
                    "Provide either latitude+longitude or an address to open in maps."
                )
            }
        }

        return try {
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage("com.google.android.apps.maps")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            // Fall back to any maps app if Google Maps is not installed
            if (intent.resolveActivity(context.packageManager) == null) {
                intent.setPackage(null)
            }
            context.startActivity(intent)

            val opened = buildJsonObject {
                put("opened", true)
                put("uri", uri.toString())
                if (lat != null && lon != null) {
                    put("latitude", lat)
                    put("longitude", lon)
                }
                address?.let { put("address", it) }
                label?.let { put("label", it) }
            }
            SkillResult.Success(opened.toString())
        } catch (e: Exception) {
            SkillResult.Error("Failed to open maps: ${e.message}")
        }
    }

    // ── start_navigation ────────────────────────────────────────────────

    private fun startNavigation(params: JsonObject): SkillResult {
        val destLat = params["destination_latitude"]?.jsonPrimitive?.doubleOrNull
        val destLon = params["destination_longitude"]?.jsonPrimitive?.doubleOrNull
        val destAddress = params["destination_address"]?.jsonPrimitive?.contentOrNull
        val mode = params["mode"]?.jsonPrimitive?.contentOrNull ?: "driving"
        val avoid = params["avoid"]?.jsonPrimitive?.contentOrNull

        val modeParam = when (mode) {
            "driving" -> "d"
            "walking" -> "w"
            "bicycling" -> "b"
            "two-wheeler" -> "l"
            else -> "d"
        }

        val destination = when {
            destLat != null && destLon != null -> "$destLat,$destLon"
            destAddress != null -> Uri.encode(destAddress)
            else -> return SkillResult.Error(
                "Provide either destination_latitude+destination_longitude or destination_address."
            )
        }

        val uriString = buildString {
            append("google.navigation:q=$destination&mode=$modeParam")
            avoid?.let { append("&avoid=$it") }
        }

        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uriString)).apply {
                setPackage("com.google.android.apps.maps")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(context.packageManager) == null) {
                return SkillResult.Error(
                    "Google Maps is not installed. Please install Google Maps to use navigation."
                )
            }
            context.startActivity(intent)

            val result = buildJsonObject {
                put("navigation_started", true)
                put("mode", mode)
                if (destLat != null && destLon != null) {
                    put("destination_latitude", destLat)
                    put("destination_longitude", destLon)
                }
                destAddress?.let { put("destination_address", it) }
                avoid?.let { put("avoid", it) }
            }
            SkillResult.Success(result.toString())
        } catch (e: Exception) {
            SkillResult.Error("Failed to start navigation: ${e.message}")
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    /**
     * Haversine distance between two lat/lon points in meters.
     */
    private fun distanceBetween(
        lat1: Double, lon1: Double,
        lat2: Double, lon2: Double,
    ): Double {
        val r = 6_371_000.0 // Earth radius in meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return r * c
    }
}

// ── Nominatim response models ───────────────────────────────────────────

@kotlinx.serialization.Serializable
private data class NominatimResult(
    @kotlinx.serialization.SerialName("display_name")
    val displayName: String = "",
    val type: String = "",
    val category: String = "",
    val lat: String = "",
    val lon: String = "",
    val address: NominatimAddress? = null,
    val extratags: NominatimExtratags? = null,
)

@kotlinx.serialization.Serializable
private data class NominatimAddress(
    val road: String? = null,
    @kotlinx.serialization.SerialName("house_number")
    val houseNumber: String? = null,
    val city: String? = null,
    val town: String? = null,
    val village: String? = null,
    val postcode: String? = null,
    val country: String? = null,
)

@kotlinx.serialization.Serializable
private data class NominatimExtratags(
    val phone: String? = null,
    val website: String? = null,
    @kotlinx.serialization.SerialName("opening_hours")
    val openingHours: String? = null,
    val cuisine: String? = null,
)
