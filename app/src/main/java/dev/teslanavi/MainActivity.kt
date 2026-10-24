package dev.teslanavi

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.teslanavi.data.VehicleProfile
import dev.teslanavi.drive.DrivePhase
import dev.teslanavi.drive.DriveService
import dev.teslanavi.drive.DriveState
import dev.teslanavi.drive.AutoConnectService
import dev.teslanavi.drive.AutoConnectSettings
import dev.teslanavi.protocol.NavigationDestination
import dev.teslanavi.protocol.VehicleIdentity
import dev.teslanavi.navigation.*
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.roundToInt

private val Ink = Color(0xFF101419)
private val Panel = Color(0xFF1A2028)
private val Mint = Color(0xFF6EE7CF)
private val Muted = Color(0xFFA2AEBD)
private val NightColors = darkColorScheme(primary = Mint, onPrimary = Ink, background = Ink,
    surface = Panel, onSurface = Color(0xFFF1F5F9), surfaceVariant = Color(0xFF242D38), onSurfaceVariant = Muted)

class MainActivity : ComponentActivity() {
    private val app get() = application as TeslaNaviApp
    private var pendingEnroll = false
    private var pendingVin: String? = null
    private var pendingAutomatic = false
    private val backgroundDisclosure = mutableStateOf(false)
    private val localError = mutableStateOf<String?>(null)
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (pendingAutomatic && (!granted(Manifest.permission.ACCESS_FINE_LOCATION) ||
                (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)))) {
            localError.value = "자동 연결에는 정확한 위치와 알림 권한이 필요합니다. 권한을 허용한 뒤 다시 켜 주세요."
            pendingAutomatic = false
        } else if (bluetoothPermissions().all { granted(it) }) enableBluetoothAndStart()
        else localError.value = "차량 검색을 위해 근처 기기와 정확한 위치 권한을 허용해 주세요."
    }
    private val bluetoothLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true) continueStart()
        else localError.value = "Bluetooth를 켜야 차량에 연결할 수 있습니다."
    }
    private val backgroundPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) activateAutomatic() else { pendingAutomatic = false; localError.value = "자동 연결을 켜려면 위치 권한을 항상 허용해 주세요." }
    }
    private val backgroundSettings = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (pendingAutomatic && AutoConnectSettings.ready(this)) activateAutomatic()
        else { pendingAutomatic = false; localError.value = "자동 연결을 켜려면 위치 권한을 항상 허용해 주세요." }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingAutomatic = savedInstanceState?.getBoolean("pending-automatic") ?: false
        pendingVin = savedInstanceState?.getString("pending-vin")
        pendingEnroll = savedInstanceState?.getBoolean("pending-enroll") ?: false
        setContent {
            MaterialTheme(colorScheme = NightColors) {
                val profiles by app.vehicles.vehicles.collectAsStateWithLifecycle()
                val selected by app.vehicles.selected.collectAsStateWithLifecycle()
                val state by app.drive.state.collectAsStateWithLifecycle()
                val cameras by app.cameras.state.collectAsStateWithLifecycle()
                val hasKakaoKey by app.kakao.hasKey.collectAsStateWithLifecycle()
                val autoEnabled by app.autoConnect.enabled.collectAsStateWithLifecycle()
                val autoStatus by app.autoConnect.status.collectAsStateWithLifecycle()
                val guidanceOptions by app.guidance.options.collectAsStateWithLifecycle()
                val settingsTab by app.features.settingsTab.collectAsStateWithLifecycle()
                var showGuidance by remember { mutableStateOf(false) }
                Surface(Modifier.fillMaxSize(), color = Ink) {
                    VehicleScreen(profiles, selected, state, cameras, hasKakaoKey, localError.value, autoEnabled, autoStatus, guidanceOptions,
                        onSettings = { showGuidance = true },
                        onAdd = { model, vin -> app.vehicles.add(model, vin); app.drive.clear(); localError.value = null },
                        onSelect = { app.vehicles.select(it); app.drive.clear(); localError.value = null },
                        onConnect = { enroll -> begin(enroll) },
                        onDisconnect = { DriveService.stop(this) },
                        onRefresh = { app.drive.requestRefresh() },
                        onAutomatic = { enabled -> if (enabled) beginAutomatic() else AutoConnectService.disable(this) },
                        onMute = { app.cameras.setMuted(it) },
                        settingsTab = settingsTab, onSettingsTab = { app.features.setSettingsTab(it) })
                }
                if (showGuidance) GuidanceSettingsDialog(app.guidance,
                    cameras.phase in setOf(CameraPhase.INITIALIZING, CameraPhase.WAITING_GPS, CameraPhase.GUIDING)) { showGuidance = false }
                if (backgroundDisclosure.value) AlertDialog(
                    onDismissRequest = { backgroundDisclosure.value = false; pendingAutomatic = false },
                    title = { Text("자동 연결에 필요한 위치 권한") },
                    text = { Text("화면이 꺼져 있거나 휴대폰을 재시작한 뒤에도 차량에 연결하고 카메라 안내를 시작하려면 위치 권한의 ‘항상 허용’이 필요합니다. 다음 설정 화면에서 권한 → 위치 → 항상 허용을 선택해 주세요.") },
                    confirmButton = { TextButton(onClick = {
                        backgroundDisclosure.value = false
                        backgroundSettings.launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                    }) { Text("권한 설정 열기") } },
                    dismissButton = { TextButton(onClick = { backgroundDisclosure.value = false; pendingAutomatic = false }) { Text("취소") } })
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("pending-automatic", pendingAutomatic)
        outState.putString("pending-vin", pendingVin)
        outState.putBoolean("pending-enroll", pendingEnroll)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        if (app.autoConnect.enabled.value && AutoConnectSettings.ready(this)) {
            try { AutoConnectService.start(this) } catch (_: Exception) { app.autoConnect.report("자동 연결을 다시 켜 주세요") }
        }
    }

    private fun granted(permission: String) = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    private fun bluetoothPermissions(): List<String> = if (Build.VERSION.SDK_INT >= 31)
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        else listOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun begin(enroll: Boolean) {
        if (app.drive.state.value.active) return
        localError.value = null
        pendingVin = app.vehicles.selected.value ?: return
        pendingEnroll = enroll
        pendingAutomatic = false
        val required = bluetoothPermissions().filterNot { granted(it) }.toMutableList()
        if (app.kakao.hasKey.value) {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                .filterNot { granted(it) }.forEach { if (it !in required) required += it }
        }
        if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) required += Manifest.permission.POST_NOTIFICATIONS
        if (required.isNotEmpty()) permissionLauncher.launch(required.toTypedArray()) else enableBluetoothAndStart()
    }

    private fun beginAutomatic() {
        pendingVin = app.vehicles.selected.value ?: return
        pendingAutomatic = true
        localError.value = null
        val required = (bluetoothPermissions() + listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION) +
            if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()).distinct().filterNot { granted(it) }
        if (required.isNotEmpty()) permissionLauncher.launch(required.toTypedArray()) else enableBluetoothAndStart()
    }

    private fun continueStart() {
        if (!pendingAutomatic) { startSession(); return }
        when {
            Build.VERSION.SDK_INT < 29 || granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION) -> activateAutomatic()
            Build.VERSION.SDK_INT == 29 -> backgroundPermission.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            else -> backgroundDisclosure.value = true
        }
    }

    private fun activateAutomatic() {
        pendingAutomatic = false
        if (!AutoConnectSettings.ready(this)) { localError.value = "자동 연결 권한을 확인해 주세요."; return }
        try {
            app.autoConnect.setEnabled(true)
            AutoConnectService.start(this)
        } catch (_: Exception) { app.autoConnect.setEnabled(false); localError.value = "자동 연결을 시작하지 못했습니다. 다시 켜 주세요." }
    }

    private fun enableBluetoothAndStart() {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null) { localError.value = "이 기기는 Bluetooth LE를 지원하지 않습니다."; return }
        if (!adapter.isEnabled) bluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) else continueStart()
    }

    private fun startSession() {
        val vin = pendingVin ?: return
        if (app.drive.state.value.active || app.vehicles.selected.value != vin) return
        try {
            app.drive.prepare(vin)
            DriveService.start(this, vin, pendingEnroll)
        } catch (error: Exception) { app.drive.fail(error) }
    }
}

