package org.hl7.davinci.priorauth;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.cert.X509Certificate;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit4.SpringRunner;

/**
 * EM-162 / 45 CFR 164.312(e)(2)(ii): outside the dev profile TLS is on by
 * default and the keystore is supplied through externalised properties.
 */
@RunWith(SpringRunner.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
    "spring.profiles.active=default", "server.servlet.contextPath=/fhir" })
public class TlsDefaultTest {

  private static final String KEYSTORE_PASSWORD = "changeit-test";

  @LocalServerPort
  private int port;

  @DynamicPropertySource
  static void keystore(DynamicPropertyRegistry registry) throws Exception {
    Path dir = Files.createTempDirectory("pas-tls-test");
    File keystore = dir.resolve("test.p12").toFile();
    keystore.deleteOnExit();
    dir.toFile().deleteOnExit();
    String keytool = Paths.get(System.getProperty("java.home"), "bin", "keytool").toString();
    Process process = new ProcessBuilder(keytool, "-genkeypair", "-alias", "pas", "-keyalg", "RSA", "-keysize",
        "2048", "-storetype", "PKCS12", "-keystore", keystore.getAbsolutePath(), "-storepass", KEYSTORE_PASSWORD,
        "-keypass", KEYSTORE_PASSWORD, "-dname", "CN=localhost", "-validity", "1").inheritIO().start();
    Assert.assertEquals(0, process.waitFor());
    registry.add("server.ssl.key-store", () -> "file:" + keystore.getAbsolutePath());
    registry.add("server.ssl.key-store-password", () -> KEYSTORE_PASSWORD);
  }

  @Before
  public void setup() {
    App.initializeAppDB();
  }

  @Test
  public void serverSpeaksHttpsByDefault() throws Exception {
    SSLContext sslContext = SSLContext.getInstance("TLS");
    sslContext.init(null, new TrustManager[] { new TrustAllManager() }, null);
    HttpClient client = HttpClient.newBuilder().sslContext(sslContext).build();
    HttpResponse<String> response = client.send(
        HttpRequest.newBuilder(URI.create("https://localhost:" + port + "/fhir/metadata")).GET().build(),
        HttpResponse.BodyHandlers.ofString());
    Assert.assertEquals(200, response.statusCode());
  }

  @Test
  public void plainHttpIsNotServed() throws Exception {
    HttpClient client = HttpClient.newHttpClient();
    try {
      HttpResponse<String> response = client.send(
          HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/fhir/metadata")).GET().build(),
          HttpResponse.BodyHandlers.ofString());
      Assert.assertNotEquals(200, response.statusCode());
    } catch (java.io.IOException expected) {
      // connection rejected by the TLS connector
    }
  }

  private static class TrustAllManager implements X509TrustManager {
    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType) {
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType) {
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
      return new X509Certificate[0];
    }
  }
}
