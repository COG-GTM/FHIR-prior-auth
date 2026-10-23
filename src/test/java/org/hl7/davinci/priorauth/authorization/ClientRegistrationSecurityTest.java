package org.hl7.davinci.priorauth.authorization;

import java.net.InetAddress;

import org.junit.Assert;
import org.junit.Test;

public class ClientRegistrationSecurityTest {

  private static boolean disallowed(String ip) throws Exception {
    return ClientRegistrationSecurity.isDisallowedAddress(InetAddress.getByName(ip));
  }

  @Test
  public void nonPublicAddressesAreDisallowed() throws Exception {
    String[] ips = { "127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.0.1", "169.254.169.254", "0.0.0.0",
        "100.64.0.1", "224.0.0.1", "::1", "::", "fe80::1", "fd00::1", "fc00::1", "::ffff:127.0.0.1", "::ffff:10.0.0.1",
        "198.18.0.1", "198.19.255.1", "192.0.2.1", "198.51.100.1", "203.0.113.1", "240.0.0.1", "255.255.255.255",
        "64:ff9b::a00:1", "64:ff9b::7f00:1" };
    for (String ip : ips) {
      Assert.assertTrue(ip + " should be disallowed", disallowed(ip));
    }
  }

  @Test
  public void publicAddressesAreAllowed() throws Exception {
    String[] ips = { "8.8.8.8", "1.1.1.1", "100.128.0.1", "198.20.0.1", "2606:4700:4700::1111", "64:ff9b::808:808" };
    for (String ip : ips) {
      Assert.assertFalse(ip + " should be allowed", disallowed(ip));
    }
  }

  @Test
  public void httpsPublicJwksUrlIsAccepted() {
    ClientRegistrationSecurity.validateJwksUrl("https://8.8.8.8/jwks");
    ClientRegistrationSecurity.validateJwksUrl("https://8.8.8.8:8443/jwks");
    ClientRegistrationSecurity.validateJwksUrl("https://[2606:4700:4700::1111]/jwks");
  }

  @Test
  public void invalidJwksUrlsAreRejected() {
    String[] urls = { null, "", "http://8.8.8.8/jwks", "ftp://8.8.8.8/jwks", "https:///jwks", "https://user@8.8.8.8/jwks",
        "https://127.0.0.1/jwks", "https://[fe80::1]/jwks", "https://169.254.169.254/", "https://8.8.8.8:999999/jwks", "https://8.8.8.8:0/jwks",
        "file:///etc/passwd", "https://[::1]/jwks" };
    for (String url : urls) {
      try {
        ClientRegistrationSecurity.validateJwksUrl(url);
        Assert.fail("Expected rejection of " + url);
      } catch (IllegalArgumentException expected) {
        // expected
      }
    }
  }
}