private enum class AppTab(val label: String, val icon: ImageVector, val selectedIcon: ImageVector) {
    MAIN("메인", Icons.Outlined.Home, Icons.Filled.Home),
    SETTINGS("설정", Icons.Outlined.Settings, Icons.Filled.Settings),
}

@Composable
private fun VehicleScreen(profiles: List<VehicleProfile>, selected: String?, state: DriveState,
    cameras: CameraState, hasKakaoKey: Boolean, localError: String?, autoEnabled: Boolean, autoStatus: String, options: GuidanceOptions, onSettings: () -> Unit,
    onAdd: (String, String) -> Unit, onSelect: (String) -> Unit, onConnect: (Boolean) -> Unit,
    onDisconnect: () -> Unit, onRefresh: () -> Unit, onAutomatic: (Boolean) -> Unit, onMute: (Boolean) -> Unit,
    settingsTab: Boolean, onSettingsTab: (Boolean) -> Unit) {
    // Without the settings tab feature, the main screen keeps every control and the top-right button opens the feature switches.
    var chosen by rememberSaveable { mutableStateOf(AppTab.MAIN) }
    var showFeatures by remember { mutableStateOf(false) }
    val tab = if (settingsTab) chosen else AppTab.MAIN
    val connected = state.phase == DrivePhase.CONNECTED
    BackHandler(enabled = tab != AppTab.MAIN) { chosen = AppTab.MAIN }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            when {
                !settingsTab -> ScreenColumn {
                    MainHeader(state, onOpenSettings = { showFeatures = true })
                    GuidanceButton(onSettings)
                    if (connected) CameraCard(cameras, hasKakaoKey, onMute, options)
                    VehicleList(profiles, selected, state, onAdd, onSelect)
                    AutoConnectCard(profiles, autoEnabled, autoStatus, onAutomatic)
                    ConnectionCard(profiles, selected, state, localError, autoEnabled, onConnect, onDisconnect)
                    DestinationCard(selected, state, onRefresh)
                    if (!connected) CameraCard(cameras, hasKakaoKey, onMute, options)
                    Diagnostics(selected, state)
                    VersionFooter()
                }
                tab == AppTab.MAIN -> ScreenColumn {
                    MainHeader(state, onOpenSettings = null)
                    if (connected) CameraCard(cameras, hasKakaoKey, onMute, options)
                    else StatusCard(profiles, selected, state, localError) { chosen = AppTab.SETTINGS }
                    DestinationCard(selected, state, onRefresh)
                    if (!connected) CameraCard(cameras, hasKakaoKey, onMute, options)
                }
                else -> ScreenColumn {
                    Text("설정", fontSize = 25.sp, fontWeight = FontWeight.Bold)
                    AppCard {
                        Text("기능", color = Muted, fontSize = 13.sp)
                        FeatureSwitches(settingsTab, onSettingsTab)
                    }
                    VehicleList(profiles, selected, state, onAdd, onSelect)
                    ConnectionCard(profiles, selected, state, localError, autoEnabled, onConnect, onDisconnect)
                    AutoConnectCard(profiles, autoEnabled, autoStatus, onAutomatic)
                    GuidanceCard(options, onSettings)
                    Diagnostics(selected, state)
                    VersionFooter()
                }
            }
        }
        if (settingsTab) TabBar(listOf(AppTab.MAIN, AppTab.SETTINGS), tab) { chosen = it }
    }
    if (showFeatures) AlertDialog(onDismissRequest = { showFeatures = false },
        title = { Text("설정") },
        text = { FeatureSwitches(settingsTab, onSettingsTab) },
        confirmButton = { TextButton(onClick = { showFeatures = false }) { Text("닫기") } })
}

