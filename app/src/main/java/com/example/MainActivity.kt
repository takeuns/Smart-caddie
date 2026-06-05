package com.example

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Toast
import java.util.Locale
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.example.data.*
import com.example.ui.CaddyViewModel
import com.example.ui.CaddyViewModelFactory
import com.example.ui.HoleDistances
import com.example.ui.RoundDriveTrend
import com.example.ui.theme.*
import com.google.android.gms.location.*

class MainActivity : ComponentActivity() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private val viewModel: CaddyViewModel by viewModels { CaddyViewModelFactory(application) }

    private var speechRecognizer: SpeechRecognizer? = null
    private var textToSpeech: TextToSpeech? = null

    private val requestAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            initSpeechRecognizer()
            viewModel.setVoiceCaddyEnabled(true)
            startVoiceCaddyListening()
        } else {
            Toast.makeText(this, "마이크 및 음성 명령 기능을 사용하려면 오디오 녹음 권한 승인이 필요합니다.", Toast.LENGTH_SHORT).show()
            viewModel.setVoiceCaddyEnabled(false)
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
        if (fineGranted || coarseGranted) {
            startLocationUpdates(viewModel.gpsUpdateInterval.value)
        } else {
            Toast.makeText(this, "실시간 GPS 기능을 활성화하려면 위치 권한 승인이 필요합니다.", Toast.LENGTH_SHORT).show()
            viewModel.setGpsLiveMode(false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                for (location in locationResult.locations) {
                    viewModel.updateLiveLocation(location.latitude, location.longitude)
                }
            }
        }

        lifecycleScope.launch {
            viewModel.gpsLiveMode.collect { live ->
                if (live) {
                    val interval = viewModel.gpsUpdateInterval.value
                    startLocationUpdates(interval)
                } else {
                    stopLocationUpdates()
                }
            }
        }

        lifecycleScope.launch {
            viewModel.gpsUpdateInterval.collect { interval ->
                if (viewModel.gpsLiveMode.value) {
                    startLocationUpdates(interval)
                }
            }
        }

        initTextToSpeech()

        lifecycleScope.launch {
            viewModel.voiceCaddyEnabled.collect { enabled ->
                if (enabled) {
                    startVoiceCaddyListening()
                } else {
                    stopVoiceCaddyListening()
                }
            }
        }

        setContent {
            CaddyTheme {
                MainAppScreen(
                    viewModel = viewModel,
                    onRequestPermissions = {
                        requestPermissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION
                            )
                        )
                    },
                    onToggleVoiceCaddy = { enable ->
                        if (enable) {
                            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                viewModel.setVoiceCaddyEnabled(true)
                            } else {
                                requestAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        } else {
                            viewModel.setVoiceCaddyEnabled(false)
                        }
                    }
                )
            }
        }
    }

    private fun startLocationUpdates(intervalMs: Long) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            stopLocationUpdates()
            
            val locationRequest = LocationRequest.create().apply {
                interval = intervalMs
                fastestInterval = (intervalMs / 2).coerceAtLeast(1000L)
                priority = LocationRequest.PRIORITY_HIGH_ACCURACY
            }
            try {
                fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
            } catch (e: SecurityException) {
                // Safeguard against OS revokes
            }
        }
    }

    private fun stopLocationUpdates() {
        try {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        } catch (e: Exception) {
            // Safe removal
        }
    }

    override fun onResume() {
        super.onResume()
        if (viewModel.gpsLiveMode.value) {
            startLocationUpdates(viewModel.gpsUpdateInterval.value)
        }
    }

    override fun onPause() {
        super.onPause()
        stopLocationUpdates()
    }

    private fun initTextToSpeech() {
        if (textToSpeech == null) {
            textToSpeech = TextToSpeech(this) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    textToSpeech?.language = Locale.KOREA
                }
            }
        }
    }

    private fun initSpeechRecognizer() {
        if (speechRecognizer == null) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        viewModel.setVoiceState("LISTENING")
                    }

                    override fun onBeginningOfSpeech() {
                        viewModel.setVoiceState("LISTENING")
                    }

                    override fun onRmsChanged(rmsdB: Float) {}

                    override fun onBufferReceived(buffer: ByteArray?) {}

                    override fun onEndOfSpeech() {
                        viewModel.setVoiceState("PROCESSING")
                    }

                    override fun onError(error: Int) {
                        if (viewModel.voiceCaddyEnabled.value) {
                            lifecycleScope.launch {
                                kotlinx.coroutines.delay(1200)
                                if (viewModel.voiceCaddyEnabled.value) {
                                    startVoiceCaddyListening()
                                }
                            }
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        if (!matches.isNullOrEmpty()) {
                            val queryText = matches[0]
                            processVoiceCommand(queryText)
                        } else {
                            if (viewModel.voiceCaddyEnabled.value) {
                                startVoiceCaddyListening()
                            }
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {}

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }
        }
    }

    private fun processVoiceCommand(query: String) {
        val queryLower = query.lowercase(Locale.ROOT)
        var response = ""
        val weather = viewModel.currentWeather.value
        val distances = viewModel.getAdjustedDistances()
        val unit = if (viewModel.metricUnitYards.value) "야드" else "미터"

        val isDistanceQuery = queryLower.contains("distance") || queryLower.contains("far") || queryLower.contains("pin") || queryLower.contains("green") ||
                queryLower.contains("거리") || queryLower.contains("핀") || queryLower.contains("남았") || queryLower.contains("깃대") || queryLower.contains("얼마나")
        
        val isWindQuery = queryLower.contains("wind") || queryLower.contains("speed") || queryLower.contains("weather") ||
                queryLower.contains("바람") || queryLower.contains("풍속") || queryLower.contains("날씨") || queryLower.contains("기상")

        val isClubQuery = queryLower.contains("club") || queryLower.contains("suggest") || queryLower.contains("recommend") ||
                queryLower.contains("클럽") || queryLower.contains("아이언") || queryLower.contains("추천") || queryLower.contains("뭐 칠") || queryLower.contains("잡을")

        if (isDistanceQuery) {
            response = "현재 위치에서 핀까지의 실거리는 ${distances.baseDistances.playerToGreen}${unit} 이며, 고도와 바람을 보정한 플레이 애스 거리는 ${distances.playerToGreenPlayAs}${unit} 입니다."
        } else if (isWindQuery) {
            val directionKo = when (weather.windDirection.uppercase()) {
                "N" -> "북풍"
                "S" -> "남풍"
                "E" -> "동풍"
                "W" -> "서풍"
                "NW" -> "북서풍"
                "NE" -> "북동풍"
                "SW" -> "남서풍"
                "SE" -> "남동풍"
                else -> weather.windDirection
            }
            response = "현재 풍속은 초속 ${weather.windSpeedMps} 미터 이며, 풍향은 ${directionKo} 입니다."
        } else if (isClubQuery) {
            val perfectMatches = viewModel.clubs.value.filter { it.averageCarryDistance > 0 }
            val bestFittingClub = perfectMatches.minByOrNull { Math.abs(it.averageCarryDistance - distances.playerToGreenPlayAs) }
            if (bestFittingClub != null) {
                response = "보정된 플레이 애스 비거리 ${distances.playerToGreenPlayAs}${unit}에 적합한 최적의 추천 클럽은 ${bestFittingClub.name} 입니다. 회원님의 평균 캐리 비거리는 ${bestFittingClub.averageCarryDistance.toInt()} ${unit} 입니다."
            } else {
                response = "보정 비거리 ${distances.playerToGreenPlayAs}${unit}에 알맞은 거리를 내는 클럽이 현재 하프백 구성에 존재하지 않습니다. 피칭웨지나 7번 아이언으로 안정적인 레이업 플레이를 제안합니다."
            }
        } else {
            response = "\"${query}\" 라고 말씀하셨군요. 저는 스마트 캐디입니다. '핀까지 거리', '바람 속도', 또는 '추천 클럽'에 대해 질문하시면 즉각 안내해 드립니다."
        }

        viewModel.setVoiceResults(query, response)
        speakAndResume(response)
    }

    private fun speakAndResume(text: String) {
        viewModel.setVoiceState("SPEAKING")
        val params = Bundle()
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "caddy_speech")
        
        textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                runOnUiThread {
                    viewModel.setVoiceState("SPEAKING")
                }
            }

            override fun onDone(utteranceId: String?) {
                runOnUiThread {
                    viewModel.setVoiceState("IDLE")
                    if (viewModel.voiceCaddyEnabled.value) {
                        startVoiceCaddyListening()
                    }
                }
            }

            override fun onError(utteranceId: String?) {
                runOnUiThread {
                    viewModel.setVoiceState("IDLE")
                    if (viewModel.voiceCaddyEnabled.value) {
                        startVoiceCaddyListening()
                    }
                }
            }
        })
        
        textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "caddy_speech")
    }

    private fun startVoiceCaddyListening() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            initSpeechRecognizer()
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toString())
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, Locale.getDefault().toString())
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            try {
                speechRecognizer?.startListening(intent)
                viewModel.setVoiceState("LISTENING")
            } catch (e: Exception) {
                viewModel.setVoiceState("IDLE")
            }
        } else {
            viewModel.setVoiceState("OFF")
        }
    }

    private fun stopVoiceCaddyListening() {
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
        } catch (e: Exception) {}
        viewModel.setVoiceState("OFF")
    }

    override fun onDestroy() {
        super.onDestroy()
        stopVoiceCaddyListening()
        try {
            speechRecognizer?.destroy()
        } catch (e: Exception) {}
        try {
            textToSpeech?.stop()
            textToSpeech?.shutdown()
        } catch (e: Exception) {}
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MainAppScreen(
    viewModel: CaddyViewModel,
    onRequestPermissions: () -> Unit,
    onToggleVoiceCaddy: (Boolean) -> Unit
) {
    val context = LocalContext.current
    var selectedTab by remember { mutableStateOf(0) }

    val courses by viewModel.courses.collectAsStateWithLifecycle()
    val selectedCourse by viewModel.selectedCourse.collectAsStateWithLifecycle()
    val holes by viewModel.holes.collectAsStateWithLifecycle()
    val selectedHole by viewModel.selectedHole.collectAsStateWithLifecycle()

    Scaffold(
        modifier = Modifier.fillMaxSize().testTag("main_scaffold"),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(
                modifier = Modifier.testTag("app_navigation_bar"),
                containerColor = GolfCardBg,
                tonalElevation = 8.dp
            ) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Filled.Map, contentDescription = "코스 맵") },
                    label = { Text("야디지맵", fontWeight = FontWeight.Bold) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = GolfSlateBg,
                        selectedTextColor = GolfGreenPrimary,
                        indicatorColor = GolfGreenPrimary,
                        unselectedIconColor = GolfMuted,
                        unselectedTextColor = GolfMuted
                    ),
                    modifier = Modifier.testTag("tab_course")
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Filled.SportsGolf, contentDescription = "스윙 AI") },
                    label = { Text("스윙분석", fontWeight = FontWeight.Bold) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = GolfSlateBg,
                        selectedTextColor = GolfGreenPrimary,
                        indicatorColor = GolfGreenPrimary,
                        unselectedIconColor = GolfMuted,
                        unselectedTextColor = GolfMuted
                    ),
                    modifier = Modifier.testTag("tab_swing")
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Filled.WorkOutline, contentDescription = "클럽") },
                    label = { Text("내 가방", fontWeight = FontWeight.Bold) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = GolfSlateBg,
                        selectedTextColor = GolfGreenPrimary,
                        indicatorColor = GolfGreenPrimary,
                        unselectedIconColor = GolfMuted,
                        unselectedTextColor = GolfMuted
                    ),
                    modifier = Modifier.testTag("tab_clubs")
                )
                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    icon = { Icon(Icons.Filled.History, contentDescription = "기록") },
                    label = { Text("라운드분석", fontWeight = FontWeight.Bold) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = GolfSlateBg,
                        selectedTextColor = GolfGreenPrimary,
                        indicatorColor = GolfGreenPrimary,
                        unselectedIconColor = GolfMuted,
                        unselectedTextColor = GolfMuted
                    ),
                    modifier = Modifier.testTag("tab_report")
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Top App Bar
            CaddyTopAppBar(viewModel = viewModel)

            // Dynamic Tabs Content
            AnimatedContent(
                targetState = selectedTab,
                transitionSpec = {
                    fadeIn() togetherWith fadeOut()
                },
                modifier = Modifier.weight(1f).fillMaxWidth(),
                label = "TabTransition"
            ) { targetTab ->
                when (targetTab) {
                    0 -> CourseGuideScreen(
                        viewModel = viewModel,
                        courses = courses,
                        selectedCourse = selectedCourse,
                        holes = holes,
                        selectedHole = selectedHole,
                        onRequestPermissions = onRequestPermissions,
                        onToggleVoiceCaddy = onToggleVoiceCaddy
                    )
                    1 -> SwingAnalyzerScreen(
                        viewModel = viewModel
                    )
                    2 -> MyClubsScreen(
                        viewModel = viewModel
                    )
                    3 -> RoundAnalysisScreen(
                        viewModel = viewModel
                    )
                }
            }
        }
    }
}

