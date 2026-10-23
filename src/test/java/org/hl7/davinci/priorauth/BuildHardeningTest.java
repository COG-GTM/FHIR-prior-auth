package org.hl7.davinci.priorauth;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Assert;
import org.junit.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Guards the production Dockerfile against shipping a JDWP remote debugger and
 * ensures the CI workflows gate pull requests to master (EM-163 / audit F12).
 */
public class BuildHardeningTest {

    private static final List<String> PR_GATE_WORKFLOWS = Arrays.asList(
        ".github/workflows/automated-tests-ci.yml",
        ".github/workflows/docker-ci.yml");

    @Test
    public void productionDockerfileDoesNotEnableRemoteDebugger() throws IOException {
        String dockerfile = new String(Files.readAllBytes(Paths.get("Dockerfile")));
        Matcher cmd = Pattern.compile("(?m)^\\s*(CMD|ENTRYPOINT)\\s+(.*)$").matcher(dockerfile);
        boolean foundCmd = false;
        while (cmd.find()) {
            foundCmd = true;
            String instruction = cmd.group(2);
            Assert.assertFalse("Production Dockerfile must not pass -Pdebug: " + instruction,
                instruction.contains("-Pdebug"));
            Assert.assertFalse("Production Dockerfile must not enable JDWP: " + instruction,
                instruction.contains("jdwp"));
        }
        Assert.assertTrue("Dockerfile has no CMD/ENTRYPOINT", foundCmd);
        Assert.assertFalse("Production Dockerfile must not expose the JDWP port",
            Pattern.compile("(?m)^\\s*EXPOSE\\s+.*\\b9016\\b").matcher(dockerfile).find());
    }

    @Test
    public void ciWorkflowsRunOnPullRequestsToMasterAndDev() throws IOException {
        for (String workflow : PR_GATE_WORKFLOWS) {
            List<?> branches = pullRequestBranches(loadWorkflow(workflow));
            Assert.assertTrue(workflow + " must run on pull_request to master", branches.contains("master"));
            Assert.assertTrue(workflow + " must run on pull_request to dev", branches.contains("dev"));
        }
    }

    @Test
    public void automatedTestsWorkflowRunsGradleCleanCheck() throws IOException {
        Map<?, ?> jobs = (Map<?, ?>) loadWorkflow(".github/workflows/automated-tests-ci.yml").get("jobs");
        boolean runsCleanCheck = jobs.values().stream()
            .flatMap(job -> ((List<?>) ((Map<?, ?>) job).get("steps")).stream())
            .map(step -> ((Map<?, ?>) step).get("run"))
            .anyMatch(run -> run != null && run.toString().matches("(?s).*gradlew?\\s+clean\\s+check.*"));
        Assert.assertTrue("automated-tests-ci.yml must execute `gradle clean check`", runsCleanCheck);
    }

    private static Map<?, ?> loadWorkflow(String path) throws IOException {
        Path file = Paths.get(path);
        Assert.assertTrue(path + " is missing", Files.exists(file));
        Object parsed = new Yaml().load(new String(Files.readAllBytes(file)));
        Assert.assertTrue(path + " is not a YAML mapping", parsed instanceof Map);
        Map<?, ?> workflow = (Map<?, ?>) parsed;
        Assert.assertNotNull(path + " has no jobs", workflow.get("jobs"));
        return workflow;
    }

    private static List<?> pullRequestBranches(Map<?, ?> workflow) {
        // YAML 1.1 parsers (SnakeYAML) read the unquoted key `on` as boolean true.
        Object triggers = workflow.containsKey("on") ? workflow.get("on") : workflow.get(Boolean.TRUE);
        Assert.assertTrue("workflow has no `on:` trigger mapping", triggers instanceof Map);
        Object pullRequest = ((Map<?, ?>) triggers).get("pull_request");
        Assert.assertTrue("workflow has no pull_request trigger", pullRequest instanceof Map);
        Object branches = ((Map<?, ?>) pullRequest).get("branches");
        Assert.assertTrue("pull_request trigger has no branches list", branches instanceof List);
        return (List<?>) branches;
    }
}
