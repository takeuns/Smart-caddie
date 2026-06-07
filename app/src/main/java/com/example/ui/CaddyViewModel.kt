package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.BuildConfig
import com.example.data.CaddyDatabase
import com.example.data.CaddyRepository
import com.example.data.Club
import com.example.data.GolfCourse
import com.example.data.GolfHole
import com.example.data.SwingLog
import com.example.data.RetrofitClient
import com.example.data.GeminiRequest
import com.example.data.GeminiContent
import com.example.data.GeminiPart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class CourseWeather(
    val temperatureCelsius: Double = 22.0,
    val windSpeedMps: Double = 3.0,
    val windDirection: String = "NW",
    val description: String = "Clear",
    val elevationChangeMeters: Double = -2.4,
    val isFetching: Boolean = false,
    val error: String? = null
)

data class AdjustedHoleDistances(
    val baseDistances: HoleDistances,
    val playerToGreenPlayAs: Int,
    val playerToTargetPlayAs: Int,
    val tempAdjustment: Double,
    val windAdjustment: Double,
    val elevationAdjustment: Double,
    val lateralDriftYards: Double
)

data class BallTrackerMetrics(
    val ballToGreenDistance: Int,
    val userToBallDistance: Int,
    val unit: String
)

data class RoundDriveTrend(
    val roundNumber: Int,
    val roundName: String,
    val dateLabel: String,
    val averageDistanceYards: Float,
    val longestDriveYards: Float
)

class CaddyViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: CaddyRepository

    init {
        val database = CaddyDatabase.getDatabase(application, viewModelScope)
        repository = CaddyRepository(database)
    }

    // --- State Observables ---
    // Weather States
    private val _currentWeather = MutableStateFlow(CourseWeather())
    val currentWeather: StateFlow<CourseWeather> = _currentWeather.asStateFlow()
    val courses: StateFlow<List<GolfCourse>> = repository.allCourses
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val clubs: StateFlow<List<Club>> = repository.allClubs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val swingLogs: StateFlow<List<SwingLog>> = repository.allLogs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val driverTrends: StateFlow<List<RoundDriveTrend>> = swingLogs
        .map { logs ->
            // Filter driver logs
            val driverLogs = logs.filter { it.clubName.contains("드라이버") || it.clubName.contains("Driver") }
            
            // Build 10 Rounds of historical trend data
            val defaultTrends = listOf(
                RoundDriveTrend(1, "R1", "04/10", 212f, 228f),
                RoundDriveTrend(2, "R2", "04/18", 215f, 230f),
                RoundDriveTrend(3, "R3", "04/25", 218f, 235f),
                RoundDriveTrend(4, "R4", "05/02", 216f, 232f),
                RoundDriveTrend(5, "R5", "05/09", 220f, 238f),
                RoundDriveTrend(6, "R6", "05/16", 224f, 242f),
                RoundDriveTrend(7, "R7", "05/23", 225f, 245f),
                RoundDriveTrend(8, "R8", "05/30", 228f, 248f),
                RoundDriveTrend(9, "R9", "06/02", 231f, 252f),
                RoundDriveTrend(10, "R10", "오늘", 232f, 255f)
            )

            if (driverLogs.isEmpty()) {
                defaultTrends
            } else {
                // Divide user's driver logs into the latest rounds or use them to enrich the latest rounds
                // If they have real logs, we replace/update Round 10 with the average of actual user driver logs!
                // If they have many logs, we group them into Rounds (e.g. chunks of 2 shots as a round)
                val chunkedLogs = driverLogs.chunked(2)
                val updatedTrends = defaultTrends.toMutableList()
                
                for (i in 0 until 10) {
                    val reverseIndex = 9 - i
                    val chunkIndex = chunkedLogs.size - 1 - i
                    if (chunkIndex >= 0 && reverseIndex >= 0) {
                        val chunk = chunkedLogs[chunkIndex]
                        val avg = chunk.map { it.carryDistanceYards }.average().toFloat()
                        val max = chunk.map { it.carryDistanceYards }.maxOrNull() ?: avg
                        
                        val roundLabel = "R${reverseIndex + 1}"
                        val dateString = if (i == 0) "오늘" else "기록 #${chunkIndex + 1}"
                        
                        updatedTrends[reverseIndex] = RoundDriveTrend(
                            roundNumber = reverseIndex + 1,
                            roundName = roundLabel,
                            dateLabel = dateString,
                            averageDistanceYards = String.format("%.1f", avg).toFloat(),
                            longestDriveYards = String.format("%.1f", max).toFloat()
                        )
                    }
                }
                updatedTrends
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Selected Course & Hole
    private val _selectedCourse = MutableStateFlow<GolfCourse?>(null)
    val selectedCourse: StateFlow<GolfCourse?> = _selectedCourse.asStateFlow()

    private val _holes = MutableStateFlow<List<GolfHole>>(emptyList())
    val holes: StateFlow<List<GolfHole>> = _holes.asStateFlow()

    private val _selectedHole = MutableStateFlow<GolfHole?>(null)
    val selectedHole: StateFlow<GolfHole?> = _selectedHole.asStateFlow()

    // GPS and Position Simulation
    private val _gpsLiveMode = MutableStateFlow(false)
    val gpsLiveMode: StateFlow<Boolean> = _gpsLiveMode.asStateFlow()

    private val _liveLocation = MutableStateFlow<Pair<Double, Double>?>(null)
    val liveLocation: StateFlow<Pair<Double, Double>?> = _liveLocation.asStateFlow()

    // Battery Saver mode config (reduces GPS updates when stationary)
    private val _batterySaverMode = MutableStateFlow(false)
    val batterySaverMode: StateFlow<Boolean> = _batterySaverMode.asStateFlow()

    private val _isMovingBetweenHoles = MutableStateFlow(false)
    val isMovingBetweenHoles: StateFlow<Boolean> = _isMovingBetweenHoles.asStateFlow()

    private val _gpsUpdateInterval = MutableStateFlow(4000L)
    val gpsUpdateInterval: StateFlow<Long> = _gpsUpdateInterval.asStateFlow()

    private var lastGpsLocation: Pair<Double, Double>? = null
    private var lastMovedTime: Long = 0L

    // simulated fraction (0.0 = Tee, 1.0 = Green)
    private val _simulatedProgress = MutableStateFlow(0.0f)
    val simulatedProgress: StateFlow<Float> = _simulatedProgress.asStateFlow()

    // Target spot fractional coordinates on canvas (X, Y)
    private val _targetPosition = MutableStateFlow<Pair<Float, Float>>(Pair(0.6f, 0.5f))
    val targetPosition: StateFlow<Pair<Float, Float>> = _targetPosition.asStateFlow()

    // AI Strategic recommendations
    private val _isAnalyzing = MutableStateFlow(false)
    val isAnalyzing: StateFlow<Boolean> = _isAnalyzing.asStateFlow()

    private val _aiRecommendation = MutableStateFlow<String>("")
    val aiRecommendation: StateFlow<String> = _aiRecommendation.asStateFlow()

    private val _apiErrorOccurred = MutableStateFlow<String?>(null)
    val apiErrorOccurred: StateFlow<String?> = _apiErrorOccurred.asStateFlow()

    // AI Club Suggestion Utility states
    private val _isSuggestingClub = MutableStateFlow(false)
    val isSuggestingClub: StateFlow<Boolean> = _isSuggestingClub.asStateFlow()

    private val _aiClubSuggestion = MutableStateFlow<String>("")
    val aiClubSuggestion: StateFlow<String> = _aiClubSuggestion.asStateFlow()

    private val _clubSuggestionError = MutableStateFlow<String?>(null)
    val clubSuggestionError: StateFlow<String?> = _clubSuggestionError.asStateFlow()

    // Distance metrics to trigger automated club recommendations
    private val _metricUnitYards = MutableStateFlow(true)
    val metricUnitYards: StateFlow<Boolean> = _metricUnitYards.asStateFlow()

    // Voice Caddy Hands-Free feature
    private val _voiceCaddyEnabled = MutableStateFlow(false)
    val voiceCaddyEnabled: StateFlow<Boolean> = _voiceCaddyEnabled.asStateFlow()

    private val _voiceState = MutableStateFlow("OFF") // OFF / IDLE / LISTENING / PROCESSING / SPEAKING
    val voiceState: StateFlow<String> = _voiceState.asStateFlow()

    private val _lastVoiceQuery = MutableStateFlow("")
    val lastVoiceQuery: StateFlow<String> = _lastVoiceQuery.asStateFlow()

    private val _lastVoiceResponse = MutableStateFlow("")
    val lastVoiceResponse: StateFlow<String> = _lastVoiceResponse.asStateFlow()

    // Real-time GPS ball tracking state
    private val _ballLocation = MutableStateFlow<Pair<Double, Double>?>(null)
    val ballLocation: StateFlow<Pair<Double, Double>?> = _ballLocation.asStateFlow()

    init {
        // Automatically set first course as selected once loaded
        viewModelScope.launch {
            courses.collect { list ->
                if (list.isNotEmpty() && _selectedCourse.value == null) {
                    selectCourse(list.first())
                }
            }
        }
    }

    // --- Actions ---
    fun selectCourse(course: GolfCourse) {
        _selectedCourse.value = course
        fetchWeatherForCourse(course)
        viewModelScope.launch {
            repository.getHolesForCourse(course.id).collect { holeList ->
                _holes.value = holeList
                if (holeList.isNotEmpty()) {
                    _selectedHole.value = holeList.first()
                    // Reset simulator position
                    _simulatedProgress.value = 0.0f
                    _targetPosition.value = Pair(0.6f, 0.5f)
                } else {
                    _selectedHole.value = null
                }
            }
        }
    }

    // Real-time weather fetch using Gemini with Google Search tool backing
    fun fetchWeatherForCourse(course: GolfCourse) {
        _currentWeather.update { it.copy(isFetching = true, error = null) }
        viewModelScope.launch {
            val apiKey = BuildConfig.GEMINI_API_KEY
            if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") {
                // If API key is not set, use localized high-quality mock data
                kotlinx.coroutines.delay(1000)
                _currentWeather.value = determineMockWeather(course)
                return@launch
            }

            try {
                val locationName = "${course.name}, ${course.location}"
                val prompt = """
                    Search Google to get the current real-time actual weather information of the golf course: '$locationName'.
                    We need:
                    1. temperature: Current actual degrees in Celsius (e.g. 24.5)
                    2. windSpeed: Current wind speed in m/s (e.g. 3.2)
                    3. windDirection: Current wind direction like NW or SE or N (e.g. "NW")
                    4. description: A short, simple weather overview in Korean (e.g. "맑고 화창함")
                    5. elevation: Local terrain elevation offset in meters relative to sea level or average course height, keep it custom for the golf course like -2.4 if downhill, 0.0, or 2.1 description.

                    Return ONLY a JSON formatted text matching this exact schema:
                    {
                      "temperature": 24.5,
                      "windSpeed": 3.2,
                      "windDirection": "NW",
                      "description": "맑고 화창함",
                      "elevation": -2.4
                    }
                    Do NOT wrap inside markdown block. Return raw json content ONLY.
                """.trimIndent()

                val response = withContext(Dispatchers.IO) {
                    val request = GeminiRequest(
                        contents = listOf(
                            GeminiContent(parts = listOf(GeminiPart(text = prompt)))
                        ),
                        tools = listOf(com.example.data.GeminiTool(googleSearch = emptyMap()))
                    )
                    RetrofitClient.service.generateContent(apiKey, request)
                }

                val text = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                if (text != null) {
                    val temp = extractDoubleValue(text, "temperature") ?: extractDoubleValue(text, "temp") ?: 22.0
                    val windSpd = extractDoubleValue(text, "windSpeed") ?: extractDoubleValue(text, "wind_speed") ?: 3.0
                    val windDir = extractStringValue(text, "windDirection") ?: extractStringValue(text, "wind_dir") ?: "NW"
                    val desc = extractStringValue(text, "description") ?: extractStringValue(text, "overview") ?: "맑음"
                    val elev = extractDoubleValue(text, "elevation") ?: extractDoubleValue(text, "elev") ?: -2.4

                    _currentWeather.value = CourseWeather(
                        temperatureCelsius = temp,
                        windSpeedMps = windSpd,
                        windDirection = windDir.uppercase(),
                        description = desc,
                        elevationChangeMeters = elev,
                        isFetching = false,
                        error = null
                    )
                } else {
                    throw Exception("No text generated for weather")
                }
            } catch (e: Exception) {
                e.printStackTrace()
                val fallback = determineMockWeather(course)
                _currentWeather.value = fallback.copy(
                    error = "실시간 날씨 검색 지연으로 대체 정보 로드 (${e.localizedMessage ?: "Unknown Error"})"
                )
            }
        }
    }

    private fun determineMockWeather(course: GolfCourse): CourseWeather {
        return when {
            course.name.contains("오거스타") || course.name.contains("Augusta") -> {
                CourseWeather(temperatureCelsius = 24.0, windSpeedMps = 3.2, windDirection = "NW", description = "구름 조금 (오거스타 코스 가이드)", elevationChangeMeters = -2.4)
            }
            course.name.contains("페블비치") || course.name.contains("Pebble") -> {
                CourseWeather(temperatureCelsius = 16.5, windSpeedMps = 6.2, windDirection = "SW", description = "서늘한 해풍 (페블비치 로컬 가이드)", elevationChangeMeters = 0.8)
            }
            course.name.contains("세인트") || course.name.contains("Andrews") -> {
                CourseWeather(temperatureCelsius = 14.0, windSpeedMps = 8.5, windDirection = "NE", description = "매우 강한 브리즈 (세인트 롭 가이드)", elevationChangeMeters = -0.1)
            }
            else -> {
                CourseWeather(temperatureCelsius = 22.5, windSpeedMps = 2.4, windDirection = "N", description = "완성형 온화함", elevationChangeMeters = 0.0)
            }
        }
    }

    private fun extractDoubleValue(jsonStr: String, key: String): Double? {
        val pattern = "\"$key\"\\s*:\\s*(-?\\d+\\.?\\d*)"
        val regex = Regex(pattern, RegexOption.IGNORE_CASE)
        val match = regex.find(jsonStr)
        return match?.groupValues?.getOrNull(1)?.toDoubleOrNull()
    }

    private fun extractStringValue(jsonStr: String, key: String): String? {
        val pattern = "\"$key\"\\s*:\\s*\"([^\"]+)\""
        val regex = Regex(pattern, RegexOption.IGNORE_CASE)
        val match = regex.find(jsonStr)
        return match?.groupValues?.getOrNull(1)
    }

    // Advanced "Play-As" Distance Calibration Engine
    fun getAdjustedDistances(): AdjustedHoleDistances {
        val base = calculateInGameDistances()
        val weather = _currentWeather.value

        val temp = weather.temperatureCelsius
        val windSpd = weather.windSpeedMps
        val windDir = weather.windDirection.uppercase()

        // 1. Temperature conversion: plays 0.25 yards longer for each degree C below 20°C
        val tempDiff = 20.0 - temp
        val tempPlayAsEffect = tempDiff * 0.25

        // 2. Wind effect:
        // Relative to forward shot. N = Headwind, S = Tailwind.
        val windPlayAsEffect: Double
        val lateralDrift: Double

        when {
            windDir.contains("N") -> {
                // Headwind: resists flight
                windPlayAsEffect = windSpd * 1.4
                lateralDrift = if (windDir.contains("W")) -windSpd * 0.7 else if (windDir.contains("E")) windSpd * 0.7 else 0.0
            }
            windDir.contains("S") -> {
                // Tailwind: pushes flight forward
                windPlayAsEffect = -windSpd * 0.95
                lateralDrift = if (windDir.contains("W")) -windSpd * 0.7 else if (windDir.contains("E")) windSpd * 0.7 else 0.0
            }
            else -> {
                // Side wind: mostly lateral shift, minor front resistance
                windPlayAsEffect = windSpd * 0.15
                lateralDrift = if (windDir.contains("W")) -windSpd * 1.1 else windSpd * 1.1
            }
        }

        // 3. Elevation effect:
        // Descent (negative elevation change) plays shorter by approx -1.1 yd per 1m descent
        val elevationPlayAsEffect = -weather.elevationChangeMeters * 1.1

        val totalPlayAsAdjustment = tempPlayAsEffect + windPlayAsEffect + elevationPlayAsEffect

        return AdjustedHoleDistances(
            baseDistances = base,
            playerToGreenPlayAs = (base.playerToGreen + totalPlayAsAdjustment).coerceAtLeast(0.0).toInt(),
            playerToTargetPlayAs = (base.playerToTarget + totalPlayAsAdjustment).coerceAtLeast(0.0).toInt(),
            tempAdjustment = tempPlayAsEffect,
            windAdjustment = windPlayAsEffect,
            elevationAdjustment = elevationPlayAsEffect,
            lateralDriftYards = lateralDrift
        )
    }

    fun selectHole(hole: GolfHole) {
        _selectedHole.value = hole
        _simulatedProgress.value = 0.0f
        _targetPosition.value = Pair(0.6f, 0.5f)
    }

    fun setGpsLiveMode(enabled: Boolean) {
        _gpsLiveMode.value = enabled
    }

    fun setBatterySaverMode(enabled: Boolean) {
        _batterySaverMode.value = enabled
        recalculateGpsInterval()
    }

    private fun recalculateGpsInterval() {
        val interval = if (_batterySaverMode.value && !_isMovingBetweenHoles.value) {
            25000L // 25s update frequency in battery saver when stationary
        } else {
            4000L  // 4s high-frequency update
        }
        if (_gpsUpdateInterval.value != interval) {
            _gpsUpdateInterval.value = interval
        }
    }

    private fun calculateDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadius = 6371000.0 // meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return earthRadius * c
    }

    fun updateLiveLocation(latitude: Double, longitude: Double) {
        val currentTime = System.currentTimeMillis()
        _liveLocation.value = Pair(latitude, longitude)
        
        val lastLoc = lastGpsLocation
        if (lastLoc != null) {
            val dist = calculateDistanceMeters(lastLoc.first, lastLoc.second, latitude, longitude)
            // If they move significantly (> 3.0 meters), mark as actively moving
            if (dist > 3.0) {
                _isMovingBetweenHoles.value = true
                lastMovedTime = currentTime
            } else {
                // If they have not moved significantly in 20 seconds, mark as stationary (not actively moving)
                if (currentTime - lastMovedTime > 20000L) {
                    _isMovingBetweenHoles.value = false
                }
            }
        } else {
            // First location received
            _isMovingBetweenHoles.value = true
            lastMovedTime = currentTime
        }
        
        lastGpsLocation = Pair(latitude, longitude)
        recalculateGpsInterval()
    }

    fun setSimulatedProgress(progress: Float) {
        _simulatedProgress.value = progress.coerceIn(0.0f, 1.0f)
    }

    private var gpsSimulatedFraction = 0.0

    fun resetGpsSimulation() {
        val hole = _selectedHole.value ?: return
        gpsSimulatedFraction = 0.0
        val lat = hole.teeLatitude
        val lng = hole.teeLongitude
        updateLiveLocation(lat, lng)
    }

    fun walkGpsTowardGreen(meters: Double) {
        val hole = _selectedHole.value ?: return
        val totalLengthMct = hole.distanceYards * 0.9144
        if (totalLengthMct <= 0.0) return
        val deltaFraction = meters / totalLengthMct
        gpsSimulatedFraction = (gpsSimulatedFraction + deltaFraction).coerceIn(0.0, 1.0)
        
        val lat = hole.teeLatitude + (hole.greenLatitude - hole.teeLatitude) * gpsSimulatedFraction
        val lng = hole.teeLongitude + (hole.greenLongitude - hole.teeLongitude) * gpsSimulatedFraction
        updateLiveLocation(lat, lng)
    }

    fun walkGpsAwayFromGreen(meters: Double) {
        val hole = _selectedHole.value ?: return
        val totalLengthMct = hole.distanceYards * 0.9144
        if (totalLengthMct <= 0.0) return
        val deltaFraction = meters / totalLengthMct
        gpsSimulatedFraction = (gpsSimulatedFraction - deltaFraction).coerceIn(0.0, 1.0)
        
        val lat = hole.teeLatitude + (hole.greenLatitude - hole.teeLatitude) * gpsSimulatedFraction
        val lng = hole.teeLongitude + (hole.greenLongitude - hole.teeLongitude) * gpsSimulatedFraction
        updateLiveLocation(lat, lng)
    }

    fun setTargetPosition(x: Float, y: Float) {
        _targetPosition.value = Pair(x.coerceIn(0.0f, 1.0f), y.coerceIn(0.0f, 1.0f))
    }

    fun markBallLocation() {
        val hole = _selectedHole.value ?: return
        if (_gpsLiveMode.value && _liveLocation.value != null) {
            _ballLocation.value = _liveLocation.value
        } else {
            // Simulated player location
            val t = _simulatedProgress.value.toDouble()
            val playerLat = hole.teeLatitude + (hole.greenLatitude - hole.teeLatitude) * t
            val playerLng = hole.teeLongitude + (hole.greenLongitude - hole.teeLongitude) * t
            _ballLocation.value = Pair(playerLat, playerLng)
        }
    }

    fun clearBallLocation() {
        _ballLocation.value = null
    }

    fun getBallTrackerMetrics(): BallTrackerMetrics? {
        val ball = _ballLocation.value ?: return null
        val hole = _selectedHole.value ?: return null

        val playerLat: Double
        val playerLng: Double

        if (_gpsLiveMode.value && _liveLocation.value != null) {
            playerLat = _liveLocation.value!!.first
            playerLng = _liveLocation.value!!.second
        } else {
            val t = _simulatedProgress.value.toDouble()
            playerLat = hole.teeLatitude + (hole.greenLatitude - hole.teeLatitude) * t
            playerLng = hole.teeLongitude + (hole.greenLongitude - hole.teeLongitude) * t
        }

        val distBallToGreen = haversineYards(ball.first, ball.second, hole.greenLatitude, hole.greenLongitude)
        val distUserToBall = haversineYards(playerLat, playerLng, ball.first, ball.second)

        val conversion = if (_metricUnitYards.value) 1.0 else 0.9144
        val unit = if (_metricUnitYards.value) "yd" else "m"

        return BallTrackerMetrics(
            ballToGreenDistance = (distBallToGreen * conversion).toInt(),
            userToBallDistance = (distUserToBall * conversion).toInt(),
            unit = unit
        )
    }

    fun setVoiceCaddyEnabled(enabled: Boolean) {
        _voiceCaddyEnabled.value = enabled
        if (!enabled) {
            _voiceState.value = "OFF"
        } else {
            _voiceState.value = "IDLE"
        }
    }

    fun setVoiceState(state: String) {
        _voiceState.value = state
    }

    fun setVoiceResults(query: String, response: String) {
        _lastVoiceQuery.value = query
        _lastVoiceResponse.value = response
    }

    fun toggleUnit() {
        _metricUnitYards.value = !_metricUnitYards.value
    }

    // --- Club Management ---
    fun addClub(name: String, type: String, distance: Float) {
        viewModelScope.launch {
            repository.insertClub(Club(name = name, clubType = type, averageCarryDistance = distance, description = ""))
        }
    }

    fun deleteClub(clubId: Long) {
        viewModelScope.launch {
            repository.deleteClub(clubId)
        }
    }

    // --- Swing Log Management ---
    fun logSwing(
        clubName: String,
        swingSpeed: Float,
        ballSpeed: Float,
        launchAngle: Float,
        spinRate: Float,
        resultDirection: String,
        notes: String = ""
    ) {
        val smash = if (swingSpeed > 0) ballSpeed / swingSpeed else 1.0f
        // Estimate carry distance using standard formula for aesthetic feedback
        // Approximate physics formula: carry = ballSpeed * 1.5 (varies by club, here is a simplified estimate for fun)
        val carryMultiplier = when {
            clubName.contains("드라이버") -> 1.55f
            clubName.contains("우드") -> 1.48f
            clubName.contains("유틸") || clubName.contains("하이브리드") -> 1.42f
            clubName.contains("아이언") -> 1.32f
            clubName.contains("웨지") -> 1.15f
            else -> 1.0f
        }
        val estimatedCarry = ballSpeed * carryMultiplier

        viewModelScope.launch {
            val log = SwingLog(
                clubName = clubName,
                swingSpeedMph = swingSpeed,
                ballSpeedMph = ballSpeed,
                smashFactor = smash,
                launchAngleDegrees = launchAngle,
                spinRateRpm = spinRate,
                carryDistanceYards = estimatedCarry,
                resultDirection = resultDirection,
                notes = notes
            )
            repository.insertLog(log)
            
            // Automatically trigger strategy adjustment analysis based on new log
            analyzeWithGemini(log)
        }
    }

    fun deleteLog(logId: Long) {
        viewModelScope.launch {
            repository.deleteLog(logId)
        }
    }

    fun clearLogs() {
        viewModelScope.launch {
            repository.clearLogs()
            _aiRecommendation.value = "이전 분석 로그가 초기화되었습니다. 새로운 스윙 데이터를 등록하고 맞춤 AI 가이드를 받아보세요!"
        }
    }

    // --- GPS Calculations ---
    // Calculate distance using Haversine formula
    fun calculateInGameDistances(): HoleDistances {
        val hole = _selectedHole.value ?: return HoleDistances(0, 0, 0)
        
        val playerLat: Double
        val playerLng: Double

        if (_gpsLiveMode.value && _liveLocation.value != null) {
            playerLat = _liveLocation.value!!.first
            playerLng = _liveLocation.value!!.second
        } else {
            // Simulated location: interpolate between Tee and Green
            val t = _simulatedProgress.value.toDouble()
            playerLat = hole.teeLatitude + (hole.greenLatitude - hole.teeLatitude) * t
            playerLng = hole.teeLongitude + (hole.greenLongitude - hole.teeLongitude) * t
        }

        // Target location: interpolated on canvas based on start coordinates
        // For convenience we place target at a vector relative to tee-to-green
        val tx = _targetPosition.value.first.toDouble()
        val targetLat = hole.teeLatitude + (hole.greenLatitude - hole.teeLatitude) * tx
        val targetLng = hole.teeLongitude + (hole.greenLongitude - hole.teeLongitude) * tx

        val distPlayerToTarget = haversineYards(playerLat, playerLng, targetLat, targetLng)
        val distTargetToGreen = haversineYards(targetLat, targetLng, hole.greenLatitude, hole.greenLongitude)
        val distPlayerToGreen = haversineYards(playerLat, playerLng, hole.greenLatitude, hole.greenLongitude)

        val conversion = if (_metricUnitYards.value) 1.0 else 0.9144 // 1 yard = 0.9144 meters

        return HoleDistances(
            playerToTarget = (distPlayerToTarget * conversion).toInt(),
            targetToGreen = (distTargetToGreen * conversion).toInt(),
            playerToGreen = (distPlayerToGreen * conversion).toInt()
        )
    }

    private fun haversineYards(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371e3 // Earth radius in meters
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val deltaPhi = Math.toRadians(lat2 - lat1)
        val deltaLambda = Math.toRadians(lon2 - lon1)

        val a = Math.sin(deltaPhi / 2) * Math.sin(deltaPhi / 2) +
                Math.cos(phi1) * Math.cos(phi2) *
                Math.sin(deltaLambda / 2) * Math.sin(deltaLambda / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))

        val meters = r * c
        return meters * 1.09361 // Convert meters to yards
    }

    // --- Gemini AI Analysis ---
    fun requestAiStrategy() {
        viewModelScope.launch {
            val latestLogs = swingLogs.value
            val lastLog = latestLogs.firstOrNull()
            analyzeWithGemini(lastLog)
        }
    }

    private suspend fun analyzeWithGemini(lastLog: SwingLog?) {
        _isAnalyzing.value = true
        _apiErrorOccurred.value = null

        val currentHole = _selectedHole.value ?: GolfHole(
            courseId = 1L, holeNumber = 1, par = 4, distanceYards = 380,
            teeLatitude = 36.5685, teeLongitude = -121.9482,
            greenLatitude = 36.5658, greenLongitude = -121.9472,
            bunkerLatitude = 36.5663, bunkerLongitude = -121.9474,
            hazardLatitude = 36.5670, hazardLongitude = -121.9477,
            tip = "첫 홀은 완만한 미들홀입니다. 벙커를 주의하세요."
        )
        val activeCourse = _selectedCourse.value ?: GolfCourse(name = "페블비치 골프 링크스", location = "미국", totalHoles = 18)

        val clubSummary = clubs.value.joinToString("\n") { 
            "- ${it.name}: 평균 ${it.averageCarryDistance.toInt()} yards" 
        }

        val prompt = if (lastLog != null) {
            """
            골프 전문 AI 캐디로서, 골퍼의 스윙 기록과 현재 라운딩 중인 홀의 특징을 모두 파악한 후, 대단히 전문적이고 한국적인 뉘앙스로 코칭 가이드를 제공하시오.

            [현재 홀 정보]
            코스명: ${activeCourse.name}
            홀 번호: ${currentHole.holeNumber}번 홀 (Par ${currentHole.par}, 총길이 ${currentHole.distanceYards} 야드)
            코스 설계가 어드바이스: ${currentHole.tip}

            [골퍼의 전체 클럽 비거리 정보]
            $clubSummary

            [골퍼의 가장 최근 샷 측정기 데이터]
            - 선택 클럽: ${lastLog.clubName}
            - 헤드 스피드 (Swing Speed): ${lastLog.swingSpeedMph} mph
            - 볼 스피드 (Ball Speed): ${lastLog.ballSpeedMph} mph
            - 스매시 팩터 (Smash Factor - 정타율): ${String.format("%.2f", lastLog.smashFactor)} 
            - 출발 발사각 (Launch Angle): ${lastLog.launchAngleDegrees}°
            - 백스핀량 (Spin Rate): ${lastLog.spinRateRpm} rpm
            - 추정 비거리 (Carry): ${lastLog.carryDistanceYards.toInt()} 야드
            - 볼의 흐름 및 구질: ${lastLog.resultDirection} (Straight / Slice / Hook 등)
            - 메모: ${lastLog.notes}

            위 데이터를 정교히 계산하여 다음 세 부분을 정성 들여 한국어로 출력할 것.
            1. **현재 홀 최적 공략 루트**: 현재 홀의 바다/해저드/벙커를 감안했을 때, 드라이버를 칠지 or 컷오프 우드나 유틸로 안전하게 레이업할지 골퍼의 클럽 평균 비거리와 부합하도록 명확히 결정해주세요.
            2. **메모된 샷 분석 코치 피드백**: 가장 마지막 샷의 정타율(Smash Factor, 드라이버는 1.45 내외, 아이언은 1.3 내외가 적정)과 구질(예: 슬라이스 성향 시 릴리즈가 안 되거나 아웃인 궤도로 깎였다는 진단)을 심층 분석하고, 문제 해결을 위한 임팩트 꿀팁을 선물해주세요.
            3. **추천 다음 샷 클럽**: 티샷 이후 페어웨이에서 온그린을 노릴 경우 바람이나 거리를 고려해서 어떤 서브 클럽들을 사용하면 좋을지 한눈에 제안하십시오.

            친근하면서도 신뢰감을 주는 프로 골프 투어 캐디의 어조(예: "~님, 이번 홀은...", "~를 겨냥하는 것을 추천합니다.")로 친절하게 작성하고 이모티콘을 적극 활용해 가독성을 높이십시오.
            """.trimIndent()
        } else {
            """
            골프 전문 AI 캐디로서, 골퍼에게 환영 인사를 건네고, 이 스마트 캐디 앱을 활용해 최고의 스코어를 기록하기 위한 가이드를 작성하세요.
            
            [플레이 정보]
            코스명: ${activeCourse.name}
            홀 번호: ${currentHole.holeNumber}번 홀 (Par ${currentHole.par}, ${currentHole.distanceYards} 야드)
            설계 팁: ${currentHole.tip}
            
            [골퍼의 가방 스펙]
            $clubSummary

            아직 스윙 데이터 로그가 없습니다. 골퍼가 다음 샷을 하기 전, 위 정보를 기반으로 이 홀에서 할 수 있는 사전 전략적 분석을 하십시오. 오프닝 팁을 알려주세요.
            """.trimIndent()
        }

        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") {
            // No API Key or Placeholder key - trigger high-quality Local Heuristic Recommendation!
            withContext(Dispatchers.Default) {
                // Mimic network latency
                kotlinx.coroutines.delay(1200)
                _aiRecommendation.value = generateLocalHeuristic(activeCourse, currentHole, lastLog, clubs.value)
                _isAnalyzing.value = false
            }
            return
        }

        try {
            val response = withContext(Dispatchers.IO) {
                val request = GeminiRequest(
                    contents = listOf(
                        GeminiContent(parts = listOf(GeminiPart(text = prompt)))
                    )
                )
                RetrofitClient.service.generateContent(apiKey, request)
            }
            val aiText = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
            if (aiText != null) {
                _aiRecommendation.value = aiText
            } else {
                _aiRecommendation.value = "AI 캐디와 연결이 혼잡합니다. 잠시 후 스윙 분석을 다시 시도해 주세요!"
            }
        } catch (e: Exception) {
            e.printStackTrace()
            _apiErrorOccurred.value = e.localizedMessage ?: "Network Connection Error"
            // Graceful fallback to heuristic database analysis!
            _aiRecommendation.value = "⚠️ [API 오류로 인한 로컬 캐디 전환] \n" + generateLocalHeuristic(activeCourse, currentHole, lastLog, clubs.value)
        } finally {
            _isAnalyzing.value = false
        }
    }

    // High quality offline fallback strategy to avoid dead UI!
    private fun generateLocalHeuristic(
        course: GolfCourse,
        hole: GolfHole,
        lastLog: SwingLog?,
        clubList: List<Club>
    ): String {
        val driverDist = clubList.find { it.name.contains("드라이버") }?.averageCarryDistance ?: 220f
        val sevenIronDist = clubList.find { it.name.contains("7번") }?.averageCarryDistance ?: 140f
        
        val sb = StringBuilder()
        sb.append("⛳ **AI 캐디의 ${course.name} ${hole.holeNumber}번 홀 특급 전략**\n\n")
        
        sb.append("🎯 **1. 홀 공략 전략 제안**\n")
        sb.append("- 이 홀은 Par ${hole.par}, 총길이 ${hole.distanceYards}야드의 코스입니다.\n")
        sb.append("- 설계가 가이드: *\"${hole.tip}\"*\n")
        if (hole.par >= 4) {
            if (driverDist > 210f && hole.distanceYards < 350) {
                sb.append("- **공략 추천:** 홀의 전장이 짧아 무리하게 드라이버를 세게 치기보다는 페어웨이가 좁을 수 있어 **3번 우드(약 ${driverDist.toInt() - 25}yd)**나 **유틸리티**로 안전하게 중앙 레이업을 가져가셔도 세컨드 샷 90~100码 이내로 쉽게 올릴 수 있습니다. 🏌️‍♂️\n")
            } else {
                sb.append("- **공략 추천:** 과감한 드라이버 티샷으로 **${driverDist.toInt()}야드 지점 페어웨이**를 점유하십시오. 우측에 배치된 슬라이스 및 물 해저드를 감안하여 페어웨이 중앙의 왼쪽 15야드 방향을 든든하게 에이밍(Aiming)하는 것이 파 세이브 환경을 조성해 줍니다. ⛳\n")
            }
        } else {
            sb.append("- **공략 추천:** 파3 숏홀입니다. 버디 존 도달을 위해 바람 저항에 유의하시고, 핀 앞쪽 샌드 벙커를 무조건 클리어할 수 있는 여유 있는 클럽 선택이 핵심입니다.\n")
        }
        sb.append("\n")

        sb.append("🔍 **2. 최근 스윙 밀착 피드백**\n")
        if (lastLog != null) {
            sb.append("- **마지막 사용 클럽:** ${lastLog.clubName}\n")
            sb.append("- **정타율(Smash Factor):** **${String.format("%.2f", lastLog.smashFactor)}** ")
            val expectedSmash = if (lastLog.clubName.contains("드라이버")) 1.45f else 1.32f
            if (lastLog.smashFactor >= expectedSmash) {
                sb.append("(최고 수준 정면 임팩트! 👏)\n")
            } else {
                sb.append("(정하점 편차 있음. 클럽 페이스 중앙 집중 요망 🎯)\n")
            }
            
            sb.append("- **구질 진단:** ")
            when (lastLog.resultDirection) {
                "Slice" -> sb.append("⚠️ **우측 슬라이스 구질 편향 발견**\n  - 임팩트 순간 페이스가 열리거나 아웃-인(Out-to-In) 스윙 궤적으로 들어올 때 나타납니다. 양손 패스를 좀 더 앞에서 가져가고 피니시까지 턱을 잡은 상태로 어깨 회전을 원활히 이끌어보세요. 힘 전달력이 극대화됩니다!\n")
                "Hook" -> sb.append("⚠️ **좌측 훅 구질 편향 발견**\n  - 손목의 롤오버가 너무 빨라 클럽이 급격히 닫혔습니다. 백스윙 시 척추 각도를 올바르게 홀딩하고 손목 사용을 자제해 페이스 스퀘어 정렬을 길게 타격해 나가세요!\n")
                else -> sb.append("✅ **스트레이트 탑볼 완성!**\n  - 궤도와 스피드가 완벽히 동조되어 이상적인 탄도와 우수한 런을 기록하였습니다. 현재 퍼포먼스 리듬감을 어드레스에 고정하십시오.\n")
            }
        } else {
            sb.append("- 아직 스윙 기록이 없습니다. 티샷이나 야외 연습장에서 스윙 속도와 발사각을 기입해 주시면 정밀한 정타율 계산과 구질 교정 패스를 생성해 드리겠습니다! 📊\n")
        }
        sb.append("\n")

        sb.append("📊 **3. 추천 클럽 포트폴리오**\n")
        val remaining = hole.distanceYards - driverDist
        sb.append("- 티샷을 우수한 드라이버로 공략 시 예상 남은 거리: **${remaining.toInt()}야드**\n")
        
        // Find best club matching this remaining yardage
        val recommendedClub = clubList.minByOrNull { Math.abs(it.averageCarryDistance - remaining) }
        if (recommendedClub != null && remaining > 30) {
            sb.append("- **세컨 샷 최적 기종:** **${recommendedClub.name}** (회원님 평균 비거리: ${recommendedClub.averageCarryDistance.toInt()}yd)로 제동 거리를 적절히 확보한 원풋 온그린 시도가 유리합니다. 👍")
        } else if (remaining <= 30 && remaining > 5) {
            sb.append("- **세컨 샷 최적 기종:** **샌드 웨지(SW)** 또는 **피칭 웨지(PW)**를 활용하여 가벼운 러닝 어프로치나 로브샷 칩인 버디를 도모해 보십시오.")
        } else {
            sb.append("- **세컨 샷 최적 기종:** 어프로치 숏 게임 단계로 가볍게 끊어가는 아이언을 선택하세요.")
        }
        
        return sb.toString()
    }

    fun generateAiClubSuggestion(distancePin: Int, unit: String, windSpeed: Double, windDirection: String) {
        _isSuggestingClub.value = true
        _clubSuggestionError.value = null
        _aiClubSuggestion.value = ""

        viewModelScope.launch {
            val clubSummary = clubs.value.filter { it.averageCarryDistance > 0 }.joinToString("\n") {
                "- ${it.name}: 평균정사거리 ${it.averageCarryDistance.toInt()} $unit"
            }

            val apiKey = BuildConfig.GEMINI_API_KEY
            if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") {
                // If API key is not set, use localized high-quality mock data with delay
                kotlinx.coroutines.delay(1200)
                _aiClubSuggestion.value = computeLocalClubSuggestionHeuristic(distancePin, unit, windSpeed, windDirection, clubs.value)
                _isSuggestingClub.value = false
                return@launch
            }

            try {
                val prompt = """
                    You are an elite, professional PGA Tour Caddie advising a golfer on their club selection under exact weather conditions.
                    
                    ⛳ [Current Shot Details]
                    - Remaining Distance to Pin: $distancePin $unit
                    - Current Wind Speed: $windSpeed m/s
                    - Current Wind Direction: $windDirection (e.g. NW, SE, N, etc.)
                    
                    🎒 [Golfer's Bag Specifications]
                    $clubSummary
                    
                    Analyze the parameters precisely:
                    1. Wind Effect: Calculate the physics adjustment. Headwind (N elements) increases the effective playing distance by ~1.3 yards per m/s of wind speed. Tailwind (S elements) decreases it by ~0.9 yards per m/s. Crosswinds create lateral drift.
                    2. Recommended Club: Select the single best club from the golfer's bag that matches this "Play-As / Adjusted" distance.
                    3. Tactical Advice: Give structured recommendations in Korean language:
                       - 🎯 **AI 최적 클럽 제안 (Recommended Club)**: Clear primary choice with adjusted "Play-As" distance estimation.
                       - 🌬️ **바람 및 환경 물리 분석 (Environment Analysis)**: Briefly state how much distance to add/subtract due to wind.
                       - 🛡️ **상황별 차선 및 대안 공략책 (Safe vs. Aggressive Option)**: Offers a safe layup/shorter club option or an aggressive high-spin fly option depending on distance.
                    
                    Respond in polite professional Korean language, using friendly tone, formatting nicely with markdown bulletins, bold text, and emojis for layout scanning.
                """.trimIndent()

                val response = withContext(Dispatchers.IO) {
                    val request = GeminiRequest(
                        contents = listOf(
                            GeminiContent(parts = listOf(GeminiPart(text = prompt)))
                        )
                    )
                    RetrofitClient.service.generateContent(apiKey, request)
                }

                val aiText = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                if (aiText != null) {
                    _aiClubSuggestion.value = aiText
                } else {
                    _aiClubSuggestion.value = "AI 클럽 분석 서비스의 응답이 비어있습니다. 잠시 후 재생성 버튼을 눌러주세요."
                }
            } catch (e: Exception) {
                e.printStackTrace()
                _clubSuggestionError.value = e.localizedMessage
                _aiClubSuggestion.value = "⚠️ [API 네트워크 오류로 인한 캐시 전환] \n" + computeLocalClubSuggestionHeuristic(distancePin, unit, windSpeed, windDirection, clubs.value)
            } finally {
                _isSuggestingClub.value = false
            }
        }
    }

    private fun computeLocalClubSuggestionHeuristic(
        distancePin: Int,
        unit: String,
        windSpeed: Double,
        windDirection: String,
        clubList: List<Club>
    ): String {
        val dir = windDirection.uppercase()
        var windImpactText = ""
        var adjustedDist = distancePin.toDouble()

        if (dir.contains("N")) { // Headwind
            val addYds = windSpeed * 1.3
            adjustedDist += addYds
            windImpactText = "앞바람(${dir}) 영향으로 볼이 밀릴 수 있습니다. 실거리 대비 약 +${addYds.toInt()}${unit} 길게 치셔야 합니다."
        } else if (dir.contains("S")) { // Tailwind
            val subYds = windSpeed * 0.9
            adjustedDist -= subYds
            windImpactText = "뒷바람(${dir})의 지원으로 볼 비거리가 증가합니다. 실거리 대비 약 -${subYds.toInt()}${unit} 짧게 공략하십시오."
        } else { // Side wind
            windImpactText = "옆바람(${dir}) 영향으로 좌우 편차가 늘어납니다. 비거리 가감은 크게 없으나, 에이밍 우측/좌측 정렬을 조율하세요."
        }

        val bestClub = clubList.filter { it.averageCarryDistance > 0 }
            .minByOrNull { Math.abs(it.averageCarryDistance - adjustedDist) }

        val sb = StringBuilder()
        sb.append("🤖 **AI 캐디의 맞춤형 실시간 클럽 분석 리포트**\n\n")
        sb.append("📊 **현재 상공 환경 진단:**\n")
        sb.append("- 핀까지 남은 실거리: **${distancePin}${unit}**\n")
        sb.append("- 바람 변수: 초속 **${windSpeed} m/s** (${dir})\n")
        sb.append("- ${windImpactText}\n\n")

        sb.append("🎯 **1. AI 최적 추천 클럽:**\n")
        if (bestClub != null) {
            sb.append("- 추천 클럽: 🏆 **${bestClub.name}**\n")
            sb.append("- 클럽 평균 정사거리: **${bestClub.averageCarryDistance.toInt()}${unit}**\n")
            sb.append("- 공략 어드바이스: 조준 비거리를 **${adjustedDist.toInt()}${unit}**로 설정한 후, 컴팩트한 비거리 메커니즘 템포로 일관된 탑볼 피니시를 노려 임팩트 하십시오. 😉\n\n")

            // Safe & aggressive options
            val safeClub = clubList.filter { it.averageCarryDistance > 0 }
                .find { it.averageCarryDistance < bestClub.averageCarryDistance }
            val aggClub = clubList.filter { it.averageCarryDistance > 0 }
                .find { it.averageCarryDistance > bestClub.averageCarryDistance }

            sb.append("🛡️ **2. 상황별 대체 공략안:**\n")
            if (safeClub != null) {
                sb.append("- **안전한 레이업 코스(Safe Option):** 그린 앞 해저드나 벙커가 두렵다면 **${safeClub.name}**(평균 ${safeClub.averageCarryDistance.toInt()}${unit})로 한 템포 부드럽게 그린 입구 중앙을 지향하여 안정적인 투펏 파를 에스코트하세요.\n")
            }
            if (aggClub != null) {
                sb.append("- **강력한 핀 직공략(Aggressive Option):** 핀 뒤쪽에 충분한 여유 공간(그린 데드존)이 확보되어 있다면 **${aggClub.name}**(평균 ${aggClub.averageCarryDistance.toInt()}${unit})로 살짝 길게 날려 런으로 직접 백스핀 핀 오케이를 이끌어내십시오.\n")
            } else {
                sb.append("- **강력한 핀 직공략:** 백스윙 탑 높이를 조금 높여 에너지를 가득 불어넣은 풀스윙 메커니즘을 적용하십시오.\n")
            }
        } else {
            sb.append("- 백(Bag) 내에 활성화된 평균 비거리 클럽이 존재하지 않습니다. 마이 클럽 리스트 탭에서 사용 중인 클럽과 정사거리를 먼저 등록해주신 후 보이스 캐디와 인공지능 추천을 호출해보세요!")
        }

        return sb.toString()
    }
}

// Immutable helper class representing relative yardage layout
data class HoleDistances(
    val playerToTarget: Int,
    val targetToGreen: Int,
    val playerToGreen: Int
)

class CaddyViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(CaddyViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return CaddyViewModel(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
