/**
 * TestBridgeControllerOVS.scala - Test BridgeController's interactions with
 *                                 Open vSwitch.
 *
 * Copyright 2011 Midokura Inc.  All rights reserved.
 */

package com.midokura.midolman

import com.midokura.midolman.eventloop.{SelectListener, SelectLoop}
import com.midokura.midolman.openflow.ControllerStubImpl
import com.midokura.midolman.openvswitch.{
                BridgeBuilder,
                OpenvSwitchDatabaseConnectionImpl,
                OpenvSwitchDatabaseConnectionBridgeConnector}
import com.midokura.midolman.packets.MAC
import com.midokura.midolman.packets.IntIPv4
import com.midokura.midolman.state.{MacPortMap, MockDirectory,
                                    PortToIntNwAddrMap}
import com.midokura.midolman.openflow.OpenFlowError
import org.openflow.protocol.{OFMatch, OFPort, OFStatisticsReply}
import org.openflow.protocol.action.OFActionEnqueue
import org.openflow.protocol.statistics.{
    OFAggregateStatisticsReply, OFDescriptionStatistics, OFFlowStatisticsReply,
    OFPortStatisticsReply, OFQueueStatisticsReply, OFStatistics,
    OFTableStatistics}
import org.apache.zookeeper.CreateMode
import org.junit.{AfterClass, BeforeClass, Ignore, Test}
import org.junit.Assert._
import org.junit.Assume._
import org.slf4j.LoggerFactory
import scala.collection.JavaConversions._
import scala.collection.mutable

import java.io.{File, RandomAccessFile}
import java.lang.Long.parseLong
import java.net.ConnectException
import java.net.InetSocketAddress
import java.nio.channels.{FileLock, SelectionKey, ServerSocketChannel}
import java.util.concurrent.{Executors, TimeUnit, ScheduledFuture, Semaphore}
import java.util.{Date, UUID}

class ChattySemaphore(capacity: Int) extends Semaphore(capacity) {
    final val log = LoggerFactory.getLogger(classOf[ChattySemaphore])

    override def release() {
        super.release
        log.info("release: {}", this)
    }

    override def acquire() {
        super.acquire
        log.info("acquire: {}", this)
    }
}

/**
 * Test the BridgeController's interaction with Open vSwitch.
 */