@Composable
private fun FeatureSwitches(settingsTab: Boolean, onSettingsTab: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text("하단 설정 탭", fontWeight = FontWeight.SemiBold)
            Text("켜면 화면 아래에 설정 탭이 생기고 차량 등록·연결, 자동 연결, 안내 설정, 연결 기록을 설정 탭으로 옮깁니다. 끄면 메인 화면에 다시 표시합니다.",
                color = Muted, fontSize = 12.sp)
        }
        Switch(checked = settingsTab, onCheckedChange = onSettingsTab, modifier = Modifier.semantics { contentDescription = "하단 설정 탭" })
    }
}

@Composable
private fun TabBar(tabs: List<AppTab>, selected: AppTab, onSelect: (AppTab) -> Unit) {
    Column(Modifier.fillMaxWidth().background(Ink).navigationBarsPadding()) {
        HorizontalDivider(thickness = 0.5.dp, color = Color(0xFF242D38))
        Row(Modifier.fillMaxWidth().height(50.dp).selectableGroup()) {
            tabs.forEach { tab ->
                val active = tab == selected
                Box(Modifier.weight(1f).fillMaxHeight().selectable(selected = active, role = Role.Tab, onClick = { onSelect(tab) }),
                    contentAlignment = Alignment.Center) {
                    Icon(if (active) tab.selectedIcon else tab.icon, contentDescription = tab.label,
                        tint = if (active) Mint else Muted, modifier = Modifier.size(26.dp))
                }
            }
        }
    }
}