@Composable
fun CaddyTopAppBar(viewModel: CaddyViewModel) {
    val metricUnitYards by viewModel.metricUnitYards.collectAsStateWithLifecycle()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(GolfSlateBg)
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(GolfGreenPrimary, GolfGreenSecondary))),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.SportsGolf,
                    contentDescription = "Golf Logo",
                    tint = GolfSlateBg,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    text = "스마트캐디 (Smart Caddy)",
                    color = GolfSoftWhite,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "AI 실시간 라운딩 파트너",
                    color = GolfMuted,
                    fontSize = 11.sp
                )
            }
        }

        // Yards / Meters unit toggle
        Button(
            onClick = { viewModel.toggleUnit() },
            colors = ButtonDefaults.buttonColors(containerColor = GolfCardBg),
            border = BorderStroke(1.dp, GolfCardBorder),
            shape = RoundedCornerShape(12.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            modifier = Modifier.height(34.dp).testTag("unit_toggle_button")
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.SyncAlt,
                    contentDescription = "단위 변경",
                    tint = GolfGreenPrimary,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (metricUnitYards) "METER 전환 (yd)" else "YARD 전환 (m)",
                    color = GolfSoftWhite,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

// --- HELPER COMPOSABLE FOR AI MARKDOWN RENDERING ---
@Composable
fun CaddyMarkdownText(text: String) {
    val lines = text.split("\n")
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        lines.forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
            } else if (trimmed.startsWith("#") || trimmed.startsWith("🤖") || trimmed.startsWith("📊") || trimmed.startsWith("🎯") || trimmed.startsWith("🛡️")) {
                val cleanHeading = trimmed.replace("###", "").replace("##", "").replace("#", "").trim()
                Text(
                    text = cleanHeading,
                    color = GolfGreenPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                )
            } else if (trimmed.startsWith("-") || trimmed.startsWith("*")) {
                val cleanItem = trimmed.substring(1).trim()
                Row(modifier = Modifier.fillMaxWidth().padding(start = 6.dp)) {
                    Text(text = "•", color = GolfGreenPrimary, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 6.dp))
                    Text(
                        text = cleanItem,
                        color = GolfSoftWhite,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            } else {
                Text(
                    text = trimmed,
                    color = GolfSoftWhite.copy(alpha = 0.85f),
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.Normal
                )
            }
        }
    }
}

