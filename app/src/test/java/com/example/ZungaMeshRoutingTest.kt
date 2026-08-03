package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.database.PeerEntity
import com.example.data.database.RouteTable
import com.example.data.database.ZungaDatabase
import com.example.network.MeshRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ZungaMeshRoutingTest {

    private lateinit var database: ZungaDatabase
    private val scope = CoroutineScope(Dispatchers.Default)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, ZungaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun testRouteTableInsertionAndLookup() = runBlocking {
        val dao = database.routeTableDao()
        val route = RouteTable(
            destinationNodeId = "peer-B",
            nextHopNodeId = "peer-C",
            hopCount = 2,
            lastSeen = System.currentTimeMillis(),
            connectionType = "WIFI"
        )
        dao.insertRoute(route)

        val retrieved = dao.getRoute("peer-B")
        assertNotNull(retrieved)
        assertEquals("peer-C", retrieved?.nextHopNodeId)
        assertEquals(2, retrieved?.hopCount)

        val allRoutes = dao.getAllRoutes().first()
        assertEquals(1, allRoutes.size)
    }

    @Test
    fun testMeshRouterLearnsRouteFromIncomingPacket() = runBlocking {
        val transmittedPackets = mutableListOf<Pair<JSONObject, String>>()
        val localReceivedPackets = mutableListOf<JSONObject>()

        val router = MeshRouter(
            myNodeId = "node-A",
            myNodeName = "Node A",
            database = database,
            scope = scope,
            getDirectNeighbors = { emptyList() },
            transmitPacket = { packet, nextHopId -> transmittedPackets.add(packet to nextHopId) },
            onLocalMessageReceived = { packet -> localReceivedPackets.add(packet) }
        )

        // Simulate incoming packet from sender-X forwarded by relay-Y
        val incomingPacket = JSONObject().apply {
            put("type", "CHAT")
            put("msgId", "msg-123")
            put("senderId", "sender-X")
            put("senderName", "Sender X")
            put("receiverId", "node-A") // Destined to me
            put("content", "Olá")
            put("isGroup", false)
            put("hopCount", 3)
            put("forwardedBy", "relay-Y")
            put("routePath", "X,Y")
        }

        router.receivePacket(incomingPacket)

        // Wait a short bit for background DB insertion to finish
        kotlinx.coroutines.delay(500)

        // Verify the packet is received locally
        assertEquals(1, localReceivedPackets.size)
        assertEquals("msg-123", localReceivedPackets[0].getString("msgId"))

        val routeToSender = database.routeTableDao().getRoute("sender-X")
        assertNotNull(routeToSender)
        assertEquals("relay-Y", routeToSender?.nextHopNodeId)
        assertEquals(3, routeToSender?.hopCount)
    }

    @Test
    fun testMeshRouterPreventsDuplicatePackets() = runBlocking {
        val localReceivedPackets = mutableListOf<JSONObject>()

        val router = MeshRouter(
            myNodeId = "node-A",
            myNodeName = "Node A",
            database = database,
            scope = scope,
            getDirectNeighbors = { emptyList() },
            transmitPacket = { _, _ -> },
            onLocalMessageReceived = { packet -> localReceivedPackets.add(packet) }
        )

        val packet = JSONObject().apply {
            put("type", "CHAT")
            put("msgId", "msg-duplicate-1")
            put("senderId", "sender-X")
            put("receiverId", "node-A")
            put("content", "Olá")
        }

        // Send twice
        router.receivePacket(packet)
        router.receivePacket(packet)

        // Local processing should only trigger once
        assertEquals(1, localReceivedPackets.size)
    }

    @Test
    fun testMeshRouterPreventsVisitedNodesLoops() = runBlocking {
        val transmittedPackets = mutableListOf<Pair<JSONObject, String>>()

        val router = MeshRouter(
            myNodeId = "node-A",
            myNodeName = "Node A",
            database = database,
            scope = scope,
            getDirectNeighbors = {
                listOf(
                    PeerEntity(id = "peer-B", name = "Peer B", deviceModel = "x", location = "y", isDirectNeighbor = true, ipAddress = "192.168.1.5", port = 18181)
                )
            },
            transmitPacket = { packet, nextHopId -> transmittedPackets.add(packet to nextHopId) },
            onLocalMessageReceived = {}
        )

        val packet = JSONObject().apply {
            put("type", "CHAT")
            put("msgId", "msg-loop-test")
            put("senderId", "sender-X")
            put("receiverId", "peer-B") // To someone else, so we need to forward
            put("content", "Olá")
            put("ttl", 5)
            put("hopCount", 1)
            // Node-A (us) is already in the visited list!
            put("visitedNodes", JSONArray().put("node-A"))
            put("forwardedBy", "sender-X")
        }

        router.receivePacket(packet)

        // Should not transmit because of loop prevention (already visited)
        assertEquals(0, transmittedPackets.size)
    }

    @Test
    fun testMeshRouterDecidesNextHopUnicast() = runBlocking {
        val transmittedPackets = mutableListOf<Pair<JSONObject, String>>()

        // Pre-insert a route to peer-C via peer-B in the route table
        val route = RouteTable(
            destinationNodeId = "peer-C",
            nextHopNodeId = "peer-B",
            hopCount = 2,
            lastSeen = System.currentTimeMillis(),
            connectionType = "WIFI"
        )
        database.routeTableDao().insertRoute(route)

        val router = MeshRouter(
            myNodeId = "node-A",
            myNodeName = "Node A",
            database = database,
            scope = scope,
            getDirectNeighbors = {
                listOf(
                    PeerEntity(id = "peer-B", name = "Peer B", deviceModel = "x", location = "y", isDirectNeighbor = true, ipAddress = "192.168.1.5", port = 18181)
                )
            },
            transmitPacket = { packet, nextHopId -> transmittedPackets.add(packet to nextHopId) },
            onLocalMessageReceived = {}
        )

        // Wait for route table load
        val dbRoute = database.routeTableDao().getRoute("peer-C")
        assertNotNull(dbRoute)

        // Manually update route in cache because our cache loader was async in init
        router.updateRoute(route)

        // Wait for route cache sync and insertion
        kotlinx.coroutines.delay(500)

        val packet = JSONObject().apply {
            put("type", "CHAT")
            put("msgId", "msg-unicast-test")
            put("senderId", "sender-X")
            put("receiverId", "peer-C") // Target is peer-C
            put("content", "Olá")
            put("ttl", 5)
            put("hopCount", 1)
            put("visitedNodes", JSONArray().put("sender-X"))
        }

        router.receivePacket(packet)

        // Wait for any async propagation
        kotlinx.coroutines.delay(500)

        // Verify it routed specifically to peer-B (the next hop to reach peer-C)
        assertEquals(1, transmittedPackets.size)
        assertEquals("peer-B", transmittedPackets[0].second)
        assertEquals("peer-B", transmittedPackets[0].first.getString("nextHop"))
        assertEquals(4, transmittedPackets[0].first.getInt("ttl")) // TTL should be decremented
        assertEquals(2, transmittedPackets[0].first.getInt("hopCount")) // hopCount should be incremented
    }
}