@Composable
private fun ScreenColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState())
        .padding(horizontal = 24.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp), content = content)
}

@Composable
private fun MainHeader(state: DriveState, onOpenSettings: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text("TESLA NAVI", letterSpacing = 3.sp, fontSize = 12.sp, color = Mint, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("내 차의 목적지", fontSize = 25.sp, fontWeight = FontWeight.Bold)
        }
        Surface(color = Color(0xFF243039), shape = RoundedCornerShape(50)) {
            Text(if (state.phase == DrivePhase.CONNECTED) "● 연결됨" else "● 대기", Modifier.padding(12.dp, 8.dp), color = Mint, fontSize = 12.sp)
        }
        if (onOpenSettings != null) IconButton(onClick = onOpenSettings, modifier = Modifier.offset(x = 8.dp)) {
            Icon(Icons.Outlined.Settings, contentDescription = "설정 열기", modifier = Modifier.size(26.dp))
        }
    }
}

@Composable
private fun GuidanceButton(onSettings: () -> Unit) {
    Button(onClick = onSettings, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Ink)) {
        Icon(painterResource(R.drawable.ic_settings), contentDescription = null, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(10.dp))
        Text("안내 설정", fontSize = 17.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun GuidanceCard(options: GuidanceOptions, onSettings: () -> Unit) {
    AppCard {
        Text("안내", color = Muted, fontSize = 13.sp)
        Text("음량 ${options.volumePercent}% · 고속도로 ${options.highwayMeters} m · 일반도로 ${options.generalMeters} m 전", fontSize = 15.sp)
        FilledTonalButton(onClick = onSettings, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Icon(painterResource(R.drawable.ic_settings), contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("안내 설정", fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun StatusCard(profiles: List<VehicleProfile>, selected: String?, state: DriveState, localError: String?, onOpenSettings: () -> Unit) {
    val current = state.takeIf { it.vin == selected }
    AppCard {
        Text("차량 연결", color = Muted, fontSize = 13.sp)
        if (state.active) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Mint)
        Text(if (profiles.isEmpty()) "차량을 등록해 주세요" else current?.status ?: "차량을 연결해 주세요", fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
        val error = localError ?: current?.error
        if (error != null) Text(error, color = MaterialTheme.colorScheme.error, fontSize = 14.sp)
        TextButton(onClick = onOpenSettings) { Text(if (profiles.isEmpty()) "설정에서 차량 등록" else "차량 설정 열기") }
    }
}

@Composable
private fun VehicleList(profiles: List<VehicleProfile>, selected: String?, state: DriveState,
    onAdd: (String, String) -> Unit, onSelect: (String) -> Unit) {
    var showAdd by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("내 차량", fontWeight = FontWeight.SemiBold)
            TextButton(onClick = { showAdd = true }, enabled = !state.active) { Text("+ 차량 등록") }
        }
        if (profiles.isEmpty()) {
            AppCard {
                Text("첫 차량을 등록해 주세요", fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                Text("Model 3 또는 Model Y의 차대번호(VIN)로 연결할 차량을 찾습니다.", color = Muted)
                FilledTonalButton(onClick = { showAdd = true }) { Text("차량 등록하기") }
            }
        } else {
            profiles.forEach { profile ->
                OutlinedButton(onClick = { onSelect(profile.vin) }, enabled = !state.active,
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (profile.vin == selected) Color(0xFF203732) else Color.Transparent,
                        contentColor = if (profile.vin == selected) Mint else Muted,
                        disabledContainerColor = if (profile.vin == selected) Color(0xFF203732) else Color.Transparent,
                        disabledContentColor = if (profile.vin == selected) Mint else Muted)) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(profile.model, fontWeight = FontWeight.SemiBold)
                        Text("••• ${profile.vin.takeLast(6)}${if (profile.vin == selected) "  ✓" else ""}")
                    }
                }
            }
        }
    }
    if (showAdd) VehicleDialog(onDismiss = { showAdd = false }, onSave = { model, vin -> onAdd(model, vin); showAdd = false })
}

@Composable
private fun AutoConnectCard(profiles: List<VehicleProfile>, autoEnabled: Boolean, autoStatus: String, onAutomatic: (Boolean) -> Unit) {
    AppCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("차량 접근 시 자동 연결", fontWeight = FontWeight.SemiBold)
                Text("연결 → 주행 시작 → 카메라 안내", color = Muted, fontSize = 12.sp)
            }
            Switch(checked = autoEnabled, onCheckedChange = onAutomatic, enabled = profiles.isNotEmpty(),
                modifier = Modifier.semantics { contentDescription = "자동 연결" })
        }
        Text(autoStatus, color = Muted, fontSize = 13.sp)
        Text("키 인증을 마친 등록 차량 중 먼저 감지된 차량에 자동 연결합니다. 각 차량은 처음 한 번 앱 키 등록과 차량 연결이 필요합니다.", color = Muted, fontSize = 11.sp)
        if (autoEnabled) TextButton(onClick = { onAutomatic(true) }) { Text("권한 확인 · 대기 재시작") }
    }
}

