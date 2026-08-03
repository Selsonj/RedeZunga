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
import android.util.Log
import com.example.data.database.PeerEntity
import kotlinx.coroutines.CoroutineScope

class WifiDirectTransport(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onPeersDiscovered: (List<PeerEntity>) -> Unit,
    private val onConnectionEstablished: (groupOwnerIp: String, isGroupOwner: Boolean) -> Unit,
    private val onConnectionLost: () -> Unit,
    private val onStateChanged: (String) -> Unit
) {
    private val TAG = "WifiDirectTransport"

    enum class WifiDirectState {
        DISCONNECTED,
        DISCOVERING,
        CONNECTING,
        CONNECTED
    }

    private val wifiP2pManager by lazy { context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager }
    private var wifiP2pChannel: WifiP2pManager.Channel? = null
    private var isReceiverRegistered = false

    private var currentState = WifiDirectState.DISCONNECTED

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    val stateText = if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) "ATIVADO" else "DESATIVADO"
                    Log.d(TAG, "[WIFI_DIRECT] Estado Wi-Fi Direct: $stateText")
                }
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    requestPeers()
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    val networkInfo = intent.getParcelableExtra<NetworkInfo>(WifiP2pManager.EXTRA_NETWORK_INFO)
                    if (networkInfo != null) {
                        if (networkInfo.isConnected) {
                            Log.d(TAG, "[WIFI_DIRECT] Conectado: Rede física ativa via P2P")
                            requestConnectionInfo()
                        } else {
                            if (currentState == WifiDirectState.CONNECTED) {
                                Log.d(TAG, "[WIFI_DIRECT] Rede Wi-Fi Direct desconectada")
                                transitionTo(WifiDirectState.DISCONNECTED)
                                onConnectionLost()
                                // Resume discovery automatically
                                startDiscovery()
                            }
                        }
                    }
                }
                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
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
        Log.d(TAG, "[WIFI_DIRECT] Iniciando serviço de Wi-Fi Direct...")
        registerReceiver()
        startDiscovery()
    }

    fun stop() {
        Log.d(TAG, "[WIFI_DIRECT] Parando serviço de Wi-Fi Direct...")
        unregisterReceiver()
        try {
            wifiP2pChannel?.let { channel ->
                wifiP2pManager?.cancelConnect(channel, null)
                wifiP2pManager?.removeGroup(channel, null)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Permissão ausente ao parar Wi-Fi Direct", e)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao limpar conexões Wi-Fi Direct", e)
        }
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

    fun startDiscovery() {
        val channel = wifiP2pChannel ?: return
        transitionTo(WifiDirectState.DISCOVERING)
        try {
            wifiP2pManager?.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(TAG, "[WIFI_DIRECT] discoverPeers iniciado com sucesso")
                }
                override fun onFailure(reason: Int) {
                    Log.e(TAG, "[WIFI_DIRECT] discoverPeers falhou: $reason")
                    transitionTo(WifiDirectState.DISCONNECTED)
                }
            })
        } catch (e: SecurityException) {
            Log.e(TAG, "[WIFI_DIRECT] Permissão ausente para discoverPeers", e)
            transitionTo(WifiDirectState.DISCONNECTED)
        } catch (e: Exception) {
            Log.e(TAG, "[WIFI_DIRECT] Erro ao iniciar descoberta", e)
            transitionTo(WifiDirectState.DISCONNECTED)
        }
    }

    private fun requestPeers() {
        val channel = wifiP2pChannel ?: return
        try {
            wifiP2pManager?.requestPeers(channel) { peerList ->
                val devices = peerList?.deviceList ?: emptyList()
                val p2pPeers = devices.map { device ->
                    val statusText = when (device.status) {
                        WifiP2pDevice.CONNECTED -> "CONNECTED"
                        WifiP2pDevice.INVITED -> "INVITED"
                        WifiP2pDevice.FAILED -> "FAILED"
                        WifiP2pDevice.AVAILABLE -> "AVAILABLE"
                        WifiP2pDevice.UNAVAILABLE -> "UNAVAILABLE"
                        else -> "UNKNOWN"
                    }
                    PeerEntity(
                        id = "p2p-${device.deviceAddress}",
                        name = device.deviceName.ifBlank { "Sem nome [P2P]" },
                        deviceModel = "Wi-Fi Direct [${device.deviceAddress}]",
                        location = "Status: $statusText",
                        isDirectNeighbor = (device.status == WifiP2pDevice.CONNECTED),
                        connectionType = "WIFI_DIRECT",
                        ipAddress = null,
                        port = 18181
                    )
                }

                // Call client callback
                onPeersDiscovered(p2pPeers)

                // Log found devices
                val peersListText = devices.joinToString(separator = ", ") { "${it.deviceName} (${it.deviceAddress})" }
                Log.d(TAG, "[WIFI_DIRECT] Peers encontrados: [$peersListText]")
                devices.forEach { device ->
                    Log.d(TAG, "[WIFI_DIRECT] Peer detalhado: name=${device.deviceName}, address=${device.deviceAddress}, status=${device.status}")
                }

                // Automatic Connection logic
                if (currentState == WifiDirectState.DISCOVERING || currentState == WifiDirectState.DISCONNECTED) {
                    val availableDevice = devices.firstOrNull { it.status == WifiP2pDevice.AVAILABLE }
                    if (availableDevice != null) {
                        connectToPeer(availableDevice)
                    }
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "[WIFI_DIRECT] Sem permissão para requestPeers", e)
        } catch (e: Exception) {
            Log.e(TAG, "[WIFI_DIRECT] Erro ao solicitar peers", e)
        }
    }

    private fun connectToPeer(device: WifiP2pDevice) {
        val channel = wifiP2pChannel ?: return
        transitionTo(WifiDirectState.CONNECTING)
        Log.d(TAG, "[WIFI_DIRECT] Tentando conectar: ${device.deviceName} (${device.deviceAddress})")

        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
            wps.setup = WpsInfo.PBC
        }

        try {
            wifiP2pManager?.connect(channel, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(TAG, "[WIFI_DIRECT] Pedido de ligação aceite")
                }
                override fun onFailure(reason: Int) {
                    Log.e(TAG, "[WIFI_DIRECT] Ligação falhou: $reason")
                    transitionTo(WifiDirectState.DISCOVERING)
                    // Retry discovery
                    startDiscovery()
                }
            })
        } catch (e: SecurityException) {
            Log.e(TAG, "[WIFI_DIRECT] Permissão ausente ao conectar ao peer", e)
            transitionTo(WifiDirectState.DISCOVERING)
        } catch (e: Exception) {
            Log.e(TAG, "[WIFI_DIRECT] Erro ao ligar ao peer", e)
            transitionTo(WifiDirectState.DISCOVERING)
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