object TestBridgeControllerOVS 
        extends OpenvSwitchDatabaseConnectionBridgeConnector 
        with SelectListener {
    // All the "static" variables and methods.
    final val log = LoggerFactory.getLogger(classOf[TestBridgeControllerOVS])
    private final val testportName = "testbrport"
    private final val publicIP = IntIPv4.fromString("192.168.1.50")
    private final var controller: BridgeControllerTester = _
    private var zkDir = new MockDirectory
    private final val zkRoot = "/zk_root"
    private final val of_port = 6635
    private final var listenSock: ServerSocketChannel = _
    private final var reactor: SelectLoop = _
    private final var tookTooLong: ScheduledFuture[_] = _
    private final var reactorThread: Thread = _
    private final var serializeTestsSemaphore = new ChattySemaphore(0)
    private final var portModSemaphore = new ChattySemaphore(0)
    private final var connectionSemaphore = new ChattySemaphore(0)
    @volatile private var tooLongFlag = false

    override final val bridgeName = "testbr"
    override final val bridgeExtIdKey = "midolman-vnet"
    override final val bridgeExtIdValue = "ebbf1184-4dc2-11e0-b2c3-a4b17460e319"
    override final val bridgeId: Long = 0x74a027d6e9288adbL
    private final val portName = "testport"
    private final var qosUUID: String = _
    private final val qosExtIdKey = "test-midolman-vnet-qos"
    private final val qosExtIdVal = UUID.randomUUID().toString
    private final var queueUUID: String = _
    private final val queueExtIdKey = "test-midolman-vnet-queue"
    private final val queueExtIdVal = UUID.randomUUID().toString

    @BeforeClass def initializeTest() {
        connectToOVSDB

        // Set up the (mock) ZooKeeper directories.
        val portLocKey = "/port_locs"
        val macPortKey = "/mac_port"
        val noData = Array[Byte]()
        val midoDirName = zkDir.add(zkRoot, noData, CreateMode.PERSISTENT)
        var midoDir = zkDir.getSubDirectory(midoDirName)
        midoDir.add(portLocKey, noData, CreateMode.PERSISTENT)
        midoDir.add(macPortKey, noData, CreateMode.PERSISTENT)
        val portLocMap = new PortToIntNwAddrMap(
            midoDir.getSubDirectory(portLocKey))
        val macPortMap = new MacPortMap(midoDir.getSubDirectory(macPortKey))
        reactor = new SelectLoop(Executors.newScheduledThreadPool(1))

        reactorThread = new Thread() { override def run() {
            log.info("reactorThread starting")

            controller = new BridgeControllerTester(
                /* datapathId */              bridgeId,
                /* switchUuid */              UUID.fromString(bridgeExtIdValue),
                /* greKey */                  0xe1234,
                /* port_loc_map */            portLocMap,
                /* mac_port_map */            macPortMap,
                /* flowExpireMillis */        300*1000,
                /* idleFlowExpireMillis */    60*1000,
                /* publicIp */                publicIP,
                /* macPortTimeoutMillis */    40*1000,
                /* ovsdb */                   ovsdb,
                /* reactor */                 reactor,
                /* externalIdKey */           bridgeExtIdKey,
                /* portSemaphore */           portModSemaphore,
                /* connectionSemaphore */     connectionSemaphore)

            // Get a connection to the OF switch.
            listenSock = ServerSocketChannel.open
            listenSock.configureBlocking(false)
            listenSock.socket.bind(new InetSocketAddress(of_port))

            reactor.register(listenSock, SelectionKey.OP_ACCEPT,
                             TestBridgeControllerOVS.this)

            registerController

            tookTooLong = reactor.schedule(
                              new Runnable() {
                                  def run {
                                      log.info("Took too long!")
                                      tooLongFlag = true
                                      reactor.shutdown
                                      ovsdb.close
                                      portModSemaphore.release(10)
                                      connectionSemaphore.release
                                  } },
                              10000, TimeUnit.MILLISECONDS)
            reactor.doLoop
            log.info("reactor thread exiting")
        } }

        reactorThread.start

        addPortWithQosAndQueue(portName)
        assertTrue(ovsdb.hasPort(portName))
        log.info("Port {} successfully added to ovsdb", portName)
        log.info("Leaving initializeTest()")
    }

    @AfterClass def finalizeTest() {
        try {
            if (null != ovsdb) {
                reactor.shutdown
                assertFalse(tooLongFlag)
                assertTrue(ovsdb.hasController(target))
                assertTrue(ovsdb.hasBridge(bridgeId))
                ovsdb.delBridgeOpenflowControllers(bridgeId)
                assertFalse(ovsdb.hasController(target))
                // Queue
                assertTrue(ovsdb.hasQueue(queueUUID))
                ovsdb.clearQosQueues(qosUUID)
                assertTrue(ovsdb.isEmptyColumn("QoS", qosUUID, "queues"))
                ovsdb.delQueue(queueUUID)
                assertFalse(ovsdb.hasQueue(queueUUID))
                // QoS
                assertTrue(ovsdb.hasQos(qosUUID))
                ovsdb.unsetPortQos(portName)
                ovsdb.delQos(qosUUID)
                assertFalse(ovsdb.hasQos(qosUUID))
                ovsdb.delPort(portName)
                assertFalse(ovsdb.hasPort(portName))
            }
        } finally {
            disconnectFromOVSDB
        }
    }

    def registerController() {
        var cb = ovsdb.addBridgeOpenflowController(bridgeName, target)
        cb.build
        assertTrue(ovsdb.hasController(target))
    }

    def handleEvent(key: SelectionKey) {
        log.info("handleEvent {}", key)

        var sock = listenSock.accept
        if (sock == null) {
            log.info("Couldn't accept connection -- isAcceptable() = {}",
                     key.isAcceptable)
            return
        }
        log.info("accepted connection from {}",
                 sock.socket.getRemoteSocketAddress)
        sock.socket.setTcpNoDelay(true)
        sock.configureBlocking(false)

        var controllerStub = new ControllerStubImpl(sock, reactor, controller)
        controller.setControllerStub(controllerStub)
        var switchKey = reactor.register(sock, SelectionKey.OP_READ,
                                         controllerStub)
        reactor.wakeup
        controllerStub.start
    }

    def addSystemPort(portName : String, vportId : UUID) {
        var pb = ovsdb.addSystemPort(bridgeName, portName)
        pb.ifMac("00:01:02:03:04:05")
        pb.externalId(bridgeExtIdKey, vportId.toString())
        pb.build
    }

    def addInternalPort(portName: String) {
        ovsdb.addInternalPort(bridgeName, portName).build
    }

    def addPortWithQosAndQueue(portName : String) {
        val queues = ovsdb.addQueue().maxRate(1000000)
        queues.externalId(queueExtIdKey, queueExtIdVal).build
        val queueUUIDs: List[String] = (ovsdb.getQueueUUIDsByExternalId(
            queueExtIdKey, queueExtIdVal): mutable.Set[String]).toList
        queueUUID = queueUUIDs(0)
        val qos = ovsdb.addQos("linux-hfsc").queues(
            (mutable.Map(
                (0L: java.lang.Long) -> (queueUUID: java.lang.String))))
        qos.externalId(qosExtIdKey, qosExtIdVal).build
        val qosUUIDs: List[String] = (ovsdb.getQosUUIDsByExternalId(
              qosExtIdKey, qosExtIdVal): mutable.Set[String]).toList
        qosUUID = qosUUIDs(0)
      ovsdb.addInternalPort(bridgeName, portName).qos(qosUUIDs(0)).build
    }

    def addTapPort(portName : String, vportId : UUID) {
        var pb = ovsdb.addTapPort(bridgeName, portName)
        pb.externalId(bridgeExtIdKey, vportId.toString())
        pb.build
    }
}