@Composable
private fun ConnectionCard(profiles: List<VehicleProfile>, selected: String?, state: DriveState, localError: String?,
    autoEnabled: Boolean, onConnect: (Boolean) -> Unit, onDisconnect: () -> Unit) {
    var showEnroll by remember { mutableStateOf(false) }
    val activeProfile = profiles.firstOrNull { it.vin == selected }
    val current = state.takeIf { it.vin == selected }
    AppCard {
        Text("차량 연결", color = Muted, fontSize = 13.sp)
        if (state.active && state.phase != DrivePhase.CONNECTED) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Mint)
        Text(current?.status ?: "차량을 연결해 주세요", fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
        if (state.phase == DrivePhase.PAIRING) Text("키 카드를 센터 콘솔의 카드 리더에 대고 차량 화면의 안내에 따라 승인해 주세요. 최대 90초 동안 확인합니다.", color = Muted)
        if (state.active) {
            OutlinedButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) { Text(if (autoEnabled) "연결 종료 · 자동 연결 끄기" else "연결 종료") }
        } else {
            Button(onClick = { onConnect(false) }, enabled = activeProfile != null, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("차량 연결", fontWeight = FontWeight.Bold) }
            TextButton(onClick = { showEnroll = true }, enabled = activeProfile != null, modifier = Modifier.fillMaxWidth()) { Text("처음 연결하나요? 앱 키 등록") }
        }
        val error = localError ?: current?.error
        if (error != null) Text(error, color = MaterialTheme.colorScheme.error, fontSize = 14.sp)
    }
    if (showEnroll) AlertDialog(onDismissRequest = { showEnroll = false },
        title = { Text("차량에 앱 키 등록") },
        text = { Text("${activeProfile?.model} 가까이에서 차량 화면을 켜고 키 카드를 준비해 주세요.\n\n등록 요청 후 센터 콘솔의 카드 리더에 키 카드를 대고 차량 화면에서 승인합니다. 차량 정보를 조회하는 앱 전용 키가 추가됩니다.") },
        confirmButton = { TextButton(onClick = { showEnroll = false; onConnect(true) }) { Text("키 등록 시작") } },
        dismissButton = { TextButton(onClick = { showEnroll = false }) { Text("취소") } })
}

@Composable
private fun DestinationCard(selected: String?, state: DriveState, onRefresh: () -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    val current = state.takeIf { it.vin == selected }
    val snapshot = current?.snapshot
    val age = current?.updatedAt?.let { ((now - it).coerceAtLeast(0) / 1000).toInt() }
    val stale = age != null && age >= 15
    val destination = snapshot?.destination
    AppCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("차량 내비게이션 목적지", fontSize = 13.sp, color = Muted)
            if (state.phase == DrivePhase.CONNECTED) TextButton(onClick = onRefresh) { Text("새로고침") }
        }
        val title = when {
            snapshot == null -> "연결을 기다리고 있어요"
            stale -> "목적지를 다시 확인하고 있어요"
            destination is NavigationDestination.Active -> destination.name ?: "좌표로 설정된 목적지"
            else -> "설정된 목적지가 없어요"
        }
        Text(title, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = if (snapshot != null && !stale) Mint else MaterialTheme.colorScheme.onSurface)
        if (!stale && destination is NavigationDestination.Active) {
            destination.point?.let { Text(String.format(Locale.US, "%.5f, %.5f", it.latitude, it.longitude), color = Muted, fontFamily = FontFamily.Monospace, fontSize = 13.sp) }
            val details = listOfNotNull(snapshot.minutesToArrival?.let { "약 ${it.toInt()}분" }, snapshot.distanceKm?.let { String.format(Locale.KOREA, "%.1f km", it) })
            if (details.isNotEmpty()) Text(details.joinToString(" · "), fontSize = 17.sp)
        }
        Text(when {
            age == null -> "연결 후 차량에서 목적지를 자동으로 읽습니다."
            stale -> "마지막 수신 ${age}초 전 · 표시한 목적지를 확인해 주세요."
            else -> "${age}초 전 확인 · 5초마다 갱신"
        }, color = Muted, fontSize = 12.sp)
    }
}

