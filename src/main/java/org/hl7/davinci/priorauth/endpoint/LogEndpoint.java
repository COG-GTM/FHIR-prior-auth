package org.hl7.davinci.priorauth.endpoint;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.http.HttpServletRequest;

import org.hl7.davinci.priorauth.App;
import org.hl7.davinci.priorauth.PALogger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the application log, which may contain PHI and credentials of every
 * client. Only available in debug mode, and only to callers presenting the
 * dedicated admin token configured via {@code priorauth.log.admin-token}
 * (env {@code PRIORAUTH_LOG_ADMINTOKEN}). If no token is configured the
 * endpoint is disabled.
 */
@RestController
@RequestMapping("/Log")
public class LogEndpoint {

    static final Logger logger = PALogger.getLogger();

    private static final String BEARER_PREFIX = "Bearer ";

    @Value("${priorauth.log.admin-token:}")
    private String adminToken;

    @GetMapping("")
    public ResponseEntity<String> getLog(HttpServletRequest request) {
        logger.info("GET /Log");
        if (!App.isDebugModeEnabled() || adminToken == null || adminToken.isEmpty()) {
            logger.warning("LogEndpoint::GET /Log rejected: endpoint disabled (debug mode off or no admin token configured)");
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }
        if (!isAdmin(request)) {
            logger.warning("LogEndpoint::GET /Log rejected: missing or invalid admin token");
            return new ResponseEntity<>(HttpStatus.UNAUTHORIZED);
        }
        try {
            String log = new String(Files.readAllBytes(Paths.get(PALogger.getLogPath())));
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Pragma", "no-cache").body(log);
        } catch (IOException e) {
            logger.log(Level.SEVERE, "LogEndpoint::IOException", e);
            return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
        }
    }

    private boolean isAdmin(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX))
            return false;
        byte[] presented = authHeader.substring(BEARER_PREFIX.length()).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(presented, adminToken.getBytes(StandardCharsets.UTF_8));
    }
}
