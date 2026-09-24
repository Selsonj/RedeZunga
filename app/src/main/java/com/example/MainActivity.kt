package com.example

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.database.GroupEntity
import com.example.data.database.MessageEntity
import com.example.data.database.PeerEntity
import com.example.ui.ZungaScreen
import com.example.ui.ZungaViewModel
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Professional Polish Light Theme matching enterprise guidelines
val SlateBackground = Color(0xFFFDFBFF)
val SlateCard = Color(0xFFFFFFFF)
val SlateCardSecondary = Color(0xFFF3F4F9)
val GoldAccent = Color(0xFF005AC1) // Indigo/Blue Primary
val CrimsonAccent = Color(0xFFBA1A1A) // Red Accent/Danger
val EmeraldConnected = Color(0xFF00875A) // Connected green
val TextLight = Color(0xFF1C1B1F) // Primary Text (Dark)
val TextMuted = Color(0xFF5D5E67) // Secondary Muted Text

class MainActivity : ComponentActivity() {
    private val viewModel: ZungaViewModel by viewModels()

    private val requestBluetoothLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            Toast.makeText(this, "Bluetooth ativado com sucesso!", Toast.LENGTH_SHORT).show()
            viewModel.meshEngine?.startBle()
        } else {
            Toast.makeText(this, "O Bluetooth deve estar ativado para a descoberta real via BLE.", Toast.LENGTH_LONG).show()
        }
    }

    fun checkAndPromptBluetooth() {
        val bluetoothManager = getSystemService(android.content.Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
        val adapter = bluetoothManager?.adapter
        if (adapter != null && !adapter.isEnabled) {
            try {
                val enableBtIntent = android.content.Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE)
                requestBluetoothLauncher.launch(enableBtIntent)
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Sem permissao bluetooth para abrir o dialogo de activacao", e)
            }
        }
    }

    private fun hasRequiredWifiP2pPermissions(): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            checkSelfPermission(android.Manifest.permission.NEARBY_WIFI_DEVICES) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    private fun checkHardwareStatus() {
        val wifiManager = applicationContext.getSystemService(android.content.Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
        if (wifiManager != null && !wifiManager.isWifiEnabled) {
            Toast.makeText(this, "Aviso: O Wi-Fi está desligado. Ative o Wi-Fi para que a rede mesh offline funcione.", Toast.LENGTH_LONG).show()
        }
        if (android.os.Build.VERSION.SDK_INT <= android.os.Build.VERSION_CODES.S_V2) {
            val locationManager = getSystemService(android.content.Context.LOCATION_SERVICE) as? android.location.LocationManager
            val isGpsEnabled = locationManager?.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) == true
            val isNetEnabled = locationManager?.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER) == true
            if (!isGpsEnabled && !isNetEnabled) {
                Toast.makeText(this, "Aviso: No Android 12 ou inferior, ative a Localização do dispositivo para o Wi-Fi Direct funcionar.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (hasRequiredWifiP2pPermissions()) {
            Toast.makeText(this, "Permissões de rede próxima concedidas!", Toast.LENGTH_SHORT).show()
            checkHardwareStatus()
            viewModel.meshEngine?.startServices()
            checkAndPromptBluetooth()
        } else {
            Toast.makeText(this, "As permissões de rede próxima são necessárias para a comunicação offline.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val permissions = mutableListOf(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            permissions.add(android.Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(android.Manifest.permission.BLUETOOTH_ADVERTISE)
            permissions.add(android.Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            permissions.add(android.Manifest.permission.NEARBY_WIFI_DEVICES)
        }

        if (hasRequiredWifiP2pPermissions()) {
            checkHardwareStatus()
            viewModel.meshEngine?.startServices()
            val hasBtConnect = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED
            } else true
            if (hasBtConnect) {
                checkAndPromptBluetooth()
            }
        } else {
            requestPermissionLauncher.launch(permissions.toTypedArray())
        }

        enableEdgeToEdge()
        setContent {
            MyApplicationTheme(darkTheme = false) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = SlateBackground
                ) {
                    val initErr = viewModel.initializationError
                    if (initErr != null) {
                        InitializationErrorScreen(initErr)
                    } else {
                        val currentScreen by viewModel.currentScreen.collectAsStateWithLifecycle()
                        
                        // Manage android native back presses on sub-screens gracefully
                        BackHandler(enabled = currentScreen != ZungaScreen.Splash && currentScreen != ZungaScreen.Dashboard) {
                            viewModel.navigateTo(ZungaScreen.Dashboard)
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .windowInsetsPadding(WindowInsets.statusBars)
                                .windowInsetsPadding(WindowInsets.navigationBars)
                        ) {
                            Crossfade(
                                targetState = currentScreen,
                                animationSpec = tween(durationMillis = 350)
                            ) { screen ->
                                when (screen) {
                                    is ZungaScreen.Splash -> SplashScreen(viewModel)
                                    is ZungaScreen.Onboarding -> OnboardingScreen(viewModel)
                                    is ZungaScreen.Dashboard -> DashboardScreen(viewModel)
                                    is ZungaScreen.ChatRoom -> ChatRoomScreen(viewModel, screen.id, screen.name, screen.isGroup)
                                    is ZungaScreen.VoiceCall -> VoiceCallScreen(viewModel, screen.peerName, screen.isIncoming)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun InitializationErrorScreen(errorText: String) {
    val context = LocalContext.current
    var isWiping by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0E1116))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Spacer(modifier = Modifier.height(32.dp))
        
        Text(
            text = "REDE ZUNGA DIAGNOSTIC CONSOLE",
            color = Color(0xFFFFB300),
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFFBA1A1A).copy(alpha = 0.15f)),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFBA1A1A)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "CRITICAL ENGINE SYSTEM INOPERABLE \nOffline mesh components could not start because of a database mismatch or hardware signature issue.",
                color = Color(0xFFFFDAD6),
                fontSize = 13.sp,
                fontFamily = FontFamily.SansSerif,
                modifier = Modifier.padding(16.dp),
                textAlign = TextAlign.Center
            )
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = "Stacktrace & Log Diagnostics:",
            color = Color(0xFF90A4AE),
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.align(Alignment.Start)
        )
        
        Spacer(modifier = Modifier.height(6.dp))
        
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color(0xFF181C24), shape = RoundedCornerShape(8.dp))
                .border(2.dp, Color(0xFF222834), shape = RoundedCornerShape(8.dp))
                .padding(12.dp)
        ) {
            val scrollState = rememberScrollState()
            Text(
                text = errorText,
                color = Color(0xFFECEFF1).copy(alpha = 0.9f),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
            )
        }
        
        Spacer(modifier = Modifier.height(20.dp))
        
        Button(
            onClick = {
                isWiping = true
                try {
                    context.deleteDatabase("zunga_mesh_database")
                    Toast.makeText(context, "Storage wiped. Restarting app...", Toast.LENGTH_LONG).show()
                    android.os.Process.killProcess(android.os.Process.myPid())
                    System.exit(0)
                } catch (e: Exception) {
                    Toast.makeText(context, "Wipe failed: ${e.message}", Toast.LENGTH_LONG).show()
                } finally {
                    isWiping = false
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFBA1A1A)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = if (isWiping) "WIPING..." else "WIPE LOCAL ENGINE STORAGE & RESTART",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp
            )
        }
        
        Spacer(modifier = Modifier.height(12.dp))
        
        Text(
            text = "This will wipe corrupt SQLite files and re-provision crypto identity, fixing database migration issues immediately.",
            color = Color(0xFF5D5E67),
            fontSize = 10.sp,
            textAlign = TextAlign.Center,
            fontFamily = FontFamily.SansSerif
        )
        
        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
fun SplashScreen(viewModel: ZungaViewModel) {
    val infiniteTransition = rememberInfiniteTransition()
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        )
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(SlateBackground, Color(0xFFEDEEF6))
                )
            )
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // App mascot logo / icon visual representations
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(140.dp)
                .background(SlateCard, CircleShape)
                .border(2.dp, GoldAccent, CircleShape)
                .padding(16.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Share,
                contentDescription = "Zunga Logo",
                tint = GoldAccent,
                modifier = Modifier
                    .size(68.dp)
            )
            
            // Pulse rings surrounding the hub
            Box(
                modifier = Modifier
                    .size(130.dp)
                    .border(
                        width = 1.dp,
                        color = GoldAccent.copy(alpha = 0.25f),
                        shape = CircleShape
                    )
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = "REDE ZUNGA",
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            color = GoldAccent,
            fontFamily = FontFamily.SansSerif,
            letterSpacing = 4.sp
        )

        Text(
            text = "Comunicação Descentralizada Offline-First",
            fontSize = 14.sp,
            color = TextMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Grid pattern highlight representing peer map
        Box(
            modifier = Modifier
                .fillMaxWidth(0.8f)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(GoldAccent.copy(alpha = 0.2f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.4f)
                    .fillMaxHeight()
                    .background(CrimsonAccent)
                    .align(Alignment.Center)
            )
        }

        Spacer(modifier = Modifier.height(64.dp))

        ElevatedButton(
            onClick = { viewModel.navigateTo(ZungaScreen.Onboarding) },
            colors = ButtonDefaults.elevatedButtonColors(
                containerColor = GoldAccent,
                contentColor = SlateBackground
            ),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
        ) {
            Text(
                text = "COMEÇAR AGORA",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                letterSpacing = 1.sp
            )
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = "Inspirado pela força dos zungueiros de Angola. P2P Mesh.",
            fontSize = 12.sp,
            color = TextMuted.copy(alpha = 0.7f),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun OnboardingScreen(viewModel: ZungaViewModel) {
    var step by remember { mutableStateOf(1) }
    var inputName by remember { mutableStateOf(viewModel.myNodeName.value) }
    var selectedLocation by remember { mutableStateOf("Moçâmedes Centro, Namibe") }
    var generatingKeys by remember { mutableStateOf(false) }
    var keyProgress by remember { mutableStateOf(0.0f) }
    
    val locations = listOf(
        "Moçâmedes Centro, Namibe",
        "Praia Amélia, Moçâmedes",
        "Bairro Facim, Moçâmedes",
        "Torre do Tombo, Moçâmedes",
        "Saco Mar, Moçâmedes",
        "Aeroporto, Moçâmedes",
        "Bibala, Namibe",
        "Camucuio, Namibe",
        "Tômbua, Namibe"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SlateBackground)
            .padding(24.dp)
    ) {
        IconButton(
            onClick = {
                if (step > 1) step-- else viewModel.navigateTo(ZungaScreen.Splash)
            },
            modifier = Modifier.padding(top = 8.dp)
        ) {
            Icon(Icons.Filled.ArrowBack, contentDescription = "Voltar", tint = TextLight)
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (step == 1) {
            Text(
                text = "Como queres ser identificado?",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = TextLight
            )
            
            Text(
                text = "Este apelido será visto pelos dispositivos vizinhos na rede mesh.",
                fontSize = 14.sp,
                color = TextMuted,
                modifier = Modifier.padding(top = 8.dp, bottom = 24.dp)
            )

            OutlinedTextField(
                value = inputName,
                onValueChange = { inputName = it },
                label = { Text("Nome do seu nó", color = GoldAccent) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = GoldAccent,
                    unfocusedBorderColor = TextMuted,
                    focusedTextColor = TextLight,
                    unfocusedTextColor = TextLight
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(modifier = Modifier.weight(1f))

            Button(
                onClick = { step = 2 },
                enabled = inputName.trim().isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = GoldAccent, contentColor = SlateBackground),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
            ) {
                Text("PROSSEGUIR", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        } else if (step == 2) {
            Text(
                text = "Selecione a sua Região",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = TextLight
            )
            
            Text(
                text = "Ajuda os repetidores locais a traçar rotas eficientes para as mensagens.",
                fontSize = 14.sp,
                color = TextMuted,
                modifier = Modifier.padding(top = 8.dp, bottom = 24.dp)
            )

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                items(locations) { loc ->
                    val isSelected = loc == selectedLocation
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isSelected) SlateCardSecondary else SlateCard)
                            .border(
                                width = if (isSelected) 1.5.dp else 0.dp,
                                color = if (isSelected) GoldAccent else Color.Transparent,
                                shape = RoundedCornerShape(12.dp)
                            )
                            .clickable { selectedLocation = loc }
                            .padding(16.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Filled.LocationOn,
                                contentDescription = "Região",
                                tint = if (isSelected) GoldAccent else TextMuted
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(
                                text = loc,
                                color = if (isSelected) GoldAccent else TextLight,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                fontSize = 16.sp
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = { 
                    step = 3
                    generatingKeys = true
                    viewModel.updateProfile(inputName, selectedLocation)
                },
                colors = ButtonDefaults.buttonColors(containerColor = GoldAccent, contentColor = SlateBackground),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
            ) {
                Text("GERAR CHAVE CRIPTOGRÁFICA", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        } else {
            // Key generation progress display
            LaunchedEffect(generatingKeys) {
                if (generatingKeys) {
                    while (keyProgress < 1.0f) {
                        delay(120)
                        keyProgress += 0.08f
                    }
                    generatingKeys = false
                }
            }

            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                if (generatingKeys) {
                    CircularProgressIndicator(
                        progress = { keyProgress },
                        color = GoldAccent,
                        strokeWidth = 6.dp,
                        modifier = Modifier.size(80.dp)
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        text = "Gerando segredos locais...",
                        color = GoldAccent,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Calculando par de chaves RSA-2048 para assinatura das suas mensagens offline. Isto é feito inteiramente no seu aparelho, sem servidores.",
                        color = TextMuted,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = "Sucesso",
                        tint = EmeraldConnected,
                        modifier = Modifier.size(96.dp)
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        text = "Identidade Criptográfica Ativa!",
                        color = TextLight,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Sua chave pública:\n${viewModel.publicKeyStr.take(30)}...${viewModel.publicKeyStr.takeLast(30)}",
                        color = TextMuted,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                            .background(SlateCard, RoundedCornerShape(8.dp))
                            .padding(12.dp)
                    )

                    Spacer(modifier = Modifier.height(32.dp))

                    Button(
                        onClick = { viewModel.navigateTo(ZungaScreen.Dashboard) },
                        colors = ButtonDefaults.buttonColors(containerColor = GoldAccent, contentColor = SlateBackground),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth(0.8f)
                            .height(54.dp)
                    ) {
                        Text("ENTRAR NA REDE", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun DashboardScreen(viewModel: ZungaViewModel) {
    var selectedTab by remember { mutableIntStateOf(0) }
    
    val myName by viewModel.myNodeName.collectAsStateWithLifecycle()
    val myLoc by viewModel.myLocation.collectAsStateWithLifecycle()
    val listPeers by viewModel.activePeers.collectAsStateWithLifecycle()
    val listGroups by viewModel.groups.collectAsStateWithLifecycle()
    val listChats by viewModel.conversations.collectAsStateWithLifecycle()

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = SlateCard,
                modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars)
            ) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Filled.Chat, contentDescription = "Chats") },
                    label = { Text("Conversas") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = GoldAccent,
                        selectedTextColor = GoldAccent,
                        unselectedIconColor = TextMuted,
                        unselectedTextColor = TextMuted,
                        indicatorColor = SlateCardSecondary
                    )
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Filled.People, contentDescription = "Peers") },
                    label = { Text("Vizinhos") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = GoldAccent,
                        selectedTextColor = GoldAccent,
                        unselectedIconColor = TextMuted,
                        unselectedTextColor = TextMuted,
                        indicatorColor = SlateCardSecondary
                    )
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Filled.Hub, contentDescription = "Mesh Map") },
                    label = { Text("Rede Map") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = GoldAccent,
                        selectedTextColor = GoldAccent,
                        unselectedIconColor = TextMuted,
                        unselectedTextColor = TextMuted,
                        indicatorColor = SlateCardSecondary
                    )
                )
                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = "Settings") },
                    label = { Text("Aplicações") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = GoldAccent,
                        selectedTextColor = GoldAccent,
                        unselectedIconColor = TextMuted,
                        unselectedTextColor = TextMuted,
                        indicatorColor = SlateCardSecondary
                    )
                )
            }
        },
        containerColor = SlateBackground
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (selectedTab) {
                0 -> ChatsTab(viewModel, listChats, listGroups)
                1 -> PeersTab(viewModel, listPeers)
                2 -> MeshMapTab(viewModel, listPeers)
                3 -> ProfileTab(viewModel, myName, myLoc)
            }
        }
    }
}

