package org.hl7.davinci.priorauth;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Assert;
import org.junit.Test;

public class LogRedactorTest {

    @Test
    public void maskPatientHidesIdentifierButIsStable() {
        String masked = LogRedactor.maskPatient("pat013");
        Assert.assertFalse(masked.contains("pat013"));
        Assert.assertTrue(masked.startsWith("patient#"));
        Assert.assertEquals(masked, LogRedactor.maskPatient("pat013"));
        Assert.assertNotEquals(masked, LogRedactor.maskPatient("pat014"));
        Assert.assertEquals("null", LogRedactor.maskPatient(null));
    }

    @Test
    public void redactMapMasksPatientAndSecrets() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", "claim-1");
        map.put("patient", "pat013");
        map.put("token", "live-bearer-token");
        map.put("resource", "{\"resourceType\":\"Patient\",\"name\":\"Jane Doe\"}");

        String rendered = LogRedactor.redactMap(map);

        Assert.assertTrue(rendered.contains("id=claim-1"));
        Assert.assertTrue(rendered.contains("token=" + LogRedactor.REDACTED));
        Assert.assertFalse(rendered.contains("pat013"));
        Assert.assertFalse(rendered.contains("live-bearer-token"));
        Assert.assertFalse(rendered.contains("Jane Doe"));
    }

    @Test
    public void tokenRequestOmitsAssertion() {
        String rendered = LogRedactor.tokenRequest("system/*.read", "client_credentials",
                "urn:ietf:params:oauth:client-assertion-type:jwt-bearer");
        Assert.assertTrue(rendered.endsWith("client_assertion=" + LogRedactor.REDACTED));
    }
}
