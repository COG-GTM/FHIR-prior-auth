package org.hl7.davinci.priorauth;

import java.net.URI;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.Assert;
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
@TestPropertySource(properties = "server.servlet.contextPath=/fhir")
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
public class WebSocketConfigTest {

    @LocalServerPort
    private int port;

    private WebSocketSession connect(String origin) throws Exception {
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        if (origin != null) {
            headers.setOrigin(origin);
        }
        URI uri = URI.create("ws://localhost:" + port + "/fhir" + WebSocketConfig.ENDPOINT_CONNECT);
        return new StandardWebSocketClient().doHandshake(new TextWebSocketHandler(), headers, uri)
                .get(10, TimeUnit.SECONDS);
    }

    @Test
    public void rejectsCrossSiteOrigin() throws Exception {
        try {
            WebSocketSession session = connect("https://evil.example.com");
            session.close();
            Assert.fail("Handshake from untrusted origin should be rejected");
        } catch (ExecutionException e) {
            // expected: server responds 403 to the handshake
        }
    }

    @Test
    public void allowsLocalhostOrigin() throws Exception {
        WebSocketSession session = connect("http://localhost:3005");
        Assert.assertTrue(session.isOpen());
        session.close();
    }

    @Test
    public void allowsSameOrigin() throws Exception {
        WebSocketSession session = connect("http://localhost:" + port);
        Assert.assertTrue(session.isOpen());
        session.close();
    }

    @Test
    public void allowsNonBrowserClientWithoutOrigin() throws Exception {
        WebSocketSession session = connect(null);
        Assert.assertTrue(session.isOpen());
        session.close();
    }

    @Test
    public void sanitizeDropsWildcardAndBlanks() {
        String[] result = WebSocketConfig.sanitizeOriginPatterns(
                new String[] { " https://payer.example.org ", "*", "", "http://localhost:[*]" });
        Assert.assertArrayEquals(new String[] { "https://payer.example.org", "http://localhost:[*]" }, result);
        Assert.assertEquals(0, WebSocketConfig.sanitizeOriginPatterns(null).length);
    }
}
