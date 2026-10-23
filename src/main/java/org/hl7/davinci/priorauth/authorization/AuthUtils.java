package org.hl7.davinci.priorauth.authorization;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.http.HttpServletRequest;

import org.hl7.davinci.priorauth.App;
import org.hl7.davinci.priorauth.PALogger;
import org.hl7.davinci.priorauth.Database.Table;
import org.hl7.fhir.r4.model.ContactPoint;
import org.hl7.fhir.r4.model.Organization;

public class AuthUtils {

    static final Logger logger = PALogger.getLogger();

    public static final int TOKEN_LIFE_MIN = 5;
    private static final String ADMIN_TOKEN = "Y3YWq2l08kvFqy50fQJY";
    private static final String BYPASS_AUTH = "BYPASS_AUTH";

    public static final String SCOPE_CLAIM_WRITE = "system/Claim.write";
    public static final String SCOPE_CLAIM_RESPONSE_READ = "system/ClaimResponse.read";

    /**
     * Populate the Client table with default data
     */
    public static void populateClientTable() {
        // Auto register DTR RI
        String clientId = "797da153-9a36-4493-9910-10648a4deb03";
        Organization organization = new Organization();
        organization.setId(clientId);
        organization.setName("MITRE");
        ContactPoint telecom = new ContactPoint();
        telecom.setValue("blangley@example.com");
        organization.setTelecom(Collections.singletonList(telecom));

        HashMap<String, Object> dataMap = new HashMap<>();
        dataMap.put("id", clientId);
        dataMap.put("jwks", "{\"keys\":[{\"ext\":true,\"kty\":\"RSA\",\"e\":\"AQAB\",\"kid\":\"3ab8b05b64d799e289e10a201786b38c\",\"key_ops\":[\"verify\"],\"alg\":\"RS384\",\"n\":\"52tcPrGJgzyGqjcUiHsbSk_PxQ7Uovz4saGxva3iyBoidsekonigJJ3LnFlHYb3vBa2NA-0GpX2E1KhNNcYWAWQFcu069zi0YZ_wWGn6PWZURuonUoKH4dGHggym3xxVUxuA8OPubGe5ji56eic4RPINg0z-TtPlS-H9dnDIVznRUTXf3fy2dqWMuTY4D2e4fXGII6OpFAsEyrOqIoR8pLWGu7AiQkothunopp9q_Gu2xqB6l8BNulsbiwsQMeRE-9SGfeFpyblHiizHDwSqeZ3iv49Ellk4yjmrf6wOaFA2IXRqL1cCLj86B6KIDrjdzOL4lOSiES-PclNpioG2rQ\"}]}");
        dataMap.put("jwks_url", null);
        dataMap.put("token", "Y3YWq2l08kvFqy50fQJY");
        dataMap.put("organization", organization);
        App.getDB().write(Table.CLIENT, dataMap);

        // Auto register Aegis Touchstone
        clientId = "d9dc3d3e-5be5-4882-832b-e74a93c2d01b";
        organization = new Organization();
        organization.setId(clientId);
        organization.setName("Aegis Touchstone");
        telecom = new ContactPoint();
        telecom.setValue("blangley@example.com");
        organization.setTelecom(Collections.singletonList(telecom));

        dataMap = new HashMap<>();
        dataMap.put("id", clientId);
        dataMap.put("jwks", null);
        dataMap.put("jwks_url", "https://touchstone.aegis.net/touchstone/tsJwks?type=RS384");
        dataMap.put("token", "Y3YWq2l08kvFqy50fQJY");
        dataMap.put("organization", organization);
        App.getDB().write(Table.CLIENT, dataMap);
    }

    /**
     * Determine whether or not the access token in the Authorization header is authorized
     * 
     * @param request HttpServletRequest
     * @return true if the access token is valid, false if invalid or no bearer token provided
     */
    public static boolean validateAccessToken(HttpServletRequest request) {

        String accessToken = getAccessToken(request);
        if (accessToken == null) return false;

        if (accessToken.equals(ADMIN_TOKEN)) {
            logger.fine("AuthEndpoint::validateAccessToken:Admin token used");
            return true;
        }

        String timestamp = App.getDB().readString(Table.CLIENT, Collections.singletonMap("token", accessToken), "timestamp");
        if (timestamp == null) return false;

        // Validate token age
        SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        Date issuedAt;
		try {
			issuedAt = formatter.parse(timestamp);
		} catch (java.text.ParseException e) {
            e.printStackTrace();
            logger.log(Level.SEVERE, "AuthEndpoint::validateAccessToken:Error parsing date", e);
            return false;
        }
        
        Date now = new Date();
        long tokenAgeMs = now.getTime() - issuedAt.getTime();

        if (tokenAgeMs > TOKEN_LIFE_MIN * 60000) {
            logger.severe("AuthEndpoint::validateAccessToken:Access Token expired. Max Age " + TOKEN_LIFE_MIN*60000 + " but token age is " + tokenAgeMs);
            return false;
        }

        return true;
    }