// --- SCREEN 1: COURSE GUIDE SCREEN ---
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CourseGuideScreen(
    viewModel: CaddyViewModel,
    courses: List<GolfCourse>,
    selectedCourse: GolfCourse?,
    holes: List<GolfHole>,
    selectedHole: GolfHole?,
    onRequestPermissions: () -> Unit,
    onToggleVoiceCaddy: (Boolean) -> Unit
) {
    val gpsLiveMode by viewModel.gpsLiveMode.collectAsStateWithLifecycle()
    val batterySaverMode by viewModel.batterySaverMode.collectAsStateWithLifecycle()
    val isMovingBetweenHoles by viewModel.isMovingBetweenHoles.collectAsStateWithLifecycle()
    val gpsUpdateInterval by viewModel.gpsUpdateInterval.collectAsStateWithLifecycle()
    val simulatedProgress by viewModel.simulatedProgress.collectAsStateWithLifecycle()
    val targetPosition by viewModel.targetPosition.collectAsStateWithLifecycle()
    val liveLocation by viewModel.liveLocation.collectAsStateWithLifecycle()
    val metricUnitYards by viewModel.metricUnitYards.collectAsStateWithLifecycle()
    val clubs by viewModel.clubs.collectAsStateWithLifecycle()
    val currentWeather by viewModel.currentWeather.collectAsStateWithLifecycle()

    val voiceCaddyEnabled by viewModel.voiceCaddyEnabled.collectAsStateWithLifecycle()
    val voiceState by viewModel.voiceState.collectAsStateWithLifecycle()
    val lastVoiceQuery by viewModel.lastVoiceQuery.collectAsStateWithLifecycle()
    val lastVoiceResponse by viewModel.lastVoiceResponse.collectAsStateWithLifecycle()

    val isSuggestingClub by viewModel.isSuggestingClub.collectAsStateWithLifecycle()
    val aiClubSuggestion by viewModel.aiClubSuggestion.collectAsStateWithLifecycle()
    val clubSuggestionError by viewModel.clubSuggestionError.collectAsStateWithLifecycle()

    val ballLocation by viewModel.ballLocation.collectAsStateWithLifecycle()
    val ballMetrics = viewModel.getBallTrackerMetrics()

    var showCourseSelectDialog by remember { mutableStateOf(false) }

    // Re-verify remaining yards and recommendations with dynamic Play-As adjustments
    val adjustedDistances = viewModel.getAdjustedDistances()
    val distances = adjustedDistances.baseDistances
    val unitStr = if (metricUnitYards) "yd" else "m"

    LaunchedEffect(selectedHole, currentWeather.windSpeedMps, currentWeather.windDirection, metricUnitYards) {
        if (selectedHole != null) {
            viewModel.generateAiClubSuggestion(
                adjustedDistances.playerToGreenPlayAs,
                unitStr,
                currentWeather.windSpeedMps,
                currentWeather.windDirection
            )
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Course & Hole selector row
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = GolfCardBg),
                border = BorderStroke(1.dp, GolfCardBorder),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "현재 플레이 중인 코스",
                                color = GolfMuted,
                                fontSize = 11.sp
                            )
                            Text(
                                text = selectedCourse?.name ?: "불러오는 중...",
                                color = GolfSoftWhite,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Button(
                            onClick = { showCourseSelectDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = GolfGreenSecondary),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.testTag("select_course_button")
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.GolfCourse, contentDescription = "코스 변경", tint = GolfSlateBg, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("코스 선택", color = GolfSlateBg, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Weather details matching Sleek Interface Design
                    if (currentWeather.error != null) {
                        Text(
                            text = currentWeather.error ?: "",
                            color = GolfError,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (currentWeather.isFetching) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(12.dp),
                                    color = GolfGreenPrimary,
                                    strokeWidth = 1.5.dp
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "실시간 날씨 정보 검색 중...",
                                    color = GolfMuted,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Normal
                                )
                            }
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Thermostat,
                                    contentDescription = "기온",
                                    tint = GolfGreenPrimary,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "${currentWeather.temperatureCelsius}°C",
                                    color = GolfSoftWhite,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Air,
                                    contentDescription = "바람",
                                    tint = GolfGreenPrimary,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "${currentWeather.windSpeedMps}m/s ${currentWeather.windDirection}",
                                    color = GolfSoftWhite,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Cloud,
                                    contentDescription = "기상 상태",
                                    tint = GolfGreenPrimary,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = currentWeather.description,
                                    color = GolfMuted,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }

                        Spacer(modifier = Modifier.weight(1f))
                        
                        IconButton(
                            onClick = { selectedCourse?.let { viewModel.fetchWeatherForCourse(it) } },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "날씨 실시간 새로고침",
                                tint = GolfGreenPrimary,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    Divider(color = GolfCardBorder)
                    Spacer(modifier = Modifier.height(10.dp))

                    if (holes.isNotEmpty()) {
                        Text(
                            text = "홀 선택 (Hole Select)",
                            color = GolfSoftWhite,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            holes.forEach { hole ->
                                OutlinedButton(
                                    onClick = { viewModel.selectHole(hole) },
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        containerColor = if (selectedHole?.id == hole.id) GolfGreenDark else Color.Transparent,
                                        contentColor = if (selectedHole?.id == hole.id) GolfGreenPrimary else GolfMuted
                                    ),
                                    border = BorderStroke(
                                        2.dp,
                                        if (selectedHole?.id == hole.id) GolfGreenPrimary else GolfCardBorder
                                    ),
                                    shape = RoundedCornerShape(12.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                    modifier = Modifier.testTag("hole_btn_${hole.holeNumber}")
                                ) {
                                    Text(
                                        text = "${hole.holeNumber}H (Par ${hole.par})",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Active Hole Summary & Live GPS toggler with ECO Battery Saver
        item {
            selectedHole?.let { hole ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(GolfCardBg.copy(alpha = 0.5f))
                        .border(1.dp, GolfCardBorder, RoundedCornerShape(18.dp))
                        .padding(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "${hole.holeNumber}번 홀",
                                    color = GolfSoftWhite,
                                    fontSize = 21.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Badge(containerColor = GolfGreenPrimary, contentColor = GolfSlateBg) {
                                    Text("Par ${hole.par}", fontWeight = FontWeight.ExtraBold, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp))
                                }
                            }
                            Text(
                                text = "챔피언티 전장: ${hole.distanceYards}yd (${(hole.distanceYards * 0.9144).toInt()}m)",
                                color = GolfMuted,
                                fontSize = 12.sp
                            )
                        }

                        // Live GPS Toggle Switch
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .background(GolfSlateBg)
                                .border(1.dp, GolfCardBorder.copy(alpha = 0.7f), RoundedCornerShape(14.dp))
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                imageVector = if (gpsLiveMode) Icons.Default.GpsFixed else Icons.Default.GpsNotFixed,
                                contentDescription = "GPS 모드",
                                tint = if (gpsLiveMode) GolfGreenPrimary else GolfMuted,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "실시간 GPS",
                                color = if (gpsLiveMode) GolfSoftWhite else GolfMuted,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Switch(
                                checked = gpsLiveMode,
                                onCheckedChange = { isChecked ->
                                    if (isChecked) {
                                        onRequestPermissions()
                                        viewModel.setGpsLiveMode(true)
                                    } else {
                                        viewModel.setGpsLiveMode(false)
                                    }
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = GolfSlateBg,
                                    checkedTrackColor = GolfGreenPrimary,
                                    uncheckedThumbColor = GolfMuted,
                                    uncheckedTrackColor = GolfCardBorder
                                ),
                                modifier = Modifier.scale(0.65f).testTag("gps_switch")
                            )
                        }
                    }

                    // ECO Battery Saver Switch Section
                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = GolfCardBorder.copy(alpha = 0.4f), thickness = 1.dp)
                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = if (batterySaverMode) Icons.Default.BatterySaver else Icons.Default.BatteryStd,
                                contentDescription = "배터리 절약 모드",
                                tint = if (batterySaverMode) GolfBunkerGold else GolfMuted,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "GPS 배터리 절약 (Eco Mode)",
                                        color = if (batterySaverMode) GolfSoftWhite else GolfMuted,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    if (batterySaverMode && gpsLiveMode) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(if (isMovingBetweenHoles) GolfGreenPrimary.copy(alpha = 0.2f) else GolfBunkerGold.copy(alpha = 0.2f))
                                                .padding(horizontal = 4.dp, vertical = 1.dp)
                                        ) {
                                            Text(
                                                text = if (isMovingBetweenHoles) "이동 감지 (고주기 4s)" else "대기 절약 (저주기 25s)",
                                                color = if (isMovingBetweenHoles) GolfGreenPrimary else GolfBunkerGold,
                                                fontSize = 8.sp,
                                                fontWeight = FontWeight.ExtraBold
                                            )
                                        }
                                    }
                                }
                                Text(
                                    text = "준비/정지 중 GPS 주기 자동 연장 (4s → 25s)으로 배터리를 보존합니다.",
                                    color = GolfMuted,
                                    fontSize = 9.sp
                                )
                            }
                        }

                        Switch(
                            checked = batterySaverMode,
                            onCheckedChange = { isChecked ->
                                viewModel.setBatterySaverMode(isChecked)
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = GolfSlateBg,
                                checkedTrackColor = GolfBunkerGold,
                                uncheckedThumbColor = GolfMuted,
                                uncheckedTrackColor = GolfCardBorder
                            ),
                            modifier = Modifier.scale(0.65f).testTag("battery_saver_switch")
                        )
                    }
                    
                    // Show current active GPS update rate
                    if (gpsLiveMode) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(if (batterySaverMode && !isMovingBetweenHoles) GolfBunkerGold else GolfGreenPrimary)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "현재 GPS 수신 주기: ${gpsUpdateInterval / 1000}초 간격",
                                color = GolfMuted,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        if (selectedHole != null) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().testTag("voice_command_card"),
                    colors = CardDefaults.cardColors(containerColor = GolfCardBg),
                    border = BorderStroke(1.dp, if (voiceCaddyEnabled) GolfGreenPrimary else GolfCardBorder),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .background(if (voiceCaddyEnabled) GolfGreenDark else GolfSlateBg)
                                        .border(2.dp, if (voiceCaddyEnabled) GolfGreenPrimary else GolfCardBorder, CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    val iconTint = if (voiceCaddyEnabled) {
                                        when (voiceState) {
                                            "LISTENING" -> GolfGreenPrimary
                                            "PROCESSING" -> GolfBunkerGold
                                            "SPEAKING" -> Color(0xFF2196F3)
                                            else -> GolfSoftWhite
                                        }
                                    } else {
                                        GolfMuted
                                    }
                                    
                                    Icon(
                                        imageVector = if (voiceState == "SPEAKING") Icons.Default.VolumeUp else Icons.Default.Mic,
                                        contentDescription = "마이크 상태",
                                        tint = iconTint,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = "보이스 캐디 (Voice Caddy)",
                                        color = GolfSoftWhite,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp
                                    )
                                    Text(
                                        text = when {
                                            !voiceCaddyEnabled -> "핸즈프리 음성 명령 비활성화됨"
                                            voiceState == "LISTENING" -> "음성 명령 대기 중... (듣는 중)"
                                            voiceState == "PROCESSING" -> "명령 분석 중..."
                                            voiceState == "SPEAKING" -> "안내 방송 송출 중..."
                                            else -> "핸즈프리 활성화됨 - 언제든 질문하세요"
                                        },
                                        color = if (voiceCaddyEnabled && voiceState == "LISTENING") GolfGreenPrimary else GolfMuted,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Switch(
                                    checked = voiceCaddyEnabled,
                                    onCheckedChange = { isChecked ->
                                        onToggleVoiceCaddy(isChecked)
                                    },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = GolfGreenPrimary,
                                        checkedTrackColor = GolfGreenDark,
                                        uncheckedThumbColor = GolfMuted,
                                        uncheckedTrackColor = GolfSlateBg,
                                        uncheckedBorderColor = GolfCardBorder
                                    ),
                                    modifier = Modifier.scale(0.85f).testTag("voice_command_switch")
                                )
                            }
                        }

                        if (voiceCaddyEnabled) {
                            Spacer(modifier = Modifier.height(12.dp))
                            
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(GolfSlateBg)
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val stateSymbol = when (voiceState) {
                                    "LISTENING" -> "● LISTENING..."
                                    "PROCESSING" -> "● PROCESSING..."
                                    "SPEAKING" -> "🔊 SPEAKING..."
                                    else -> "🔋 IDLE (Hands-Free Listening)"
                                }
                                val stateColor = when (voiceState) {
                                    "LISTENING" -> GolfGreenPrimary
                                    "PROCESSING" -> GolfBunkerGold
                                    "SPEAKING" -> Color(0xFF2196F3)
                                    else -> GolfMuted
                                }
                                Text(
                                    text = stateSymbol,
                                    color = stateColor,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Black,
                                    textAlign = TextAlign.Center
                                )
                            }

                            if (lastVoiceQuery.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(12.dp))
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(GolfSlateBg.copy(alpha = 0.5f))
                                        .border(1.dp, GolfCardBorder, RoundedCornerShape(12.dp))
                                        .padding(12.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.Top) {
                                        Icon(
                                            imageVector = Icons.Default.Person,
                                            contentDescription = "유저 질문",
                                            tint = GolfMuted,
                                            modifier = Modifier.size(14.dp).padding(top = 1.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "\"$lastVoiceQuery\"",
                                            color = GolfSoftWhite.copy(alpha = 0.9f),
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Row(verticalAlignment = Alignment.Top) {
                                        Icon(
                                            imageVector = Icons.Default.Face,
                                            contentDescription = "캐디 답변",
                                            tint = GolfGreenPrimary,
                                            modifier = Modifier.size(14.dp).padding(top = 1.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = lastVoiceResponse,
                                            color = GolfGreenPrimary,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        Divider(color = GolfCardBorder, thickness = 0.5.dp)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "💡 음성 질문 예시 (Say hands-free commands):",
                            color = GolfMuted,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .weight(1.5f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(GolfSlateBg)
                                    .padding(vertical = 6.dp, horizontal = 10.dp)
                            ) {
                                Text(
                                    text = "🗣️ \"핀까지 얼마나 남았어?\"",
                                    color = GolfSoftWhite,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .weight(1.2f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(GolfSlateBg)
                                    .padding(vertical = 6.dp, horizontal = 10.dp)
                            ) {
                                Text(
                                    text = "🗣️ \"바람 속도 알려줘\"",
                                    color = GolfSoftWhite,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }
        }

        if (selectedHole != null) {
            // INTERACTIVE TACTICAL CANVAS MAP
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(2.dp, GolfCardBorder)
                ) {
                    Box(modifier = Modifier.fillMaxWidth().height(250.dp)) {
                        Canvas(
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(Unit) {
                                    detectTapGestures { offset ->
                                        // Map touch X coordinates to simulated strategic target width fraction
                                        val fx = (offset.x / size.width).coerceIn(0.1f, 0.9f)
                                        val fy = (offset.y / size.height).coerceIn(0.1f, 0.9f)
                                        viewModel.setTargetPosition(fx, fy)
                                    }
                                }
                                .testTag("tactical_golf_canvas")
                        ) {
                            val w = size.width
                            val h = size.height

                            // Draw basic dark background with a beautiful sleek gradient (Rough Area)
                            drawRect(
                                brush = Brush.linearGradient(
                                    colors = listOf(
                                        GolfRoughGreen,
                                        Color(0xFF386641),
                                        GolfFairwayGreen
                                    )
                                )
                            )

                            // Construct a smooth organic curve representing the Fairway
                            val fairwayPath = Path().apply {
                                moveTo(0f, h * 0.5f)
                                cubicTo(
                                    w * 0.25f, h * 0.35f,
                                    w * 0.55f, h * 0.65f,
                                    w * 0.9f, h * 0.5f
                                )
                                lineTo(w * 0.9f, h * 0.56f)
                                cubicTo(
                                    w * 0.55f, h * 0.72f,
                                    w * 0.25f, h * 0.44f,
                                    0f, h * 0.56f
                                )
                                close()
                            }
                            drawPath(path = fairwayPath, color = GolfFairwayGreen)

                            // Green drawing (at 88% X space)
                            val greenCenterX = w * 0.88f
                            val greenCenterY = h * 0.5f
                            val greenRadius = 28.dp.toPx()
                            drawCircle(
                                color = GolfGreenPrimary,
                                radius = greenRadius,
                                center = Offset(greenCenterX, greenCenterY)
                            )
                            // Draw flag stick
                            drawLine(
                                color = GolfSoftWhite,
                                start = Offset(greenCenterX, greenCenterY + 10f),
                                end = Offset(greenCenterX, greenCenterY - 45f),
                                strokeWidth = 3f
                            )
                            // Draw red flag triangular path
                            val flagPath = Path().apply {
                                moveTo(greenCenterX, greenCenterY - 45f)
                                lineTo(greenCenterX - 22f, greenCenterY - 35f)
                                lineTo(greenCenterX, greenCenterY - 25f)
                                close()
                            }
                            drawPath(path = flagPath, color = GolfRedPin)

                            // Sand bunkers
                            val sandRadius = 14.dp.toPx()
                            // Bunker 1: Near the green
                            drawCircle(
                                color = GolfBunkerGold,
                                radius = sandRadius,
                                center = Offset(w * 0.82f, h * 0.38f)
                            )
                            // Bunker 2: Side of the fairway
                            drawCircle(
                                color = GolfBunkerGold,
                                radius = sandRadius * 0.8f,
                                center = Offset(w * 0.52f, h * 0.33f)
                            )

                            // Water hazard (circular lake at bottom halfway)
                            drawCircle(
                                color = GolfWaterBlue,
                                radius = 22.dp.toPx(),
                                center = Offset(w * 0.45f, h * 0.68f)
                            )

                            // Tee Box marking (at 8% X)
                            drawCircle(
                                color = GolfSlateBg,
                                radius = 10.dp.toPx(),
                                center = Offset(w * 0.08f, h * 0.5f)
                            )
                            drawCircle(
                                color = GolfSoftWhite,
                                radius = 8.dp.toPx(),
                                center = Offset(w * 0.08f, h * 0.5f),
                                style = Stroke(width = 2.dp.toPx())
                            )

                            // Player position marker based on either Live GPS or Simulator slider progress
                            val playerX: Float
                            val playerY: Float

                            if (gpsLiveMode && liveLocation != null) {
                                // Maps latitude fractional offsets to canvas coordinates nicely
                                val latDiff = selectedHole.greenLatitude - selectedHole.teeLatitude
                                val lngDiff = selectedHole.greenLongitude - selectedHole.teeLongitude
                                val platDiff = liveLocation!!.first - selectedHole.teeLatitude
                                val plngDiff = liveLocation!!.second - selectedHole.teeLongitude

                                val rawProgX = if (latDiff != 0.0) (platDiff / latDiff).toFloat() else 0.5f
                                playerX = (0.08f + (0.8f * rawProgX)).coerceIn(0.08f, 0.88f) * w
                                playerY = h * 0.5f // Simplify vertical mapping for stability
                            } else {
                                // Simulated progress interpolation
                                val progressVal = simulatedProgress
                                playerX = (0.08f + (0.8f * progressVal)) * w
                                playerY = h * 0.5f
                            }

                            // Blue player ball marker
                            drawCircle(
                                color = GolfWaterBlue,
                                radius = 10.dp.toPx(),
                                center = Offset(playerX, playerY),
                                style = Stroke(width = 3.dp.toPx())
                            )
                            drawCircle(
                                color = GolfSoftWhite,
                                radius = 5.dp.toPx(),
                                center = Offset(playerX, playerY)
                            )

                            // Draw marked ball if present
                            if (ballLocation != null) {
                                val latDiff = selectedHole.greenLatitude - selectedHole.teeLatitude
                                val platDiff = ballLocation!!.first - selectedHole.teeLatitude
                                val rawProgX = if (latDiff != 0.0) (platDiff / latDiff).toFloat() else 0.5f
                                val ballX = (0.08f + (0.8f * rawProgX)).coerceIn(0.08f, 0.88f) * w
                                val ballY = h * 0.42f // Offset slightly up to avoid direct overlapping with player

                                // Dotted path from Ball to green
                                drawLine(
                                    color = GolfBunkerGold.copy(alpha = 0.8f),
                                    start = Offset(ballX, ballY),
                                    end = Offset(greenCenterX, greenCenterY),
                                    strokeWidth = 3f,
                                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
                                )

                                // Dotted path from Player to Ball
                                drawLine(
                                    color = GolfWaterBlue.copy(alpha = 0.8f),
                                    start = Offset(playerX, playerY),
                                    end = Offset(ballX, ballY),
                                    strokeWidth = 3f,
                                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(11f, 11f), 0f)
                                )

                                // Orange golf ball marker
                                drawCircle(
                                    color = Color(0xFFFF9800),
                                    radius = 11.dp.toPx(),
                                    center = Offset(ballX, ballY),
                                    style = Stroke(width = 3.dp.toPx())
                                )
                                drawCircle(
                                    color = Color.White,
                                    radius = 6.dp.toPx(),
                                    center = Offset(ballX, ballY)
                                )
                            }

                            // Target Pin layout based on tap coordinates
                            val targetCanvasX = targetPosition.first * w
                            val targetCanvasY = targetPosition.second * h

                            // Flight trails dashed lines (1. player to target, 2. target to green)
                            // We construct a simple visual solid line guide
                            drawLine(
                                color = Color.White.copy(alpha = 0.5f),
                                start = Offset(playerX, playerY),
                                end = Offset(targetCanvasX, targetCanvasY),
                                strokeWidth = 3f
                            )
                            drawLine(
                                color = Color.White.copy(alpha = 0.5f),
                                start = Offset(targetCanvasX, targetCanvasY),
                                end = Offset(greenCenterX, greenCenterY),
                                strokeWidth = 3f
                            )

                            // Concentric target crosshairs
                            drawCircle(
                                color = GolfGreenPrimary,
                                radius = 12.dp.toPx(),
                                center = Offset(targetCanvasX, targetCanvasY),
                                style = Stroke(width = 2.dp.toPx())
                            )
                            drawCircle(
                                color = GolfGreenPrimary,
                                radius = 4.dp.toPx(),
                                center = Offset(targetCanvasX, targetCanvasY)
                            )
                        }

                        // Floating Canvas UI badges overlay
                        Row(
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(10.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(GolfSlateBg.copy(alpha = 0.8f))
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(Color.White))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("비거리 과녘 터치 커스텀 조준 가능", color = GolfSoftWhite, fontSize = 9.sp)
                        }

                        // Floating glassmorphic stats overlay matching Sleek Interface Design HTML with weather support
                        Column(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(10.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color.Black.copy(alpha = 0.55f))
                                .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(10.dp))
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                "실거리 (ACTUAL)",
                                color = Color.White.copy(alpha = 0.6f),
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.3.sp
                            )
                            Text(
                                "${distances.playerToGreen} $unitStr",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                "보정거리 (PLAY-AS)",
                                color = GolfBunkerGold.copy(alpha = 0.9f),
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.3.sp
                            )
                            Text(
                                "${adjustedDistances.playerToGreenPlayAs} $unitStr",
                                color = GolfBunkerGold,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                            val elevDirection = if (currentWeather.elevationChangeMeters >= 0) "+" else ""
                            Text(
                                "높낮이 ${elevDirection}${String.format("%.1f", currentWeather.elevationChangeMeters)}m",
                                color = GolfGreenPrimary,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Row(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(10.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(GolfSlateBg.copy(alpha = 0.8f))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = if (gpsLiveMode) "실시간성 위성 링크 활성" else "맵 시뮬레이터 구동 중",
                                color = if (gpsLiveMode) GolfGreenPrimary else GolfBunkerGold,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            // DYNAMIC IN-GAME DISTANCES METRICS
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Metric 1: Distance from current ball location to target Aim box
                    Card(
                        modifier = Modifier.weight(1f),
                        colors = CardDefaults.cardColors(containerColor = GolfCardBg),
                        border = BorderStroke(1.dp, GolfCardBorder)
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp).fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(Icons.Default.Adjust, contentDescription = "타겟 조준", tint = GolfGreenPrimary, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("조준점까지", fontSize = 11.sp, color = GolfMuted)
                            Text(
                                text = "${distances.playerToTarget}$unitStr",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = GolfSoftWhite
                            )
                        }
                    }

                    // Metric 2: Distance from Target to Green flag
                    Card(
                        modifier = Modifier.weight(1f),
                        colors = CardDefaults.cardColors(containerColor = GolfCardBg),
                        border = BorderStroke(1.dp, GolfCardBorder)
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp).fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(Icons.Default.TrendingUp, contentDescription = "잔여 공략", tint = GolfBunkerGold, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("조준점 ➡️ 핀", fontSize = 11.sp, color = GolfMuted)
                            Text(
                                text = "${distances.targetToGreen}$unitStr",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = GolfSoftWhite
                            )
                        }
                    }

                    // Metric 3: Total remaining yards to pin (Crucial!)
                    Card(
                        modifier = Modifier.weight(1.3f),
                        colors = CardDefaults.cardColors(containerColor = GolfGreenDark),
                        border = BorderStroke(1.dp, GolfGreenPrimary)
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp).fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(Icons.Default.Flag, contentDescription = "그린 깃대", tint = GolfGreenPrimary, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("실거리 ➔ 보정거리", fontSize = 10.sp, color = GolfGreenPrimary.copy(alpha = 0.8f))
                            Text(
                                text = "${distances.playerToGreen}$unitStr ➔ ${adjustedDistances.playerToGreenPlayAs}$unitStr",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = GolfSoftWhite.copy(alpha = 0.8f),
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "${adjustedDistances.playerToGreenPlayAs}$unitStr",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Black,
                                color = GolfBunkerGold,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }

            // 📡 REAL-TIME GPS SHOT TRACKER & BALL DISTANCE UTILITY
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().testTag("gps_ball_tracker_card"),
                    colors = CardDefaults.cardColors(containerColor = GolfCardBg),
                    border = BorderStroke(1.dp, if (ballLocation != null) GolfGreenPrimary else GolfCardBorder),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(if (ballLocation != null) GolfGreenDark else GolfSlateBg),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.LocationOn,
                                        contentDescription = "공 추적기 아이콘",
                                        tint = if (ballLocation != null) GolfGreenPrimary else GolfMuted,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = "GPS 샷 비거리 & 볼 트래커",
                                        color = GolfSoftWhite,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp
                                    )
                                    Text(
                                        text = if (ballLocation != null) "실시간 공 위치와 홀(그린) 간 거리 추적 중" else "볼 비거리 및 하프백 실위치 트래킹 비활성",
                                        color = if (ballLocation != null) GolfGreenPrimary else GolfMuted,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        Divider(color = GolfCardBorder, thickness = 0.5.dp)
                        Spacer(modifier = Modifier.height(12.dp))

                        if (ballLocation == null) {
                            // Empty state
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "볼의 최초 타구 위치가 비어 있습니다.\n샷을 한 직후 아래 단추를 눌러 실시간 비거리를 세밀하게 추적해보세요!",
                                    color = GolfMuted,
                                    fontSize = 12.sp,
                                    textAlign = TextAlign.Center,
                                    lineHeight = 16.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Button(
                                    onClick = { viewModel.markBallLocation() },
                                    colors = ButtonDefaults.buttonColors(containerColor = GolfGreenDark),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.fillMaxWidth().testTag("mark_ball_btn")
                                ) {
                                    Icon(Icons.Default.AddLocation, contentDescription = "타구 위치 마킹", tint = GolfGreenPrimary, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("현재 내 위치를 볼 위치로 마킹", color = GolfGreenPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                            }
                        } else {
                            // Active tracking state
                            if (ballMetrics != null) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    // Metric A: Distance from Ball to Hole
                                    Card(
                                        modifier = Modifier.weight(1f),
                                        colors = CardDefaults.cardColors(containerColor = GolfSlateBg),
                                        border = BorderStroke(1.dp, GolfGreenPrimary.copy(alpha = 0.3f))
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(12.dp).fillMaxWidth(),
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            Icon(Icons.Default.Flag, contentDescription = "볼에서 핀까지", tint = GolfGreenPrimary, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text("볼에서 핀까지", fontSize = 10.sp, color = GolfMuted, fontWeight = FontWeight.Bold)
                                            Text(
                                                text = "${ballMetrics.ballToGreenDistance} ${ballMetrics.unit}",
                                                fontSize = 20.sp,
                                                fontWeight = FontWeight.Black,
                                                color = GolfGreenPrimary
                                            )
                                        }
                                    }

                                    // Metric B: Distance from user to Ball (Shot distance!)
                                    Card(
                                        modifier = Modifier.weight(1f),
                                        colors = CardDefaults.cardColors(containerColor = GolfSlateBg),
                                        border = BorderStroke(1.dp, GolfCardBorder)
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(12.dp).fillMaxWidth(),
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            Icon(Icons.Default.DirectionsWalk, contentDescription = "현재 걸어온 비거리", tint = GolfBunkerGold, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text("마크한 볼과의 거리", fontSize = 10.sp, color = GolfMuted, fontWeight = FontWeight.Bold)
                                            Text(
                                                text = "${ballMetrics.userToBallDistance} ${ballMetrics.unit}",
                                                fontSize = 20.sp,
                                                fontWeight = FontWeight.Black,
                                                color = GolfBunkerGold
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(14.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = { viewModel.markBallLocation() },
                                        modifier = Modifier.weight(1f).testTag("remark_ball_btn"),
                                        border = BorderStroke(1.dp, GolfGreenPrimary),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = GolfGreenPrimary),
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Text("볼 위치 재마킹", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                    }

                                    OutlinedButton(
                                        onClick = { viewModel.clearBallLocation() },
                                        modifier = Modifier.weight(1f).testTag("clear_ball_btn"),
                                        border = BorderStroke(1.dp, GolfError.copy(alpha = 0.5f)),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = GolfError),
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Text("마킹 지우기", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // POSITION CHANGER SLIDER (SIMULATION CONTROL) - Visible only when live-gps is false so as not to confuse user
            if (!gpsLiveMode) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = GolfCardBg),
                        border = BorderStroke(1.dp, GolfCardBorder)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.DirectionsWalk, contentDescription = "시뮬레이션", tint = GolfBunkerGold, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("가상 위치 시뮬레이터", color = GolfSoftWhite, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                                Text(
                                    text = if (simulatedProgress < 0.1f) "티 보스 (Tee)" else if (simulatedProgress > 0.9f) "그린 안착 (Green)" else "페어웨이 전도 (${(simulatedProgress * 100).toInt()}%)",
                                    color = GolfGreenPrimary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Slider(
                                value = simulatedProgress,
                                onValueChange = { viewModel.setSimulatedProgress(it) },
                                colors = SliderDefaults.colors(
                                    thumbColor = GolfGreenPrimary,
                                    activeTrackColor = GolfGreenPrimary,
                                    inactiveTrackColor = GolfCardBorder
                                ),
                                modifier = Modifier.testTag("simulator_slider")
                            )
                        }
                    }
                }
            }

            // AUTO-CADDY REALTIME RECOMENDATION BASED ON BAG GAP DISTANCES
            item {
                // Find matching clubs from user's bag that fits best for playerToGreen (distance remaining) with Dynamic Play-As adjustment
                val perfectMatches = clubs.filter { it.averageCarryDistance > 0 }
                val bestFittingClub = perfectMatches.minByOrNull { Math.abs(it.averageCarryDistance - adjustedDistances.playerToGreenPlayAs) }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = GolfGreenSecondary),
                    border = BorderStroke(1.dp, Color(0xFF425E45))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(Color(0xFF143D1F))
                                        .padding(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        "AI CADDIE",
                                        color = Color.White,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        letterSpacing = 0.5.sp
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    "실시간 코스 공략 추천",
                                    color = Color(0xFF00210B),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                if (bestFittingClub != null) {
                                    Text(
                                        text = "${bestFittingClub.name} 어드바이스",
                                        color = Color(0xFF143D1F),
                                        fontSize = 17.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "사용자 비거리 오차범위 평균인 ${bestFittingClub.averageCarryDistance.toInt()}yd를 고려한 정밀 조준점 라인을 발급합니다. 바람 및 높낮이를 감안해 컴팩트하게 조준하십시오.",
                                        color = Color(0xFF00210B).copy(alpha = 0.85f),
                                        fontSize = 12.sp,
                                        lineHeight = 16.sp
                                    )
                                } else {
                                    Text(
                                        text = "피칭 마스터리 가이드 권고",
                                        color = Color(0xFF143D1F),
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "인게임 잔여거리에 알맞는 가방 내 클럽 스펙 정보가 부재합니다. 7번 아이언 혹은 웨지로 정밀 임팩트 위주의 짧고 안전한 레이업 공략을 권고합니다.",
                                        color = Color(0xFF00210B).copy(alpha = 0.85f),
                                        fontSize = 12.sp,
                                        lineHeight = 16.sp
                                    )
                                }
                            }

                            if (bestFittingClub != null) {
                                Spacer(modifier = Modifier.width(10.dp))
                                Box(
                                    modifier = Modifier
                                        .size(72.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Color(0xFF143D1F)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            text = bestFittingClub.name.replace("번 아이언", "I").replace("드라이버", "1W").take(5),
                                            color = GolfGreenPrimary,
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.Black,
                                            textAlign = TextAlign.Center
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = "${bestFittingClub.averageCarryDistance.toInt()}$unitStr",
                                            color = Color.White,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 🤖 AI-POWERED CLUB SUGGESTION CARD UTILITY
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("ai_club_suggestion_card"),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = GolfCardBg),
                    border = BorderStroke(1.dp, GolfCardBorder)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(GolfGreenDark),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Lightbulb,
                                        contentDescription = "AI 클럽 제안",
                                        tint = GolfGreenPrimary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = "AI 실시간 클럽 제안 (Physical Suggestor)",
                                        color = GolfSoftWhite,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp
                                    )
                                    Text(
                                        text = "상공 풍량 초속 ${currentWeather.windSpeedMps}m/s 반영 가이던스",
                                        color = GolfMuted,
                                        fontSize = 11.sp
                                    )
                                }
                            }

                            IconButton(
                                onClick = {
                                    viewModel.generateAiClubSuggestion(
                                        adjustedDistances.playerToGreenPlayAs,
                                        unitStr,
                                        currentWeather.windSpeedMps,
                                        currentWeather.windDirection
                                    )
                                },
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(GolfGreenDark.copy(alpha = 0.5f))
                                    .testTag("ai_club_suggest_refresh_btn")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "클럽 매칭 갱신",
                                    tint = GolfGreenPrimary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        Divider(color = GolfCardBorder, thickness = 0.5.dp)
                        Spacer(modifier = Modifier.height(10.dp))

                        if (isSuggestingClub) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 16.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    color = GolfGreenPrimary,
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = "AI Caddie가 볼 비행 및 탄도 보정 공략을 분석 중...",
                                    color = GolfMuted,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        } else if (clubSuggestionError != null) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "⚠️ 분석 중 일시적인 지연이 발생했습니다.",
                                    color = GolfError,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Button(
                                    onClick = {
                                        viewModel.generateAiClubSuggestion(
                                            adjustedDistances.playerToGreenPlayAs,
                                            unitStr,
                                            currentWeather.windSpeedMps,
                                            currentWeather.windDirection
                                        )
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = GolfGreenDark),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                                ) {
                                    Text("원격 재생성 시도", color = GolfGreenPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        } else {
                            if (aiClubSuggestion.isNotEmpty()) {
                                CaddyMarkdownText(text = aiClubSuggestion)
                            } else {
                                Text(
                                    text = "정보를 계산할 수 없습니다. 상단의 동기화 단추를 눌러주십시오.",
                                    color = GolfMuted,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // --- Course Select Dialog ---
    if (showCourseSelectDialog) {
        Dialog(onDismissRequest = { showCourseSelectDialog = false }) {
            Card(
                colors = CardDefaults.cardColors(containerColor = GolfCardBg),
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, GolfCardBorder),
                modifier = Modifier.fillMaxWidth().padding(16.dp).testTag("course_select_dialog")
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "플레이할 골프장 선택",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = GolfSoftWhite,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )

                    Divider(color = GolfCardBorder)
                    Spacer(modifier = Modifier.height(10.dp))

                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())
                    ) {
                        courses.forEach { course ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (selectedCourse?.id == course.id) GolfGreenDark else Color.Transparent)
                                    .clickable {
                                        viewModel.selectCourse(course)
                                        showCourseSelectDialog = false
                                    }
                                    .padding(14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = course.name,
                                        color = if (selectedCourse?.id == course.id) GolfGreenPrimary else GolfSoftWhite,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp
                                    )
                                    Text(
                                        text = "${course.location} • ${course.totalHoles}홀 코스",
                                        color = GolfMuted,
                                        fontSize = 11.sp
                                    )
                                }
                                if (selectedCourse?.id == course.id) {
                                    Icon(Icons.Default.CheckCircle, contentDescription = "선택됨", tint = GolfGreenPrimary)
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    TextButton(
                        onClick = { showCourseSelectDialog = false },
                        modifier = Modifier.align(Alignment.End).testTag("close_course_dialog_button")
                    ) {
                        Text("취소", color = GolfGreenPrimary)
                    }
                }
            }
        }
    }
}

// --- SCREEN 2: SWING ANALYZER SCREEN (AI CORE INTEGRATION) ---
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SwingAnalyzerScreen(viewModel: CaddyViewModel) {
    val context = LocalContext.current
    val clubs by viewModel.clubs.collectAsStateWithLifecycle()
    val isAnalyzing by viewModel.isAnalyzing.collectAsStateWithLifecycle()
    val aiRecommendation by viewModel.aiRecommendation.collectAsStateWithLifecycle()
    val apiErrorOccurred by viewModel.apiErrorOccurred.collectAsStateWithLifecycle()

    var showClubDropdown by remember { mutableStateOf(false) }

    // Forms field states
    var selectedClubForLog by remember { mutableStateOf<Club?>(null) }
    var swingSpeedInput by remember { mutableStateOf(95f) }
    var ballSpeedInput by remember { mutableStateOf(138f) }
    var launchAngleInput by remember { mutableStateOf(14.0f) }
    var spinRateInput by remember { mutableStateOf(2600f) }
    var selectedDirection by remember { mutableStateOf("Straight") }
    var userNotesInput by remember { mutableStateOf("") }

    // Populate default selection on first load
    LaunchedEffect(clubs) {
        if (clubs.isNotEmpty() && selectedClubForLog == null) {
            selectedClubForLog = clubs.first()
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // TELEMETRY ENTRY CARD
        item {
            Card(
                modifier = Modifier.fillMaxWidth().testTag("swing_telemetry_card"),
                colors = CardDefaults.cardColors(containerColor = GolfCardBg),
                border = BorderStroke(1.dp, GolfCardBorder),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.DirectionsRun, contentDescription = "Swing Info", tint = GolfGreenPrimary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("실시간 스윙 데이터 수동 기입", color = GolfSoftWhite, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                    Text(
                        text = "스마트폰 탑재 센서나 레인지파인더 기밀 데이터를 반영하세요.",
                        color = GolfMuted,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    // 1. Club used selector
                    Text("선택 골프 클럽", color = GolfSoftWhite, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(6.dp))
                    Box(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(GolfSlateBg)
                                .border(1.dp, GolfCardBorder, RoundedCornerShape(12.dp))
                                .clickable { showClubDropdown = true }
                                .padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = selectedClubForLog?.name ?: "지정된 서브 기종 없음",
                                color = GolfSoftWhite,
                                fontSize = 14.sp
                            )
                            Icon(Icons.Default.ArrowDropDown, contentDescription = "열기", tint = GolfGreenPrimary)
                        }

                        DropdownMenu(
                            expanded = showClubDropdown,
                            onDismissRequest = { showClubDropdown = false },
                            modifier = Modifier.background(GolfCardBg)
                        ) {
                            clubs.forEach { club ->
                                DropdownMenuItem(
                                    text = { Text(club.name, color = GolfSoftWhite) },
                                    onClick = {
                                        selectedClubForLog = club
                                        // Auto-adjust values according to club to provide a slick, realistic user experience!
                                        if (club.name.contains("드라이버")) {
                                            swingSpeedInput = 100f
                                            ballSpeedInput = 145f
                                            launchAngleInput = 13.5f
                                            spinRateInput = 2500f
                                        } else if (club.name.contains("아이언")) {
                                            swingSpeedInput = 82f
                                            ballSpeedInput = 112f
                                            launchAngleInput = 19.5f
                                            spinRateInput = 5500f
                                        } else if (club.name.contains("웨지")) {
                                            swingSpeedInput = 73f
                                            ballSpeedInput = 87f
                                            launchAngleInput = 28f
                                            spinRateInput = 8000f
                                        }
                                        showClubDropdown = false
                                    }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // 2. Swing & Ball speeds telemetry sliders
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("스윙 속도 (헤드): ${swingSpeedInput.toInt()} mph", color = GolfSoftWhite, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Slider(
                                value = swingSpeedInput,
                                onValueChange = { swingSpeedInput = it },
                                valueRange = 40f..140f,
                                colors = SliderDefaults.colors(thumbColor = GolfGreenPrimary, activeTrackColor = GolfGreenPrimary)
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text("볼 전달 속도: ${ballSpeedInput.toInt()} mph", color = GolfSoftWhite, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Slider(
                                value = ballSpeedInput,
                                onValueChange = { ballSpeedInput = it },
                                valueRange = 50f..190f,
                                colors = SliderDefaults.colors(thumbColor = GolfGreenPrimary, activeTrackColor = GolfGreenPrimary)
                            )
                        }
                    }

                    // Simulated Smash Factor readout in real-time
                    val computedSmash = if (swingSpeedInput > 0) ballSpeedInput / swingSpeedInput else 1.0f
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(GolfSlateBg)
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("실시간 예상 스매시 효율성", color = GolfMuted, fontSize = 11.sp)
                        Text(
                            text = "Factor ${String.format("%.2f", computedSmash)}",
                            color = if (computedSmash >= 1.45f) GolfGreenPrimary else GolfBunkerGold,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // 3. Launch angles and Spinrate sliders
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("발사각: ${String.format("%.1f", launchAngleInput)}°", color = GolfSoftWhite, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Slider(
                                value = launchAngleInput,
                                onValueChange = { launchAngleInput = it },
                                valueRange = 5f..45f,
                                colors = SliderDefaults.colors(thumbColor = GolfGreenPrimary, activeTrackColor = GolfGreenPrimary)
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text("백스핀량: ${spinRateInput.toInt()} rpm", color = GolfSoftWhite, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Slider(
                                value = spinRateInput,
                                onValueChange = { spinRateInput = it },
                                valueRange = 1000f..10000f,
                                colors = SliderDefaults.colors(thumbColor = GolfGreenPrimary, activeTrackColor = GolfGreenPrimary)
                            )
                        }
                    }

                    // 4. Ball Direction chips selector
                    Text("샷 구질 및 궤적 (Trajectory)", color = GolfSoftWhite, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val directions = listOf("Straight" to "스트레이트", "Slice" to "우측슬라이스", "Hook" to "좌측훅")
                        directions.forEach { (dirVal, dirLabel) ->
                            OutlinedButton(
                                onClick = { selectedDirection = dirVal },
                                modifier = Modifier.weight(1f).testTag("direction_btn_$dirVal"),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = if (selectedDirection == dirVal) GolfGreenDark else Color.Transparent,
                                    contentColor = if (selectedDirection == dirVal) GolfGreenPrimary else GolfMuted
                                ),
                                border = BorderStroke(
                                    1.dp,
                                    if (selectedDirection == dirVal) GolfGreenPrimary else GolfCardBorder
                                ),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(dirLabel, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // 5. Notes input field
                    OutlinedTextField(
                        value = userNotesInput,
                        onValueChange = { userNotesInput = it },
                        placeholder = { Text("스탬프 메모 (선택: '내리막 라이 슬라이스 주의')", color = GolfMuted, fontSize = 12.sp) },
                        modifier = Modifier.fillMaxWidth().testTag("notes_text_field"),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = GolfGreenPrimary,
                            unfocusedBorderColor = GolfCardBorder,
                            focusedTextColor = GolfSoftWhite,
                            unfocusedTextColor = GolfSoftWhite
                        ),
                        shape = RoundedCornerShape(10.dp)
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Save Log & Trigger AI Analysis Button!
                    Button(
                        onClick = {
                            viewModel.logSwing(
                                clubName = selectedClubForLog?.name ?: "일반 서브",
                                swingSpeed = swingSpeedInput,
                                ballSpeed = ballSpeedInput,
                                launchAngle = launchAngleInput,
                                spinRate = spinRateInput,
                                resultDirection = selectedDirection,
                                notes = userNotesInput
                            )
                            Toast.makeText(context, "스윙 로그 저장 완료 및 AI 코칭 구동!", Toast.LENGTH_SHORT).show()
                            userNotesInput = ""
                        },
                        modifier = Modifier.fillMaxWidth().height(48.dp).testTag("save_and_analyze_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = GolfGreenPrimary),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.FlashOn, contentDescription = "AI", tint = GolfSlateBg)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("스윙 기록 & AI 실시간 전술 코칭 받기", color = GolfSlateBg, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // DYNAMIC PRESTIGE GLOWING AI RESPONSE CARD
        item {
            Card(
                modifier = Modifier.fillMaxWidth().testTag("ai_result_box"),
                colors = CardDefaults.cardColors(containerColor = GolfSlateBg),
                border = BorderStroke(1.5.dp, if (apiErrorOccurred != null) GolfError else GolfGreenPrimary),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.ChatBubbleOutline,
                                contentDescription = "AI Caddy Response",
                                tint = GolfGreenPrimary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "AI 실시간 전술 브리핑",
                                color = GolfSoftWhite,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        // Trigger analysis manually if logs loaded
                        IconButton(
                            onClick = { viewModel.requestAiStrategy() },
                            modifier = Modifier.testTag("manual_refresh_recommendation_btn")
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "새로고침", tint = GolfGreenPrimary)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Divider(color = GolfCardBorder)
                    Spacer(modifier = Modifier.height(14.dp))

                    if (isAnalyzing) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator(color = GolfGreenPrimary)
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                "AI 캐디가 코스 도면과 스윙 고도 데이터를 연동하여 맞춤 비거리 가이드를 도출하고 있습니다...",
                                color = GolfSoftWhite,
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center
                            )
                        }
                    } else {
                        // Display Strategy Recommendations content with Markdown support mock styled neatly!
                        val feedbackText = aiRecommendation.ifEmpty {
                            "아직 인게임 분석 데이터가 부재합니다. 위 양식을 기입하고 저장버튼을 터치해 실시간 맞춤 리듬 가이드를 발급받으세요!"
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            feedbackText.split("\n").forEach { line ->
                                if (line.trim().startsWith("⛳") || line.trim().startsWith("🎯") || line.trim().startsWith("🔍") || line.trim().startsWith("📊") || line.trim().startsWith("**")) {
                                    Text(
                                        text = line,
                                        color = GolfGreenPrimary,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        lineHeight = 20.sp
                                    )
                                } else {
                                    Text(
                                        text = line,
                                        color = GolfSoftWhite,
                                        fontSize = 13.sp,
                                        lineHeight = 19.sp
                                    )
                                }
                            }
                        }

                        // API notice warnings
                        apiErrorOccurred?.let {
                            Spacer(modifier = Modifier.height(12.dp))
                            Card(
                                colors = CardDefaults.cardColors(containerColor = GolfError.copy(alpha = 0.15f)),
                                border = BorderStroke(1.dp, GolfError)
                            ) {
                                Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Info, contentDescription = "오류", tint = GolfError, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "연결 기밀 감지: 로컬 스마트 룰셋으로 안전히 긴급 우회 하였습니다.",
                                        color = GolfError,
                                        fontSize = 10.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// --- SCREEN 3: MY CLUBS BAG & GAP ENGINE ---
@Composable
fun MyClubsScreen(viewModel: CaddyViewModel) {
    val clubs by viewModel.clubs.collectAsStateWithLifecycle()
    var showAddClubDialog by remember { mutableStateOf(false) }

    var newClubName by remember { mutableStateOf("") }
    var newClubType by remember { mutableStateOf("IRON") }
    var newClubDist by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // GAP CHOPPERS ENGINE DETAILS
        item {
            Card(
                modifier = Modifier.fillMaxWidth().testTag("gap_analyzer_card"),
                colors = CardDefaults.cardColors(containerColor = GolfCardBg),
                border = BorderStroke(1.dp, GolfGreenPrimary.copy(alpha = 0.3f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Analytics, contentDescription = "Gap Analyzer", tint = GolfGreenPrimary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("가방 속 비거리 갭 분석 (Gap Gapping)", color = GolfSoftWhite, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                    Text(
                        "아이언 및 우드 간 비거리 공백(Gap)이 일정한지 확인하여 인게임 전도율을 완화하세요.",
                        color = GolfMuted,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    // Gapping layout visualizer bars
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (clubs.isNotEmpty()) {
                            // Find max distance in current list to formulate scaling
                            val maxDist = clubs.maxOfOrNull { it.averageCarryDistance } ?: 220f
                            
                            clubs.forEach { club ->
                                val percent = if (maxDist > 0) club.averageCarryDistance / maxDist else 0.5f

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Club Name label
                                    Text(
                                        text = club.name,
                                        color = GolfSoftWhite,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.width(90.dp),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )

                                    // Bar Graph Visuals
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                        .height(12.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(GolfCardBorder)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxHeight()
                                                .fillMaxWidth(percent)
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(
                                                    Brush.horizontalGradient(
                                                        listOf(GolfGreenDark, GolfGreenPrimary)
                                                    )
                                                )
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(10.dp))

                                    // Distance values readout
                                    Text(
                                        text = "${club.averageCarryDistance.toInt()} yd",
                                        color = GolfGreenPrimary,
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 12.sp,
                                        modifier = Modifier.width(50.dp),
                                        textAlign = TextAlign.End
                                    )
                                }
                            }
                        } else {
                            Text("등록된 골프 클럽이 하나도 없습니다. 아래 추가 버튼을 사용하여 클럽 사양을 연동해 주세요.", color = GolfSoftWhite, fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // BAG HEADER WITH ADD OPTION
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "내 클럽 리스트 (${clubs.size}종)",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = GolfSoftWhite
                )

                Button(
                    onClick = { showAddClubDialog = true },
                    colors = ButtonDefaults.buttonColors(containerColor = GolfGreenPrimary),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.testTag("add_custom_club_btn")
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Add, contentDescription = "추가", tint = GolfSlateBg, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("새 클럽 추가", color = GolfSlateBg, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    }
                }
            }
        }

        // CLUBS ROW LISTINGS
        items(clubs) { club ->
            Card(
                modifier = Modifier.fillMaxWidth().testTag("club_item_${club.id}"),
                colors = CardDefaults.cardColors(containerColor = GolfCardBg),
                border = BorderStroke(1.dp, GolfCardBorder)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(GolfGreenDark),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = club.clubType.take(2),
                                color = GolfGreenPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Black
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = club.name,
                                color = GolfSoftWhite,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                            Text(
                                text = "${club.clubType} 기종 • 평균 정사거리",
                                color = GolfMuted,
                                fontSize = 11.sp
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "${club.averageCarryDistance.toInt()} yd",
                            color = GolfGreenPrimary,
                            fontWeight = FontWeight.Black,
                            fontSize = 16.sp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        // Delete club trigger
                        IconButton(
                            onClick = { viewModel.deleteClub(club.id) },
                            modifier = Modifier.testTag("delete_club_btn_${club.id}")
                        ) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = "삭제", tint = GolfError)
                        }
                    }
                }
            }
        }
    }

    // Add custom club Dialog
    if (showAddClubDialog) {
        Dialog(onDismissRequest = { showAddClubDialog = false }) {
            Card(
                colors = CardDefaults.cardColors(containerColor = GolfCardBg),
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, GolfCardBorder),
                modifier = Modifier.fillMaxWidth().padding(16.dp).testTag("add_club_dialog")
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "새로운 클럽 맞춤 등록",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = GolfSoftWhite,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )

                    Divider(color = GolfCardBorder)
                    Spacer(modifier = Modifier.height(14.dp))

                    // 1. Club name input
                    OutlinedTextField(
                        value = newClubName,
                        onValueChange = { newClubName = it },
                        label = { Text("클럽 명칭 (예: 5번 목재우드)", color = GolfMuted) },
                        modifier = Modifier.fillMaxWidth().testTag("dialog_club_name_field"),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = GolfGreenPrimary,
                            unfocusedBorderColor = GolfCardBorder,
                            focusedTextColor = GolfSoftWhite,
                            unfocusedTextColor = GolfSoftWhite
                        )
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // 2. Club Type Selection Row
                    Text("클럽 범주", color = GolfSoftWhite, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val types = listOf("WOOD", "IRON", "WEDGE")
                        types.forEach { type ->
                            OutlinedButton(
                                onClick = { newClubType = type },
                                modifier = Modifier.weight(1f).testTag("type_btn_$type"),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = if (newClubType == type) GolfGreenDark else Color.Transparent,
                                    contentColor = if (newClubType == type) GolfGreenPrimary else GolfMuted
                                ),
                                border = BorderStroke(1.dp, if (newClubType == type) GolfGreenPrimary else GolfCardBorder)
                            ) {
                                Text(type, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // 3. Average Distance input
                    OutlinedTextField(
                        value = newClubDist,
                        onValueChange = { newClubDist = it },
                        label = { Text("평균 사정비거리 (야드)", color = GolfMuted) },
                        modifier = Modifier.fillMaxWidth().testTag("dialog_club_distance_field"),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = GolfGreenPrimary,
                            unfocusedBorderColor = GolfCardBorder,
                            focusedTextColor = GolfSoftWhite,
                            unfocusedTextColor = GolfSoftWhite
                        )
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(
                            onClick = { showAddClubDialog = false }
                        ) {
                            Text("취소", color = GolfMuted)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                val dist = newClubDist.toFloatOrNull() ?: 100f
                                if (newClubName.isNotEmpty()) {
                                    viewModel.addClub(newClubName, newClubType, dist)
                                    newClubName = ""
                                    newClubDist = ""
                                    showAddClubDialog = false
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = GolfGreenPrimary),
                            modifier = Modifier.testTag("confirm_add_club_button")
                        ) {
                            Text("가방에 기입", color = GolfSlateBg, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

// --- SCREEN 4: ROUND & HISTORICAL SWING REPORTS ---
@Composable
fun RoundAnalysisScreen(viewModel: CaddyViewModel) {
    val swingLogs by viewModel.swingLogs.collectAsStateWithLifecycle()
    val driverTrends by viewModel.driverTrends.collectAsStateWithLifecycle()
    val metricUnitYards by viewModel.metricUnitYards.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // SUMMARY DIAGNOSTIC MATRIX
        item {
            Card(
                modifier = Modifier.fillMaxWidth().testTag("diagnostic_summary_card"),
                colors = CardDefaults.cardColors(containerColor = GolfCardBg),
                border = BorderStroke(1.dp, GolfCardBorder),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Analytics, contentDescription = "Summary", tint = GolfGreenPrimary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("통계적 스윙 진단 리포트", color = GolfSoftWhite, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                    Text(
                        "인게임 적립된 기록들을 기반으로 종합적인 운동 지수를 진단합니다.",
                        color = GolfMuted,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                    Spacer(modifier = Modifier.height(14.dp))

                    Divider(color = GolfCardBorder)
                    Spacer(modifier = Modifier.height(14.dp))

                    // Calculates diagnostic metrics on-the-fly safely
                    val logCount = swingLogs.size
                    val avgHeadSpeed = if (logCount > 0) swingLogs.map { it.swingSpeedMph }.average() else 0.0
                    val avgBallSpeed = if (logCount > 0) swingLogs.map { it.ballSpeedMph }.average() else 0.0
                    val driverLogs = swingLogs.filter { it.clubName.contains("드라이버") }
                    val avgDriverDist = if (driverLogs.isNotEmpty()) driverLogs.map { it.carryDistanceYards }.average() else 0.0
                    val maxSmashFactor = if (logCount > 0) swingLogs.maxOf { it.smashFactor } else 0.0f

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("누적 시타", color = GolfMuted, fontSize = 10.sp)
                            Text("${logCount}회 스윙", color = GolfSoftWhite, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        }
                        Column {
                            Text("평균 드라이버", color = GolfMuted, fontSize = 10.sp)
                            Text(if (avgDriverDist > 0) "${avgDriverDist.toInt()} yd" else "미측정", color = GolfGreenPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        }
                        Column {
                            Text("평균 헤드스피드", color = GolfMuted, fontSize = 10.sp)
                            Text(if (avgHeadSpeed > 0) "${String.format("%.1f", avgHeadSpeed)} mph" else "미측정", color = GolfSoftWhite, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        }
                        Column {
                            Text("최고 정타 효율", color = GolfMuted, fontSize = 10.sp)
                            Text(if (maxSmashFactor > 0) "${String.format("%.2f", maxSmashFactor)}" else "미측정", color = GolfBunkerGold, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Trajectory scatter slices indicators (Straight vs Slice vs Hook percent)
                    if (logCount > 0) {
                        val straightCount = swingLogs.count { it.resultDirection == "Straight" }
                        val sliceCount = swingLogs.count { it.resultDirection == "Slice" }
                        val hookCount = swingLogs.count { it.resultDirection == "Hook" }

                        val pStraight = (straightCount.toFloat() / logCount * 100).toInt()
                        val pSlice = (sliceCount.toFloat() / logCount * 100).toInt()
                        val pHook = (hookCount.toFloat() / logCount * 100).toInt()

                        Text("구질 성향 분포 차트", color = GolfSoftWhite, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth().height(26.dp).clip(RoundedCornerShape(8.dp))
                        ) {
                            if (pStraight > 0) {
                                Box(
                                    modifier = Modifier.weight(pStraight.toFloat()).fillMaxHeight().background(GolfGreenPrimary),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("스트레이트 $pStraight%", color = GolfSlateBg, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                                }
                            }
                            if (pSlice > 0) {
                                Box(
                                    modifier = Modifier.weight(pSlice.toFloat()).fillMaxHeight().background(GolfBunkerGold),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("슬라이스 $pSlice%", color = GolfSlateBg, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                                }
                            }
                            if (pHook > 0) {
                                Box(
                                    modifier = Modifier.weight(pHook.toFloat()).fillMaxHeight().background(GolfWaterBlue),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("훅 $pHook%", color = GolfSlateBg, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                                }
                            }
                        }

                        // Coaching prompt helper
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = when {
                                pSlice > 40 -> "💡 슬라이드 경향이 높습니다. 임팩트 시 오른쪽 어깨가 일찍 열리는지 캐스케이드 체크하십시오."
                                pHook > 40 -> "💡 훅 편차가 빈번합니다. 양손 그립을 과도히 스트롱 그립으로 쥐고 있는지 이완해서 체크해 주십시오."
                                else -> "💡 구질이 고르게 잡혀 좋은 아이언 리딩감을 소화하고 계십니다! 샷 메이킹 퍼포먼스가 발군입니다."
                            },
                            color = GolfGreenPrimary,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }

        // RECHARTS-STYLE DRIVING DISTANCE TREND CHART
        item {
            RechartsStyleDriveTrendChart(trends = driverTrends, metricUnitYards = metricUnitYards)
        }

        // SWING LOGS TIMELINE LIST HEADER
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "스윙 로그 타임라인 (${swingLogs.size}회 기록됨)",
                    color = GolfSoftWhite,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )

                TextButton(
                    onClick = { viewModel.clearLogs() },
                    colors = ButtonDefaults.textButtonColors(contentColor = GolfError),
                    modifier = Modifier.testTag("clear_logs_button")
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = "모두 비우기", modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("전체 비우기", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    }
                }
            }
        }

        // HISTORICAL LOG ITEMS
        if (swingLogs.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = GolfCardBg)
                ) {
                    Column(
                        modifier = Modifier.padding(32.dp).fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("역사 기록이 비어있습니다.", color = GolfMuted, fontSize = 12.sp)
                    }
                }
            }
        } else {
            items(swingLogs) { log ->
                Card(
                    modifier = Modifier.fillMaxWidth().testTag("log_item_${log.id}"),
                    colors = CardDefaults.cardColors(containerColor = GolfCardBg),
                    border = BorderStroke(1.dp, GolfCardBorder)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(GolfGreenDark),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.ShowChart, contentDescription = "스윙 진도", tint = GolfGreenPrimary, modifier = Modifier.size(16.dp))
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(log.clubName, color = GolfSoftWhite, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    Text(
                                        text = "구질: " + when (log.resultDirection) {
                                            "Straight" -> "스트레이트"
                                            "Slice" -> "슬라이스"
                                            "Hook" -> "훅"
                                            else -> log.resultDirection
                                        },
                                        color = when (log.resultDirection) {
                                            "Straight" -> GolfGreenPrimary
                                            "Slice" -> GolfBunkerGold
                                            else -> GolfWaterBlue
                                        },
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = "${log.carryDistanceYards.toInt()} yd",
                                    color = GolfGreenPrimary,
                                    fontWeight = FontWeight.Black,
                                    fontSize = 17.sp
                                )
                                Text(
                                    text = "정타율 SF ${String.format("%.2f", log.smashFactor)}",
                                    color = GolfMuted,
                                    fontSize = 10.sp
                                )
                            }
                        }

                        if (log.notes.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(GolfSlateBg)
                                    .padding(8.dp)
                            ) {
                                Text(
                                    text = "📝 메모: ${log.notes}",
                                    color = GolfSoftWhite.copy(alpha = 0.9f),
                                    fontSize = 11.sp
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "헤드속도: ${log.swingSpeedMph.toInt()}mph | 볼속도: ${log.ballSpeedMph.toInt()}mph",
                                color = GolfMuted,
                                fontSize = 10.sp
                            )

                            IconButton(
                                onClick = { viewModel.deleteLog(log.id) },
                                modifier = Modifier.size(24.dp).testTag("delete_log_btn_${log.id}")
                            ) {
                                Icon(Icons.Default.DeleteOutline, contentDescription = "로그 삭제", tint = GolfError, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RechartsStyleDriveTrendChart(trends: List<RoundDriveTrend>, metricUnitYards: Boolean) {
    val unitStr = if (metricUnitYards) "yd" else "m"
    var selectedIndex by remember { mutableStateOf<Int?>(null) }
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("drive_trends_chart_card"),
        colors = CardDefaults.cardColors(containerColor = GolfCardBg),
        border = BorderStroke(1.dp, GolfCardBorder),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(GolfGreenPrimary)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "드라이버 비거리 트렌드 (최근 10라운드)",
                            color = GolfSoftWhite,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = "각 라운드별 평균 및 최장 비거리 시뮬레이션 추이 파악",
                        color = GolfMuted,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }

                // Legend indicator
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(GolfGreenPrimary))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("평균 ($unitStr)", color = GolfMuted, fontSize = 9.sp)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(GolfBunkerGold))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("최장 ($unitStr)", color = GolfMuted, fontSize = 9.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            if (trends.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("트렌드 데이터를 분석할 수 없습니다.", color = GolfMuted)
                }
            } else {
                val maxVal = 300f // benchmark ceiling
                
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                ) {
                    val width = constraints.maxWidth
                    val height = constraints.maxHeight
                    
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectTapGestures { offset ->
                                    // Calculate which bar was tapped
                                    val leftSpacing = 35.dp.toPx()
                                    val rightSpacing = 10.dp.toPx()
                                    val usableWidth = size.width - leftSpacing - rightSpacing
                                    val barWidthWithSpacing = usableWidth / trends.size
                                    
                                    val clickedX = offset.x - leftSpacing
                                    val index = (clickedX / barWidthWithSpacing).toInt()
                                    if (index in trends.indices) {
                                        selectedIndex = if (selectedIndex == index) null else index
                                    } else {
                                        selectedIndex = null
                                    }
                                }
                            }
                    ) {
                        val leftSpacingPx = 35.dp.toPx()
                        val rightSpacingPx = 10.dp.toPx()
                        val topSpacingPx = 15.dp.toPx()
                        val bottomSpacingPx = 25.dp.toPx()
                        
                        val chartWidth = size.width - leftSpacingPx - rightSpacingPx
                        val chartHeight = size.height - topSpacingPx - bottomSpacingPx
                        
                        // Draw grid lines & Y-axis labels
                        val gridLines = 4
                        for (i in 0..gridLines) {
                            val fraction = i.toFloat() / gridLines
                            val y = topSpacingPx + chartHeight * (1f - fraction)
                            val value = (maxVal * fraction).toInt()
                            
                            // Horizontal guide line
                            drawLine(
                                color = GolfCardBorder.copy(alpha = 0.5f),
                                start = Offset(leftSpacingPx, y),
                                end = Offset(size.width - rightSpacingPx, y),
                                strokeWidth = 1f,
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
                            )
                            
                            // Y-axis label
                            drawContext.canvas.nativeCanvas.apply {
                                val paint = android.graphics.Paint().apply {
                                    color = android.graphics.Color.parseColor("#7B9182") // GolfMuted
                                    textSize = 9.dp.toPx()
                                    textAlign = android.graphics.Paint.Align.RIGHT
                                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                                }
                                drawText(
                                    "$value",
                                    leftSpacingPx - 8.dp.toPx(),
                                    y + 3.dp.toPx(),
                                    paint
                                )
                            }
                        }
                        
                        // Render bars
                        val barWidthWithSpacing = chartWidth / trends.size
                        val barSpacing = barWidthWithSpacing * 0.35f
                        val individualBarWidth = (barWidthWithSpacing - barSpacing) / 2f
                        
                        trends.forEachIndexed { idx, trend ->
                            val xPos = leftSpacingPx + idx * barWidthWithSpacing + barSpacing / 2f
                            
                            // 1. Average distance bar (GolfGreenPrimary)
                            val avgHeight = (trend.averageDistanceYards / maxVal) * chartHeight
                            val avgTop = topSpacingPx + chartHeight - avgHeight
                            val avgRect = Rect(
                                left = xPos,
                                top = avgTop,
                                right = xPos + individualBarWidth,
                                bottom = topSpacingPx + chartHeight
                            )
                            
                            // Highlight bar if selected
                            val avgBarColor = if (selectedIndex == idx) GolfGreenPrimary else GolfGreenPrimary.copy(alpha = 0.82f)
                            
                            // Gradient fill
                            drawRoundRect(
                                brush = Brush.verticalGradient(
                                    colors = listOf(avgBarColor, GolfGreenDark)
                                ),
                                topLeft = Offset(avgRect.left, avgRect.top),
                                size = Size(avgRect.width, avgRect.height),
                                cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
                            )
                            
                            // 2. Longest distance bar (GolfBunkerGold)
                            val longHeight = (trend.longestDriveYards / maxVal) * chartHeight
                            val longTop = topSpacingPx + chartHeight - longHeight
                            val longRect = Rect(
                                left = xPos + individualBarWidth + 2.dp.toPx(),
                                top = longTop,
                                right = xPos + 2 * individualBarWidth + 2.dp.toPx(),
                                bottom = topSpacingPx + chartHeight
                            )
                            
                            val longBarColor = if (selectedIndex == idx) GolfBunkerGold else GolfBunkerGold.copy(alpha = 0.8f)
                            drawRoundRect(
                                brush = Brush.verticalGradient(
                                    colors = listOf(longBarColor, Color(0xFF534125))
                                ),
                                topLeft = Offset(longRect.left, longRect.top),
                                size = Size(longRect.width, longRect.height),
                                cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
                            )
                            
                            // Background shadow highlighting tapped column
                            if (selectedIndex == idx) {
                                drawRect(
                                    color = Color.White.copy(alpha = 0.05f),
                                    topLeft = Offset(xPos - barSpacing/4f, topSpacingPx),
                                    size = Size(barWidthWithSpacing - barSpacing/2f, chartHeight)
                                )
                            }
                            
                            // X-axis label
                            drawContext.canvas.nativeCanvas.apply {
                                val paint = android.graphics.Paint().apply {
                                    color = if (selectedIndex == idx) android.graphics.Color.parseColor("#A7C957") else android.graphics.Color.parseColor("#7B9182")
                                    textSize = 9.dp.toPx()
                                    textAlign = android.graphics.Paint.Align.CENTER
                                    typeface = if (selectedIndex == idx) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
                                }
                                drawText(
                                    trend.roundName,
                                    xPos + individualBarWidth + 1.dp.toPx(),
                                    size.height - 8.dp.toPx(),
                                    paint
                                )
                            }
                        }
                    }
                }
            }
            
            // Pop-up tooltips info box matching Recharts
            AnimatedVisibility(
                visible = selectedIndex != null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                selectedIndex?.let { idx ->
                    val trend = trends.getOrNull(idx)
                    if (trend != null) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(GolfGreenDark.copy(alpha = 0.4f))
                                .border(1.dp, GolfGreenPrimary.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "라운드 ${trend.roundNumber} 세부 기록 (${trend.dateLabel})",
                                    color = GolfGreenPrimary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                    Column {
                                        Text("평균 드라이버", color = GolfMuted, fontSize = 9.sp)
                                        Text("${trend.averageDistanceYards} $unitStr", color = GolfSoftWhite, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                    }
                                    Column {
                                        Text("최장 드라이버", color = GolfMuted, fontSize = 9.sp)
                                        Text("${trend.longestDriveYards} $unitStr", color = GolfBunkerGold, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                            
                            Column(horizontalAlignment = Alignment.End) {
                                val improvement = if (idx > 0) {
                                    trend.averageDistanceYards - trends[idx - 1].averageDistanceYards
                                } else 0f
                                
                                Text("이전 라운드 대비", color = GolfMuted, fontSize = 9.sp)
                                if (improvement >= 0) {
                                    Text("+${String.format("%.1f", improvement)} $unitStr", color = GolfGreenPrimary, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
                                } else {
                                    Text("${String.format("%.1f", improvement)} $unitStr", color = GolfError, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
                                }
                            }
                        }
                    }
                }
            }
            
            if (selectedIndex == null) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "터치 가이드",
                        tint = GolfGreenPrimary.copy(alpha = 0.6f),
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "개별 막대를 터치하면 회차별 상세 비거리 및 성장 추이를 확인할 수 있습니다.",
                        color = GolfMuted,
                        fontSize = 10.sp
                    )
                }
            }
        }
    }
}