@Composable
fun ChatsTab(
    viewModel: ZungaViewModel,
    chats: List<MessageEntity>,
    groups: List<GroupEntity>
) {
    var showCreateGroupDialog by remember { mutableStateOf(false) }
    var groupInputName by remember { mutableStateOf("") }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    Text(
                        text = "Conversas",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextLight
                    )
                    Text(
                        text = "Dispositivos Wi-Fi NSD ativos",
                        fontSize = 13.sp,
                        color = EmeraldConnected,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }

                FilledTonalButton(
                    onClick = { showCreateGroupDialog = true },
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = SlateCard,
                        contentColor = GoldAccent
                    )
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "Novo Canal")
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Criar Canal")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            HardwareStatusBanner()

            Spacer(modifier = Modifier.height(8.dp))

            // Sub title
            Text(
                text = "CANAIS DA COMUNIDADE (P2P)",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = TextMuted,
                letterSpacing = 1.sp,
                modifier = Modifier.padding(vertical = 8.dp)
            )

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(groups) { grp ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(SlateCard)
                            .clickable {
                                viewModel.navigateTo(ZungaScreen.ChatRoom(grp.id, grp.name, isGroup = true))
                            }
                            .padding(16.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(46.dp)
                                    .background(GoldAccent.copy(alpha = 0.15f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Filled.Groups, contentDescription = "Grupo", tint = GoldAccent)
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = grp.name,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextLight
                                )
                                Text(
                                    text = "${grp.membersCount} repetidores sintonizados",
                                    fontSize = 12.sp,
                                    color = TextMuted
                                )
                            }
                            Icon(Icons.Filled.ChevronRight, contentDescription = "Abrir", tint = TextMuted)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "MENSAGENS DIRETAS",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = TextMuted,
                letterSpacing = 1.sp,
                modifier = Modifier.padding(vertical = 8.dp)
            )

            if (chats.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Filled.SmsFailed,
                            contentDescription = "Vazio",
                            tint = TextMuted,
                            modifier = Modifier.size(48.dp)
                        )
                        Text(
                            text = "Sem conversas diretas. Abra a aba 'Vizinhos' para iniciar um papo com alguém próximo!",
                            color = TextMuted,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(24.dp)
                        )
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    items(chats.filter { !it.isGroup }) { chat ->
                        val recipientName = if (chat.senderId == viewModel.myNodeId) chat.receiverId else chat.senderName
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(SlateCard)
                                .clickable {
                                    val id = if (chat.senderId == viewModel.myNodeId) chat.receiverId else chat.senderId
                                    viewModel.navigateTo(ZungaScreen.ChatRoom(id, recipientName, isGroup = false))
                                }
                                .padding(16.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(46.dp)
                                        .background(SlateCardSecondary, CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Person,
                                        contentDescription = "Avatar",
                                        tint = GoldAccent
                                    )
                                }
                                Spacer(modifier = Modifier.width(16.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = if (chat.senderId == viewModel.myNodeId) "Vizinho Mesh" else chat.senderName,
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = TextLight,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "Ativo",
                                            fontSize = 11.sp,
                                            color = EmeraldConnected,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    Text(
                                        text = chat.content,
                                        fontSize = 13.sp,
                                        color = TextMuted,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(top = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Dialog to create a community channel
        if (showCreateGroupDialog) {
            Dialog(onDismissRequest = { showCreateGroupDialog = false }) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(SlateCard)
                        .padding(20.dp)
                ) {
                    Column {
                        Text(
                            text = "Criar Novo Canal P2P",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextLight
                        )
                        Text(
                            text = "Canais funcionam como repetidores automáticos de rádio: as mensagens são retransmitidas para todos na rede que sintonizarem esse nome.",
                            fontSize = 13.sp,
                            color = TextMuted,
                            modifier = Modifier.padding(top = 8.dp, bottom = 16.dp)
                        )

                        OutlinedTextField(
                            value = groupInputName,
                            onValueChange = { groupInputName = it },
                            label = { Text("Nome do Canal (ex: Mercado Asa Branca)", color = GoldAccent) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = GoldAccent,
                                unfocusedBorderColor = TextMuted,
                                focusedTextColor = TextLight,
                                unfocusedTextColor = TextLight
                            ),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(24.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(onClick = { showCreateGroupDialog = false }) {
                                Text("CANCELAR", color = TextMuted)
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Button(
                                onClick = {
                                    if (groupInputName.trim().isNotEmpty()) {
                                        viewModel.createGroupChannel(groupInputName.trim())
                                        groupInputName = ""
                                        showCreateGroupDialog = false
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = GoldAccent, contentColor = SlateBackground)
                            ) {
                                Text("CRIAR", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun HardwareStatusBanner() {
    val context = LocalContext.current
    var isBtEnabled by remember { mutableStateOf(true) }
    var isWifiEnabled by remember { mutableStateOf(true) }
    var isLocationEnabled by remember { mutableStateOf(true) }

    // Run active hardware status checks reactively
    LaunchedEffect(Unit) {
        while (true) {
            val bluetoothManager = context.getSystemService(android.content.Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
            isBtEnabled = bluetoothManager?.adapter?.isEnabled == true

            val wifiManager = context.applicationContext.getSystemService(android.content.Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
            isWifiEnabled = wifiManager?.isWifiEnabled == true

            val locationManager = context.getSystemService(android.content.Context.LOCATION_SERVICE) as? android.location.LocationManager
            isLocationEnabled = locationManager?.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) == true ||
                                locationManager?.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER) == true

            delay(2000) // check status every 2 seconds
        }
    }

    if (!isBtEnabled || !isWifiEnabled || !isLocationEnabled) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFFFFDAD6))
                .border(1.dp, Color(0xFFBA1A1A), RoundedCornerShape(12.dp))
                .padding(12.dp)
        ) {
            Text(
                text = "ALERTA: HARDWARE AD-HOC INATIVO",
                color = Color(0xFF410002),
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Para que a descoberta local real funcione entre aparelhos sem internet na província do Namibe, o Wi-Fi, Bluetooth e GPS (Localização) devem estar ativados.",
                color = Color(0xFF410002),
                fontSize = 11.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (!isBtEnabled) {
                    Button(
                        onClick = {
                            try {
                                val enableBtIntent = android.content.Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE)
                                context.startActivity(enableBtIntent)
                            } catch (e: Exception) {
                                Toast.makeText(context, "Ative o Bluetooth nas configurações rápidas do aparelho.", Toast.LENGTH_LONG).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFBA1A1A)),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Icon(Icons.Filled.Bluetooth, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.White)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("ATIVAR BT", fontSize = 10.sp, color = Color.White)
                    }
                }
                if (!isWifiEnabled) {
                    Button(
                        onClick = {
                            try {
                                if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
                                    val wifiManager = context.applicationContext.getSystemService(android.content.Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
                                    wifiManager?.isWifiEnabled = true
                                } else {
                                    context.startActivity(android.content.Intent(android.provider.Settings.ACTION_WIFI_SETTINGS))
                                }
                            } catch (e: Exception) {
                                Toast.makeText(context, "Ative o Wi-Fi nas configurações.", Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFBA1A1A)),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Icon(Icons.Filled.Wifi, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.White)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("ATIVAR WI-FI", fontSize = 10.sp, color = Color.White)
                    }
                }
                if (!isLocationEnabled) {
                    Button(
                        onClick = {
                            try {
                                context.startActivity(android.content.Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                            } catch (e: Exception) {}
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFBA1A1A)),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Icon(Icons.Filled.LocationOn, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.White)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("ATIVAR GPS", fontSize = 10.sp, color = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
fun OfflineConnectionAssistant(viewModel: ZungaViewModel) {
    val context = LocalContext.current
    var isExpanded by remember { mutableStateOf(false) }
    val connectionStatus by viewModel.connectionStatus.collectAsStateWithLifecycle()

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = SlateCard),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, GoldAccent.copy(alpha = 0.35f))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.WifiTethering,
                        contentDescription = "Hotspot",
                        tint = GoldAccent,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Ligar Aparelhos Estranhos",
                            color = TextLight,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Como funciona a conexão sem roteador?",
                            color = TextMuted,
                            fontSize = 11.sp
                        )
                    }
                }
                Icon(
                    imageVector = if (isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = "Expandir",
                    tint = GoldAccent
                )
            }

            if (isExpanded) {
                Spacer(modifier = Modifier.height(14.dp))
                
                Text(
                    text = "Para que telemóveis novos ou estranhos se conectem sem internet nem roteador doméstico, o ZungaMesh permite buscar e aceder a redes locais ad-hoc nas proximidades do Namibe de forma imediata.",
                    color = TextMuted,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )

                Spacer(modifier = Modifier.height(14.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(SlateCardSecondary)
                        .padding(12.dp)
                ) {
                    Text(
                        text = "CONEXÃO P2P DIRECTA",
                        color = GoldAccent,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Unir à Rede do Vizinho",
                        color = TextLight,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                    Text(
                        text = "O sistema ligará o seu Wi-Fi diretamente ao ponto de transmissão offline 'ZungaMesh' mais próximo e restabelecerá a comunicação local para chats e chamadas.",
                        color = TextMuted,
                        fontSize = 11.sp,
                        lineHeight = 14.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = { viewModel.connectToZungaHotspot(context) },
                        colors = ButtonDefaults.buttonColors(containerColor = EmeraldConnected, contentColor = Color.White),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().height(40.dp)
                    ) {
                        Icon(Icons.Filled.Wifi, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("PROCURAR E UNIR VIZINHO", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }

                if (connectionStatus != null) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(GoldAccent.copy(alpha = 0.12f))
                            .padding(8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = connectionStatus ?: "",
                                color = TextLight,
                                fontSize = 11.sp,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = { viewModel.resetConnectionStatus() },
                                modifier = Modifier.size(20.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = "Fechar",
                                    tint = TextMuted,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PeersTab(viewModel: ZungaViewModel, peers: List<PeerEntity>) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Column {
            Text(
                text = "Vizinhos Próximos",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = TextLight
            )
            val directCount = peers.count { it.isDirectNeighbor }
            Text(
                text = if (directCount == 1) "1 dispositivo próximo" else "$directCount dispositivos próximos",
                fontSize = 14.sp,
                color = EmeraldConnected,
                modifier = Modifier.padding(top = 2.dp)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        HardwareStatusBanner()

        Spacer(modifier = Modifier.height(4.dp))

        OfflineConnectionAssistant(viewModel)

        Spacer(modifier = Modifier.height(8.dp))

        if (peers.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = GoldAccent)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Escaneando vizinhos locais via Wi-Fi NSD e Bluetooth...\nCertifique-se de que o outro dispositivo tem o ZungaMesh aberto.",
                        color = TextMuted,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                items(peers) { peer ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(SlateCard)
                            .padding(14.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Status icon indicating link type
                            Box(
                                modifier = Modifier
                                    .size(46.dp)
                                    .background(SlateCardSecondary, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (peer.connectionType == "BLUETOOTH") Icons.Filled.Bluetooth else Icons.Filled.Wifi,
                                    contentDescription = "Tipo link",
                                    tint = if (peer.isDirectNeighbor) GoldAccent else TextMuted
                                )
                            }
                            
                            Spacer(modifier = Modifier.width(14.dp))
                            
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = peer.name,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextLight
                                )
                                Text(
                                    text = "${peer.deviceModel} • ${peer.location}",
                                    fontSize = 12.sp,
                                    color = TextMuted
                                )
                                Row(
                                    modifier = Modifier.padding(top = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .background(
                                                if (peer.isDirectNeighbor) EmeraldConnected else Color(0xFFC0392B),
                                                CircleShape
                                            )
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = if (peer.isDirectNeighbor) "Alcance Direto (1 Hop)" else "Multi-hop (Repetidores)",
                                        fontSize = 11.sp,
                                        color = if (peer.isDirectNeighbor) EmeraldConnected else GoldAccent,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }

                            // Quick action triggers
                            Row {
                                IconButton(
                                    onClick = { viewModel.initiateVoiceCall(peer.id, peer.name) }
                                ) {
                                    Icon(Icons.Filled.Phone, contentDescription = "Ligar", tint = GoldAccent)
                                }
                                IconButton(
                                    onClick = {
                                        viewModel.navigateTo(ZungaScreen.ChatRoom(peer.id, peer.name, isGroup = false))
                                    }
                                ) {
                                    Icon(Icons.Filled.Chat, contentDescription = "Chat", tint = TextLight)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MeshMapTab(viewModel: ZungaViewModel, peers: List<PeerEntity>) {
    val hopsHistory by viewModel.routingHopsTrace.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Mapa da Rede Mesh Namibe",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = TextLight,
            modifier = Modifier.align(Alignment.Start)
        )
        Text(
            text = "Visualização em tempo real das conexões físicas e lógicas descentralizadas.",
            fontSize = 12.sp,
            color = TextMuted,
            modifier = Modifier
                .align(Alignment.Start)
                .padding(bottom = 12.dp)
        )

        // Custom drawn Canvas SVG-like layout for Mesh Graph Map
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(SlateCard)
                .border(1.dp, GoldAccent.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2, size.height / 2)
                
                // Draw local center node (Me)
                drawCircle(
                    color = GoldAccent.copy(alpha = 0.15f),
                    radius = 45f,
                    center = center
                )
                drawCircle(
                    color = GoldAccent,
                    radius = 16f,
                    center = center
                )

                // Render lines and nodes on circumference
                val numPeers = peers.size.coerceAtLeast(1)
                val radius = 170f
                val angleStep = (2 * Math.PI) / numPeers

                for (i in peers.indices) {
                    val angle = i * angleStep
                    val nodeX = (center.x + radius * Math.cos(angle)).toFloat()
                    val nodeY = (center.y + radius * Math.sin(angle)).toFloat()
                    val nodeOffset = Offset(nodeX, nodeY)

                    // Draw connecting physical lines
                    drawLine(
                        color = if (peers[i].isDirectNeighbor) GoldAccent.copy(alpha = 0.6f) else TextMuted.copy(alpha = 0.2f),
                        start = center,
                        end = nodeOffset,
                        strokeWidth = if (peers[i].isDirectNeighbor) 3f else 1f,
                        pathEffect = if (!peers[i].isDirectNeighbor) PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f) else null
                    )

                    // Draw node circles
                    drawCircle(
                        color = if (peers[i].isDirectNeighbor) EmeraldConnected else TextMuted,
                        radius = 10f,
                        center = nodeOffset
                    )
                }
            }

            // Labels overlaid
            Text(
                text = "EU (Você)",
                color = GoldAccent,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(top = 44.dp)
            )
            
            Text(
                text = "Peers conectados: ${peers.size}",
                color = TextLight,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
                    .background(SlateCardSecondary, RoundedCornerShape(4.dp))
                    .padding(6.dp)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Traceback diagnostics section
        Card(
            colors = CardDefaults.cardColors(containerColor = SlateCard),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "DIAGNÓSTICO DO ROTEADOR P2P",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextMuted,
                    letterSpacing = 1.sp
                )
                
                Spacer(modifier = Modifier.height(10.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.NetworkCheck, contentDescription = "Router status", tint = GoldAccent)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Função de Repetidor (Router)", color = TextLight, fontSize = 14.sp)
                    }
                    Text(
                        text = "ATIVO",
                        color = EmeraldConnected,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = "Último rastreamento de salto (Message Hops Trace):",
                    fontSize = 12.sp,
                    color = TextMuted,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(modifier = Modifier.height(10.dp))

                if (hopsHistory.isEmpty()) {
                    Text(
                        text = "Envie uma mensagem em um chat para rastrear o caminho mecânico dos pacotes.",
                        color = TextMuted,
                        fontSize = 12.sp,
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(SlateCardSecondary, RoundedCornerShape(8.dp))
                            .padding(12.dp)
                    )
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(SlateCardSecondary, RoundedCornerShape(8.dp))
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        hopsHistory.forEachIndexed { idx, hop ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        imageVector = if (idx == 0) Icons.Filled.PersonPin else if (idx == hopsHistory.size - 1) Icons.Filled.CellTower else Icons.Filled.AltRoute,
                                        contentDescription = "Salto",
                                        tint = if (idx == hopsHistory.size - 1) GoldAccent else TextLight,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Text(hop.split(" ").firstOrNull() ?: hop, color = TextLight, fontSize = 9.sp)
                                }
                                if (idx < hopsHistory.size - 1) {
                                    Icon(
                                        imageVector = Icons.Filled.TrendingFlat,
                                        contentDescription = "Frente",
                                        tint = GoldAccent,
                                        modifier = Modifier
                                            .padding(horizontal = 4.dp)
                                            .size(16.dp)
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

@Composable
fun ProfileTab(viewModel: ZungaViewModel, name: String, location: String) {
    val context = LocalContext.current
    val isBatterySaver by viewModel.isBatterySaver.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Perfil do Meu Dispositivo", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = TextLight)

        // Custom info box displaying local details
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(SlateCard)
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .background(GoldAccent.copy(alpha = 0.15f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.ManageAccounts, contentDescription = "Me Details", tint = GoldAccent, modifier = Modifier.size(32.dp))
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(name, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = TextLight)
                    Text(location, color = TextMuted, fontSize = 14.sp)
                    Text("Node ID: ${viewModel.myNodeId.take(12)}...", color = GoldAccent, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }

        // Encryption keys explorer panel
        Card(
            colors = CardDefaults.cardColors(containerColor = SlateCard),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "CHAVE CRIPTOGRÁFICA REDE ZUNGA",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextMuted,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "Suas conversas privadas usam end-to-end encryption. Suas chaves públicas são anexadas automaticamente durante a descoberta mDNS.",
                    fontSize = 12.sp,
                    color = TextMuted
                )
                Spacer(modifier = Modifier.height(12.dp))
                
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SlateCardSecondary, RoundedCornerShape(8.dp))
                        .padding(10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = viewModel.publicKeyStr.take(38) + "...",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = TextLight
                    )
                    IconButton(
                        onClick = {
                            Toast.makeText(context, "Chave pública copiada para área de transferência!", Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = "Copiar", tint = GoldAccent)
                    }
                }
            }
        }

        // Options toggles
        Card(
            colors = CardDefaults.cardColors(containerColor = SlateCard),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "OTIMIZAÇÕES DE ENERGIA",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextMuted,
                    letterSpacing = 1.sp
                )
                
                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Modo Bateria Baixa (África)", color = TextLight, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Text("Diminui o ping de handshake para 30 segundos, economizando até 40% de bateria de rádio.", color = TextMuted, fontSize = 12.sp)
                    }
                    Switch(
                        checked = isBatterySaver,
                        onCheckedChange = { viewModel.setBatterySaverEnabled(it) },
                        colors = SwitchDefaults.colors(checkedTrackColor = GoldAccent, checkedThumbColor = SlateBackground)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))
    }
}

@Composable
fun ChatRoomScreen(
    viewModel: ZungaViewModel,
    id: String,
    name: String,
    isGroup: Boolean
) {
    val messages by viewModel.activeChatMessages.collectAsStateWithLifecycle()
    var inputMsgField by remember { mutableStateOf("") }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SlateBackground)
    ) {
        // Chat screen custom Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(SlateCard)
                .padding(horizontal = 12.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { viewModel.navigateTo(ZungaScreen.Dashboard) }) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Voltar", tint = TextLight)
            }
            
            Spacer(modifier = Modifier.width(6.dp))

            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(SlateCardSecondary, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isGroup) Icons.Filled.Groups else Icons.Filled.Person,
                    contentDescription = "Avatar",
                    tint = GoldAccent
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextLight,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (isGroup) "Canal Público Mesh" else "Criptografada ponta-a-ponta",
                    fontSize = 11.sp,
                    color = if (isGroup) GoldAccent else EmeraldConnected,
                    fontWeight = FontWeight.SemiBold
                )
            }

            if (!isGroup) {
                IconButton(onClick = { viewModel.initiateVoiceCall(id, name) }) {
                    Icon(Icons.Filled.Phone, contentDescription = "Voz", tint = GoldAccent)
                }
            }
        }

        // Messages list
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(messages) { msg ->
                val isMyMessage = msg.senderId == viewModel.myNodeId
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (isMyMessage) Arrangement.End else Arrangement.Start
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.82f)
                            .wrapContentWidth(align = if (isMyMessage) Alignment.End else Alignment.Start)
                            .clip(
                                RoundedCornerShape(
                                    topStart = 16.dp,
                                    topEnd = 16.dp,
                                    bottomStart = if (isMyMessage) 16.dp else 0.dp,
                                    bottomEnd = if (isMyMessage) 0.dp else 16.dp
                                )
                            )
                            .background(if (isMyMessage) GoldAccent else SlateCard)
                            .padding(12.dp)
                    ) {
                        Column {
                            if (!isMyMessage && isGroup) {
                                Text(
                                    text = msg.senderName,
                                    fontSize = 11.sp,
                                    color = GoldAccent,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(bottom = 2.dp)
                                )
                            }
                            Text(
                                text = msg.content,
                                fontSize = 14.sp,
                                color = if (isMyMessage) SlateBackground else TextLight
                            )
                            
                            Row(
                                modifier = Modifier
                                    .padding(top = 4.dp)
                                    .align(Alignment.End),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = if (msg.hopCount > 1) "via repetição (hops: ${msg.hopCount})" else "direct link",
                                    fontSize = 9.sp,
                                    color = if (isMyMessage) SlateBackground.copy(alpha = 0.5f) else TextMuted
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                // Delivery Tick marks
                                Icon(
                                    imageVector = if (msg.status == "DELIVERED") Icons.Filled.DoneAll else Icons.Filled.Done,
                                    contentDescription = msg.status,
                                    tint = if (isMyMessage) SlateBackground.copy(alpha = 0.7f) else EmeraldConnected,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Messages inputs bar bottom
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(SlateCard)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(SlateCardSecondary, CircleShape)
                    .clickable {
                        // Simulate audio clip attachment dialog
                        inputMsgField = "🎤 [Áudio de Voz - 4s]"
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Mic, contentDescription = "Audio", tint = GoldAccent)
            }

            Spacer(modifier = Modifier.width(8.dp))

            OutlinedTextField(
                value = inputMsgField,
                onValueChange = { inputMsgField = it },
                placeholder = { Text("Digita uma mensagem offline...", color = TextMuted) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                    focusedTextColor = TextLight,
                    unfocusedTextColor = TextLight
                ),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .weight(1f)
                    .background(SlateCardSecondary, RoundedCornerShape(24.dp)),
                singleLine = true
            )

            Spacer(modifier = Modifier.width(8.dp))

            FloatingActionButton(
                onClick = {
                    if (inputMsgField.trim().isNotEmpty()) {
                        viewModel.sendTextMessage(inputMsgField.trim())
                        inputMsgField = ""
                    }
                },
                containerColor = GoldAccent,
                contentColor = SlateBackground,
                shape = CircleShape,
                modifier = Modifier.size(44.dp)
            ) {
                Icon(Icons.Filled.Send, contentDescription = "Enviar", modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
fun VoiceCallScreen(viewModel: ZungaViewModel, peerName: String, isIncoming: Boolean) {
    val callState by viewModel.callStateStr.collectAsStateWithLifecycle()
    val durationCount by viewModel.callDurationCount.collectAsStateWithLifecycle()
    
    // Wave pulse visual animation specs
    val infiniteTransition = rememberInfiniteTransition()
    val pulseSize by infiniteTransition.animateFloat(
        initialValue = 180f,
        targetValue = 240f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        )
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SlateBackground)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Back/Status header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start
        ) {
            Text(
                text = "Rede Voip Mesh Offline",
                color = GoldAccent,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                letterSpacing = 2.sp
            )
        }

        // Pulse audio canvas visualization
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp),
            contentAlignment = Alignment.Center
        ) {
            if (callState == "CONNECTED") {
                Box(
                    modifier = Modifier
                        .size(pulseSize.dp)
                        .border(1.5.dp, GoldAccent.copy(alpha = 0.4f), CircleShape)
                )
            }
            
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(110.dp)
                        .background(SlateCard, CircleShape)
                        .border(2.dp, GoldAccent, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.RecordVoiceOver, contentDescription = "Voice", tint = GoldAccent, modifier = Modifier.size(54.dp))
                }
                
                Spacer(modifier = Modifier.height(20.dp))
                
                Text(
                    text = peerName,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextLight
                )

                Text(
                    text = if (callState == "RINGING") {
                        if (isIncoming) "CHAMADA DE VOZ A RECEBER" else "CHAMANDO VIZINHO..."
                    } else {
                        val min = durationCount / 60
                        val sec = durationCount % 60
                        String.format("CONECTADO • %02d:%02d", min, sec)
                    },
                    fontSize = 13.sp,
                    color = if (callState == "RINGING") GoldAccent else EmeraldConnected,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }

        // Toggles & answer/decline controls
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Esta chamada usa WebRTC DataChannels transmitidos diretamente via sockets P2P, garantindo latência ultrabaixa sem internet.",
                fontSize = 11.sp,
                color = TextMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (callState == "RINGING" && isIncoming) {
                    // Accept call button green
                    IconButton(
                        onClick = { viewModel.acceptCall() },
                        modifier = Modifier
                            .size(68.dp)
                            .background(EmeraldConnected, CircleShape)
                    ) {
                        Icon(Icons.Filled.Call, contentDescription = "Aceitar", tint = TextLight, modifier = Modifier.size(32.dp))
                    }
                    
                    Spacer(modifier = Modifier.width(32.dp))
                }

                // Hang Up call button red
                IconButton(
                    onClick = { viewModel.hangUpCall() },
                    modifier = Modifier
                        .size(68.dp)
                        .background(CrimsonAccent, CircleShape)
                ) {
                    Icon(Icons.Filled.CallEnd, contentDescription = "Desligar", tint = TextLight, modifier = Modifier.size(32.dp))
                }
            }
        }
    }
}