    /**
     * Determine whether the access token in the Authorization header was granted a scope
     * satisfying requiredScope. Callers must check validateAccessToken first.
     *
     * @param request       HttpServletRequest
     * @param requiredScope SMART scope required by the operation, e.g. system/Claim.write
     * @return true if the token's granted scopes satisfy requiredScope
     */
    public static boolean hasScope(HttpServletRequest request, String requiredScope) {
        String accessToken = getAccessToken(request);
        if (accessToken == null) return false;

        if (accessToken.equals(ADMIN_TOKEN)) return true;

        String grantedScopes = App.getDB().readString(Table.CLIENT, Collections.singletonMap("token", accessToken), "scope");
        boolean allowed = scopeSatisfies(grantedScopes, requiredScope);
        if (!allowed)
            logger.warning("AuthUtils::hasScope:Access token lacks required scope " + requiredScope);
        return allowed;
    }

    /**
     * Check whether a space separated list of granted SMART scopes satisfies a required scope.
     * Wildcards (*) are honoured for both the resource type and the access level.
     *
     * @param grantedScopes space separated granted scopes (may be null)
     * @param requiredScope required scope in the form context/ResourceType.access
     * @return true if any granted scope satisfies requiredScope
     */
    public static boolean scopeSatisfies(String grantedScopes, String requiredScope) {
        if (grantedScopes == null || requiredScope == null) return false;
        String[] required = parseScope(requiredScope);
        if (required == null) return false;

        for (String granted : grantedScopes.trim().split("\\s+")) {
            String[] g = parseScope(granted);
            if (g == null) continue;
            boolean contextMatches = g[0].equals(required[0]);
            boolean resourceMatches = g[1].equals("*") || g[1].equals(required[1]);
            boolean accessMatches = g[2].equals("*") || g[2].equals(required[2]);
            if (contextMatches && resourceMatches && accessMatches) return true;
        }
        return false;
    }

    /**
     * Split a SMART scope (context/ResourceType.access) into its three parts
     *
     * @param scope the scope string
     * @return [context, resourceType, access] or null if the scope is not a resource scope
     */
    private static String[] parseScope(String scope) {
        Matcher matcher = Pattern.compile("^(patient|user|system)/([A-Za-z]+|\\*)\\.(read|write|\\*)$").matcher(scope);
        if (!matcher.matches()) return null;
        return new String[] { matcher.group(1), matcher.group(2), matcher.group(3) };
    }

    /**
     * Log loudly at startup if the removed BYPASS_AUTH kill-switch is still configured
     */
    public static void warnIfBypassAuthConfigured() {
        if (System.getenv(BYPASS_AUTH) != null)
            logger.severe("AuthUtils::" + BYPASS_AUTH + " is set but is no longer supported and is ignored. Authentication is always enforced.");
    }

    /**
     * Helper method to get client id via the access token from the request
     * 
     * @param request HttpServletRequest
     * @return clientId if it exists or a string explaining why it does not
     */
    public static String getClientId(HttpServletRequest request) {
        String accessToken = getAccessToken(request);
        if (accessToken == null) return "Unknown Client: No Access Token";

        if (accessToken.equals(ADMIN_TOKEN)) return "Admin";

        String clientId = App.getDB().readString(Table.CLIENT, Collections.singletonMap("token", accessToken), "id");
        return clientId == null ? "Unknown Client: Invalid Access Token" : clientId;
    }

    /**
     * Helper method to get a list of all the supported scopes
     * 
     * @return list of scopes supported
     */
    public static List<String> getSupportedScopes() {
        ArrayList<String> scopes = new ArrayList<>();
        scopes.add("system/*.read");
        scopes.add("system/*.write");
        scopes.add("system/Claim.read");
        scopes.add(SCOPE_CLAIM_WRITE);
        scopes.add(SCOPE_CLAIM_RESPONSE_READ);
        scopes.add("offline_access");
        return scopes;
    }

    /**
     * Helper method to read the access token from the Authorization: Bearer {token} header
     * 
     * @param request request to get the token from
     * @return access token if it was included, otherwise null
     */
    private static String getAccessToken(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        logger.log(Level.FINE, "AuthEndpoint::getAccessToken:Authorization header: " + authHeader);
        if (authHeader != null) {
            String regex = "Bearer (.*)";
            Pattern pattern = Pattern.compile(regex);
            Matcher accessTokenMatcher = pattern.matcher(authHeader);
            if (accessTokenMatcher.find() && accessTokenMatcher.groupCount() == 1) {
                return accessTokenMatcher.group(1);
            }
        }

        return null;
    }
}
