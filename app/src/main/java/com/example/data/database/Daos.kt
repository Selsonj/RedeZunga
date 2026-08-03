package com.example.data.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface PeerDao {
    @Query("SELECT * FROM peers ORDER BY lastSeen DESC")
    fun getAllPeers(): Flow<List<PeerEntity>>

    @Query("SELECT * FROM peers WHERE isDirectNeighbor = 1 AND isBlocked = 0 ORDER BY lastSeen DESC")
    fun getActiveNeighbors(): Flow<List<PeerEntity>>

    @Query("SELECT * FROM peers WHERE id = :id LIMIT 1")
    suspend fun getPeerById(id: String): PeerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPeer(peer: PeerEntity)

    @Query("UPDATE peers SET isDirectNeighbor = :isDirect, lastSeen = :time, ipAddress = :ip, port = :port WHERE id = :id")
    suspend fun updateNeighborStatus(id: String, isDirect: Boolean, time: Long, ip: String?, port: Int?)

    @Query("UPDATE peers SET isDirectNeighbor = 0")
    suspend fun clearDirectNeighbors()

    @Delete
    suspend fun deletePeer(peer: PeerEntity)
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE (senderId = :peerId AND receiverId = :myId) OR (senderId = :myId AND receiverId = :peerId) ORDER BY timestamp ASC")
    fun getPrivateChatMessages(peerId: String, myId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE isGroup = 1 AND receiverId = :groupId ORDER BY timestamp ASC")
    fun getGroupChatMessages(groupId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages ORDER BY timestamp DESC")
    fun getAllMessages(): Flow<List<MessageEntity>>

    // Retrieve active chat summaries (latest message per recipient or sender)
    @Query("""
        SELECT * FROM messages 
        WHERE id IN (
            SELECT MAX(id) FROM messages 
            GROUP BY CASE WHEN isGroup = 1 THEN receiverId ELSE (CASE WHEN senderId = :myId THEN receiverId ELSE senderId END) END
        )
        ORDER BY timestamp DESC
    """)
    fun getChatConversations(myId: String): Flow<List<MessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity)

    @Query("UPDATE messages SET status = :status WHERE id = :messageId")
    suspend fun updateMessageStatus(messageId: String, status: String)

    @Query("SELECT * FROM messages WHERE status = 'PENDING' ORDER BY timestamp ASC")
    suspend fun getPendingOfflineMessages(): List<MessageEntity>
}

@Dao
interface GroupDao {
    @Query("SELECT * FROM groups ORDER BY createdAt DESC")
    fun getAllGroups(): Flow<List<GroupEntity>>

    @Query("SELECT * FROM groups WHERE id = :id LIMIT 1")
    suspend fun getGroupById(id: String): GroupEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGroup(group: GroupEntity)

    @Delete
    suspend fun deleteGroup(group: GroupEntity)
}

@Dao
interface RouteHopDao {
    @Query("SELECT * FROM route_hops WHERE messageId = :messageId ORDER BY timestamp ASC")
    fun getHopsForMessage(messageId: String): Flow<List<RouteHopEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHop(hop: RouteHopEntity)
}

@Dao
interface RouteTableDao {
    @Query("SELECT * FROM route_table")
    fun getAllRoutes(): Flow<List<RouteTable>>

    @Query("SELECT * FROM route_table WHERE destinationNodeId = :dest LIMIT 1")
    suspend fun getRoute(dest: String): RouteTable?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRoute(route: RouteTable)

    @Query("DELETE FROM route_table WHERE destinationNodeId = :dest")
    suspend fun deleteRoute(dest: String)

    @Query("DELETE FROM route_table")
    suspend fun clearRoutes()
}
