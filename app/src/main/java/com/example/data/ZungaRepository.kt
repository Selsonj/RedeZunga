package com.example.data

import android.content.Context
import java.util.UUID
import com.example.data.database.*
import com.example.network.ZungaMeshEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class ZungaRepository(
    private val context: Context,
    private val database: ZungaDatabase,
    val meshEngine: ZungaMeshEngine,
    private val scope: CoroutineScope
) {
    private val peerDao = database.peerDao()
    private val messageDao = database.messageDao()
    private val groupDao = database.groupDao()
    private val routeHopDao = database.routeHopDao()

    // Observe active peers, combining real peers or simulated peers based on simulator mode toggled in UI
    val activePeersFlow: Flow<List<PeerEntity>> = combine(
        meshEngine.isSimulatorMode,
        meshEngine.realPeers,
        meshEngine.simulatedPeers,
        peerDao.getAllPeers()
    ) { isSim, realList, simList, dbList ->
        if (isSim) {
            simList
        } else {
            // Merge DB discovered peers with memory real status
            (realList + dbList).distinctBy { it.id }
        }
    }

    // Direct conversations list
    val conversationsFlow: Flow<List<MessageEntity>> = messageDao.getChatConversations(meshEngine.myNodeId)

    // All local groups flow
    val groupsFlow: Flow<List<GroupEntity>> = groupDao.getAllGroups()

    init {
        // Automatically save incoming packets received through NSD/Sockets into Room database
        meshEngine.setOnMessageReceivedListener { messageEntity ->
            scope.launch {
                insertMessage(messageEntity)
                
                // If this peer is not yet registered in our database, register them!
                val existing = peerDao.getPeerById(messageEntity.senderId)
                if (existing == null) {
                    val incomingPeer = PeerEntity(
                        id = messageEntity.senderId,
                        name = messageEntity.senderName,
                        deviceModel = "Dispositivo Remoto",
                        location = "Rede Mesh local",
                        isDirectNeighbor = true
                    )
                    peerDao.insertPeer(incomingPeer)
                }

                // If message had routing hops, save them for diagram mapping
                if (messageEntity.routePath.isNotEmpty()) {
                    val hops = messageEntity.routePath.split(",")
                    for (i in 0 until hops.size - 1) {
                        routeHopDao.insertHop(
                            RouteHopEntity(
                                messageId = messageEntity.id,
                                fromNode = hops[i],
                                toNode = hops[i + 1]
                            )
                        )
                    }
                }
            }
        }

        // Initialize with default mesh groups (P2P Angola Channels)
        scope.launch {
            if (groupDao.getGroupById("grupo-angola-geral") == null) {
                groupDao.insertGroup(
                    GroupEntity(
                        id = "grupo-angola-geral",
                        name = "Comunidade Geral - Luanda",
                        creatorId = "sistema-zunga",
                        membersCount = 37
                    )
                )
            }
            if (groupDao.getGroupById("grupo-mercado-cazenga") == null) {
                groupDao.insertGroup(
                    GroupEntity(
                        id = "grupo-mercado-cazenga",
                        name = "Mercado do Asa Branca - Trocas",
                        creatorId = "sistema-zunga",
                        membersCount = 14
                    )
                )
            }
        }
    }

    // Get messages for direct chat
    fun getChatMessagesFlow(peerId: String): Flow<List<MessageEntity>> {
        return messageDao.getPrivateChatMessages(peerId, meshEngine.myNodeId)
    }

    // Get messages for group channel
    fun getGroupMessagesFlow(groupId: String): Flow<List<MessageEntity>> {
        return messageDao.getGroupChatMessages(groupId)
    }

    // Insert Message locally
    suspend fun insertMessage(message: MessageEntity) {
        messageDao.insertMessage(message)
    }

    // Send chat message
    suspend fun sendChatMessage(content: String, recipientId: String, isGroup: Boolean = false, groupId: String? = null) {
        // 1. Dispatch packet over physical or simulated mesh routing
        val message = meshEngine.sendMessage(content, recipientId, isGroup, groupId)
        
        // 2. Insert into local SQLite immediately as "SENT" or "DELIVERED"
        messageDao.insertMessage(message)

        // 3. Keep peer record up to date in DB if single peer
        if (!isGroup) {
            val peer = peerDao.getPeerById(recipientId)
            if (peer == null) {
                // Insert a placeholder to prevent missing lists
                val simulatorPeer = meshEngine.simulatedPeers.value.find { it.id == recipientId }
                val realPeer = meshEngine.realPeers.value.find { it.id == recipientId }
                val resolvedModel = simulatorPeer?.deviceModel ?: (realPeer?.deviceModel ?: "Ativo")
                val resolvedLoc = simulatorPeer?.location ?: (realPeer?.location ?: "Próximo")
                
                peerDao.insertPeer(
                    PeerEntity(
                        id = recipientId,
                        name = simulatorPeer?.name ?: (realPeer?.name ?: "Zunga Peer"),
                        deviceModel = resolvedModel,
                        location = resolvedLoc,
                        isDirectNeighbor = true
                    )
                )
            }
        }
    }

    // Create a new local group channel
    suspend fun createGroup(name: String) {
        val newGroupId = "group-${UUID.randomUUID().toString().take(8)}"
        val group = GroupEntity(
            id = newGroupId,
            name = name,
            creatorId = meshEngine.myNodeId,
            membersCount = 1
        )
        groupDao.insertGroup(group)

        // Insert initial system notification message
        val welcomeMsg = MessageEntity(
            id = UUID.randomUUID().toString(),
            senderId = "sistema-zunga",
            senderName = "Rede Zunga",
            receiverId = newGroupId,
            isGroup = true,
            groupId = newGroupId,
            content = "Criaste o canal de conversação: $name. Peers próximos podem sintonizar e participar sem internet!",
            timestamp = System.currentTimeMillis(),
            status = "DELIVERED"
        )
        messageDao.insertMessage(welcomeMsg)
    }

    // Clear direct neighbor states
    suspend fun resetNeighbors() {
        peerDao.clearDirectNeighbors()
    }
}
