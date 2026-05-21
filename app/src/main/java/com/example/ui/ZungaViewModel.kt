package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.ZungaRepository
import com.example.data.database.GroupEntity
import com.example.data.database.MessageEntity
import com.example.data.database.PeerEntity
import com.example.data.database.ZungaDatabase
import com.example.network.ZungaMeshEngine
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

sealed class ZungaScreen {
    object Splash : ZungaScreen()
    object Onboarding : ZungaScreen()
    object Dashboard : ZungaScreen()
    data class ChatRoom(val id: String, val name: String, val isGroup: Boolean) : ZungaScreen()
    data class VoiceCall(val peerName: String, val isIncoming: Boolean) : ZungaScreen()
}

class ZungaViewModel(application: Application) : AndroidViewModel(application) {

    private var database: ZungaDatabase? = null
    var meshEngine: ZungaMeshEngine? = null
    private var repository: ZungaRepository? = null

    var initializationError: String? = null
        private set

    init {
        try {
            val db = ZungaDatabase.getDatabase(application)
            database = db
            val engine = ZungaMeshEngine(application, viewModelScope)
            meshEngine = engine
            repository = ZungaRepository(application, db, engine, viewModelScope)
        } catch (e: Throwable) {
            android.util.Log.e("ZungaViewModel", "FATAL EXCEPTION DURING VIEWMODEL INSTANTIATION", e)
            initializationError = "Error: ${e.javaClass.simpleName}: ${e.message}\n" + 
                e.stackTrace.take(15).joinToString("\n") { "  at ${it.className}.${it.methodName}(${it.fileName}:${it.lineNumber})" }
        }
    }

    // UI screen state
    private val _currentScreen = MutableStateFlow<ZungaScreen>(ZungaScreen.Splash)
    val currentScreen = _currentScreen.asStateFlow()

    // Mesh Settings
    val isSimulatorMode = meshEngine?.isSimulatorMode ?: MutableStateFlow(true).asStateFlow()
    val isMeshEngineRunning = meshEngine?.networkingActive ?: MutableStateFlow(false).asStateFlow()
    val routingHopsTrace = meshEngine?.routingHopsTrace ?: MutableStateFlow<List<String>>(emptyList()).asStateFlow()

    // Profile settings
    private val _myNodeName = MutableStateFlow(meshEngine?.myNodeName ?: "Zunga Node")
    val myNodeName = _myNodeName.asStateFlow()

    private val _myLocation = MutableStateFlow(meshEngine?.myLocation ?: "Luanda, Angola")
    val myLocation = _myLocation.asStateFlow()

    private val _isBatterySaver = MutableStateFlow(false)
    val isBatterySaver = _isBatterySaver.asStateFlow()

    // Crypto diagnostics
    val myNodeId = meshEngine?.myNodeId ?: ""
    val publicKeyStr = meshEngine?.myPublicKeyString ?: ""

    // Database state flows
    val activePeers: StateFlow<List<PeerEntity>> = (repository?.activePeersFlow ?: emptyFlow())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val conversations: StateFlow<List<MessageEntity>> = (repository?.conversationsFlow ?: emptyFlow())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val groups: StateFlow<List<GroupEntity>> = (repository?.groupsFlow ?: emptyFlow())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Message details state flow for current chat room
    private val _activeChatPeerId = MutableStateFlow<String?>(null)
    private val _activeChatIsGroup = MutableStateFlow(false)

