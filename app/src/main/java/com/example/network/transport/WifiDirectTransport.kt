package com.example.network.transport

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.NetworkInfo
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.util.Log
import com.example.data.database.PeerEntity
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

class WifiDirectTransport(
    private val context: Context,
    private val scope: CoroutineScope,
    private val myNodeId: String,
    private val myNodeName: String,
    private val onPeersDiscovered: (List<PeerEntity>) -> Unit,
    private val onConnectionEstablished: (groupOwnerIp: String, isGroupOwner: Boolean) -> Unit,
    private val onConnectionLost: () -> Unit,
    private val onStateChanged: (String) -> Unit
) {
    private val TAG = "WifiDirectTransport"
    private val SERVICE_TYPE = "_zungamesh._tcp"
    private val CONNECTING_TIMEOUT_MS = 30_000L
    private val REDISCOVERY_INTERVAL_MS = 60_000L

    enum class WifiDirectState {
        DISCONNECTED,
        DISCOVERING,
        CONNECTING,
        CONNECTED
    }

    private data class DiscoveredZungaPeer(
        val device: WifiP2pDevice,
        val peerNodeId: String,
        val peerNodeName: String,
        val timestamp: Long
    )

    private val wifiP2pManager by lazy { context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager }
    private var wifiP2pChannel: WifiP2pManager.Channel? = null
    private var isReceiverRegistered = false
    private var isServiceRegistered = false

    private var currentState = WifiDirectState.DISCONNECTED
    private val discoveredZungaPeers = ConcurrentHashMap<String, DiscoveredZungaPeer>()

    private var connectingTimeoutJob: Job? = null
    private var periodicDiscoveryJob: Job? = null
    private var retryJob: Job? = null
    private var discoveryBackoffMs = 2000L
    private var connectBackoffMs = 2000L

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    val stateText = if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) "ATIVADO" else "DESATIVADO"
                    Log.d(TAG, "[WIFI_DIRECT] Estado Wi-Fi Direct: $stateText")
                }
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    // Log informational peers count if needed
                    Log.d(TAG, "[WIFI_DIRECT] WIFI_P2P_PEERS_CHANGED_ACTION recebido")
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    @Suppress("DEPRECATION")
                    val networkInfo = intent.getParcelableExtra<NetworkInfo>(WifiP2pManager.EXTRA_NETWORK_INFO)
                    if (networkInfo != null) {
                        if (networkInfo.isConnected) {
                            connectingTimeoutJob?.cancel()
                            Log.d(TAG, "[WIFI_DIRECT] Conectado: Rede física ativa via P2P")
                            requestConnectionInfo()
                        } else {
                            // Tratar desconexão tanto quando estava CONNECTED quanto quando estava em negociação CONNECTING
                            if (currentState == WifiDirectState.CONNECTED || currentState == WifiDirectState.CONNECTING) {
                                connectingTimeoutJob?.cancel()
                                Log.d(TAG, "[WIFI_DIRECT] Rede Wi-Fi Direct desconectada (era estado: $currentState)")
                                transitionTo(WifiDirectState.DISCONNECTED)
                                onConnectionLost()
                                // Retomar a descoberta automaticamente
                                scope.launch {
                                    delay(1500)
                                    if (currentState == WifiDirectState.DISCONNECTED) {
                                        startDiscovery()
                                    }
                                }
                            }
                        }
                    }
                }
                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                    @Suppress("DEPRECATION")
                    val device = intent.getParcelableExtra<WifiP2pDevice>(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
                    if (device != null) {
                        Log.d(TAG, "[WIFI_DIRECT] Dispositivo local alterado: ${device.deviceName} (${device.deviceAddress})")
                    }
                }
            }
        }
    }

    init {
        try {
            wifiP2pChannel = wifiP2pManager?.initialize(context, context.mainLooper, null)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao inicializar WifiP2pManager", e)
        }
    }

    private fun transitionTo(newState: WifiDirectState) {
        if (currentState != newState) {
            currentState = newState
            Log.d(TAG, "[WIFI_DIRECT] Estado alterado para: $newState")
            onStateChanged(newState.name)
        }
    }

    fun start() {
        Log.d(TAG, "[WIFI_DIRECT] Iniciando transporte Wi-Fi Direct (NodeId: $myNodeId)...")
        registerReceiver()
        registerLocalService()
        startDiscovery()
        startPeriodicDiscovery()
    }

    fun stop() {
        Log.d(TAG, "[WIFI_DIRECT] Parando transporte Wi-Fi Direct...")
        unregisterReceiver()
        connectingTimeoutJob?.cancel()
        periodicDiscoveryJob?.cancel()
        retryJob?.cancel()

        try {
            wifiP2pChannel?.let { channel ->
                wifiP2pManager?.cancelConnect(channel, null)
                wifiP2pManager?.removeGroup(channel, null)
                wifiP2pManager?.clearLocalServices(channel, null)
                wifiP2pManager?.clearServiceRequests(channel, null)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Permissão ausente ao parar Wi-Fi Direct", e)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao limpar conexões Wi-Fi Direct", e)
        }
        isServiceRegistered = false
        discoveredZungaPeers.clear()
        transitionTo(WifiDirectState.DISCONNECTED)
    }

    private fun registerReceiver() {
        if (isReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        context.registerReceiver(receiver, filter)
        isReceiverRegistered = true
    }

    private fun unregisterReceiver() {
        if (!isReceiverRegistered) return
        try {
            context.unregisterReceiver(receiver)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao desregistar receiver", e)
        }
        isReceiverRegistered = false
    }

    /**
     * 2. Registar serviço local DNS-SD do Wi-Fi P2P exclusivo para a Rede Zunga
     */
    private fun registerLocalService() {
        val channel = wifiP2pChannel ?: return
        val manager = wifiP2pManager ?: return

        val record = mapOf(
            "nodeId" to myNodeId,
            "name" to myNodeName,
            "port" to "18181"
        )
        val serviceInfo = WifiP2pDnsSdServiceInfo.newInstance(
            "Zunga_${myNodeId.take(8)}",
            SERVICE_TYPE,
            record
        )

        try {
            manager.clearLocalServices(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    try {
                        manager.addLocalService(channel, serviceInfo, object : WifiP2pManager.ActionListener {
                            override fun onSuccess() {
                                isServiceRegistered = true
                                Log.d(TAG, "[WIFI_DIRECT] addLocalService Zunga registrado com sucesso (nodeId=$myNodeId)")
                            }
                            override fun onFailure(reason: Int) {
                                Log.e(TAG, "[WIFI_DIRECT] Falha ao adicionar local service: $reason")
                            }
                        })
                    } catch (e: SecurityException) {
                        Log.e(TAG, "[WIFI_DIRECT] Sem permissão para addLocalService", e)
                    } catch (e: Exception) {
                        Log.e(TAG, "[WIFI_DIRECT] Erro ao registrar local service", e)
                    }
                }
                override fun onFailure(reason: Int) {
                    Log.w(TAG, "[WIFI_DIRECT] Falha ao limpar local services anteriores: $reason")
                }
            })
        } catch (e: SecurityException) {
            Log.e(TAG, "[WIFI_DIRECT] Sem permissão para clearLocalServices", e)
        } catch (e: Exception) {
            Log.e(TAG, "[WIFI_DIRECT] Erro ao limpar local services", e)
        }
    }

    /**
     * Configurar listeners de resposta DNS-SD para descobrir exclusivamente nós Zunga
     */
    private fun setupDnsSdListeners() {
        val channel = wifiP2pChannel ?: return
        val manager = wifiP2pManager ?: return

        val txtListener = WifiP2pManager.DnsSdTxtRecordListener { _, record, srcDevice ->
            Log.d(TAG, "[WIFI_DIRECT] TXT record recebido de ${srcDevice.deviceName} (${srcDevice.deviceAddress}): $record")
            val peerNodeId = record["nodeId"]
            if (peerNodeId != null) {
                val peerName = record["name"] ?: srcDevice.deviceName.ifBlank { "Zunga Node" }
                onZungaPeerDiscovered(srcDevice, peerNodeId, peerName)
            }
        }

        val servListener = WifiP2pManager.DnsSdServiceResponseListener { instanceName, registrationType, srcDevice ->
            Log.d(TAG, "[WIFI_DIRECT] Resposta DNS-SD: $instanceName ($registrationType) de ${srcDevice.deviceName}")
        }

        try {
            manager.setDnsSdResponseListeners(channel, servListener, txtListener)
        } catch (e: Exception) {
            Log.e(TAG, "[WIFI_DIRECT] Erro ao definir listeners DNS-SD", e)
        }
    }

    /**
     * Inicia a descoberta de serviços Wi-Fi P2P DNS-SD
     */
    fun startDiscovery() {
        val channel = wifiP2pChannel ?: return
        val manager = wifiP2pManager ?: return

        if (currentState == WifiDirectState.CONNECTED || currentState == WifiDirectState.CONNECTING) {
            Log.d(TAG, "[WIFI_DIRECT] Ignorando startDiscovery pois o estado atual é $currentState")
            return
        }

        transitionTo(WifiDirectState.DISCOVERING)
        setupDnsSdListeners()

        val serviceRequest = WifiP2pDnsSdServiceRequest.newInstance()

        try {
            manager.clearServiceRequests(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    try {
                        manager.addServiceRequest(channel, serviceRequest, object : WifiP2pManager.ActionListener {
                            override fun onSuccess() {
                                try {
                                    manager.discoverServices(channel, object : WifiP2pManager.ActionListener {
                                        override fun onSuccess() {
                                            discoveryBackoffMs = 2000L
                                            Log.d(TAG, "[WIFI_DIRECT] discoverServices iniciado com sucesso!")
                                        }
                                        override fun onFailure(reason: Int) {
                                            handleDiscoveryFailure(reason)
                                        }
                                    })
                                } catch (e: SecurityException) {
                                    Log.e(TAG, "[WIFI_DIRECT] Permissão ausente para discoverServices", e)
                                    transitionTo(WifiDirectState.DISCONNECTED)
                                } catch (e: Exception) {
                                    Log.e(TAG, "[WIFI_DIRECT] Erro ao invocar discoverServices", e)
                                    handleDiscoveryFailure(-1)
                                }
                            }
                            override fun onFailure(reason: Int) {
                                Log.e(TAG, "[WIFI_DIRECT] addServiceRequest falhou: $reason")
                                handleDiscoveryFailure(reason)
                            }
                        })
                    } catch (e: SecurityException) {
                        Log.e(TAG, "[WIFI_DIRECT] Permissão ausente para addServiceRequest", e)
                        transitionTo(WifiDirectState.DISCONNECTED)
                    } catch (e: Exception) {
                        Log.e(TAG, "[WIFI_DIRECT] Erro ao invocar addServiceRequest", e)
                        handleDiscoveryFailure(-1)
                    }
                }
                override fun onFailure(reason: Int) {
                    Log.w(TAG, "[WIFI_DIRECT] clearServiceRequests falhou: $reason")
                    handleDiscoveryFailure(reason)
                }
            })
        } catch (e: SecurityException) {
            Log.e(TAG, "[WIFI_DIRECT] Permissão ausente para clearServiceRequests", e)
            transitionTo(WifiDirectState.DISCONNECTED)
        } catch (e: Exception) {
            Log.e(TAG, "[WIFI_DIRECT] Erro ao invocar clearServiceRequests", e)
            handleDiscoveryFailure(-1)
        }
    }

    /**
     * 4. Retry com backoff exponencial quando a descoberta falhar
     */
    private fun handleDiscoveryFailure(reason: Int) {
        Log.e(TAG, "[WIFI_DIRECT] discoverServices falhou com código: $reason (BUSY=2). Aguardando ${discoveryBackoffMs}ms para retry...")
        transitionTo(WifiDirectState.DISCONNECTED)
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(discoveryBackoffMs)
            discoveryBackoffMs = (discoveryBackoffMs * 2).coerceAtMost(30000L)
            if (currentState == WifiDirectState.DISCONNECTED || currentState == WifiDirectState.DISCOVERING) {
                startDiscovery()
            }
        }
    }

    /**
     * 4. Repetir a descoberta periodicamente (o Wi-Fi P2P expira ao fim de ~2 min)
     */
    private fun startPeriodicDiscovery() {
        periodicDiscoveryJob?.cancel()
        periodicDiscoveryJob = scope.launch {
            while (isActive) {
                delay(REDISCOVERY_INTERVAL_MS)
                if (currentState == WifiDirectState.DISCOVERING || currentState == WifiDirectState.DISCONNECTED) {
                    Log.d(TAG, "[WIFI_DIRECT] Renovando descoberta periódica de serviços P2P...")
                    startDiscovery()
                }
            }
        }
    }

    /**
     * Processa a identificação de um dispositivo autenticado como nó da Rede Zunga
     */
    private fun onZungaPeerDiscovered(device: WifiP2pDevice, peerNodeId: String, peerName: String) {
        if (peerNodeId == myNodeId) {
            // Ignorar o próprio nó
            return
        }

        discoveredZungaPeers[device.deviceAddress] = DiscoveredZungaPeer(
            device = device,
            peerNodeId = peerNodeId,
            peerNodeName = peerName,
            timestamp = System.currentTimeMillis()
        )

        // Atualizar lista para UI
        val peerEntities = discoveredZungaPeers.values.map { p ->
            PeerEntity(
                id = "p2p-${p.peerNodeId}",
                name = p.peerNodeName,
                deviceModel = "Wi-Fi Direct [${p.device.deviceAddress}]",
                location = "Zunga P2P",
                isDirectNeighbor = (currentState == WifiDirectState.CONNECTED && p.device.status == WifiP2pDevice.CONNECTED),
                connectionType = "WIFI_DIRECT",
                ipAddress = null,
                port = 18181
            )
        }
        onPeersDiscovered(peerEntities)
        Log.d(TAG, "[WIFI_DIRECT] Peers encontrados: [${peerEntities.joinToString { "${it.name} (${it.id})" }}]")

        // 3. Evitar ligações simultâneas cruzadas:
        // Só o nó com menor nodeId (ordem lexicográfica) inicia o connect(). O outro espera.
        if (currentState == WifiDirectState.DISCOVERING || currentState == WifiDirectState.DISCONNECTED) {
            val comparison = myNodeId.compareTo(peerNodeId)
            if (comparison < 0) {
                Log.d(TAG, "[WIFI_DIRECT] Tie-breaker: Meu nodeId ($myNodeId) < peerNodeId ($peerNodeId). Eu sou o iniciador!")
                connectToPeer(device, peerName)
            } else {
                Log.d(TAG, "[WIFI_DIRECT] Tie-breaker: Meu nodeId ($myNodeId) >= peerNodeId ($peerNodeId). Aguardando que o outro nó inicie a conexão.")
            }
        }
    }

    /**
     * Liga-se ao dispositivo com timeout de 30s e backoff exponencial
     */
    private fun connectToPeer(device: WifiP2pDevice, peerName: String) {
        val channel = wifiP2pChannel ?: return
        val manager = wifiP2pManager ?: return

        transitionTo(WifiDirectState.CONNECTING)
        Log.d(TAG, "[WIFI_DIRECT] Tentando conectar: $peerName (${device.deviceAddress})")

        // 4. Timeout de 30s no estado CONNECTING
        connectingTimeoutJob?.cancel()
        connectingTimeoutJob = scope.launch {
            delay(CONNECTING_TIMEOUT_MS)
            if (currentState == WifiDirectState.CONNECTING) {
                Log.w(TAG, "[WIFI_DIRECT] Timeout de conexão atingido (30s). Cancelando ligação pendente...")
                cancelConnectAndRediscover()
            }
        }

        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
            wps.setup = WpsInfo.PBC
        }

        try {
            manager.connect(channel, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    connectBackoffMs = 2000L
                    Log.d(TAG, "[WIFI_DIRECT] Pedido de ligação aceite pelo framework")
                }
                override fun onFailure(reason: Int) {
                    Log.e(TAG, "[WIFI_DIRECT] Ligação falhou: $reason (BUSY=2). Aplicando retry com backoff...")
                    connectingTimeoutJob?.cancel()
                    transitionTo(WifiDirectState.DISCONNECTED)
                    scope.launch {
                        delay(connectBackoffMs)
                        connectBackoffMs = (connectBackoffMs * 2).coerceAtMost(30000L)
                        if (currentState == WifiDirectState.DISCONNECTED) {
                            startDiscovery()
                        }
                    }
                }
            })
        } catch (e: SecurityException) {
            Log.e(TAG, "[WIFI_DIRECT] Permissão ausente ao conectar ao peer", e)
            connectingTimeoutJob?.cancel()
            transitionTo(WifiDirectState.DISCONNECTED)
        } catch (e: Exception) {
            Log.e(TAG, "[WIFI_DIRECT] Erro ao ligar ao peer", e)
            connectingTimeoutJob?.cancel()
            transitionTo(WifiDirectState.DISCONNECTED)
        }
    }

    /**
     * Cancela conexão pendente e reinicia a descoberta
     */
    private fun cancelConnectAndRediscover() {
        val channel = wifiP2pChannel
        val manager = wifiP2pManager
        if (channel != null && manager != null) {
            try {
                manager.cancelConnect(channel, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        Log.d(TAG, "[WIFI_DIRECT] cancelConnect efetuado com sucesso")
                        transitionTo(WifiDirectState.DISCONNECTED)
                        startDiscovery()
                    }
                    override fun onFailure(reason: Int) {
                        Log.w(TAG, "[WIFI_DIRECT] cancelConnect falhou com código: $reason")
                        transitionTo(WifiDirectState.DISCONNECTED)
                        startDiscovery()
                    }
                })
            } catch (e: Exception) {
                Log.e(TAG, "[WIFI_DIRECT] Erro ao chamar cancelConnect", e)
                transitionTo(WifiDirectState.DISCONNECTED)
                startDiscovery()
            }
        } else {
            transitionTo(WifiDirectState.DISCONNECTED)
            startDiscovery()
        }
    }

    private fun requestConnectionInfo() {
        val channel = wifiP2pChannel ?: return
        try {
            wifiP2pManager?.requestConnectionInfo(channel) { info ->
                if (info != null && info.groupFormed) {
                    Log.d(TAG, "[WIFI_DIRECT] Conectado: Grupo criado. Owner=${info.isGroupOwner}")
                    transitionTo(WifiDirectState.CONNECTED)
                    val groupOwnerAddress = info.groupOwnerAddress
                    if (groupOwnerAddress != null) {
                        val ipAddress = groupOwnerAddress.hostAddress
                        if (ipAddress != null) {
                            Log.d(TAG, "[WIFI_DIRECT] IP do grupo: $ipAddress")
                            Log.d(TAG, "[WIFI_DIRECT] Socket iniciado: canal de transporte P2P pronto!")
                            onConnectionEstablished(ipAddress, info.isGroupOwner)
                        }
                    }
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "[WIFI_DIRECT] Permissão ausente ao obter dados da ligação", e)
        } catch (e: Exception) {
            Log.e(TAG, "[WIFI_DIRECT] Erro ao pedir dados da ligação", e)
        }
    }
}
