/*******************************************************************************
 * Copyright (c) 2015-2016 Apcera Inc. All rights reserved. This program and the accompanying
 * materials are made available under the terms of the MIT License (MIT) which accompanies this
 * distribution, and is available at http://opensource.org/licenses/MIT
 *******************************************************************************/

package io.nats.client;

import static io.nats.client.ConnectionImpl.DEFAULT_BUF_SIZE;
import static io.nats.client.Constants.ERR_BAD_SUBJECT;
import static io.nats.client.Constants.ERR_BAD_TIMEOUT;
import static io.nats.client.Constants.ERR_CONNECTION_CLOSED;
import static io.nats.client.Constants.ERR_CONNECTION_READ;
import static io.nats.client.Constants.ERR_MAX_PAYLOAD;
import static io.nats.client.Constants.ERR_NO_INFO_RECEIVED;
import static io.nats.client.Constants.ERR_NO_SERVERS;
import static io.nats.client.Constants.ERR_PROTOCOL;
import static io.nats.client.Constants.ERR_SECURE_CONN_REQUIRED;
import static io.nats.client.Constants.ERR_SECURE_CONN_WANTED;
import static io.nats.client.Constants.ERR_STALE_CONNECTION;
import static io.nats.client.Constants.ERR_TIMEOUT;
import static io.nats.client.UnitTestUtilities.await;
import static io.nats.client.UnitTestUtilities.newMockedConnection;
import static io.nats.client.UnitTestUtilities.newNewMockedConnection;
import static io.nats.client.UnitTestUtilities.setLogLevel;
import static io.nats.client.UnitTestUtilities.sleep;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.nats.client.ConnectionImpl.Control;
import io.nats.client.Constants.ConnState;

import ch.qos.logback.classic.Level;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.rules.ExpectedException;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketException;
import java.net.URI;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;

import javax.net.ssl.SSLContext;

@Category(UnitTest.class)
public class ConnectionImplTest {
    static final Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    static final Logger logger = LoggerFactory.getLogger(ConnectionImplTest.class);

    static final LogVerifier verifier = new LogVerifier();

    @Rule
    public ExpectedException thrown = ExpectedException.none();

    @Rule
    public TestCasePrinterRule pr = new TestCasePrinterRule(System.out);

    @Mock
    private BlockingQueue<Boolean> fchMock;

    @Mock
    private BlockingQueue<Message> mchMock;

    @Mock
    private MessageHandler mcbMock;

    @Mock
    private Message msgMock;

    @Mock
    private BlockingQueue<Boolean> pongsMock;

    @Mock
    private BufferedOutputStream bwMock;

    @Mock
    private InputStream brMock;

    @Mock
    private ByteArrayOutputStream pendingMock;

    @Mock
    private ByteBuffer pubProtoBufMock;

    @Mock
    private ExecutorService cbExecMock;

    @Mock
    private Map<Long, SubscriptionImpl> subsMock;

    @Mock
    private SyncSubscriptionImpl syncSubMock;

    /**
     * @throws java.lang.Exception if a problem occurs
     */
    @BeforeClass
    public static void setUpBeforeClass() throws Exception {}

    /**
     * @throws java.lang.Exception if a problem occurs
     */
    @AfterClass
    public static void tearDownAfterClass() throws Exception {}

