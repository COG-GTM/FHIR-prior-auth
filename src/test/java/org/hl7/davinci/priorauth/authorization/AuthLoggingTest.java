package org.hl7.davinci.priorauth.authorization;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.hl7.davinci.priorauth.App;
import org.hl7.davinci.priorauth.LogRedactor;
import org.hl7.davinci.priorauth.PALogger;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;

public class AuthLoggingTest {

    private static final String CLIENT_ASSERTION_TYPE = "urn:ietf:params:oauth:client-assertion-type:jwt-bearer";

    private final List<String> messages = new ArrayList<>();
    private Logger logger;
    private Level previousLevel;
    private Handler handler;

    @BeforeClass
    public static void setupClass() {
        App.initializeAppDB();
    }

    @Before
    public void captureLogs() {
        logger = PALogger.getLogger();
        previousLevel = logger.getLevel();
        logger.setLevel(Level.ALL);
        handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                messages.add(record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        handler.setLevel(Level.ALL);
        logger.addHandler(handler);
    }

    @After
    public void releaseLogs() {
        logger.removeHandler(handler);
        logger.setLevel(previousLevel);
    }

    private static String b64(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/auth/token");
        request.setServerName("localhost");
        request.setServerPort(9015);
        return request;
    }

    @Test
    public void tokenRequestLogDoesNotContainClientAssertion() {
        String assertion = b64("{\"alg\":\"RS384\",\"kid\":\"k1\"}") + "."
                + b64("{\"iss\":\"unregistered-client\"}") + ".c2lnbmF0dXJlLXNlY3JldA";

        new AuthEndpoint().token(request(), "system/*.read", "client_credentials", CLIENT_ASSERTION_TYPE, assertion);

        String tokenLine = messages.stream().filter(m -> m != null && m.startsWith("AuthEndpoint::token:"))
                .findFirst().orElse(null);
        Assert.assertNotNull("expected the token request to be logged", tokenLine);
        Assert.assertTrue(tokenLine.contains("client_assertion=" + LogRedactor.REDACTED));
        Assert.assertFalse(tokenLine.contains(assertion));
        for (String message : messages) {
            Assert.assertFalse("log leaked client_assertion: " + message,
                    message != null && message.contains("c2lnbmF0dXJlLXNlY3JldA"));
        }
    }

    @Test
    public void authorizationHeaderAndBearerTokenAreNotLogged() {
        String bearer = "dGhpcy1pcy1hLWxpdmUtYmVhcmVyLXRva2Vu";
        MockHttpServletRequest request = request();
        request.addHeader("Authorization", "Bearer " + bearer);

        Assert.assertFalse(AuthUtils.validateAccessToken(request));

        Assert.assertTrue(messages.stream().anyMatch(m -> m.contains("Authorization header present: true")));
        for (String message : messages) {
            Assert.assertFalse("log leaked bearer token: " + message, message.contains(bearer));
        }
    }
}
