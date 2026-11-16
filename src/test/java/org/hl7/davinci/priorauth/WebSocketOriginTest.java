package org.hl7.davinci.priorauth;

import java.net.URI;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@RunWith(SpringRunner.class)
@TestPropertySource(properties = { "server.servlet.contextPath=/fhir",
        "priorauth.websocket.allowed-origins=http://trusted.example.com" })
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
public class WebSocketOriginTest {

    @LocalServerPort
    private int port;

    @BeforeClass
    public static void setup() {
        App.initializeAppDB();
    }

    private WebSocketSession connect(String origin) throws Exception {
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        if (origin != null) {
            headers.setOrigin(origin);
        }
        URI uri = new URI("ws://localhost:" + port + "/fhir" + WebSocketConfig.ENDPOINT_CONNECT);
        return new StandardWebSocketClient()
                .doHandshake(new TextWebSocketHandler(), headers, uri)
                .get(10, TimeUnit.SECONDS);
    }

    private void assertRejected(String origin) throws Exception {
        try {
            WebSocketSession session = connect(origin);
            session.close();
            Assert.fail("WebSocket handshake from origin " + origin + " should have been rejected");
        } catch (ExecutionException expected) {
            // handshake rejected (HTTP 403)
        }
    }

    private void assertAccepted(String origin) throws Exception {
        WebSocketSession session = connect(origin);
        Assert.assertTrue(session.isOpen());
        session.close();
    }

    @Test
    public void rejectsCrossSiteOrigin() throws Exception {
        assertRejected("http://evil.example.com");
    }

    @Test
    public void rejectsWildcardSpoofedOrigin() throws Exception {
        assertRejected("null");
    }

    @Test
    public void acceptsConfiguredOrigin() throws Exception {
        assertAccepted("http://trusted.example.com");
    }

    @Test
    public void acceptsSameOrigin() throws Exception {
        assertAccepted("http://localhost:" + port);
    }

    @Test
    public void acceptsNonBrowserClientWithoutOrigin() throws Exception {
        assertAccepted(null);
    }
}
