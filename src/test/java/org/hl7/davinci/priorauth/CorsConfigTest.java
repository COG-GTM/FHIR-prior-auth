package org.hl7.davinci.priorauth;

import org.junit.Assert;
import org.junit.Test;

public class CorsConfigTest {

  @Test
  public void emptyAllowListDisablesCors() {
    CorsConfig config = new CorsConfig(new String[0]);
    Assert.assertFalse(config.isCorsEnabled());
    Assert.assertArrayEquals(new String[0], config.getAllowedOrigins());
  }

  @Test
  public void wildcardAndBlankEntriesAreIgnored() {
    CorsConfig config = new CorsConfig(new String[] { "*", " https://a.example.org ", "" });
    Assert.assertTrue(config.isCorsEnabled());
    Assert.assertArrayEquals(new String[] { "https://a.example.org" }, config.getAllowedOrigins());
  }
}
