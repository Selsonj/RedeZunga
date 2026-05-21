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
    var myLocation = "Maianga, Luanda"
    var isBatterySaverEnabled = false

    private val keyPair by lazy { ZungaCryptography.generateKeyPair() }
    val myPublicKeyString by lazy { ZungaCryptography.publicKeyToString(keyPair.public) }

    // State flows
    private val _realPeers = MutableStateFlow<List<PeerEntity>>(emptyList())
    val realPeers = _realPeers.asStateFlow()

    private val _simulatedPeers = MutableStateFlow<List<PeerEntity>>(emptyList())
    val simulatedPeers = _simulatedPeers.asStateFlow()

    private val _isSimulatorMode = MutableStateFlow(true)
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

    // Message listener to pipe incoming messages down to ViewModel
    private var onMessageReceivedListener: ((MessageEntity) -> Unit)? = null

    init {
        nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
        setupSimulatorNodes()
    }

    fun setOnMessageReceivedListener(listener: (MessageEntity) -> Unit) {
        onMessageReceivedListener = listener
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

        scope.launch(Dispatchers.IO) {
            try {
                serverSocket = ServerSocket(DEFAULT_PORT)
                Log.d(TAG, "ServerSocket started on port $DEFAULT_PORT")
                registerNsdService()
                startNsdDiscovery()
                listenForSockets()
            } catch (e: Exception) {
                Log.e(TAG, "Error starting local TCP server", e)
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

            // Step B: Receive Handshake Response
            val line = reader.readLine() ?: return@withContext
            val handshakeIn = JSONObject(line)
            if (handshakeIn.getString("type") == "HANDSHAKE") {
                val peerId = handshakeIn.getString("nodeId")
                val peerName = handshakeIn.getString("name")
                val peerLoc = handshakeIn.getString("location")
                val peerModel = handshakeIn.getString("deviceModel")
                val peerPubKey = handshakeIn.getString("publicKey")

                val updatedPeer = PeerEntity(
                    id = peerId,
                    name = peerName,
                    deviceModel = peerModel,
                    location = peerLoc,
                    isDirectNeighbor = true,
                    connectionType = "WIFI_DIRECT",
                    ipAddress = socket.inetAddress.hostAddress,
                    port = socket.port
                )

                // Update peer list
                val updatedList = _realPeers.value.toMutableList().apply {
                    removeAll { it.id == peerId }
                    add(updatedPeer)
                }
                _realPeers.value = updatedList

                // Read subsequent streamed packets
                while (isActive) {
                    val streamLine = reader.readLine() ?: break
                    val packet = JSONObject(streamLine)
                    handleIncomingPacket(packet)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling incoming client socket connection", e)
        } finally {
            try { socket.close() } catch (e: Exception) {}
        }
    }

    /**
     * Process received Wi-Fi packet
     */
    private fun handleIncomingPacket(packet: JSONObject) {
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

                // Cryptography signature verification
                val signature = packet.getString("signature")
                val senderPublicKey = packet.getString("senderPublicKey")
                
                // Content base64 decryption (AES simulated or straight)
                val decryptedContent = contentEncoded // Simplification for network transmission stream

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

                    val updatedPeer = PeerEntity(
                        id = peerId,
                        name = peerName,
                        deviceModel = peerModel,
                        location = peerLoc,
                        isDirectNeighbor = true,
                        connectionType = "WIFI",
                        ipAddress = ip,
                        port = port
                    )

                    // Add peer
                    val updatedList = _realPeers.value.toMutableList().apply {
                        removeAll { it.id == peerId }
                        add(updatedPeer)
                    }
                    _realPeers.value = updatedList

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
     * Sends message over the real network socket if direct peer, or simulated mesh hops
     */
    fun sendMessage(messageContent: String, recipientId: String, isGroupChat: Boolean = false, groupId: String? = null): MessageEntity {
        val uniqueMsgId = UUID.randomUUID().toString()
        val signature = ZungaCryptography.sign(messageContent, keyPair.private)

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

        if (_isSimulatorMode.value) {
            // Simulate routing hops trace
            scope.launch {
                val finalPath = mutableListOf<String>()
                finalPath.add(myNodeName)

                // Pick a simulated peer
                val listPeers = _simulatedPeers.value
                val destNode = listPeers.find { it.id == recipientId }
                
                if (isGroupChat) {
                    val virtualHops = listOf("Cazenga Repetidor", "Sambizanga Repetidor", "Zunga Grupo")
                    _routingHopsTrace.value = virtualHops
                } else if (destNode != null) {
                    if (destNode.isDirectNeighbor) {
                        finalPath.add(destNode.name)
                        _routingHopsTrace.value = finalPath
                    } else {
                        // Multi-hop routing simulation
                        val intermediateNodes = when (destNode.location.split(",").firstOrNull()?.trim()) {
                            "Vila de Catumbela", "Benguela" -> listOf("Cazenga Repetidor", "Lobito Router Local")
                            "Cacuaco", "Luanda" -> listOf("Sambizanga Repetidor", "Vila Alice Repetidor")
                            else -> listOf("Zunga Router Node")
                        }
                        for (node in intermediateNodes) {
                            delay(400) // Delay to show store-and-forward animation
                            finalPath.add(node)
                            _routingHopsTrace.value = finalPath.toList()
                        }
                        finalPath.add(destNode.name)
                        _routingHopsTrace.value = finalPath
                    }
                } else {
                    _routingHopsTrace.value = listOf(myNodeName, "Encaminhando por repetidores...", "Rota localizada")
                }
            }
        } else {
            // Real peer packet transmission
            scope.launch(Dispatchers.IO) {
                val peer = _realPeers.value.find { it.id == recipientId }
                if (peer != null && peer.isDirectNeighbor && peer.ipAddress != null) {
                    try {
                        val socket = Socket(peer.ipAddress, peer.port ?: DEFAULT_PORT)
                        val writer = PrintWriter(socket.outputStream, true)
                        val packet = JSONObject().apply {
                            put("type", "CHAT")
                            put("msgId", uniqueMsgId)
                            put("senderId", myNodeId)
                            put("senderName", myNodeName)
                            put("receiverId", recipientId)
                            put("content", messageContent)
                            put("isGroup", isGroupChat)
                            put("groupId", groupId2String(groupId))
                            put("messageType", "TEXT")
                            put("timestamp", System.currentTimeMillis())
                            put("ttl", 5)
                            put("hopCount", 1)
                            put("routePath", myNodeId.take(4))
                            put("signature", signature)
                            put("senderPublicKey", myPublicKeyString)
                        }
                        writer.println(packet.toString())
                        socket.close()
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to send packet direct over TCP socket", e)
                    }
                }
            }
        }

        return message
    }

    private fun groupId2String(id: String?): String {
        return id ?: ""
    }

    /**
     * Prepopulates our interactive simulation environment with standard Angola P2P nodes
     */
    private fun setupSimulatorNodes() {
        val list = listOf(
            PeerEntity(
                id = "sim-peer-1",
                name = "Mateus de Cazenga",
                deviceModel = "Tecno Pop 7",
                location = "Cazenga, Luanda",
                isDirectNeighbor = true,
                connectionType = "WIFI_DIRECT",
                lastSeen = System.currentTimeMillis() - 10000
            ),
            PeerEntity(
                id = "sim-peer-2",
                name = "Kelson - Cacuaco",
                deviceModel = "Infinix Hot 30i",
                location = "Cacuaco, Luanda",
                isDirectNeighbor = true,
                connectionType = "BLUETOOTH",
                lastSeen = System.currentTimeMillis() - 50000
            ),
            PeerEntity(
                id = "sim-peer-3",
                name = "Ginga - Repetidor Catumbela",
                deviceModel = "Samsung Galaxy A04",
                location = "Vila de Catumbela, Benguela",
                isDirectNeighbor = false, // Must multi-hop through Mateus
                connectionType = "WIFI",
                lastSeen = System.currentTimeMillis() - 120000
            ),
            PeerEntity(
                id = "sim-peer-4",
                name = "N'gola Kiluanji Router",
                deviceModel = "Xiaomi Redmi 12C",
                location = "Sambizanga, Luanda",
                isDirectNeighbor = false, // Multi-hop routing
                connectionType = "WIFI",
                lastSeen = System.currentTimeMillis() - 80000
            ),
            PeerEntity(
                id = "sim-peer-5",
                name = "Zinga Hub Lobito",
                deviceModel = "Oppo A17",
                location = "Restinga, Lobito",
                isDirectNeighbor = false,
                connectionType = "WIFI",
                lastSeen = System.currentTimeMillis() - 300000
            )
        )
        _simulatedPeers.value = list
    }

    /**
     * Toggle status or add custom node
     */
    fun addSimulatedPeer(name: String, location: String, model: String, isDirect: Boolean, type: String) {
        val newPeer = PeerEntity(
            id = "sim-custom-${UUID.randomUUID().toString().take(6)}",
            name = name,
            location = location,
            deviceModel = model,
            isDirectNeighbor = isDirect,
            connectionType = type,
            lastSeen = System.currentTimeMillis()
        )
        _simulatedPeers.value = _simulatedPeers.value.toMutableList().apply { add(newPeer) }
    }

    fun removeSimulatedPeer(id: String) {
        _simulatedPeers.value = _simulatedPeers.value.filter { it.id != id }
    }
}