    val activeChatMessages: StateFlow<List<MessageEntity>> = combine(
        _activeChatPeerId,
        _activeChatIsGroup,
        repository?.conversationsFlow ?: emptyFlow() // trigger updates
    ) { peerId, isGroup, _ ->
        val repo = repository
        if (peerId == null || repo == null) {
            emptyList()
        } else if (isGroup) {
            repo.getGroupMessagesFlow(peerId).firstOrNull() ?: emptyList()
        } else {
            repo.getChatMessagesFlow(peerId).firstOrNull() ?: emptyList()
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Voice call simulation details
    private val _callStateStr = MutableStateFlow("DISCONNECTED") // "RINGING", "CONNECTED", "DISCONNECTED"
    val callStateStr = _callStateStr.asStateFlow()

    private val _callDurationCount = MutableStateFlow(0)
    val callDurationCount = _callDurationCount.asStateFlow()

    private var callTimerJob: Job? = null

    init {
        val engine = meshEngine
        if (engine != null) {
            // Auto start mesh on boot
            engine.startServices()

            // Sync local settings UI with engine
            _myNodeName.value = engine.myNodeName
            _myLocation.value = engine.myLocation
        }
    }

    fun navigateTo(screen: ZungaScreen) {
        _currentScreen.value = screen
        
        // Update loaded chat when entering room
        if (screen is ZungaScreen.ChatRoom) {
            _activeChatPeerId.value = screen.id
            _activeChatIsGroup.value = screen.isGroup
            // Start listening to message stream for Room
            syncActiveChat(screen.id, screen.isGroup)
        } else {
            _activeChatPeerId.value = null
        }
    }

    private fun syncActiveChat(id: String, isGroup: Boolean) {
        val repo = repository ?: return
        viewModelScope.launch {
            if (isGroup) {
                repo.getGroupMessagesFlow(id).collect {
                    // Triggers state refresh
                }
            } else {
                repo.getChatMessagesFlow(id).collect {
                    // Triggers state refresh
                }
            }
        }
    }

    // Toggle simulation mode
    fun setSimulatorMode(enabled: Boolean) {
        meshEngine?.setSimulatorMode(enabled)
    }

    // Toggle real NSD sockets
    fun toggleMeshNetwork(enabled: Boolean) {
        val engine = meshEngine ?: return
        if (enabled) {
            engine.startServices()
        } else {
            engine.stopServices()
        }
    }

    // Modify profile
    fun updateProfile(name: String, location: String) {
        _myNodeName.value = name
        _myLocation.value = location
        meshEngine?.let {
            it.myNodeName = name
            it.myLocation = location
        }
    }

    // Toggle Battery Saver
    fun setBatterySaverEnabled(enabled: Boolean) {
        _isBatterySaver.value = enabled
        meshEngine?.isBatterySaverEnabled = enabled
    }

    // Send Message
    fun sendTextMessage(content: String) {
        val peerId = _activeChatPeerId.value ?: return
        val isGroup = _activeChatIsGroup.value
        val repo = repository ?: return
        
        viewModelScope.launch {
            if (isGroup) {
                repo.sendChatMessage(content, peerId, isGroup = true, groupId = peerId)
            } else {
                repo.sendChatMessage(content, peerId, isGroup = false)
            }
        }
    }

    // Create a new mesh communication group
    fun createGroupChannel(name: String) {
        val repo = repository ?: return
        viewModelScope.launch {
            repo.createGroup(name)
        }
    }

    // Add a custom simulator node
    fun addCustomMockNode(name: String, location: String, model: String, isDirect: Boolean, connectionType: String) {
        meshEngine?.addSimulatedPeer(name, location, model, isDirect, connectionType)
    }

    // Direct remove peer
    fun removeSimulatorNode(id: String) {
        meshEngine?.removeSimulatedPeer(id)
    }

    // Voice call controls
    fun initiateVoiceCall(peerName: String) {
        navigateTo(ZungaScreen.VoiceCall(peerName, isIncoming = false))
        _callStateStr.value = "RINGING"
        
        viewModelScope.launch {
            delay(1500) // Simulate recipient pickup delay
            _callStateStr.value = "CONNECTED"
            _callDurationCount.value = 0
            
            callTimerJob?.cancel()
            callTimerJob = viewModelScope.launch {
                while (_callStateStr.value == "CONNECTED") {
                    delay(1000)
                    _callDurationCount.value += 1
                }
            }
        }
    }

    fun receiveSimulatedVoiceCall(peerName: String) {
        navigateTo(ZungaScreen.VoiceCall(peerName, isIncoming = true))
        _callStateStr.value = "RINGING"
    }

    fun acceptCall() {
        _callStateStr.value = "CONNECTED"
        _callDurationCount.value = 0
        callTimerJob?.cancel()
        callTimerJob = viewModelScope.launch {
            while (_callStateStr.value == "CONNECTED") {
                delay(1000)
                _callDurationCount.value += 1
            }
        }
    }

    fun hangUpCall() {
        _callStateStr.value = "DISCONNECTED"
        callTimerJob?.cancel()
        _callDurationCount.value = 0
        navigateTo(ZungaScreen.Dashboard)
    }

    override fun onCleared() {
        super.onCleared()
        meshEngine?.stopServices()
    }
}