@Composable
private fun Diagnostics(selected: String?, state: DriveState) {
    var diagnostics by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    var diagnosticCopied by remember(state.events) { mutableStateOf(false) }
    val current = state.takeIf { it.vin == selected }
    TextButton(onClick = { diagnostics = !diagnostics }, modifier = Modifier.fillMaxWidth()) { Text(if (diagnostics) "연결 기록 접기" else "연결 기록 보기", color = Muted) }
    if (diagnostics) AppCard {
        Text("연결 기록", fontWeight = FontWeight.SemiBold)
        Text("최근 기록 160개 · 앱을 다시 열어도 유지됩니다", color = Muted, fontSize = 12.sp)
        TextButton(onClick = {
            clipboard.setText(AnnotatedString(buildString {
                appendLine("Tesla Navi ${BuildConfig.VERSION_NAME} / Android ${Build.VERSION.RELEASE} / ${Build.MODEL}")
                appendLine("단계: ${state.phase}")
                appendLine("상태: ${state.status}")
                state.error?.let { appendLine("오류: $it") }
                append(state.events.joinToString("\n"))
            }))
            diagnosticCopied = true
        }) { Text(if (diagnosticCopied) "연결 기록 복사됨" else "연결 기록 복사") }
        current?.fingerprint?.let { Text("앱 키 식별자\n$it", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Muted) }
        Text(if (state.events.isEmpty()) "연결 기록이 없습니다." else state.events.joinToString("\n"), color = Muted, fontSize = 12.sp)
    }
}

@Composable
private fun ColumnScope.VersionFooter() {
    Text("개인용 · Tesla Navi ${BuildConfig.VERSION_NAME}", Modifier.align(Alignment.CenterHorizontally), color = Muted, fontSize = 11.sp)
}

@Composable
private fun CameraCard(state: CameraState, hasKey: Boolean, onMute: (Boolean) -> Unit, options: GuidanceOptions) {
    AppCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("단속카메라 안내", fontWeight = FontWeight.SemiBold)
            if (hasKey) TextButton(onClick = { onMute(!state.muted) }) { Text(if (state.muted) "음성 켜기" else "음소거") }
        }
        if (state.phase == CameraPhase.INITIALIZING) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Mint)
        if (state.overSpeed) Text("과속 경고 · 제한속도보다 5 km/h 이상 빠릅니다", color = MaterialTheme.colorScheme.error,
            fontWeight = FontWeight.Bold)
        val next = state.overSpeedCamera ?: state.cameras.firstOrNull()
        if (next != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Surface(color = Color(0xFF203732), shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(next.speedLimitKph?.toString() ?: "—", fontSize = 36.sp, fontWeight = FontWeight.Bold, color = Mint)
                        Text("km/h", color = Mint, fontSize = 12.sp)
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(next.kind.label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text(formatCameraDistance(next.distanceMeters), fontSize = 27.sp, fontWeight = FontWeight.Bold, color = Mint)
                }
            }
            if (next.variableLimit) Text("가변 제한속도 · 현장 표지판을 확인해 주세요", color = Muted, fontSize = 12.sp)
            state.cameras.drop(1).forEach {
                Text("${it.kind.label} · ${formatCameraDistance(it.distanceMeters)}", color = Muted, fontSize = 13.sp)
            }
        } else Text(when (state.phase) {
            CameraPhase.GUIDING -> "안내 중인 단속카메라가 없습니다"
            CameraPhase.WAITING_GPS -> "현재 위치를 확인하고 있어요"
            else -> state.message
        }, fontSize = 17.sp, color = Muted)
        state.mode?.let {
            Text(if (it == NavigationMode.ROUTE) "카카오 경로 기준 · 테슬라 경로와 다를 수 있습니다" else "현재 주행 도로 기준 · 안전운행 모드", color = Muted, fontSize = 11.sp)
        }
        if (state.phase == CameraPhase.GUIDING) Text(when (state.roadClass) {
            RoadClass.HIGHWAY -> "고속도로 · 카메라 ${options.highwayMeters} m 전 안내"
            RoadClass.GENERAL -> "일반도로 · 카메라 ${options.generalMeters} m 전 안내"
            RoadClass.UNKNOWN -> "도로 종류 확인 중 · 카메라 ${options.highwayMeters} m 전 예비 안내"
        }, color = Muted, fontSize = 11.sp)
        if (state.mode != null) Text(state.message, color = Muted, fontSize = 12.sp)
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
        state.voiceError?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }

    }
}