class TestBridgeControllerOVS {
    // import all the statics.
    import TestBridgeControllerOVS._

    @Test def testConnectionMade() {
        // Ensure that this runs first, by having the other tests block on
        // serializeTestsSemaphore, which this routine .releases.
        log.info("testConnectionMade")
        // Wait for the connection to be established.
        connectionSemaphore.acquire
        // Drain the portModSemaphroe from the ports of the initial connection.
        portModSemaphore.drainPermits
        serializeTestsSemaphore.release
    }

    @Test
    @Ignore
    def testNewSystemPort() {
        log.info("testNewSystemPort called")
        serializeTestsSemaphore.acquire
        try {
            log.info("testNewSystemPort has semaphore")
            val portName = "sys" + testportName
            // Clear the list of added ports, and make a new port which should
            // trigger an addPort callback.
            controller.addedPorts = List()
            val portId = UUID.randomUUID()
            addSystemPort(portName, portId)
            assertTrue(ovsdb.hasPort(portName))
            log.info("Port {} successfully added to ovsdb", portName)
            // TODO: Verify this is a system port.
            portModSemaphore.acquire
            assertEquals(1, controller.addedPorts.size)
            assertEquals(portId, controller.addedPorts(0))
            ovsdb.delPort(portName)
            assertFalse(ovsdb.hasPort(portName))
            controller.addedPorts = List()
        } finally {
            serializeTestsSemaphore.release
            log.info("testNewSystemPort exiting")
        }
    }

    @Test(timeout=1000) def testGetDescStats() {
        log.info("testGetDescStats")
        try {
            val response = controller.getDescStats()
            log.info("Controller got the response: {}", response)
            for (reply: OFDescriptionStatistics <- response) {
                assertEquals(reply.getManufacturerDescription,
                    "Nicira Networks, Inc.")
                assertEquals(reply.getHardwareDescription,
                    "Open vSwitch")
            }
        } catch {
            case e: OpenFlowError => throw e
        } finally {
            log.info("testGetDescStats exiting")
        }
    }

    // TODO(tfukushima): Add a unit test for getFlowStats.
    @Ignore @Test(timeout=1000) def testGetFlowStats() {
        log.info("testGetFlowStatas")
        try {
            val ofmatch = new OFMatch()
            val ofActionEnqueue = new OFActionEnqueue
            val portNum = ovsdb.getPortNumsByPortName(
              portName).filter(_ > 0).head
            ofActionEnqueue.setPort(portNum)
            ofActionEnqueue.setQueueId(ovsdb.getQueueNumByQueueUUID(
              qosUUID, queueUUID))
            val ofActions = List(ofActionEnqueue)
            // Add flow such as...
            // controller.addFlow(ofmatch, 0,
            //     1000, 1000, 0,
            //     0, true, true, false, ofActions)
            val reply = controller.getFlowStats(
                ofmatch, 0xff.toByte, OFPort.OFPP_NONE.getValue)
            log.info("Controller got the response: {}", reply)
            for (response: OFFlowStatisticsReply <- reply)
                 assertNotSame(0, response.getDurationNanoseconds)
        } catch {
            case e: OpenFlowError => throw e
        } finally {
            log.info("testGetFlowStats exiting")
        }
    }

    @Test(timeout=1000) def testGetAggregateStats() {
        log.info("testAggregateStats")
        try {
            val ofmatch = new OFMatch()
            val response = controller.getAggregateStats(
                ofmatch, 0xff.toByte,
                ovsdb.getPortNumByUUID(ovsdb.getPortUUID(portName)))
            log.info("Controller got the response: {}", response)
            for (reply: OFAggregateStatisticsReply <- response)
                 assertEquals(0, reply.getFlowCount)
        } catch {
            case e: OpenFlowError => throw e
        } finally {
            log.info("testGetAggregateStats exiting")
        }
    }

    @Ignore @Test(timeout=1000) def testTableStats() {
        log.info("testTableStats")
        try {
            val response = controller.getTableStats
            log.info("Controlelr got the response {}", response)
            for (reply: OFTableStatistics <- response) {
                assertEquals(reply.getTableId, 1)
                assertNotSame(reply.getActiveCount, 0)
            }
        } catch {
            case e: OpenFlowError => throw e
        } finally {
            log.info("testTableStats exiting")
        }
    }

