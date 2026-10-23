package org.hl7.davinci.priorauth.endpoint;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.http.HttpServletRequest;

import org.hl7.davinci.priorauth.App;
import org.hl7.davinci.priorauth.PALogger;
import org.hl7.davinci.priorauth.authorization.AuthUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the application log. Only available in debug mode and only to callers
 * presenting a valid access token, since the log may contain PHI and credentials.
 */
@RestController
@RequestMapping("/Log")
public class LogEndpoint {

    static final Logger logger = PALogger.getLogger();

    @GetMapping("")
    public ResponseEntity<String> getLog(HttpServletRequest request) {
        logger.info("GET /Log");
        if (!App.isDebugModeEnabled()) {
            logger.warning("LogEndpoint::GET /Log rejected: app not in debug mode");
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }
        if (!AuthUtils.validateAccessToken(request)) {
            logger.warning("LogEndpoint::GET /Log rejected: missing or invalid access token");
            return new ResponseEntity<>(HttpStatus.UNAUTHORIZED);
        }
        try {
            String log = new String(Files.readAllBytes(Paths.get(PALogger.getLogPath())));
            return new ResponseEntity<>(log, HttpStatus.OK);
        } catch (IOException e) {
            logger.log(Level.SEVERE, "LogEndpoint::IOException", e);
            return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
        }
    }
}
