package org.hl7.davinci.priorauth;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@RunWith(SpringRunner.class)
@TestPropertySource(properties = "server.servlet.contextPath=/fhir")
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
public class LogEndpointTest {

  @Autowired
  private WebApplicationContext wac;

  @Test
  public void getLogIsForbiddenWhenNotInDebugMode() throws Exception {
    Assert.assertFalse(App.isDebugModeEnabled());
    PALogger.getLogger().info("LogEndpointTest::sentinel-log-line");

    MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();
    MvcResult result = mockMvc.perform(MockMvcRequestBuilders.get("/Log"))
        .andExpect(MockMvcResultMatchers.status().isForbidden()).andReturn();

    Assert.assertFalse(result.getResponse().getContentAsString().contains("sentinel-log-line"));
  }
}
