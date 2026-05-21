package com.example.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "peers")
data class PeerEntity(
    @PrimaryKey val id: String,
    val name: String,
    val deviceModel: String,
    val location: String,
    val lastSeen: Long = System.currentTimeMillis(),
    val isDirectNeighbor: Boolean = false,
    val connectionType: String = "WIFI", // "WIFI", "BLUETOOTH", "LAN"
    val ipAddress: String? = null,
    val port: Int? = null,
    val isBlocked: Boolean = false
)

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val id: String,
    val senderId: String,
    val senderName: String,
    val receiverId: String, // peer id or group id
    val isGroup: Boolean = false,
    val groupId: String? = null,
    val content: String,
    val messageType: String = "TEXT", // "TEXT", "AUDIO", "IMAGE", "FILE"
    val timestamp: Long = System.currentTimeMillis(),
    val status: String = "PENDING", // "PENDING", "SENT", "DELIVERED"
    val ttl: Int = 5,
    val hopCount: Int = 0,
    val routePath: String = "", // Comma-separated path of hop node IDs
    val filePath: String? = null,
    val fileName: String? = null,
    val fileSize: Long = 0L
)

@Entity(tableName = "groups")
data class GroupEntity(
    @PrimaryKey val id: String,
    val name: String,
    val creatorId: String,
    val createdAt: Long = System.currentTimeMillis(),
    val membersCount: Int = 1
)

@Entity(tableName = "route_hops")
data class RouteHopEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val messageId: String,
    val fromNode: String,
    val toNode: String,
    val timestamp: Long = System.currentTimeMillis()
)
