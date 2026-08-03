package com.example.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import com.example.data.database.MessageEntity
import com.example.data.database.PeerEntity
import com.example.security.ZungaCryptography
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID

class ZungaMeshEngine(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private val TAG = "ZungaMeshEngine"
    private val SERVICE_TYPE = "_zungamesh._tcp."
    private val DEFAULT_PORT = 18181

    // Node configuration
    var myNodeId = UUID.randomUUID().toString()
    var myNodeName = "Zunga " + Build.MODEL.take(6)
    var myLocation = "Centro, Moçâmedes"
    var isBatterySaverEnabled = false

    private val keyPair by lazy { ZungaCryptography.generateKeyPair() }
    val myPublicKeyString by lazy { ZungaCryptography.publicKeyToString(keyPair.public) }

    // Thread-safe map to store discovered peer public keys
    private val peerPublicKeys = java.util.concurrent.ConcurrentHashMap<String, String>()

    // Instância do MeshRouter para roteamento dinâmico multi-hop
    private val database by lazy { com.example.data.database.ZungaDatabase.getDatabase(context) }
    val meshRouter by lazy {
        MeshRouter(
            myNodeId = myNodeId,
            myNodeName = myNodeName,
            database = database,
            scope = scope,
            getDirectNeighbors = { _realPeers.value },
            transmitPacket = ::transmitRawPacket,
            onLocalMessageReceived = ::processLocalPacket
        )
    }

    // State flows
    private val _realPeers = MutableStateFlow<List<PeerEntity>>(emptyList())
    val realPeers = _realPeers.asStateFlow()

    private val _simulatedPeers = MutableStateFlow<List<PeerEntity>>(emptyList())
    val simulatedPeers = _simulatedPeers.asStateFlow()

    private val _isSimulatorMode = MutableStateFlow(false)
    val isSimulatorMode = _isSimulatorMode.asStateFlow()

    private val _networkingActive = MutableStateFlow(false)
    val networkingActive = _networkingActive.asStateFlow()

    private val _routingHopsTrace = MutableStateFlow<List<String>>(emptyList())
    val routingHopsTrace = _routingHopsTrace.asStateFlow()

    // Real Socket references
    private var serverSocket: ServerSocket? = null
    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var multicastLock: android.net.wifi.WifiManager.MulticastLock? = null

    // Bluetooth Hardware References
    private val bluetoothManager by lazy { context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager }
    private val bluetoothAdapter by lazy { bluetoothManager?.adapter }
    private var advertiseCallback: android.bluetooth.le.AdvertiseCallback? = null
    private var scanCallback: android.bluetooth.le.ScanCallback? = null

    // Wi-Fi Direct Transport Layer
    private val wifiDirectTransport by lazy {
        com.example.network.transport.WifiDirectTransport(
            context = context,
            scope = scope,
            onPeersDiscovered = { p2pPeers ->
                val updatedList = _realPeers.value.toMutableList().apply {
                    removeAll { it.id.startsWith("p2p-") }
                    addAll(p2pPeers)
                }
                _realPeers.value = updatedList
            },
            onConnectionEstablished = { groupOwnerIp, isGroupOwner ->
                if (!isGroupOwner) {
                    connectToNeighbor(groupOwnerIp, DEFAULT_PORT, "WifiP2p GO")
                }
            },
            onConnectionLost = {
                val updatedList = _realPeers.value.map {
                    if (it.id.startsWith("p2p-") || it.connectionType == "WIFI_DIRECT") {
                        it.copy(isDirectNeighbor = false)
                    } else {
                        it
                    }
                }
                _realPeers.value = updatedList
            },
            onStateChanged = { stateName ->
                Log.d(TAG, "[WIFI_DIRECT] Estado alterado: $stateName")
            }
        )
    }

    // Message listener to pipe incoming messages down to ViewModel
    private var onMessageReceivedListener: ((MessageEntity) -> Unit)? = null

    // Call signaling listener to pipe incoming voice call events to ViewModel
    private var onCallSignalReceivedListener: ((senderId: String, senderName: String, signal: String) -> Unit)? = null

    init {
        nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    }

    fun setOnMessageReceivedListener(listener: (MessageEntity) -> Unit) {
        onMessageReceivedListener = listener
    }

    fun setOnCallSignalReceivedListener(listener: (senderId: String, senderName: String, signal: String) -> Unit) {
        onCallSignalReceivedListener = listener
    }

    fun setSimulatorMode(isActive: Boolean) {
        _isSimulatorMode.value = isActive
    }

    /**
     * Start rede zunga services: server socket and mDNS discovery
     */
    fun startServices() {
        if (_networkingActive.value) return
        _networkingActive.value = true

        // Acquire MulticastLock to receive mDNS (NSD) packets on physical devices
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
            if (wifiManager != null) {
                multicastLock = wifiManager.createMulticastLock("ZungaMeshMulticastLock").apply {
                    setReferenceCounted(false)
                    acquire()
                }
                Log.d(TAG, "Acquired WiFi MulticastLock")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error acquiring MulticastLock", e)
        }

        // 1. Start TCP Server socket
        scope.launch(Dispatchers.IO) {
            try {
                serverSocket = ServerSocket(DEFAULT_PORT)
                Log.d(TAG, "ServerSocket started on port $DEFAULT_PORT")
                listenForSockets()
            } catch (e: Exception) {
                Log.e(TAG, "Error starting local TCP server", e)
            }
        }

        // 2. Start mDNS NSD local network discovery
        scope.launch(Dispatchers.IO) {
            try {
                registerNsdService()
                startNsdDiscovery()
            } catch (e: Exception) {
                Log.e(TAG, "Error registering NSD local network discovery", e)
            }
        }

        // 3. Start physical Wi-Fi Direct (P2P) transport
        wifiDirectTransport.start()

        // 4. Start Local Bluetooth BLE advertising & scanning
        scope.launch(Dispatchers.IO) {
            try {
                startBleAdvertising()
                startBleScanning()
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing BLE hardware mesh advertise/scan", e)
            }
        }
    }

    /**
     * Terminate sockets and mDNS listeners
     */
    fun stopServices() {
        if (!_networkingActive.value) return
        _networkingActive.value = false

        scope.launch(Dispatchers.IO) {
            try {
                serverSocket?.close()
                serverSocket = null
                
                registrationListener?.let { nsdManager?.unregisterService(it) }
                discoveryListener?.let { nsdManager?.stopServiceDiscovery(it) }

                // Release WiFi MulticastLock
                try {
                    multicastLock?.let {
                        if (it.isHeld) {
                            it.release()
                        }
                    }
                    multicastLock = null
                    Log.d(TAG, "Released WiFi MulticastLock")
                } catch (e: Exception) {
                    Log.e(TAG, "Error releasing MulticastLock", e)
                }

                // Stop BLE Advertising & Scanning
                try {
                    advertiseCallback?.let { bluetoothAdapter?.bluetoothLeAdvertiser?.stopAdvertising(it) }
                    scanCallback?.let { bluetoothAdapter?.bluetoothLeScanner?.stopScan(it) }
                } catch (e: SecurityException) {
                    Log.e(TAG, "BLE Permission missing on stop", e)
                }
                advertiseCallback = null
                scanCallback = null

                // Stop Wi-Fi Direct (P2P) transport
                wifiDirectTransport.stop()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping services", e)
            }
        }
    }

    /**
     * 1. Register local service using NsdManager so nearby nodes find us
     */
    private fun registerNsdService() {
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "Zunga-${myNodeId.take(8)}"
            serviceType = SERVICE_TYPE
            port = DEFAULT_PORT
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.d(TAG, "NSD Service registered: ${info.serviceName}")
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "NSD service registration failed: $errorCode")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {
                Log.d(TAG, "NSD service unregistered: ${info.serviceName}")
            }
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "NSD service unregistration failed: $errorCode")
            }
        }

        nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
    }

    /**
     * 2. Start discovering nearby nodes
     */
    private fun startNsdDiscovery() {
        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.d(TAG, "NSD Discovery started")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Service found: ${serviceInfo.serviceName}")
                if (serviceInfo.serviceType != SERVICE_TYPE) return
                // Check that it's from Rede Zunga but not ourselves
                if (serviceInfo.serviceName.contains("Zunga") && !serviceInfo.serviceName.endsWith(myNodeId.take(8))) {
                    nsdManager?.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                            Log.e(TAG, "Resolve failed for nearby node: $errorCode")
                        }

                        override fun onServiceResolved(resolvedInfo: NsdServiceInfo) {
                            val hostAddress = resolvedInfo.host.hostAddress
                            val hostPort = resolvedInfo.port
                            Log.d(TAG, "Resolved neighbor: ${resolvedInfo.serviceName} at $hostAddress:$hostPort")
                            connectToNeighbor(hostAddress, hostPort, resolvedInfo.serviceName)
                        }
                    })
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Service lost: ${serviceInfo.serviceName}")
                removePeerByName(serviceInfo.serviceName)
            }

            override fun onDiscoveryStopped(regType: String) {
                Log.d(TAG, "Discovery stopped")
            }

            override fun onStartDiscoveryFailed(regType: String, errorCode: Int) {
                Log.e(TAG, "Discovery start failed: $errorCode")
                nsdManager?.stopServiceDiscovery(this)
            }

            override fun onStopDiscoveryFailed(regType: String, errorCode: Int) {
                Log.e(TAG, "Discovery stop failed: $errorCode")
            }
        }

        nsdManager?.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
    }

    /**
     * 3. Background TCP server loop listening for incoming handshakes and packets
     */
    private suspend fun listenForSockets() = withContext(Dispatchers.IO) {
        while (isActive && serverSocket != null) {
            try {
                val socket = serverSocket?.accept() ?: break
                launch { handleIncomingConnection(socket) }
            } catch (e: Exception) {
                // Socket closed
                break
            }
        }
    }

    /**
     * 4. Perform Handshake and packet streaming over Socket channel
     */
    private suspend fun handleIncomingConnection(socket: Socket) = withContext(Dispatchers.IO) {
        try {
            val reader = BufferedReader(InputStreamReader(socket.inputStream))
            val writer = PrintWriter(socket.outputStream, true)

            // Step A: Send Handshake Invitation
            val handshakeOut = JSONObject().apply {
                put("type", "HANDSHAKE")
                put("nodeId", myNodeId)
                put("name", myNodeName)
                put("location", myLocation)
                put("deviceModel", Build.MODEL)
                put("publicKey", myPublicKeyString)
            }
            writer.println(handshakeOut.toString())

            // Step B: Receive Handshake or Direct Packet
            val line = reader.readLine() ?: return@withContext
            val packet = JSONObject(line)
            val packetType = packet.optString("type")

            if (packetType == "HANDSHAKE") {
                val peerId = packet.getString("nodeId")
                val peerName = packet.getString("name")
                val peerLoc = packet.getString("location")
                val peerModel = packet.getString("deviceModel")
                val peerPublicKey = packet.optString("publicKey")

                if (peerPublicKey.isNotEmpty()) {
                    peerPublicKeys[peerId] = peerPublicKey
                }

                val isP2pIp = socket.inetAddress.hostAddress?.startsWith("192.168.49.") == true
                val finalConnectionType = if (isP2pIp) "WIFI_DIRECT" else "WIFI_LOCAL"

                val updatedPeer = PeerEntity(
                    id = peerId,
                    name = peerName,
                    deviceModel = peerModel,
                    location = peerLoc,
                    isDirectNeighbor = true,
                    connectionType = finalConnectionType,
                    ipAddress = socket.inetAddress.hostAddress,
                    port = DEFAULT_PORT
                )

                // Update peer list
                val updatedList = _realPeers.value.toMutableList().apply {
                    removeAll { it.id == peerId }
                    add(updatedPeer)
                }
                _realPeers.value = updatedList

                // Update direct route in routing table
                meshRouter.updateRoute(
                    com.example.data.database.RouteTable(
                        destinationNodeId = peerId,
                        nextHopNodeId = peerId,
                        hopCount = 1,
                        lastSeen = System.currentTimeMillis(),
                        connectionType = finalConnectionType
                    )
                )

                // Read subsequent streamed packets
                while (isActive) {
                    val streamLine = reader.readLine() ?: break
                    val streamPacket = JSONObject(streamLine)
                    handleIncomingPacket(streamPacket)
                }
            } else if (packetType == "CHAT" || packetType == "CALL") {
                // Directly handle transaction packets sent over short-lived sockets
                handleIncomingPacket(packet)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling incoming client socket connection", e)
        } finally {
            try { socket.close() } catch (e: Exception) {}
        }
    }

    /**
     * Process received Wi-Fi packet by handing it to the MeshRouter
     */
    private fun handleIncomingPacket(packet: JSONObject) {
        meshRouter.receivePacket(packet)
    }

    private fun processLocalPacket(packet: JSONObject) {
        try {
            when (packet.getString("type")) {
                "CHAT" -> {
                    val msgId = packet.getString("msgId")
                    val senderId = packet.getString("senderId")
                    val senderName = packet.getString("senderName")
                    val receiverId = packet.getString("receiverId")
                    val contentEncoded = packet.getString("content")
                    val isGrp = packet.getBoolean("isGroup")
                    val grpId = if (packet.has("groupId")) packet.getString("groupId") else null
                    val msgType = packet.getString("messageType")
                    val timestamp = packet.getLong("timestamp")
                    val ttl = packet.getInt("ttl")
                    val hopCount = packet.getInt("hopCount")
                    val routePath = packet.getString("routePath")

                    // Security & Decryption parsing
                    val signature = if (packet.has("signature")) packet.getString("signature") else null
                    val senderPublicKeyStr = if (packet.has("senderPublicKey")) packet.getString("senderPublicKey") else null
                    val isEncrypted = packet.optBoolean("isEncrypted", false)
                    val encryptedAESKey = if (packet.has("encryptedAESKey")) packet.getString("encryptedAESKey") else null
                    val iv = if (packet.has("iv")) packet.getString("iv") else null

                    var decryptedContent = contentEncoded
                    var isSignatureValid = true

                    if (isEncrypted && encryptedAESKey != null && iv != null && signature != null && senderPublicKeyStr != null) {
                        // 1. Validar assinatura RSA on the ciphertext content
                        isSignatureValid = ZungaCryptography.verify(contentEncoded, signature, senderPublicKeyStr)
                        if (isSignatureValid) {
                            try {
                                // 2. Descriptografar chave AES usando chave privada do receptor
                                val secretKey = ZungaCryptography.decryptKeyRSA(encryptedAESKey, keyPair.private)
                                
                                // 3. Descriptografar conteúdo usando AES e o IV explicitado
                                decryptedContent = ZungaCryptography.decryptAESWithIv(contentEncoded, iv, secretKey)
                            } catch (e: Exception) {
                                Log.e(TAG, "Erro ao descriptografar dados da mensagem", e)
                                decryptedContent = "[Erro de Descriptografia: chave ou dados inválidos]"
                            }
                        } else {
                            Log.e(TAG, "Assinatura inválida na mensagem cifrada de $senderName")
                            decryptedContent = "[Erro: Assinatura digital inválida]"
                        }
                    } else if (signature != null && senderPublicKeyStr != null) {
                        // Compatibility verification for unencrypted signed packets
                        isSignatureValid = ZungaCryptography.verify(contentEncoded, signature, senderPublicKeyStr)
                        if (!isSignatureValid) {
                            decryptedContent = "[Erro: Assinatura digital de texto simples inválida]"
                        }
                    }

                    val message = MessageEntity(
                        id = msgId,
                        senderId = senderId,
                        senderName = senderName,
                        receiverId = receiverId,
                        isGroup = isGrp,
                        groupId = grpId,
                        content = decryptedContent,
                        messageType = msgType,
                        timestamp = timestamp,
                        status = "DELIVERED",
                        ttl = ttl,
                        hopCount = hopCount,
                        routePath = routePath + if (routePath.isEmpty()) myNodeId.take(4) else ",${myNodeId.take(4)}"
                    )

                    onMessageReceivedListener?.invoke(message)
                }
                "CALL" -> {
                    val signal = packet.getString("signal") // "RINGING", "ACCEPTED", "HANGUP"
                    val senderId = packet.getString("senderId")
                    val senderName = packet.getString("senderName")
                    Log.d(TAG, "Received CALL signal $signal from $senderName (ID: $senderId)")
                    onCallSignalReceivedListener?.invoke(senderId, senderName, signal)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing local packet", e)
        }
    }

    private fun transmitRawPacket(packet: JSONObject, nextHopId: String) {
        scope.launch(Dispatchers.IO) {
            val peer = _realPeers.value.find { it.id == nextHopId }
            if (peer != null && peer.isDirectNeighbor && peer.ipAddress != null) {
                try {
                    val socket = Socket(peer.ipAddress, peer.port ?: DEFAULT_PORT)
                    val writer = PrintWriter(socket.outputStream, true)
                    writer.println(packet.toString())
                    socket.close()
                    Log.d(TAG, "[Network] Pacote enviado para o vizinho direto $nextHopId com sucesso.")
                } catch (e: Exception) {
                    Log.e(TAG, "[Network] Falha ao enviar pacote via socket para $nextHopId", e)
                }
            } else {
                Log.e(TAG, "[Network] Próximo salto $nextHopId não é um vizinho direto ativo ou IP está nulo.")
            }
        }
    }

    /**
     * Connect to discovered neighbor
     */
    private fun connectToNeighbor(ip: String, port: Int, nameTag: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val socket = Socket(ip, port)
                val writer = PrintWriter(socket.outputStream, true)
                val reader = BufferedReader(InputStreamReader(socket.inputStream))

                // Exchange handshake
                val line = reader.readLine() ?: return@launch
                val handshakeIn = JSONObject(line)
                if (handshakeIn.getString("type") == "HANDSHAKE") {
                    val peerId = handshakeIn.getString("nodeId")
                    val peerName = handshakeIn.getString("name")
                    val peerLoc = handshakeIn.getString("location")
                    val peerModel = handshakeIn.getString("deviceModel")
                    val peerPublicKey = handshakeIn.optString("publicKey")

                    if (peerPublicKey.isNotEmpty()) {
                        peerPublicKeys[peerId] = peerPublicKey
                    }

                    val isP2pIp = ip.startsWith("192.168.49.")
                    val finalConnectionType = if (isP2pIp) "WIFI_DIRECT" else "WIFI_LOCAL"

                    val updatedPeer = PeerEntity(
                        id = peerId,
                        name = peerName,
                        deviceModel = peerModel,
                        location = peerLoc,
                        isDirectNeighbor = true,
                        connectionType = finalConnectionType,
                        ipAddress = ip,
                        port = port
                    )

                    // Add peer
                    val updatedList = _realPeers.value.toMutableList().apply {
                        removeAll { it.id == peerId }
                        add(updatedPeer)
                    }
                    _realPeers.value = updatedList

                    // Update direct route in routing table
                    meshRouter.updateRoute(
                        com.example.data.database.RouteTable(
                            destinationNodeId = peerId,
                            nextHopNodeId = peerId,
                            hopCount = 1,
                            lastSeen = System.currentTimeMillis(),
                            connectionType = finalConnectionType
                        )
                    )

                    // Reply handshake
                    val handshakeOut = JSONObject().apply {
                        put("type", "HANDSHAKE")
                        put("nodeId", myNodeId)
                        put("name", myNodeName)
                        put("location", myLocation)
                        put("deviceModel", Build.MODEL)
                        put("publicKey", myPublicKeyString)
                    }
                    writer.println(handshakeOut.toString())
                }
            } catch (e: Exception) {
                Log.e(TAG, "Could not establish outgoing connection to resolved Peer $ip:$port", e)
            }
        }
    }

    private fun removePeerByName(nsdName: String) {
        // Simply marks peer offline
        val sub = nsdName.takeLast(8)
        val filtered = _realPeers.value.map {
            if (it.id.endsWith(sub)) {
                it.copy(isDirectNeighbor = false)
            } else {
                it
            }
        }
        _realPeers.value = filtered
    }

    /**
     * Sends message over the dynamic mesh network (multi-hop routing)
     */
    fun sendMessage(messageContent: String, recipientId: String, isGroupChat: Boolean = false, groupId: String? = null): MessageEntity {
        val uniqueMsgId = UUID.randomUUID().toString()

        val message = MessageEntity(
            id = uniqueMsgId,
            senderId = myNodeId,
            senderName = myNodeName,
            receiverId = if (isGroupChat) groupId!! else recipientId,
            isGroup = isGroupChat,
            groupId = groupId,
            content = messageContent,
            messageType = "TEXT",
            timestamp = System.currentTimeMillis(),
            status = "SENT",
            ttl = 5,
            hopCount = 1,
            routePath = myNodeId.take(4)
        )

        scope.launch(Dispatchers.IO) {
            try {
                val packet = JSONObject().apply {
                    put("type", "CHAT")
                    put("msgId", uniqueMsgId)
                    put("senderId", myNodeId)
                    put("senderName", myNodeName)
                    put("receiverId", if (isGroupChat) groupId!! else recipientId)
                    put("isGroup", isGroupChat)
                    put("groupId", groupId2String(groupId))
                    put("messageType", "TEXT")
                    put("timestamp", System.currentTimeMillis())
                    put("ttl", 5)
                    put("hopCount", 1)
                    put("routePath", myNodeId.take(4))
                    put("senderPublicKey", myPublicKeyString)
                    put("visitedNodes", JSONArray().put(myNodeId))
                    put("forwardedBy", myNodeId)

                    // Encryption and security
                    val recipientPubKeyStr = if (isGroupChat) null else peerPublicKeys[recipientId]
                    if (recipientPubKeyStr != null) {
                        try {
                            val aesKey = ZungaCryptography.generateAESKey()
                            val (cipherText, ivBase64) = ZungaCryptography.encryptAESWithIv(messageContent, aesKey)
                            val recipientPubKey = ZungaCryptography.stringToPublicKey(recipientPubKeyStr)
                            val encryptedAESKey = ZungaCryptography.encryptKeyRSA(aesKey, recipientPubKey)
                            val signature = ZungaCryptography.sign(cipherText, keyPair.private)

                            put("content", cipherText)
                            put("isEncrypted", true)
                            put("encryptedAESKey", encryptedAESKey)
                            put("iv", ivBase64)
                            put("signature", signature)
                        } catch (e: Exception) {
                            Log.e(TAG, "Falha ao criptografar mensagem direta para $recipientId", e)
                            put("content", messageContent)
                            put("isEncrypted", false)
                            val signature = ZungaCryptography.sign(messageContent, keyPair.private)
                            put("signature", signature)
                        }
                    } else {
                        put("content", messageContent)
                        put("isEncrypted", false)
                        val signature = ZungaCryptography.sign(messageContent, keyPair.private)
                        put("signature", signature)
                    }
                }

                // Pass the packet to the meshRouter to be routed multi-hop!
                meshRouter.receivePacket(packet)
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao preparar pacote para envio", e)
            }
        }

        return message
    }

    private fun groupId2String(id: String?): String {
        return id ?: ""
    }

    /**
     * Send real telephony/voice signaling events over the ad-hoc P2P socket
     */
    fun sendCallSignaling(recipientId: String, signal: String, peerName: String) {
        scope.launch(Dispatchers.IO) {
            val peer = _realPeers.value.find { it.id == recipientId }
            if (peer != null && peer.isDirectNeighbor && peer.ipAddress != null) {
                try {
                    val socket = Socket(peer.ipAddress, peer.port ?: DEFAULT_PORT)
                    val writer = PrintWriter(socket.outputStream, true)
                    val packet = JSONObject().apply {
                        put("type", "CALL")
                        put("signal", signal) // "RINGING", "ACCEPTED", "HANGUP"
                        put("senderId", myNodeId)
                        put("senderName", myNodeName)
                        put("receiverId", recipientId)
                        put("timestamp", System.currentTimeMillis())
                    }
                    writer.println(packet.toString())
                    socket.close()
                    Log.d(TAG, "Successfully sent CALL signal '$signal' to $peerName")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send CALL signal '$signal' direct over socket to $peerName", e)
                }
            } else {
                Log.w(TAG, "Cannot send CALL signal '$signal': Peer $recipientId is not found, not direct or has no resolved IP")
            }
        }
    }

    /**
     * Prepopulates our interactive simulation environment with standard Angola P2P nodes
     */
    private fun setupSimulatorNodes() {
        val list = listOf(
            PeerEntity(
                id = "sim-peer-1",
                name = "Mateus de Moçâmedes",
                deviceModel = "Tecno Pop 7",
                location = "Bairro Facim, Moçâmedes",
                isDirectNeighbor = true,
                connectionType = "WIFI_DIRECT",
                lastSeen = System.currentTimeMillis() - 10000
            ),
            PeerEntity(
                id = "sim-peer-2",
                name = "Kelson - Praia Amélia",
                deviceModel = "Infinix Hot 30i",
                location = "Praia Amélia, Moçâmedes",
                isDirectNeighbor = true,
                connectionType = "BLUETOOTH",
                lastSeen = System.currentTimeMillis() - 50000
            ),
            PeerEntity(
                id = "sim-peer-3",
                name = "Ginga - Forte S. Fernando",
                deviceModel = "Samsung Galaxy A04",
                location = "Forte de S. Fernando, Moçâmedes",
                isDirectNeighbor = false, // Must multi-hop through Mateus
                connectionType = "WIFI_LOCAL",
                lastSeen = System.currentTimeMillis() - 120000
            ),
            PeerEntity(
                id = "sim-peer-4",
                name = "Rede Torre do Tombo Router",
                deviceModel = "Xiaomi Redmi 12C",
                location = "Torre do Tombo, Moçâmedes",
                isDirectNeighbor = false, // Multi-hop routing
                connectionType = "WIFI_LOCAL",
                lastSeen = System.currentTimeMillis() - 80000
            ),
            PeerEntity(
                id = "sim-peer-5",
                name = "Zinga Namibe Hub",
                deviceModel = "Oppo A17",
                location = "Aeroporto, Moçâmedes",
                isDirectNeighbor = false,
                connectionType = "WIFI_LOCAL",
                lastSeen = System.currentTimeMillis() - 300000
            )
        )
        _simulatedPeers.value = list
    }

    // --- Private bluetooth hardware/BLE implementation helpers ---

    private fun startBleAdvertising() {
        val adapter = bluetoothAdapter ?: return
        if (!adapter.isEnabled) return
        val advertiser = adapter.bluetoothLeAdvertiser ?: return

        val settings = android.bluetooth.le.AdvertiseSettings.Builder()
            .setAdvertiseMode(android.bluetooth.le.AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(android.bluetooth.le.AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()

        val data = android.bluetooth.le.AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .addServiceUuid(android.os.ParcelUuid(UUID.fromString("0000fe9f-0000-1000-8000-00805f9b34fb")))
            .build()

        val callback = object : android.bluetooth.le.AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: android.bluetooth.le.AdvertiseSettings) {
                Log.d(TAG, "BLE advertising started successfully")
            }
            override fun onStartFailure(errorCode: Int) {
                Log.e(TAG, "BLE advertising failed: $errorCode")
                // Fallback option: if advertisement fails due to packet limit constraints on some models, retry without device name
                if (errorCode == android.bluetooth.le.AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE) {
                    Log.w(TAG, "BLE packet too large, retrying advertise without device name...")
                    try {
                        val fallbackData = android.bluetooth.le.AdvertiseData.Builder()
                            .setIncludeDeviceName(false)
                            .addServiceUuid(android.os.ParcelUuid(UUID.fromString("0000fe9f-0000-1000-8000-00805f9b34fb")))
                            .build()
                        advertiser.startAdvertising(settings, fallbackData, this)
                    } catch (e: SecurityException) {
                        Log.e(TAG, "SecurityException during BLE retry", e)
                    }
                }
            }
        }
        advertiseCallback = callback
        try {
            advertiser.startAdvertising(settings, data, callback)
        } catch (e: SecurityException) {
            Log.e(TAG, "BLE advertise security permission missing", e)
        }
    }

    private fun startBleScanning() {
        val adapter = bluetoothAdapter ?: return
        if (!adapter.isEnabled) return
        val scanner = adapter.bluetoothLeScanner ?: return

        val settings = android.bluetooth.le.ScanSettings.Builder()
            .setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val callback = object : android.bluetooth.le.ScanCallback() {
            override fun onScanResult(callbackType: Int, result: android.bluetooth.le.ScanResult) {
                val device = result.device
                val scanRecord = result.scanRecord ?: return
                
                // Inspect search triggers: service UUID or "Zunga" contained inside name string
                val serviceUuids = scanRecord.serviceUuids ?: emptyList()
                val targetUuid = android.os.ParcelUuid(UUID.fromString("0000fe9f-0000-1000-8000-00805f9b34fb"))
                val hasZungaUuid = serviceUuids.contains(targetUuid)
                
                var devName = ""
                try {
                    devName = scanRecord.deviceName ?: device.name ?: ""
                } catch (e: SecurityException) {}
                
                val hasZungaName = devName.contains("Zunga", ignoreCase = true)

                if (hasZungaUuid || hasZungaName) {
                    val finalName = if (devName.isNotEmpty()) devName else "Zunga Node"
                    val rssi = result.rssi
                    Log.d(TAG, "Discovered Zunga BLE Node: $finalName, RSSI: $rssi, Address: ${device.address}")

                    val blePeer = PeerEntity(
                        id = "ble-${device.address}",
                        name = finalName,
                        deviceModel = "BLE Node (RSSI: $rssi dBm)",
                        location = "Conexão de Rádio BLE",
                        isDirectNeighbor = true,
                        connectionType = "BLUETOOTH"
                    )

                    val updatedList = _realPeers.value.toMutableList().apply {
                        removeAll { it.id == blePeer.id }
                        add(blePeer)
                    }
                    _realPeers.value = updatedList
                }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "BLE Scan failed: $errorCode")
            }
        }
        scanCallback = callback
        try {
            // Scan without strict filter list for maximum OEM hardware capability, filter manually in onScanResult
            scanner.startScan(null, settings, callback)
        } catch (e: SecurityException) {
            Log.e(TAG, "BLE Scan security permission missing", e)
        }
    }
}
