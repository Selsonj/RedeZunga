package com.example.network

import android.util.Log
import com.example.data.database.PeerEntity
import com.example.data.database.RouteTable
import com.example.data.database.ZungaDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

class MeshRouter(
    private val myNodeId: String,
    private val myNodeName: String,
    private val database: ZungaDatabase,
    private val scope: CoroutineScope,
    private val getDirectNeighbors: () -> List<PeerEntity>,
    private val transmitPacket: (packet: JSONObject, nextHopId: String) -> Unit,
    private val onLocalMessageReceived: (packet: JSONObject) -> Unit
) {
    private val TAG = "MeshRouter"

    // Thread-safe map for routing table in-memory cache
    private val routeCache = ConcurrentHashMap<String, RouteTable>()

    // Protection against duplicate messages (recent msgIds)
    private val processedMsgIds = ConcurrentHashMap.newKeySet<String>()

    init {
        // Load initial routes from Room database into memory cache
        scope.launch(Dispatchers.IO) {
            try {
                val routes = database.routeTableDao().getAllRoutes().first()
                routes.forEach { route ->
                    routeCache[route.destinationNodeId] = route
                }
                Log.d(TAG, "Loaded ${routes.size} routes from database into cache.")
            } catch (e: Exception) {
                Log.e(TAG, "Error loading routes from DB", e)
            }
        }
    }

    /**
     * Get a snapshot of the current routing table (useful for UI/debugging)
     */
    fun getRouteCacheSnapshot(): List<RouteTable> {
        return routeCache.values.toList()
    }

    /**
     * Learn or update a route to a destination.
     * We prefer routes with lower hopCount.
     */
    fun updateRoute(route: RouteTable) {
        val existing = routeCache[route.destinationNodeId]
        if (existing == null || route.hopCount < existing.hopCount || (System.currentTimeMillis() - existing.lastSeen > 60000)) {
            routeCache[route.destinationNodeId] = route
            scope.launch(Dispatchers.IO) {
                try {
                    database.routeTableDao().insertRoute(route)
                    Log.d(TAG, "[RouteTable] Rota atualizada: Destino=${route.destinationNodeId}, PróximoSalto=${route.nextHopNodeId}, Saltos=${route.hopCount}")
                } catch (e: Exception) {
                    Log.e(TAG, "Erro ao persistir rota no Room", e)
                }
            }
        }
    }

    /**
     * Recebe um pacote de rede e decide o fluxo (processamento local, descarte, ou encaminhamento multi-hop).
     */
    fun receivePacket(packet: JSONObject) {
        try {
            val msgId = packet.optString("msgId", "")
            val senderId = packet.optString("senderId", "")
            val senderName = packet.optString("senderName", "")
            val receiverId = packet.optString("receiverId", "")
            val isGroup = packet.optBoolean("isGroup", false)

            if (msgId.isEmpty()) return

            // 4. Proteção contra mensagens duplicadas
            if (processedMsgIds.contains(msgId)) {
                Log.d(TAG, "[LoopPrevention] Pacote com msgId $msgId já processado. Ignorando para evitar loop.")
                return
            }
            processedMsgIds.add(msgId)

            // Keep buffer size bounded
            if (processedMsgIds.size > 500) {
                processedMsgIds.clear() // Simple clean to prevent memory bloat
            }

            Log.d(TAG, "---------------------------------------------")
            Log.d(TAG, "LOG DE ROTEAMENTO: Mensagem recebida de $senderName ($senderId)")

            // Learning: update route to the originator (senderId)
            val forwardedBy = packet.optString("forwardedBy", "")
            val hopCount = packet.optInt("hopCount", 1)

            // If forwardedBy is blank, it means it's direct from senderId
            val nextHopForSender = if (forwardedBy.isNotEmpty()) forwardedBy else senderId
            val learnedRoute = RouteTable(
                destinationNodeId = senderId,
                nextHopNodeId = nextHopForSender,
                hopCount = hopCount,
                lastSeen = System.currentTimeMillis(),
                connectionType = "WIFI"
            )
            updateRoute(learnedRoute)

            // 3. Alterar tratamento de mensagens
            val isForMe = (receiverId == myNodeId)

            if (isForMe || isGroup) {
                Log.d(TAG, "LOG DE ROTEAMENTO: Destino é local (ou grupo). Processando mensagem localmente.")
                onLocalMessageReceived(packet)
            }

            if (!isForMe || isGroup) {
                // We need to forward/relay the message
                if (!isForMe) {
                    Log.d(TAG, "LOG DE ROTEAMENTO: Destino não é local ($receiverId)")
                } else {
                    Log.d(TAG, "LOG DE ROTEAMENTO: Mensagem de grupo. Propagando na rede mesh.")
                }

                val ttl = packet.optInt("ttl", 5) - 1
                Log.d(TAG, "LOG DE ROTEAMENTO: TTL restante: $ttl")

                if (ttl <= 0) {
                    Log.d(TAG, "LOG DE ROTEAMENTO: TTL esgotado. Descartando pacote para evitar inundação infinita.")
                    return
                }

                // Incrementar hopCount e atualizar routePath
                val newHopCount = hopCount + 1
                val routePath = packet.optString("routePath", "")
                val updatedRoutePath = if (routePath.isEmpty()) myNodeId.take(4) else "$routePath,${myNodeId.take(4)}"

                // Modificar o pacote para re-encaminhamento
                packet.put("ttl", ttl)
                packet.put("hopCount", newHopCount)
                packet.put("routePath", updatedRoutePath)
                packet.put("forwardedBy", myNodeId)

                // Loop prevention: add ourselves to visitedNodes
                val visitedArray = packet.optJSONArray("visitedNodes") ?: JSONArray()
                
                // Check if we already visited
                var alreadyVisited = false
                for (i in 0 until visitedArray.length()) {
                    if (visitedArray.getString(i) == myNodeId) {
                        alreadyVisited = true
                        break
                    }
                }
                if (alreadyVisited) {
                    Log.d(TAG, "LOG DE ROTEAMENTO: Loop evitado. Este nó já consta em visitedNodes.")
                    return
                }

                visitedArray.put(myNodeId)
                packet.put("visitedNodes", visitedArray)

                if (isGroup) {
                    // Flood/Broadcast multi-hop para todos os vizinhos diretos (exceto de quem recebemos)
                    val activeNeighbors = getDirectNeighbors().filter { it.isDirectNeighbor && it.ipAddress != null }
                    activeNeighbors.forEach { neighbor ->
                        var isNeighborVisited = false
                        for (i in 0 until visitedArray.length()) {
                            if (visitedArray.getString(i) == neighbor.id) {
                                isNeighborVisited = true
                                break
                            }
                        }
                        if (neighbor.id != forwardedBy && neighbor.id != senderId && !isNeighborVisited) {
                            Log.d(TAG, "LOG DE ROTEAMENTO: Encaminhando transmissão de grupo para vizinho direto: ${neighbor.name} (${neighbor.id})")
                            transmitPacket(packet, neighbor.id)
                        }
                    }
                } else {
                    // Unicast multi-hop
                    val routeToDest = routeCache[receiverId]
                    if (routeToDest != null) {
                        val nextHopId = routeToDest.nextHopNodeId
                        Log.d(TAG, "LOG DE ROTEAMENTO: Rota encontrada para destino. Encaminhando para próximo salto (nextHop): $nextHopId")
                        packet.put("nextHop", nextHopId)
                        transmitPacket(packet, nextHopId)
                    } else {
                        // Se não há rota conhecida, tentar inundação de busca (fallback) para vizinhos diretos
                        Log.d(TAG, "LOG DE ROTEAMENTO: Rota desconhecida para o destino $receiverId. Inundando vizinhos diretos...")
                        val activeNeighbors = getDirectNeighbors().filter { it.isDirectNeighbor && it.ipAddress != null }
                        activeNeighbors.forEach { neighbor ->
                            var isNeighborVisited = false
                            for (i in 0 until visitedArray.length()) {
                                if (visitedArray.getString(i) == neighbor.id) {
                                    isNeighborVisited = true
                                    break
                                }
                            }
                            if (neighbor.id != forwardedBy && neighbor.id != senderId && !isNeighborVisited) {
                                Log.d(TAG, "LOG DE ROTEAMENTO: Encaminhando busca para vizinho direto: ${neighbor.name}")
                                transmitPacket(packet, neighbor.id)
                            }
                        }
                    }
                }
            }
            Log.d(TAG, "---------------------------------------------")
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao encaminhar/processar pacote no roteador", e)
        }
    }
}
