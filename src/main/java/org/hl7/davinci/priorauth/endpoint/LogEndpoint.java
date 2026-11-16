package org.hl7.davinci.priorauth.endpoint;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.http.HttpServletRequest;

import org.hl7.davinci.priorauth.App;
import org.hl7.davinci.priorauth.Audit;
import org.hl7.davinci.priorauth.PALogger;
import org.hl7.davinci.priorauth.Audit.AuditEventOutcome;
import org.hl7.davinci.priorauth.Audit.AuditEventType;
import org.hl7.fhir.r4.model.AuditEvent.AuditEventAction;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@CrossOrigin
@RestController
@RequestMapping("/Log")
public class LogEndpoint {

    static final Logger logger = PALogger.getLogger();

    @GetMapping("")
    public ResponseEntity<String> getLog(HttpServletRequest request) {
        logger.info("GET /Log");
        if (!App.isDebugModeEnabled()) {
            logger.warning("LogEndpoint::read log disabled");
            String description = "Attempted to read application log but app is not in debug mode.";
            Audit.createAuditEvent(AuditEventType.QUERY, AuditEventAction.R, AuditEventOutcome.MINOR_FAILURE, null, request, description);
            return new ResponseEntity<>(HttpStatus.FORBIDDEN);
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