    @Test(timeout=1000) def testGetPortStats() {
       log.info("testGetPortStats")
       try {
           val portNum = ovsdb.getPortNumsByPortName(
               portName).filter(_ > 0).head
           val reply = controller.getPortStats(portNum)
           for (response: OFPortStatisticsReply <- reply) {
               log.info("Controller got the response: {}",
                        response.getPortNumber)
               assertEquals(response.getPortNumber, portNum)
           }
       } catch {
           case e: OpenFlowError => throw e
       } finally {
           log.info("testGetPortStats exiting")
       }
    }

    @Test(timeout=1000) def testGetQueueStats() {
        log.info("testGetQueueStats")
        try {
            val portNum = ovsdb.getPortNumsByPortName(
                portName).filter(_ > 0).head
            val reply = controller.getQueueStats(portNum,
                ovsdb.getQueueNumByQueueUUID(qosUUID, queueUUID))
            for (response: OFQueueStatisticsReply <- reply) {
                log.info("Controller got the response: {}",
                    response.getQueueId)
                assertEquals(response.getPortNumber, portNum)
            }
        } catch {
            case e: OpenFlowError => throw e
        } finally {
            log.info("testGetQueueStats exiting")
        }
    }

    @Test def testNewInternalPort() {
        log.info("testNewInternalPort")
        serializeTestsSemaphore.acquire
        log.info("testNewInternalPort has semaphore")
        try {
            val oldNumDownPorts = controller.getDownPorts.size
            val portName = "int" + testportName
            // Clear the list of added ports, and make a new port which should
            // go into the downPorts list.
            controller.addedPorts = List()
            addInternalPort(portName)
            assertTrue(ovsdb.hasPort(portName))
            portModSemaphore.acquire
            assertEquals(oldNumDownPorts+1, controller.getDownPorts.size)
            //assertEquals(portName, controller.addedPorts(0).getName)
            // TODO: Verify this is an internal port.
            // TODO: Try this with a port which is up.
            ovsdb.delPort(portName)
            assertFalse(ovsdb.hasPort(portName))
        } finally {
            serializeTestsSemaphore.release
            log.info("testNewInternalPort exiting")
        }
    }

    @Ignore
    @Test def testNewTapPort() = {
        log.info("testNewTapPort")
        serializeTestsSemaphore.acquire
        log.info("testNewTapPort has semaphore")
        try {
            val portName = "tap" + testportName
            val portId = UUID.randomUUID()
            addTapPort(portName, portId)
            // Clear the list of added ports, and make a new port which should
            // trigger an addPort callback.
            controller.addedPorts = List()
            assertTrue(ovsdb.hasPort(portName))
            portModSemaphore.acquire
            assertEquals(1, controller.addedPorts.size)
            assertEquals(portName, controller.addedPorts(0))
            // TODO: Verify this is a TAP port.
            ovsdb.delPort(portName)
            assertFalse(ovsdb.hasPort(portName))
        } finally {
            serializeTestsSemaphore.release
            log.info("testNewTapPort exiting")
        }
    }
}


// Used for the downPorts set, so that adding a port will release a semaphore,
// whether the port is up (in addPort) or down (here).
private class NotifyingSet(val portSemaphore: Semaphore) 
        extends java.util.HashSet[java.lang.Integer]() {
    override def add(i: java.lang.Integer) = {
        try { super.add(i) }
        finally { portSemaphore.release }
    }
}

private class BridgeControllerTester(datapath: Long, switchID: UUID,
        greKey: Int, portLocMap: PortToIntNwAddrMap, macPortMap: MacPortMap,
        flowExpireMillis: Long, idleFlowExpireMillis: Long,
        publicIP: IntIPv4, macPortTimeoutMillis: Long,
        ovsdb: OpenvSwitchDatabaseConnectionImpl, reactor: SelectLoop,
        externalIDKey: String, val portSemaphore: Semaphore,
        val connectionSemaphore: Semaphore) extends
                BridgeController(datapath, switchID, greKey, portLocMap,
                        macPortMap, flowExpireMillis, idleFlowExpireMillis,
                        publicIP, macPortTimeoutMillis, ovsdb, reactor,
                        externalIDKey) {
    downPorts = new NotifyingSet(portSemaphore)
    var addedPorts = List[UUID]()

    def getDownPorts() = { downPorts }

    override def onConnectionMade() {
        try {
            log.info("BridgeControllerTester: onConnectionMade")
            super.onConnectionMade
        } finally {
            connectionSemaphore.release
        }
    }

    override def addVirtualPort(portNum: Int, name: String, addr: MAC,
            vId: UUID) {
        try {
            log.info("BridgeControllerTester: addPort")
            super.addVirtualPort(portNum, name, addr, vId)
            addedPorts ::= vId
        } finally {
           portSemaphore.release
        }
    }
}