private fun formatCameraDistance(meters: Int): String = if (meters >= 1000) String.format(Locale.KOREA, "%.1f km 앞", meters / 1000.0) else "$meters m 앞"

@Composable
private fun AppCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), color = Panel) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun VehicleDialog(onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var vin by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("Model 3") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("차량 등록") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    listOf("Model 3", "Model Y").forEach { item -> FilterChip(selected = model == item, onClick = { model = item }, label = { Text(item) }) }
                }
                OutlinedTextField(value = vin, onValueChange = { vin = VehicleIdentity.normalizeVin(it); error = null },
                    label = { Text("차대번호(VIN) 17자리") }, singleLine = true, isError = error != null,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, keyboardType = KeyboardType.Ascii),
                    supportingText = { Text(error ?: "Tesla 앱 또는 차량의 제어 > 소프트웨어에서 확인할 수 있습니다.") })
            }
        },
        confirmButton = { TextButton(onClick = {
            try { onSave(model, vin) } catch (failure: Exception) { error = failure.message ?: "차량 저장 실패" }
        }, enabled = vin.isNotBlank()) { Text("등록") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } })
}

@Composable
private fun GuidanceSettingsDialog(settings: GuidanceSettings, guidanceActive: Boolean, onDismiss: () -> Unit) {
    val options by settings.options.collectAsStateWithLifecycle()
    var volume by remember(options.volumePercent) { mutableFloatStateOf(options.volumePercent.toFloat()) }
    var highway by remember(options.highwayMeters) { mutableFloatStateOf(options.highwayMeters.toFloat()) }
    var general by remember(options.generalMeters) { mutableFloatStateOf(options.generalMeters.toFloat()) }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("안내 설정") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("안내 음량 ${volume.roundToInt()}%", fontWeight = FontWeight.SemiBold)
                Slider(value = volume, onValueChange = { volume = it }, onValueChangeFinished = { settings.volume(volume.roundToInt()) },
                    valueRange = 0f..100f, steps = 19, modifier = Modifier.semantics { contentDescription = "안내 음량" })
                Text("휴대폰 미디어 음량 기준 · 음성과 경고음에 함께 적용", fontSize = 12.sp, color = Muted)
                GuidanceSoundCheck(volume.roundToInt(), guidanceActive)
                Text("고속도로 ${highway.roundToInt()} m 전", fontWeight = FontWeight.SemiBold)
                Slider(value = highway, onValueChange = { highway = it }, onValueChangeFinished = { settings.highway(highway.roundToInt()) },
                    valueRange = 100f..2000f, steps = 18, modifier = Modifier.semantics { contentDescription = "고속도로 안내 거리" })
                Text("일반도로 ${general.roundToInt()} m 전", fontWeight = FontWeight.SemiBold)
                Slider(value = general, onValueChange = { general = it }, onValueChangeFinished = { settings.general(general.roundToInt()) },
                    valueRange = 100f..1000f, steps = 8, modifier = Modifier.semantics { contentDescription = "일반도로 안내 거리" })
                Text("카메라 사전 안내와 +5 km/h 과속 경고 구간에 적용됩니다. 도로 종류를 모르면 고속도로 설정을 사용합니다.", fontSize = 12.sp, color = Muted)
                Text("설정은 자동 저장됩니다. 카메라 정보 수신 시점에 따라 안내가 늦어질 수 있습니다.", fontSize = 12.sp, color = Muted)
                TextButton(onClick = { settings.reset() }) { Text("기본값 복원") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } })
}