    /**
     * @throws java.lang.Exception if a problem occurs
     */
    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        verifier.setup();
    }

    /**
     * @throws java.lang.Exception if a problem occurs
     */
    @After
    public void tearDown() throws Exception {
        verifier.teardown();
        setLogLevel(Level.WARN);
    }

    @SuppressWarnings("resource")
    @Test
    public void testConnectionImpl() {
        new ConnectionImpl();
    }

    @Test
    public void testConnectProto() throws IOException, TimeoutException {
        Options opts = new Options();
        opts.setUrl("nats://derek:foo@localhost:4222");
        try (ConnectionImpl conn = (ConnectionImpl) new ConnectionImpl(opts)) {
            assertNotNull(conn.getUrl().getUserInfo());
            conn.setOutputStream(bwMock);
            String proto = conn.connectProto();
            ClientConnectInfo connectInfo = ClientConnectInfo.createFromWire(proto);
            assertEquals("derek", connectInfo.getUser());
            assertEquals("foo", connectInfo.getPass());
        }

        opts.setUrl("nats://thisismytoken@localhost:4222");
        try (ConnectionImpl conn = (ConnectionImpl) new ConnectionImpl(opts)) {
            assertNotNull(conn.getUrl().getUserInfo());
            conn.setOutputStream(bwMock);
            String proto = conn.connectProto();
            ClientConnectInfo connectInfo = ClientConnectInfo.createFromWire(proto);
            assertEquals("thisismytoken", connectInfo.getToken());
        }

    }

    @Test
    public void testGetPropertiesInputStreamFailures() throws IOException {
        ConnectionImpl conn = new ConnectionImpl(new Options());
        conn.setOutputStream(bwMock);
        InputStream is = mock(InputStream.class);
        doThrow(new IOException("fake I/O exception")).when(is).read(any(byte[].class));

        conn.getProperties(is);
        verifier.verifyLogMsgEquals(Level.WARN, "nats: error loading properties from InputStream");

        Properties props = conn.getProperties((InputStream) null);
        assertNull(props);
        conn.close();
    }

    @Test
    public void testIsReconnecting() throws IOException, TimeoutException {
        Options opts = new ConnectionFactory().options();
        try (ConnectionImpl conn = (ConnectionImpl) new ConnectionImpl(opts)) {
            assertFalse(conn.isReconnecting());
            conn.status = ConnState.RECONNECTING;
            assertTrue(conn.isReconnecting());
            conn.status = ConnState.CONNECTED;
        }
    }

    @Test
    public void testSelectNextServerNoServers() throws Exception {
        thrown.expect(IOException.class);
        thrown.expectMessage(ERR_NO_SERVERS);

        try (ConnectionImpl conn = (ConnectionImpl) newMockedConnection()) {
            List<ConnectionImpl.Srv> pool = conn.getServerPool();
            pool.clear();
            conn.selectNextServer();
        }
    }

    @Test
    public void testSelectNextServer() throws Exception {
        Options opts = new Options();
        opts.setMaxReconnect(-1);
        opts.setNoRandomize(true);
        opts.setServers(new String[] { "nats://localhost:5222", "nats://localhost:6222" });
        ConnectionImpl conn = new ConnectionImpl(opts);
        final List<ConnectionImpl.Srv> pool = conn.getServerPool();
        assertEquals("nats://localhost:5222", conn.currentServer().url.toString());

        ConnectionImpl.Srv srv = conn.selectNextServer();
        assertEquals("nats://localhost:6222", srv.url.toString());
        conn.setUrl(srv.url);
        assertEquals(2, pool.size());
        assertEquals("nats://localhost:6222", pool.get(0).url.toString());
        assertEquals("nats://localhost:5222", pool.get(1).url.toString());

        srv = conn.selectNextServer();
        assertEquals("nats://localhost:5222", srv.url.toString());
        conn.setUrl(srv.url);
        assertEquals(2, pool.size());
        assertEquals("nats://localhost:5222", pool.get(0).url.toString());
        assertEquals("nats://localhost:6222", pool.get(1).url.toString());

        conn.getOptions().setMaxReconnect(1);
        srv.reconnects = 1;
        srv = conn.selectNextServer();
        assertEquals("nats://localhost:6222", srv.url.toString());
        conn.setUrl(srv.url);
        assertEquals(1, pool.size());
        assertEquals("nats://localhost:6222", pool.get(0).url.toString());

        conn.setOutputStream(mock(OutputStream.class));
        conn.close();
    }

    @Test
    public void testFlushReconnectPendingItems() throws IOException, TimeoutException {
        try (ConnectionImpl c = (ConnectionImpl) newMockedConnection()) {
            ByteArrayOutputStream baos = mock(ByteArrayOutputStream.class);
            when(baos.size()).thenReturn(c.pingProtoBytesLen);
            when(baos.toByteArray()).thenReturn(c.pingProtoBytes);

            assertNull(c.getPending());
            assertEquals(0, c.getPendingByteCount());

            // Test successful flush
            c.setPending(baos);
            c.setOutputStream(bwMock);
            c.flushReconnectPendingItems();
            verify(baos, times(1)).toByteArray();
            verify(bwMock, times(1)).write(eq(c.pingProtoBytes), eq(0), eq(c.pingProtoBytesLen));

            // Test with PING pending
            doThrow(new IOException("IOException from testFlushReconnectPendingItems")).when(bwMock)
                    .flush();

            c.setPending(baos);
            assertEquals(c.pingProtoBytesLen, c.getPendingByteCount());
            c.setOutputStream(bwMock);
            c.flushReconnectPendingItems();
            verifier.verifyLogMsgEquals(Level.ERROR, "Error flushing pending items");
            verify(baos, times(2)).toByteArray();
            verify(bwMock, times(2)).write(eq(c.pingProtoBytes), eq(0), eq(c.pingProtoBytesLen));
        }
    }

    @Test
    public void testFlushReconnectPendingNull() throws IOException, TimeoutException {
        try (ConnectionImpl c = (ConnectionImpl) newMockedConnection()) {
            c.setPending(null);
            c.setOutputStream(bwMock);
            assertNull(c.getPending());

            // Test pending flush with null pending returns without exception
            c.flushReconnectPendingItems();
        }
    }

    @Test
    public void testReadLoopExitsIfConnClosed() throws IOException, TimeoutException {
        Parser parserMock = mock(Parser.class);
        try (ConnectionImpl c =
                (ConnectionImpl) spy(new ConnectionImpl(new ConnectionFactory().options()))) {
            c.parser = parserMock;
            c.setInputStream(brMock);
            TcpConnection connMock = mock(TcpConnection.class);
            c.setTcpConnection(connMock);
            when(c._isClosed()).thenReturn(true);
            c.readLoop();
            assertNull(c.ps);
            verify(brMock, times(0)).read(any(byte[].class));
        }
    }

    @Test
    public void testReadLoopExitsIfConnNull() throws IOException, TimeoutException {
        Parser parserMock = mock(Parser.class);
        try (ConnectionImpl c =
                (ConnectionImpl) spy(new ConnectionImpl(new ConnectionFactory().options()))) {
            c.parser = parserMock;
            c.setInputStream(brMock);
            // TcpConnection connMock = mock(TcpConnection.class);
            c.setTcpConnection(null);
            c.readLoop();
            assertNull(c.ps);
            verify(brMock, times(0)).read(any(byte[].class));
        }
    }

    @Test
    public void testReadLoopExitsIfReconnecting() throws IOException, TimeoutException {
        Parser parserMock = mock(Parser.class);
        try (ConnectionImpl c =
                (ConnectionImpl) spy(new ConnectionImpl(new ConnectionFactory().options()))) {
            c.parser = parserMock;
            c.setInputStream(brMock);
            c.setOutputStream(bwMock);
            TcpConnection connMock = mock(TcpConnection.class);
            c.setTcpConnection(connMock);
            when(c._isReconnecting()).thenReturn(true);
            c.readLoop();
            assertNull(c.ps);
            verify(brMock, times(0)).read(any(byte[].class));
        }
    }


    @Test
    public void testReadOp() throws IOException, TimeoutException {
        try (ConnectionImpl c = (ConnectionImpl) Mockito.spy(newMockedConnection())) {
            TcpConnection connMock = mock(TcpConnection.class);
            c.setTcpConnection(connMock);
            BufferedReader brMock = mock(BufferedReader.class);
            when(connMock.getBufferedReader()).thenReturn(brMock);
            when(brMock.readLine()).thenReturn("PING\r\n");
            Control pingLine = c.readOp();
            assertEquals(pingLine.op, "PING");
        }
    }

    @Test
    public void testReadOpNull() {
        TcpConnectionFactoryMock mcf = new TcpConnectionFactoryMock();
        mcf.setCloseStream(true);
        try (ConnectionImpl c = new ConnectionFactory().createConnection(mcf)) {
            fail("Shouldn't have connected.");
        } catch (IOException | TimeoutException e) {
            assertTrue(e instanceof IOException);
            assertEquals(ERR_CONNECTION_READ, e.getMessage());
        }
    }

    @Test
    public void testRemoveFlushEntry() {
        try (ConnectionImpl c = (ConnectionImpl) newMockedConnection()) {
            ArrayList<BlockingQueue<Boolean>> pongs = new ArrayList<BlockingQueue<Boolean>>();
            c.setPongs(pongs);
            assertEquals(pongs, c.getPongs());
            // Basic case
            BlockingQueue<Boolean> testChan = new LinkedBlockingQueue<Boolean>();
            testChan.add(true);
            pongs.add(testChan);
            assertTrue("Failed to find chan", c.removeFlushEntry(testChan));

            BlockingQueue<Boolean> testChan2 = new LinkedBlockingQueue<Boolean>();
            testChan2.add(false);
            pongs.add(testChan);
            assertFalse("Should not have found chan", c.removeFlushEntry(testChan2));

            pongs.clear();
            assertFalse("Should have returned false", c.removeFlushEntry(testChan));

        } catch (IOException | TimeoutException e) {
            fail("Unexpected exception: " + e.getMessage());
        }
    }

    @Test
    public void testDoReconnectSuccess()
            throws IOException, TimeoutException, InterruptedException {
        try (ConnectionImpl c = (ConnectionImpl) spy(newMockedConnection())) {
            final CountDownLatch dcbLatch = new CountDownLatch(1);
            final CountDownLatch rcbLatch = new CountDownLatch(1);
            final AtomicInteger dcbCount = new AtomicInteger(0);
            final AtomicInteger rcbCount = new AtomicInteger(0);
            c.setDisconnectedCallback(new DisconnectedCallback() {
                public void onDisconnect(ConnectionEvent event) {
                    dcbCount.incrementAndGet();
                    dcbLatch.countDown();
                }
            });
            c.setReconnectedCallback(new ReconnectedCallback() {
                public void onReconnect(ConnectionEvent event) {
                    rcbCount.incrementAndGet();
                    rcbLatch.countDown();
                }
            });

            c.opts.setReconnectAllowed(true);
            c.opts.setReconnectWait(500);
            // c.doReconnect();
            TcpConnectionMock mockServer = (TcpConnectionMock) c.getTcpConnection();
            mockServer.bounce();

            assertTrue("DisconnectedCallback not triggered", dcbLatch.await(2, TimeUnit.SECONDS));
            assertEquals(1, dcbCount.get());
            assertTrue("ReconnectedCallback not triggered", rcbLatch.await(2, TimeUnit.SECONDS));
            assertEquals(1, rcbCount.get());
            verify(c, times(0)).close();

            // Now test the path where there are no callbacks, for coverage
            // c.opts.setDisconnectedCallback(null);
            // c.opts.setReconnectedCallback(null);
            // c.doReconnect();
            // assertEquals(1, dcbCount.get());
            // assertEquals(1, rcbCount.get());
        }
    }

    // @Test
    // public void testDoReconnectFlushFails()
    // // TODO fix this once threading is fixed
    // throws IOException, TimeoutException, InterruptedException {
    // final ByteArrayOutputStream mockOut =
    // mock(ByteArrayOutputStream.class, withSettings().verboseLogging());
    // try (ConnectionImpl c = (ConnectionImpl) spy(newMockedConnection())) {
    // final CountDownLatch rcbLatch = new CountDownLatch(1);
    // final AtomicInteger rcbCount = new AtomicInteger(0);
    // c.setReconnectedCallback(new ReconnectedCallback() {
    // public void onReconnect(ConnectionEvent event) {
    // rcbCount.incrementAndGet();
    // rcbLatch.countDown();
    // }
    // });
    //
    // c.opts.setReconnectAllowed(true);
    // // c.opts.setMaxReconnect(1);
    // c.opts.setReconnectWait(1000);
    //
    // doAnswer(new Answer<Void>() {
    // @Override
    // public Void answer(InvocationOnMock invocation) throws Throwable {
    // logger.error("HELLO in doAnswer");
    // c.setOutputStream(mockOut);
    // return null;
    // }
    // }).when(c).flushReconnectPendingItems();
    //
    // doThrow(new IOException("flush error")).when(mockOut).flush();
    // // when(c.getOutputStream()).thenReturn(mockOut);
    // setLogLevel(Level.TRACE);
    // TcpConnectionMock mockServer = (TcpConnectionMock) c.getTcpConnection();
    // c.setPending(mockOut);
    // mockServer.bounce();
    // sleep(100);
    // // verify(c, times(1)).getOutputStream();
    // verify(c, times(1)).resendSubscriptions();
    //
    // // c.doReconnect();
    // // verifier.verifyLogMsgEquals(Level.DEBUG, "Error flushing output stream");
    //
    // assertTrue("DisconnectedCallback not triggered", rcbLatch.await(5, TimeUnit.SECONDS));
    // assertEquals(1, rcbCount.get());
    // verify(mockOut, times(1)).flush();
    // setLogLevel(Level.INFO);
    // }
    // }

    @Test
    public void testDoReconnectNoServers()
            throws IOException, TimeoutException, InterruptedException {
        try (ConnectionImpl c = (ConnectionImpl) Mockito
                .spy(new ConnectionImpl(new ConnectionFactory().options()))) {
            c.opts.setReconnectAllowed(true);
            c.opts.setReconnectWait(1);
            c.setOutputStream(bwMock);
            c.setPending(pendingMock);
            doThrow(new IOException(ERR_NO_SERVERS)).when(c).selectNextServer();
            c.doReconnect();
            assertTrue(c.getLastException() instanceof IOException);
            assertEquals(ERR_NO_SERVERS, c.getLastException().getMessage());
        }
    }

    @Test
    public void testDoReconnectCreateConnFailed()
            throws IOException, TimeoutException, InterruptedException {
        try (ConnectionImpl c = (ConnectionImpl) Mockito
                .spy(new ConnectionImpl(new ConnectionFactory().options()))) {
            c.opts.setReconnectAllowed(true);
            c.opts.setReconnectWait(1);
            c.opts.setMaxReconnect(1);
            c.setOutputStream(bwMock);
            c.setPending(pendingMock);
            doThrow(new IOException(ERR_NO_SERVERS)).when(c).createConn();
            c.doReconnect();
            assertTrue(c.getLastException() instanceof IOException);
            assertEquals(ERR_NO_SERVERS, c.getLastException().getMessage());
        }
    }

    @Test
    public void testDoReconnectProcessConnInitFailed()
            throws IOException, TimeoutException, InterruptedException {
        try (ConnectionImpl c = (ConnectionImpl) Mockito
                .spy(new ConnectionImpl(new ConnectionFactory().options()))) {
            c.setTcpConnectionFactory(new TcpConnectionFactoryMock());
            c.opts.setReconnectAllowed(true);
            c.opts.setReconnectWait(1);
            c.opts.setMaxReconnect(1);
            c.setOutputStream(bwMock);
            c.setPending(pendingMock);

            doThrow(new IOException(ERR_PROTOCOL + ", INFO not received")).when(c)
                    .processExpectedInfo();
            c.doReconnect();
            verify(c, times(1)).processConnectInit();
            verifier.verifyLogMsgMatches(Level.WARN, "doReconnect: processConnectInit FAILED.+$");
        }
    }

    @Test
    public void testDoReconnectConnClosed()
            throws IOException, TimeoutException, InterruptedException {
        try (ConnectionImpl c = (ConnectionImpl) Mockito
                .spy(new ConnectionImpl(new ConnectionFactory().options()))) {
            c.opts.setReconnectAllowed(true);
            c.opts.setReconnectWait(1);
            c.opts.setMaxReconnect(1);
            c.setOutputStream(bwMock);
            c.setPending(pendingMock);
            when(c.isClosed()).thenReturn(true);
            c.doReconnect();
            assertEquals(ERR_NO_SERVERS, c.getLastException().getMessage());
        }
    }

    @Test
    public void testDoReconnectCallsReconnectedCb()
            throws IOException, TimeoutException, InterruptedException {
        try (ConnectionImpl c = (ConnectionImpl) Mockito.spy(newMockedConnection())) {
            final AtomicInteger rcbCount = new AtomicInteger(0);
            final CountDownLatch rcbLatch = new CountDownLatch(1);

            c.opts.setReconnectAllowed(true);
            c.opts.setReconnectWait(1);
            c.opts.setMaxReconnect(1);
            c.setReconnectedCallback(new ReconnectedCallback() {
                public void onReconnect(ConnectionEvent event) {
                    rcbCount.incrementAndGet();
                    rcbLatch.countDown();
                }
            });
            TcpConnectionMock connMock = (TcpConnectionMock) c.getTcpConnection();
            connMock.bounce();
            // c.doReconnect();
            assertTrue(rcbLatch.await(5, TimeUnit.SECONDS));
            assertEquals(1, rcbCount.get());
        }
    }

    @Test
    public void testProcessAsyncInfo() throws IOException, TimeoutException {
        ServerInfo info = ServerInfo.createFromWire(UnitTestUtilities.defaultAsyncInfo);
        try (ConnectionImpl c = (ConnectionImpl) newMockedConnection()) {
            c.processAsyncInfo(UnitTestUtilities.defaultAsyncInfo.trim());
            assertEquals(info, c.getConnectedServerInfo());
        }
    }

    @Test
    public void testProcessAsyncInfoFinallyBlock() throws IOException, TimeoutException {
        thrown.expect(IllegalStateException.class);
        thrown.expectMessage("test message");
        try (ConnectionImpl c = (ConnectionImpl) spy(newMockedConnection())) {
            doThrow(new IllegalStateException("test message")).when(c)
                    .processInfo(eq(UnitTestUtilities.defaultAsyncInfo.trim()));
            c.processAsyncInfo(UnitTestUtilities.defaultAsyncInfo.trim());
        }
    }

    @Test
    public void testProcessInfo() throws IOException, TimeoutException {
        try (ConnectionImpl c = (ConnectionImpl) newNewMockedConnection()) {
            ServerInfo info = ServerInfo.createFromWire(UnitTestUtilities.defaultInfo);
            c.processInfo(UnitTestUtilities.defaultInfo.trim());
            assertEquals(info, c.getConnectedServerInfo());
        }
    }

    @Test
    public void testProcessInfoWithConnectUrls() throws IOException, TimeoutException {
        ServerInfo info = ServerInfo.createFromWire(UnitTestUtilities.defaultAsyncInfo);
        try (ConnectionImpl c = (ConnectionImpl) newMockedConnection()) {
            c.processInfo(UnitTestUtilities.defaultAsyncInfo.trim());
            assertEquals(info, c.getConnectedServerInfo());
        }
    }

    @Test
    public void testProcessInfoNullOrEmptyReturnsEarly() throws IOException, TimeoutException {
        try (ConnectionImpl nc = (ConnectionImpl) Mockito
                .spy(new ConnectionImpl(new ConnectionFactory().options()))) {
            nc.setOutputStream(bwMock);
            nc.processInfo(null);
            verify(nc, times(0)).addUrlToPool(any(String.class));
            nc.processInfo("");
            verify(nc, times(0)).addUrlToPool(any(String.class));
        }
    }

    @Test
    public void testProcessOpErrConditionsNotMet() throws IOException, TimeoutException {
        try (ConnectionImpl c = (ConnectionImpl) Mockito.spy(newMockedConnection())) {
            c.opts = spy(new ConnectionFactory().options());
            when(c.isConnecting()).thenReturn(true);
            assertTrue(c.isConnecting());

            c.processOpError(new Exception("foo"));
            verify(c.opts, times(0)).isReconnectAllowed();

            when(c.isConnecting()).thenReturn(false);
            when(c._isClosed()).thenReturn(true);
            c.processOpError(new Exception("foo"));
            verify(c.opts, times(0)).isReconnectAllowed();


            when(c.isConnecting()).thenReturn(false);
            when(c._isClosed()).thenReturn(false);
            when(c._isReconnecting()).thenReturn(true);
            c.processOpError(new Exception("foo"));
            verify(c.opts, times(0)).isReconnectAllowed();
        }
    }

    @Test
    public void testProcessOpErrFlushError() throws IOException, TimeoutException {
        try (ConnectionImpl c = (ConnectionImpl) Mockito.spy(newMockedConnection())) {
            c.setOutputStream(bwMock);
            doThrow(new IOException("testProcessOpErrFlushErrort()")).when(bwMock).flush();
            c.processOpError(new Exception("foo"));
            verifier.verifyLogMsgEquals(Level.ERROR, "I/O error during flush");
        }
    }

    @Test
    public void testProcessPingError() throws IOException, TimeoutException {
        try (ConnectionImpl c = (ConnectionImpl) spy(newMockedConnection())) {
            Exception ex = new IOException("testProcessPingError()");
            doThrow(ex).when(c).sendProto(any(byte[].class), anyInt());
            c.processPing();
            verify(c, times(1)).setLastError(eq(ex));
        }
    }

    @Test
    public void testPublishIoError() throws IOException, TimeoutException {
        try (ConnectionImpl c = (ConnectionImpl) newMockedConnection()) {
            Message msg = new Message();
            msg.setSubject("foo");
            msg.setData(null);
            BufferedOutputStream bw = mock(BufferedOutputStream.class);
            doThrow(new IOException("Mock OutputStream write exception")).when(bw)
                    .write(any(byte[].class), any(int.class), any(int.class));
            c.setOutputStream(bw);
            c.publish(msg);
            assertTrue(c.getLastException() instanceof IOException);
            assertEquals("Mock OutputStream write exception", c.getLastException().getMessage());
        }
    }


    @Test
    public void testPublishBadSubject() throws IOException, TimeoutException {
        try (Connection c = newMockedConnection()) {
            boolean exThrown = false;
            try {
                c.publish("", (byte[]) null);
            } catch (IllegalArgumentException e) {
                assertEquals(ERR_BAD_SUBJECT, e.getMessage());
                exThrown = true;
            } finally {
                assertTrue(exThrown);
            }
            exThrown = false;
            try {
                c.publish(null, "Hello".getBytes());
            } catch (NullPointerException e) {
                assertEquals(ERR_BAD_SUBJECT, e.getMessage());
                exThrown = true;
            } finally {
                assertTrue(exThrown);
            }
        }
    }

    @Test
    public void testNewMockConn() throws IOException, TimeoutException {
        Options opts = new ConnectionFactory().options();
        try (Connection nc = UnitTestUtilities.newNewMockedConnection(opts)) {
            assertFalse(nc.isClosed());
        }
    }

    @Test
    public void testPublishWithReply() throws IOException, TimeoutException {
        try (ConnectionImpl c = (ConnectionImpl) Mockito.spy(newMockedConnection())) {
            c.publish("foo", "bar", null);
            verify(c, times(1)).publish(eq("foo".getBytes()), eq("bar".getBytes()),
                    eq((byte[]) null), any(boolean.class));
        }
    }

    @Test
    public void testResendSubscriptions() throws IOException, TimeoutException {
        try (ConnectionImpl c = (ConnectionImpl) newNewMockedConnection()) {
            AsyncSubscriptionImpl sub =
                    (AsyncSubscriptionImpl) c.subscribe("foo", new MessageHandler() {
                        public void onMessage(Message msg) {
                            /* NOOP */
                        }
                    });
            sub.setMax(122);
            assertEquals(122, sub.max);
            sub.delivered = 122;
            assertEquals(122, sub.getDelivered());
            setLogLevel(Level.TRACE);
            c.resendSubscriptions();
            c.getOutputStream().flush();
            sleep(100);
            String str = String.format("UNSUB %d\r\n", sub.getSid());
            // TcpConnectionMock mock = (TcpConnectionMock) c.getTcpConnection();
            // assertEquals(str, mock.getBuffer());
            OutputStream bw = c.getOutputStream();
            verify(c, times(1)).writeUnsubProto(eq(sub), eq(0));
            verify(bw, times(1)).write(str.getBytes(), 0, str.length());

            SyncSubscriptionImpl syncSub = (SyncSubscriptionImpl) c.subscribeSync("foo");
            syncSub.setMax(10);
            syncSub.delivered = 8;
            long adjustedMax = (syncSub.getMax() - syncSub.getDelivered());
            assertEquals(2, adjustedMax);
            c.resendSubscriptions();
            c.getOutputStream().flush();
            sleep(100);
            str = String.format("UNSUB %d %d\r\n", syncSub.getSid(), adjustedMax);
            verify(bw, times(1)).write(str.getBytes(), 0, str.length());
            // assertEquals(str, mock.getBuffer());
        }
    }


    @Test
    public void testErrOnMaxPayloadLimit() throws IOException, TimeoutException {
        long expectedMaxPayload = 10;
        String serverInfo =
                "INFO {\"server_id\":\"foobar\",\"version\":\"0.6.6\",\"go\":\"go1.5.1\","
                        + "\"host\":\"%s\",\"port\":%d,\"auth_required\":false,"
                        + "\"ssl_required\":false,\"max_payload\":%d}\r\n";

        TcpConnectionFactoryMock mcf = new TcpConnectionFactoryMock();
        String infoString = (String.format(serverInfo, "mockserver", 2222, expectedMaxPayload));
        // System.err.println(infoString);
        mcf.setServerInfoString(infoString);
        ConnectionFactory cf = new ConnectionFactory();
        try (Connection c = cf.createConnection(mcf)) {
            // Make sure we parsed max payload correctly
            assertEquals(c.getMaxPayload(), expectedMaxPayload);

            // Check for correct exception
            boolean exThrown = false;
            try {
                c.publish("hello", "hello world".getBytes());
            } catch (IllegalArgumentException e) {
                assertEquals(ERR_MAX_PAYLOAD, e.getMessage());
                exThrown = true;
            } finally {
                assertTrue("Should have generated a IllegalArgumentException.", exThrown);
            }

            // Check for success on less than maxPayload

        }
    }

    @Test
    public void testGetConnectedServerId() {
        final String expectedId = "a1c9cf0c66c3ea102c600200d441ad8e";
        try (Connection c = newMockedConnection()) {
            assertTrue(!c.isClosed());
            assertEquals("Wrong server ID", c.getConnectedServerId(), expectedId);
            c.close();
            assertNull("Should have returned NULL", c.getConnectedServerId());
        } catch (IOException | TimeoutException e) {
            fail(e.getMessage());
        }
    }

    @Test
    public void testGetConnectedServerInfo() throws IOException, TimeoutException {
        try (Connection nc = newMockedConnection()) {
            assertTrue(!nc.isClosed());
            String expected = UnitTestUtilities.defaultInfo;
            ServerInfo actual = nc.getConnectedServerInfo();
            assertEquals("Wrong server INFO.", expected, actual.toString());
            assertTrue(actual.equals(ServerInfo.createFromWire(expected)));
        }
    }

    @Test
    public void testPublishClosedConnection() throws IOException, TimeoutException {
        thrown.expect(IllegalStateException.class);
        thrown.expectMessage(Constants.ERR_CONNECTION_CLOSED);
        try (ConnectionImpl c = (ConnectionImpl) Mockito.spy(newMockedConnection())) {
            c.close();
            c.publish("foo", null);
        }
    }

    @Test
    public void testPublishReconnectingFlushError() throws IOException, TimeoutException {
        OutputStream os = mock(OutputStream.class);
        doThrow(new IOException("test")).when(os).flush();
        try (ConnectionImpl c = (ConnectionImpl) Mockito.spy(newMockedConnection())) {
            c.setOutputStream(os);
            c.setPending(mock(ByteArrayOutputStream.class));
            c.status = ConnState.RECONNECTING;
            c.publish("foo", null);
            verifier.verifyLogMsgEquals(Level.ERROR, "I/O exception during flush");
        }
    }

    @Test
    public void testPublishReconnectingPendingBufferTooLarge()
            throws IOException, TimeoutException {
        thrown.expect(IOException.class);
        thrown.expectMessage(Constants.ERR_RECONNECT_BUF_EXCEEDED);

        try (ConnectionImpl c = (ConnectionImpl) Mockito.spy(newMockedConnection())) {
            c.setOutputStream(bwMock);
            c.setPending(pendingMock);
            when(pendingMock.size()).thenReturn(c.opts.getReconnectBufSize() + 10);
            c.status = ConnState.RECONNECTING;
            c.publish("foo", null);
            verifier.verifyLogMsgEquals(Level.ERROR, "I/O exception during flush");
        }
    }

    @Test
    public void testPublishBufferOverflow() throws IOException, TimeoutException {
        try (ConnectionImpl c = (ConnectionImpl) Mockito.spy(newMockedConnection())) {
            // This will cause writePublishProto to throw an exception on first invocation but not
            // on the second invocation
            doThrow(new BufferOverflowException()).doNothing().when(c).writePublishProto(
                    any(java.nio.ByteBuffer.class), eq("foo".getBytes()), eq((byte[]) null), eq(0));
            c.setOutputStream(bwMock);
            c.setPending(pendingMock);
            c.publish("foo", null);
            verifier.verifyLogMsgEquals(Level.WARN,
                    "nats: reallocating publish buffer due to overflow");
        }
    }

    @Test
    public void testGetPropertiesStringFailure() throws IOException, TimeoutException {
        try (ConnectionImpl c = (ConnectionImpl) newMockedConnection()) {
            Properties props = c.getProperties("foobar.properties");
            assertNull(props);
        }
    }

    @Test
    public void testGetPropertiesSuccess() {
        try (ConnectionImpl c = (ConnectionImpl) newMockedConnection()) {
            Properties props = c.getProperties("jnats.properties");
            assertNotNull(props);
            String version = props.getProperty("client.version");
            assertNotNull(version);
        } catch (Exception e) {
            fail(e.getMessage());
        }
    }

    @Test
    public void testConnectThrowsConnectionRefused() throws IOException, TimeoutException {
        thrown.expect(IOException.class);
        thrown.expectMessage(ERR_NO_SERVERS);
        try (ConnectionImpl c =
                (ConnectionImpl) spy(new ConnectionImpl(new ConnectionFactory().options()))) {
            doThrow(new SocketException("Connection refused")).when(c).createConn();
            c.connect();
            // When createConn() throws "connection refused", setup() should not be called
            verify(c, times(0)).setup();
            // When createConn() throws "connection refused", setLastError(null) should happen
            verify(c, times(1)).setLastError(eq((Exception) null));
        }
    }

    @Test
    public void testConnectVerbose() throws IOException, TimeoutException {
        Options opts = new ConnectionFactory().options();
        opts.setVerbose(true);
        try (ConnectionImpl c = (ConnectionImpl) spy(newMockedConnection(opts))) {
            // Should work
        }
    }

    @Test
    public void testCreateConnCurrentSrvNull() throws IOException {
        thrown.expect(IOException.class);
        thrown.expectMessage(ERR_NO_SERVERS);

        try (ConnectionImpl c =
                (ConnectionImpl) spy(new ConnectionImpl(new ConnectionFactory().options()))) {
            when(c.currentServer()).thenReturn(null);
            c.createConn();
        }
    }

    @Test
    public void testCreateConnFlushFailure() throws IOException {
        try (ConnectionImpl c =
                (ConnectionImpl) spy(new ConnectionImpl(new ConnectionFactory().options()))) {
            c.setTcpConnectionFactory(new TcpConnectionFactoryMock());
            c.setOutputStream(bwMock);
            c.setPending(pendingMock);
            doThrow(new IOException("testCreateConnFlushFailure()")).when(bwMock).flush();
            when(c.currentServer()).thenReturn(c.new Srv(URI.create("nats://localhost:4222")));
            c.createConn();
            verify(bwMock, times(1)).flush();
            verifier.verifyLogMsgEquals(Level.WARN, Constants.ERR_TCP_FLUSH_FAILED);
        }
    }

    @Test
    public void testCreateConnFailure() throws IOException, TimeoutException {
        thrown.expect(IOException.class);
        thrown.expectMessage(ERR_NO_SERVERS);

        try (ConnectionImpl nc = (ConnectionImpl) spy(newNewMockedConnection())) {
            TcpConnection tcpConn = nc.getTcpConnection();
            doThrow(new IOException(ERR_NO_SERVERS)).when(tcpConn).open(anyString(), anyInt(),
                    anyInt());
            nc.createConn();
            verifier.verifyLogMsgMatches(Level.DEBUG, "Couldn't establish connection to .+$");
        }
    }

    @Test
    public void testClosedCallback() throws IOException, TimeoutException, InterruptedException {
        final CountDownLatch ccbLatch = new CountDownLatch(1);
        final AtomicInteger ccbCount = new AtomicInteger(0);
        ClosedCallback ccb = new ClosedCallback() {
            public void onClose(ConnectionEvent event) {
                ccbCount.incrementAndGet();
                ccbLatch.countDown();
            }
        };
        try (Connection c = newMockedConnection()) {
            c.setClosedCallback(ccb);
            assertEquals(ccb, c.getClosedCallback());
        }

        assertTrue(ccbLatch.await(2, TimeUnit.SECONDS));
        assertEquals(1, ccbCount.get());
    }

    @Test
    public void testClosedConnections() throws IOException, TimeoutException {
        Connection nc = newMockedConnection();
        SyncSubscription sub = nc.subscribeSync("foo");

        nc.close();
        assertTrue(nc.isClosed());

        // While we can annotate all the exceptions in the test framework,
        // just do it manually.

        boolean exThrown = false;

        try {
            nc.publish("foo", null);
        } catch (Exception e) {
            assertTrue("Expected IllegalStateException, got " + e.getClass().getSimpleName(),
                    e instanceof IllegalStateException);
            assertEquals(ERR_CONNECTION_CLOSED, e.getMessage());
            exThrown = true;
        } finally {
            assertTrue("Didn't throw an exception", exThrown);
        }

        exThrown = false;
        assertTrue(nc.isClosed());
        try {
            nc.publish(new Message("foo", null, null));
        } catch (Exception e) {
            exThrown = true;
            assertTrue("Expected IllegalStateException, got " + e.getClass().getSimpleName(),
                    e instanceof IllegalStateException);
            assertEquals(ERR_CONNECTION_CLOSED, e.getMessage());
        } finally {
            assertTrue("Didn't throw an exception", exThrown);
        }

        exThrown = false;
        try {
            nc.subscribeSync("foo");
        } catch (Exception e) {
            assertTrue("Expected IllegalStateException, got " + e.getClass().getSimpleName(),
                    e instanceof IllegalStateException);
            assertEquals(ERR_CONNECTION_CLOSED, e.getMessage());
            exThrown = true;
        } finally {
            assertTrue("Didn't throw an exception", exThrown);
        }

        exThrown = false;
        try {
            nc.subscribeSync("foo", "bar");
        } catch (Exception e) {
            assertTrue("Expected IllegalStateException, got " + e.getClass().getSimpleName(),
                    e instanceof IllegalStateException);
            assertEquals(ERR_CONNECTION_CLOSED, e.getMessage());
            exThrown = true;
        } finally {
            assertTrue("Didn't throw an exception", exThrown);
        }

        exThrown = false;
        try {
            nc.request("foo", null);
            assertTrue(nc.isClosed());
        } catch (Exception e) {
            assertTrue("Expected IllegalStateException, got " + e.getClass().getSimpleName(),
                    e instanceof IllegalStateException);
            assertEquals(ERR_CONNECTION_CLOSED, e.getMessage());
            exThrown = true;
        } finally {
            assertTrue("Didn't throw an exception", exThrown);
        }

        exThrown = false;
        try {
            sub.nextMessage();
        } catch (Exception e) {
            assertTrue("Expected IllegalStateException, got " + e.getClass().getSimpleName(),
                    e instanceof IllegalStateException);
            assertEquals(ERR_CONNECTION_CLOSED, e.getMessage());
            exThrown = true;
        } finally {
            assertTrue("Didn't throw an exception", exThrown);
        }

        exThrown = false;
        try {
            sub.nextMessage(100);
        } catch (Exception e) {
            assertTrue("Expected IllegalStateException, got " + e.getClass().getSimpleName(),
                    e instanceof IllegalStateException);
            assertEquals(ERR_CONNECTION_CLOSED, e.getMessage());
            exThrown = true;
        } finally {
            assertTrue("Didn't throw an exception", exThrown);
        }

        exThrown = false;
        try {
            sub.unsubscribe();
        } catch (Exception e) {
            assertTrue("Expected IllegalStateException, got " + e.getClass().getSimpleName(),
                    e instanceof IllegalStateException);
            assertEquals(ERR_CONNECTION_CLOSED, e.getMessage());
            exThrown = true;
        } finally {
            assertTrue("Didn't throw an exception", exThrown);
        }

        exThrown = false;
        try {
            sub.autoUnsubscribe(1);
        } catch (Exception e) {
            assertTrue("Expected IllegalStateException, got " + e.getClass().getSimpleName(),
                    e instanceof IllegalStateException);
            assertEquals(ERR_CONNECTION_CLOSED, e.getMessage());
            exThrown = true;
        } finally {
            assertTrue("Didn't throw an exception", exThrown);
        }
    }

    @Test
    public void testCloseNullTcpConn() throws IOException, TimeoutException, InterruptedException {
        // Null TCP connection and null disconnected callback
        try (ConnectionImpl conn = (ConnectionImpl) newMockedConnection()) {
            conn.setTcpConnection(null);
            conn.setOutputStream(bwMock);
            assertNull(conn.getDisconnectedCallback());
            conn.close();
            // If the TCP conn is null, we should not attempt to flush
            verify(bwMock, times(0)).flush();
            // And we should not execute the dcb, or an exception will be thrown during close()
        }

        // Null TCP connection and valid disconnected callback
        try (ConnectionImpl conn = (ConnectionImpl) newMockedConnection()) {
            final AtomicInteger dcbCount = new AtomicInteger(0);
            conn.setDisconnectedCallback(new DisconnectedCallback() {
                public void onDisconnect(ConnectionEvent event) {
                    dcbCount.incrementAndGet();
                }
            });
            conn.setTcpConnection(null);
            conn.setOutputStream(bwMock);
            conn.close();
            // If the TCP conn is null, we should not attempt to flush
            verify(bwMock, times(0)).flush();
            // And we should not call the dcb
            assertTrue(conn.isClosed());
            assertEquals(0, dcbCount.get());
        }

        // Test for valid TCP connection and valid disconnected callback
        try (ConnectionImpl conn = (ConnectionImpl) newMockedConnection()) {
            final CountDownLatch dcbLatch = new CountDownLatch(1);
            conn.setDisconnectedCallback(new DisconnectedCallback() {
                public void onDisconnect(ConnectionEvent event) {
                    dcbLatch.countDown();
                }
            });
            conn.setOutputStream(bwMock);
            conn.close();
            // If the TCP conn is null, we should have flushed
            verify(bwMock, times(1)).flush();
            // And we should have called the dcb
            assertTrue(dcbLatch.await(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void testExhaustedSrvPool() throws IOException, TimeoutException {
        try (ConnectionImpl c = (ConnectionImpl) newMockedConnection()) {
            List<ConnectionImpl.Srv> pool = new ArrayList<ConnectionImpl.Srv>();
            c.setServerPool(pool);
            boolean exThrown = false;
            try {
                assertNull(c.currentServer());
                c.createConn();
            } catch (IOException e) {
                assertEquals(ERR_NO_SERVERS, e.getMessage());
                exThrown = true;
            }
            assertTrue("Should have thrown exception.", exThrown);
        }
    }


    @Test
    public void testFlushBadTimeout() throws Exception {
        thrown.expect(IllegalArgumentException.class);
        thrown.expectMessage(ERR_BAD_TIMEOUT);
        try (ConnectionImpl c = (ConnectionImpl) newMockedConnection()) {
            c.flush(-10);
        }
    }

    @Test
    public void testFlushTimeoutFailure() throws Exception {
        thrown.expect(TimeoutException.class);
        thrown.expectMessage(ERR_TIMEOUT);
        try (ConnectionImpl c = (ConnectionImpl) spy(newNewMockedConnection())) {
            // Default behavior of mock is it doesn't return pongs after the first one
            c.flush(500);
        }
    }

    @Test
    public void testFlushClosedFailure() throws Exception {
        thrown.expect(IllegalStateException.class);
        thrown.expectMessage(ERR_CONNECTION_CLOSED);
        try (ConnectionImpl c = (ConnectionImpl) newMockedConnection()) {
            c.close();
            c.flush(500);
        }
    }

    @Test
    public void testFlushFalsePongFailure() throws Exception {
        thrown.expect(IllegalStateException.class);
        thrown.expectMessage(ERR_CONNECTION_CLOSED);
        @SuppressWarnings("unchecked")
        BlockingQueue<Boolean> ch = (BlockingQueue<Boolean>) mock(BlockingQueue.class);
        try (ConnectionImpl c = (ConnectionImpl) Mockito.spy(newMockedConnection())) {
            when(c.createBooleanChannel(1)).thenReturn(ch);
            when(ch.poll(500, TimeUnit.MILLISECONDS)).thenReturn(false);
            c.flush(500);
        }
    }

    @Test
    public void testFlushPollInterrupted() throws Exception {
        @SuppressWarnings("unchecked")
        BlockingQueue<Boolean> ch = (BlockingQueue<Boolean>) mock(BlockingQueue.class);
        try (ConnectionImpl c = (ConnectionImpl) Mockito.spy(newMockedConnection())) {
            when(c.createBooleanChannel(1)).thenReturn(ch);
            doThrow(new InterruptedException()).when(ch).poll(500, TimeUnit.MILLISECONDS);
            setLogLevel(Level.DEBUG);
            c.flush(500);
            assertTrue(Thread.interrupted());
            verifier.verifyLogMsgEquals(Level.DEBUG,
                    "flush was interrupted while waiting for PONG");
        }
    }


    @Test
    public void testWaitForMsgsSuccess()
            throws IOException, TimeoutException, InterruptedException {
        final String subj = "foo";
        final byte[] payload = "Hello there!".getBytes();
        try (ConnectionImpl nc = (ConnectionImpl) Mockito.spy(newMockedConnection())) {
            final AsyncSubscriptionImpl sub = mock(AsyncSubscriptionImpl.class);
            final Message msg = new Message(payload, payload.length, subj, null, sub);
            when(sub.getSid()).thenReturn(14L);
            @SuppressWarnings("unchecked")
            BlockingQueue<Message> ch = (BlockingQueue<Message>) mock(BlockingQueue.class);
            when(ch.size()).thenReturn(1);
            when(ch.take()).thenReturn(msg).thenReturn(null);
            sub.mch = ch;

            MessageHandler mcbMock = mock(MessageHandler.class);
            sub.msgHandler = mcbMock;

            sub.setChannel(mchMock);
            when(mchMock.size()).thenReturn(1).thenReturn(0);
            when(mchMock.poll()).thenReturn(msg).thenReturn(null);
            when(sub.getChannel()).thenReturn(mchMock);

            Condition pendingCondMock = mock(Condition.class);
            sub.pCond = pendingCondMock;

            sub.max = 1; // To make sure the message is removed after one

            when(sub.isClosed()).thenReturn(false).thenReturn(true);
            nc.waitForMsgs(sub);

            // verify the msg was processed
            verify(mcbMock, times(1)).onMessage(msg);

            // verify the sub was removed
            verify(nc, times(1)).removeSub(eq(sub));
        }
    }

    @Test
    public void testProcessErr() throws IOException, TimeoutException {
        String err = "this is a test";
        // byte[] argBufBase = new byte[DEFAULT_BUF_SIZE];
        ByteBuffer errorStream = ByteBuffer.wrap(err.getBytes());

        try (ConnectionImpl c = (ConnectionImpl) newMockedConnection()) {
            assertTrue(!c.isClosed());
            c.processErr(errorStream);
            assertTrue(c.isClosed());
            NATSException ex = (NATSException) c.getLastException();
            assertNotNull(ex);
            String msg = ex.getMessage();
            assertNotNull(msg);
            assertEquals("nats: " + err, msg);
            Connection exConn = ex.getConnection();
            assertNotNull(exConn);
            assertEquals(c, exConn);
        }
    }

    // @Test
    // public void testServerParseError() {
    // try (ConnectionImpl c = (ConnectionImpl) newMockedConnection()) {
    // assertTrue(!c.isClosed());
    // byte[] data = "Hello\r\n".getBytes();
    // c.sendProto(data, data.length);
    // try {
    // Thread.sleep(100);
    // } catch (InterruptedException e) {
    // /* NOOP */ }
    // assertTrue(c.isClosed());
    // } catch (IOException | TimeoutException e) {
    // fail(e.getMessage());
    // }
    // }
    //


    @Test
    public void testProcessErrStaleConnection() throws IOException, TimeoutException {
        ConnectionFactory cf = new ConnectionFactory();
        final CountDownLatch ccbLatch = new CountDownLatch(1);
        cf.setClosedCallback(new ClosedCallback() {
            public void onClose(ConnectionEvent event) {
                ccbLatch.countDown();
            }
        });
        cf.setReconnectAllowed(false);
        try (ConnectionImpl c = cf.createConnection(new TcpConnectionFactoryMock())) {
            ByteBuffer error = ByteBuffer.allocate(DEFAULT_BUF_SIZE);
            error.put(ConnectionImpl.STALE_CONNECTION.getBytes());
            error.flip();
            c.processErr(error);
            assertTrue(c.isClosed());
            assertTrue("Closed callback should have fired", await(ccbLatch));
        }
    }

    @Test
    public void testProcessErrPermissionsViolation() throws IOException, TimeoutException {
        ConnectionFactory cf = new ConnectionFactory();
        final CountDownLatch ccbLatch = new CountDownLatch(1);
        cf.setClosedCallback(new ClosedCallback() {
            public void onClose(ConnectionEvent event) {
                ccbLatch.countDown();
            }
        });
        cf.setReconnectAllowed(false);
        try (ConnectionImpl c = spy(cf.createConnection(new TcpConnectionFactoryMock()))) {
            ByteBuffer error = ByteBuffer.allocate(DEFAULT_BUF_SIZE);
            String err = Constants.PERMISSIONS_ERR + " foobar";
            error.put(err.getBytes());
            error.flip();
            c.processErr(error);

            // Verify we identified and processed it
            verify(c, times(1)).processPermissionsViolation(err);
        }
    }

    @Test
    public void testProcessPermissionsViolation() throws IOException, TimeoutException {
        final CountDownLatch ehLatch = new CountDownLatch(1);
        final String errorString = "some error";
        try (ConnectionImpl c = (ConnectionImpl) spy(newMockedConnection())) {
            c.setExceptionHandler(new ExceptionHandler() {
                @Override
                public void onException(NATSException e) {
                    assertEquals(c, e.getConnection());
                    Exception lastEx = c.getLastException();
                    assertNotNull(lastEx);
                    assertNotNull(e.getCause());
                    // Our cause should be the same as the last exception registered on the
                    // connection
                    assertEquals(lastEx, e.getCause());

                    assertNotNull(e.getCause().getMessage());
                    assertEquals("nats: " + errorString, e.getCause().getMessage());
                    ehLatch.countDown();
                }
            });

            c.processPermissionsViolation(errorString);

            // Verify we identified and processed it
            verify(c, times(1)).processPermissionsViolation(errorString);
        }
    }

    @Test
    public void testProcessExpectedInfoThrowsErrNoInfoReceived()
            throws IOException, TimeoutException {
        thrown.expect(IOException.class);
        thrown.expectMessage(ERR_NO_INFO_RECEIVED);

        // TODO do this with normal mock
        TcpConnectionFactoryMock mcf = new TcpConnectionFactoryMock();
        mcf.setNoInfo(true);
        try (ConnectionImpl c = new ConnectionFactory().createConnection(mcf)) {
            fail("Shouldn't have connected.");
        }
    }

    @Test
    public void testProcessExpectedInfoReadOpFailure() throws IOException, TimeoutException {
        try (ConnectionImpl conn = (ConnectionImpl) Mockito.spy(newMockedConnection())) {
            TcpConnection connMock = mock(TcpConnection.class);
            BufferedReader brMock = mock(BufferedReader.class);
            when(connMock.getBufferedReader()).thenReturn(brMock);
            conn.setTcpConnection(connMock);
            doThrow(new IOException("flush error")).when(brMock).readLine();
            conn.processExpectedInfo();
            verify(conn, times(1)).processOpError(any(IOException.class));
        }
    }

    @Test
    public void testProcessMsg() throws IOException, TimeoutException {
        final byte[] data = "Hello, World!".getBytes();
        final int offset = 0;
        final int length = data.length;
        try (ConnectionImpl c = (ConnectionImpl) Mockito.spy(newMockedConnection())) {

            SubscriptionImpl sub = (SubscriptionImpl) Mockito.spy(c.subscribe("foo", mcbMock));
            c.ps.ma.sid = 44L;
            when(subsMock.get(any(long.class))).thenReturn(sub);
            when(mchMock.add(any(Message.class))).thenReturn(true);
            c.setSubs(subsMock);
            sub.setChannel(mchMock);
            sub.pCond = mock(Condition.class);

            c.ps.ma.size = length;
            c.processMsg(data, offset, length);

            // InMsgs should be incremented by 1
            assertEquals(1, c.getStats().getInMsgs());
            // InBytes should be incremented by length
            assertEquals(length, c.getStats().getInBytes());
            // sub.addMessage(msg) should have been called exactly once
            verify(mchMock, times(1)).add(any(Message.class));
            // condition should have been signaled
            verify(sub.pCond, times(1)).signal();
            // sub.setSlowConsumer(false) should have been called
            verify(sub, times(1)).setSlowConsumer(eq(false));
            // c.removeSub should NOT have been called
            verify(c, times(0)).removeSub(eq(sub));
        }
    }

    @Test
    public void testProcessMsgMaxReached() throws IOException, TimeoutException {
        final byte[] data = "Hello, World!".getBytes();
        final int offset = 0;
        final int length = data.length;
        final long sid = 4L;
        try (ConnectionImpl c = (ConnectionImpl) Mockito.spy(newMockedConnection())) {
            SubscriptionImpl sub = spy(new AsyncSubscriptionImpl(c, "foo", "bar", mcbMock));
            when(sub.getSid()).thenReturn(sid);
            c.ps.ma.sid = sid;
            c.ps.ma.size = length;
            when(subsMock.get(eq(sid))).thenReturn(sub);
            assertEquals(sub, subsMock.get(sid));
            c.setSubs(subsMock);

            sub.setPendingLimits(1, 1024);
            sub.pMsgs = 1;

            assertEquals(1, sub.getPendingMsgsLimit());
            assertEquals(1, sub.pMsgs);

            c.processMsg(data, offset, length);

            // InMsgs should be incremented by 1, even if the sub stats don't increase
            assertEquals(1, c.getStats().getInMsgs());
            // InBytes should be incremented by length, even if the sub stats don't increase
            assertEquals(length, c.getStats().getInBytes());
            // handleSlowConsumer should have been called once
            verify(c, times(1)).handleSlowConsumer(eq(sub), any(Message.class));
            // sub.addMessage(msg) should not have been called
            verify(mchMock, times(0)).add(any(Message.class));
            // sub.setSlowConsumer(false) should NOT have been called
            verify(sub, times(0)).setSlowConsumer(eq(false));
        }
    }

    @Test
    public void testProcessMsgSubChannelAddFails() throws IOException, TimeoutException {
        final byte[] data = "Hello, World!".getBytes();
        final int offset = 0;
        final int length = data.length;
        final long sid = 4L;
        try (ConnectionImpl c = (ConnectionImpl) Mockito.spy(newMockedConnection())) {
            SubscriptionImpl sub = spy(new AsyncSubscriptionImpl(c, "foo", "bar", mcbMock));
            when(sub.getSid()).thenReturn(sid);
            c.ps.ma.sid = sid;
            c.ps.ma.size = length;
            when(subsMock.get(eq(sid))).thenReturn(sub);
            assertEquals(sub, subsMock.get(sid));
            c.setSubs(subsMock);

            when(mchMock.add(any(Message.class))).thenReturn(false);
            when(sub.getChannel()).thenReturn(mchMock);

            sub.pCond = mock(Condition.class);

            c.processMsg(data, offset, length);

            // InMsgs should be incremented by 1, even if the sub stats don't increase
            assertEquals(1, c.getStats().getInMsgs());
            // InBytes should be incremented by length, even if the sub stats don't increase
            assertEquals(length, c.getStats().getInBytes());
            // handleSlowConsumer should have been called zero times
            verify(c, times(1)).handleSlowConsumer(eq(sub), any(Message.class));
            // sub.addMessage(msg) should have been called
            verify(mchMock, times(1)).add(any(Message.class));
            // the condition should not have been signaled
            verify(sub.pCond, times(0)).signal();
        }

    }

    @Test
    public void testProcessSlowConsumer()
            throws IOException, TimeoutException, InterruptedException {
        try (ConnectionImpl c = (ConnectionImpl) newMockedConnection()) {
            final AtomicInteger ecbCount = new AtomicInteger(0);
            final CountDownLatch ecbLatch = new CountDownLatch(1);
            c.setExceptionHandler(new ExceptionHandler() {
                @Override
                public void onException(NATSException nex) {
                    assertTrue(nex.getCause() instanceof IOException);
                    assertEquals(Constants.ERR_SLOW_CONSUMER, nex.getCause().getMessage());
                    ecbCount.incrementAndGet();
                    ecbLatch.countDown();
                }
            });
            SubscriptionImpl sub = (SubscriptionImpl) c.subscribe("foo", new MessageHandler() {
                public void onMessage(Message msg) { /* NOOP */ }
            });

            assertTrue(sub.mu.tryLock(1, TimeUnit.SECONDS));
            sub.mu.unlock();

            c.processSlowConsumer(sub);
            ecbLatch.await(2, TimeUnit.SECONDS);
            assertEquals(1, ecbCount.get());
            assertTrue(sub.sc);

            // Now go a second time, making sure the handler wasn't invoked again
            c.processSlowConsumer(sub);
            assertEquals(1, ecbCount.get());

            sub.close();
            c.close();
        }
    }

    @Test
    public void testGetConnectedUrl() throws IOException, TimeoutException, NullPointerException {
        thrown.expect(NullPointerException.class);
        try (ConnectionImpl c = (ConnectionImpl) Mockito.spy(newMockedConnection())) {
            assertEquals("nats://localhost:4222", c.getConnectedUrl());
            c.close();
            assertNull(c.getConnectedUrl());
        }

        try (ConnectionImpl c = (ConnectionImpl) Mockito.spy(newMockedConnection())) {
            assertEquals("nats://localhost:4222", c.getConnectedUrl());
            doThrow(new NullPointerException()).when(c).getUrl();
            assertNull(c.getConnectedUrl());
        }
    }

    @Test
    public void testMakeTlsConn() throws IOException, TimeoutException, NoSuchAlgorithmException {
        // TODO put better checks in here. This is sloppy.
        Options opts = new ConnectionFactory().options();
        opts.setSecure(true);
        opts.setSslContext(SSLContext.getDefault());
        opts.setUrl("tls://localhost:4224");
        TcpConnectionFactoryMock mcf = new TcpConnectionFactoryMock();
        ServerInfo info = new ServerInfo("asfasdfs", "127.0.0.1", 4224, "0.9.4", false, true,
                1024 * 1024, null);
        mcf.setServerInfoString(info.toString());
        ConnectionImpl conn = new ConnectionImpl(opts, mcf);
        try {
            conn.connect();
            conn.makeTLSConn();
        } catch (Exception e) {
            System.err.println(conn.getConnectedServerInfo());
            e.printStackTrace();
        } finally {
            conn.close();
        }
    }

    // @Test
    // public void testFlusherPreconditions()
    // throws IOException, TimeoutException, InterruptedException {
    // try (ConnectionImpl c = (ConnectionImpl) Mockito
    // .spy(new ConnectionImpl(new ConnectionFactory().options()))) {
    // c.setFlushChannel(fchMock);
    // c.setOutputStream(bwMock);
    // TcpConnection tconn = mock(TcpConnection.class);
    // when(tconn.isConnected()).thenReturn(true);
    // c.setTcpConnection(tconn);
    // c.status = ConnState.CONNECTED;
    //
    // c.conn = null;
    // c.flusher();
    // verify(fchMock, times(0)).take();
    //
    // c.setTcpConnection(tconn);
    // c.setOutputStream(null);
    // c.flusher();
    // verify(fchMock, times(0)).take();
    // }
    // }
    //
    // @Test
    // public void testFlusherChannelTakeInterrupted()
    // throws IOException, TimeoutException, InterruptedException {
    // try (ConnectionImpl c = (ConnectionImpl) Mockito
    // .spy(new ConnectionImpl(new ConnectionFactory().options()))) {
    // c.setFlushChannel(fchMock);
    // c.setOutputStream(bwMock);
    // TcpConnection tconn = mock(TcpConnection.class);
    // when(tconn.isConnected()).thenReturn(true);
    // c.setTcpConnection(tconn);
    // c.status = ConnState.CONNECTED;
    // doThrow(new InterruptedException("test")).when(fchMock).take();
    // c.flusher();
    // assertTrue(Thread.interrupted());
    // verify(bwMock, times(0)).flush();
    // }
    // }

    // @Test
    // public void testFlusherFlushError() throws IOException, TimeoutException,
    // InterruptedException {
    // try (ConnectionImpl c = (ConnectionImpl) Mockito
    // .spy(new ConnectionImpl(new ConnectionFactory().options()))) {
    // c.setFlushChannel(fchMock);
    // when(fchMock.take()).thenReturn(true).thenReturn(false);
    // c.setOutputStream(bwMock);
    // doThrow(new IOException("flush error")).when(bwMock).flush();
    //
    // TcpConnection tconn = mock(TcpConnection.class);
    // when(tconn.isConnected()).thenReturn(true);
    // c.setTcpConnection(tconn);
    // c.status = ConnState.CONNECTED;
    // c.flusher();
    // verifier.verifyLogMsgEquals(Level.ERROR, "I/O exception encountered during flush");
    //
    // }
    // }

    @Test
    public void testGetServerInfo() throws IOException, TimeoutException {
        try (ConnectionImpl c = (ConnectionImpl) newMockedConnection()) {
            assertTrue(!c.isClosed());
            ServerInfo info = c.getConnectedServerInfo();
            assertEquals("0.0.0.0", info.getHost());
            assertEquals("0.7.2", info.getVersion());
            assertEquals(4222, info.getPort());
            assertFalse(info.isAuthRequired());
            assertFalse(info.isTlsRequired());
            assertEquals(1048576, info.getMaxPayload());
        }
    }

    @Test
    public void testRequest() throws IOException, TimeoutException, InterruptedException {
        final String inbox = "_INBOX.DEADBEEF";
        // SyncSubscriptionImpl mockSub = mock(SyncSubscriptionImpl.class);
        Message replyMsg = new Message();
        replyMsg.setData("answer".getBytes());
        replyMsg.setSubject(inbox);
        when(syncSubMock.nextMessage(any(long.class), any(TimeUnit.class))).thenReturn(replyMsg);
        try (ConnectionImpl c = (ConnectionImpl) newNewMockedConnection()) {
            doReturn(inbox).when(c).newInbox();
            doReturn(mchMock).when(c).createMsgChannel(anyInt());
            doReturn(syncSubMock).when(c).subscribe(inbox, (String) null, (MessageHandler) null,
                    mchMock);
            Message msg = c.request("foo", null);
            verify(syncSubMock, times(1)).nextMessage(-1, TimeUnit.MILLISECONDS);
            assertEquals(replyMsg, msg);

            msg = c.request("foo", null, 1, TimeUnit.SECONDS);
            assertEquals(replyMsg, msg);

            msg = c.request("foo", null, 500);
            assertEquals(replyMsg, msg);
        }
    }

    @Test
    public void testRequestErrors() throws IOException, TimeoutException, InterruptedException {
        final String errMsg = "testRequestErrors()";
        thrown.expect(IOException.class);
        thrown.expectMessage(errMsg);
        try (ConnectionImpl nc = (ConnectionImpl) spy(newMockedConnection())) {
            when(nc.subscribeSync("foo", null)).thenReturn(syncSubMock);
            doThrow(new IOException(errMsg)).when(nc).publish(anyString(), anyString(),
                    any(byte[].class));

            nc.request("foo", "help".getBytes());
            verify(syncSubMock, times(0)).nextMessage(-1, TimeUnit.MILLISECONDS);
        }
    }

    @Test
    public void testSendConnectFailsWhenPongIsNull() {
        // TODO do this with normal mock
        TcpConnectionFactoryMock mcf = new TcpConnectionFactoryMock();
        mcf.setSendNullPong(true);
        try (ConnectionImpl c = new ConnectionFactory().createConnection(mcf)) {
            fail("Shouldn't have connected.");
        } catch (IOException | TimeoutException e) {
            assertTrue(e instanceof IOException);
            assertTrue("Unexpected text: " + e.getMessage(), e.getMessage().startsWith("nats: "));
        }
    }


    @Test
    public void testSendConnectThrowsExceptionWhenPingResponseIsntPong() {
        // TODO do this a different way
        TcpConnectionFactoryMock mcf = new TcpConnectionFactoryMock();
        ConnectionFactory cf = new ConnectionFactory();
        mcf.setBadWriter(true);
        ConnectionImpl nc = null;
        try {
            nc = cf.createConnection(mcf);
            if (nc.getState().equals(ConnState.CONNECTED)) {
                fail("Shouldn't have connected.");
            }
        } catch (IOException | TimeoutException e) {
            assertTrue(e instanceof IOException);
            assertEquals("Mock write I/O error", e.getMessage());
        }
        // TcpConnectionMock mock = (TcpConnectionMock) c.getTcpConnection();
        // mock.bounce();

        mcf.setBadWriter(false);
        mcf.setVerboseNoOK(true);
        cf.setVerbose(true);
        try {
            nc = cf.createConnection(mcf);
            if (nc.getState().equals(ConnState.CONNECTED)) {
                fail("Shouldn't have connected.");
            }
        } catch (IOException | TimeoutException e) {
            assertTrue(e instanceof IOException);
            String expected = String.format("nats: expected '%s', got '%s'", ConnectionImpl._OK_OP_,
                    "+WRONGPROTO");
            assertEquals(expected, e.getMessage());
        }
    }

    @Test
    public void testSendPingError() throws IOException, TimeoutException {
        try (ConnectionImpl c = (ConnectionImpl) newMockedConnection()) {
            doThrow(new IOException("Mock OutputStream write exception")).when(bwMock).flush();
            c.setOutputStream(bwMock);
            c.sendPing(new LinkedBlockingQueue<Boolean>());
            assertTrue(c.getLastException() instanceof IOException);
            assertEquals("Mock OutputStream write exception", c.getLastException().getMessage());
        }
    }

    @Test
    public void testSendSubscriptionMessage() throws IOException, TimeoutException {
        try (ConnectionImpl c = (ConnectionImpl) Mockito.spy(newMockedConnection())) {
            SubscriptionImpl mockSub = mock(SubscriptionImpl.class);
            when(mockSub.getQueue()).thenReturn("foo");
            when(mockSub.getSid()).thenReturn(55L);
            c.setOutputStream(bwMock);

            // Ensure bw write error is logged
            c.status = ConnState.CONNECTED;
            doThrow(new IOException("test")).when(bwMock).write(any(byte[].class));
            c.sendSubscriptionMessage(mockSub);
            verify(bwMock, times(1)).write(any(byte[].class));
            verifier.verifyLogMsgEquals(Level.WARN,
                    "nats: I/O exception while sending subscription message");
        }
    }

    @Test
    public void testGetSetInputStream() throws IOException, TimeoutException {
        try (ConnectionImpl conn = (ConnectionImpl) newNewMockedConnection()) {
            conn.setInputStream(brMock);
            assertEquals(brMock, conn.getInputStream());
        }
    }

    @Test
    public void testGetSetHandlers() throws IOException, TimeoutException {
        ClosedCallback ccb = mock(ClosedCallback.class);
        DisconnectedCallback dcb = mock(DisconnectedCallback.class);
        ReconnectedCallback rcb = mock(ReconnectedCallback.class);
        ExceptionHandler eh = mock(ExceptionHandler.class);
        try (Connection c = newMockedConnection()) {
            assertNull(c.getClosedCallback());
            assertNull(c.getReconnectedCallback());
            assertNull(c.getDisconnectedCallback());
            assertNull(c.getExceptionHandler());
            c.setClosedCallback(ccb);
            assertEquals(ccb, c.getClosedCallback());
            c.setDisconnectedCallback(dcb);
            assertEquals(dcb, c.getDisconnectedCallback());
            c.setReconnectedCallback(rcb);
            assertEquals(rcb, c.getReconnectedCallback());
            c.setExceptionHandler(eh);
            assertEquals(eh, c.getExceptionHandler());
        }
    }

    @Test
    public void testNormalizeErr() {
        final String errString = "-ERR 'Authorization Violation'";
        ByteBuffer error = ByteBuffer.allocate(1024);
        error.put(errString.getBytes());
        error.flip();

        String str = ConnectionImpl.normalizeErr(error);
        assertEquals("authorization violation", str);
    }

    @Test
    public void testPingTimerTask() throws IOException, TimeoutException {
        try (ConnectionImpl c = (ConnectionImpl) spy(newMockedConnection())) {
            assertTrue(c.getActualPingsOutstanding() + 1 < c.opts.getMaxPingsOut());
            SynchronousExecutorService sexec = new SynchronousExecutorService();
            sexec.submit(c.new PingTimerTask());
            verify(c, times(1)).sendPing(null);
        }
    }

    @Test
    public void testPingTimerTaskFailsFastIfNotConnected() throws IOException, TimeoutException {
        try (ConnectionImpl c = (ConnectionImpl) spy(newMockedConnection())) {
            c.status = ConnState.DISCONNECTED;
            SynchronousExecutorService sexec = new SynchronousExecutorService();
            sexec.submit(c.new PingTimerTask());
            verify(c, times(0)).sendPing(null);
            c.status = ConnState.CONNECTED;
        }
    }

    @Test
    public void testResetPingTimer() throws IOException, TimeoutException {
        try (ConnectionImpl nc = (ConnectionImpl) spy(newMockedConnection())) {
            ScheduledFuture ptmrMock = mock(ScheduledFuture.class);
            when(nc.createPingTimer()).thenReturn(ptmrMock);

            // Test for ptmr already exists
            nc.setPingTimer(ptmrMock);
            nc.resetPingTimer();
            // verify old was cancelled
            verify(ptmrMock, times(1)).cancel(true);
            // verify new was created
            verify(nc, times(1)).createPingTimer();
            assertEquals(ptmrMock, nc.getPingTimer());

            // Test for ping interval <= 0
            nc.setPingTimer(null);
            nc.opts.setPingInterval(0L);
            nc.resetPingTimer();
            // Verify that no ping timer was created, since ping interval was 0
            assertNull(nc.getPingTimer());
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testPingTimerTaskMaxPingsOutExceeded() throws IOException, TimeoutException {
        Options opts = new ConnectionFactory().options();
        opts.setReconnectAllowed(false);
        try (ConnectionImpl nc = (ConnectionImpl) spy(newMockedConnection(opts))) {
            nc.opts.setMaxPingsOut(4);
            nc.setActualPingsOutstanding(5);
            SynchronousExecutorService sexec = new SynchronousExecutorService();
            sexec.execute(nc.new PingTimerTask());
            verify(nc, times(1)).processOpError(any(IOException.class));
            verify(nc, times(0)).sendPing(any(BlockingQueue.class));
            assertTrue(nc.getLastException() instanceof IOException);
            assertEquals(ERR_STALE_CONNECTION, nc.getLastException().getMessage());
            assertTrue(nc.isClosed());
        }
    }

    @Test
    public void testTlsMismatchClient() {
        // TODO do this with normal mock
        Options opts = new ConnectionFactory("tls://localhost:4222").options();
        opts.setSecure(true);
        try (Connection c = (Connection) newMockedConnection(opts)) {
            fail("Shouldn't have connected.");
        } catch (IOException | TimeoutException e) {
            assertTrue(e instanceof IOException);
            assertNotNull(e.getMessage());
            assertEquals(ERR_SECURE_CONN_WANTED, e.getMessage());
        }
    }

    @Test
    public void testTlsMismatchServer() {
        // TODO do this with normal mock
        TcpConnectionFactoryMock mcf = new TcpConnectionFactoryMock();
        mcf.setTlsRequired(true);
        try (ConnectionImpl c = new ConnectionFactory().createConnection(mcf)) {
            fail("Shouldn't have connected.");
        } catch (IOException | TimeoutException e) {
            assertTrue(e instanceof IOException);
            assertNotNull(e.getMessage());
            assertEquals(ERR_SECURE_CONN_REQUIRED, e.getMessage());
        }
    }

    @Test
    public void testUnsubscribe() throws IOException, TimeoutException {
        thrown.expect(IllegalStateException.class);
        thrown.expectMessage(ERR_CONNECTION_CLOSED);

        try (ConnectionImpl nc = (ConnectionImpl) newMockedConnection()) {
            SyncSubscriptionImpl sub = (SyncSubscriptionImpl) nc.subscribeSync("foo");
            long sid = sub.getSid();
            assertNotNull("Sub should have been present", nc.getSubs().get(sid));
            sub.unsubscribe();
            assertNull("Sub should have been removed", nc.getSubs().get(sid));
            nc.close();
            assertTrue(nc.isClosed());
            nc.unsubscribe(sub, 0);
        }
    }


    @Test
    public void testUnsubscribeAlreadyUnsubscribed() throws IOException, TimeoutException {
        try (ConnectionImpl nc = (ConnectionImpl) spy(newMockedConnection())) {
            nc.setSubs(subsMock);
            SubscriptionImpl sub = mock(AsyncSubscriptionImpl.class);
            nc.unsubscribe(sub, 100);
            verify(sub, times(0)).setMax(any(long.class));
            verify(nc, times(0)).removeSub(eq(sub));
        }
    }

    @Test
    public void testUnsubscribeWithMax() throws IOException, TimeoutException {
        try (ConnectionImpl nc = (ConnectionImpl) spy(newMockedConnection())) {
            nc.setSubs(subsMock);
            SubscriptionImpl sub = mock(AsyncSubscriptionImpl.class);
            when(sub.getSid()).thenReturn(14L);
            when(subsMock.get(eq(14L))).thenReturn(sub);

            nc.unsubscribe(sub, 100);
            verify(sub, times(1)).setMax(eq(100L));
            verify(nc, times(0)).removeSub(eq(sub));
        }
    }

    @Test
    public void testHandleSlowConsumer() throws IOException, TimeoutException {
        MessageHandler mcb = new MessageHandler() {
            public void onMessage(Message msg) {

            }

        };
        try (ConnectionImpl nc = (ConnectionImpl) spy(newMockedConnection())) {
            AsyncSubscriptionImpl sub = new AsyncSubscriptionImpl(nc, "foo", "bar", mcb);
            Message msg = new Message("foo", "bar", "Hello World".getBytes());
            sub.pBytes += msg.getData().length;
            sub.pMsgs = 1;
            nc.handleSlowConsumer(sub, msg);
            assertEquals(1, sub.dropped);
            assertEquals(0, sub.pMsgs);
            assertEquals(0, sub.pBytes);

            msg.setData(null);
            sub.pMsgs = 1;
            nc.handleSlowConsumer(sub, msg);
            assertEquals(2, sub.getDropped());
            assertEquals(0, sub.pMsgs);
            assertEquals(0, sub.pBytes);
        }

    }

    @Test
    public void testIsConnected() throws IOException, TimeoutException {
        try (ConnectionImpl nc = (ConnectionImpl) spy(newMockedConnection())) {
            assertTrue(nc.isConnected());
        }
    }

}
