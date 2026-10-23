package org.hl7.davinci.priorauth.authorization;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.servlet.http.HttpServletRequest;

import okhttp3.Dns;
import okhttp3.OkHttpClient;

/**
 * Controls who may register SMART Backend Services clients and which jwks_url
 * values the server is willing to fetch.
 *
 * Registration is disabled unless an admin registration token is configured via
 * the {@value #REGISTRATION_TOKEN_PROPERTY} system property or the
 * {@value #REGISTRATION_TOKEN_ENV} environment variable. Callers must present it
 * as {@code Authorization: Bearer <token>}.
 */
public final class ClientRegistrationSecurity {

    public static final String REGISTRATION_TOKEN_PROPERTY = "registration.admin.token";
    public static final String REGISTRATION_TOKEN_ENV = "REGISTRATION_ADMIN_TOKEN";

    private static final String BEARER_PREFIX = "Bearer ";

    /** Resolves hostnames and rejects any that map to a non-public address. */
    static final Dns PUBLIC_ONLY_DNS = hostname -> {
        List<InetAddress> addresses = Arrays.asList(InetAddress.getAllByName(hostname));
        for (InetAddress address : addresses) {
            if (isDisallowedAddress(address))
                throw new UnknownHostException("jwks_url host " + hostname + " resolves to a non-public address");
        }
        return addresses;
    };

    private ClientRegistrationSecurity() {
    }

    /**
     * @return true if registration is enabled and the request carries the admin
     *         registration token
     */
    public static boolean isAuthorizedRegistrar(HttpServletRequest request) {
        String expected = getRegistrationToken();
        if (expected == null) return false;

        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER_PREFIX)) return false;
        String provided = header.substring(BEARER_PREFIX.length()).trim();

        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8));
    }

    public static boolean isRegistrationEnabled() {
        return getRegistrationToken() != null;
    }

    private static String getRegistrationToken() {
        String token = System.getProperty(REGISTRATION_TOKEN_PROPERTY);
        if (token == null || token.isBlank()) token = System.getenv(REGISTRATION_TOKEN_ENV);
        return token == null || token.isBlank() ? null : token.trim();
    }

    /**
     * Validate a client-supplied jwks_url: must be an absolute https URL whose host
     * resolves only to public addresses.
     *
     * @throws IllegalArgumentException if the URL is not acceptable
     */
    public static void validateJwksUrl(String jwksUrl) {
        if (jwksUrl == null || jwksUrl.isBlank()) throw new IllegalArgumentException("jwks_url is empty");

        URI uri;
        try {
            uri = new URI(jwksUrl.trim());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("jwks_url is not a valid URL");
        }

        if (!"https".equalsIgnoreCase(uri.getScheme()))
            throw new IllegalArgumentException("jwks_url must use https");
        if (uri.getUserInfo() != null)
            throw new IllegalArgumentException("jwks_url must not contain user info");

        String host = uri.getHost();
        if (host == null || host.isEmpty()) throw new IllegalArgumentException("jwks_url must include a host");

        try {
            PUBLIC_ONLY_DNS.lookup(stripBrackets(host));
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("jwks_url host is not resolvable to a public address");
        }
    }

    /**
     * HTTP client for fetching jwks_url: no redirects, public-only DNS, short timeouts.
     */
    public static OkHttpClient jwksHttpClient() {
        return new OkHttpClient.Builder().dns(PUBLIC_ONLY_DNS).followRedirects(false).followSslRedirects(false)
                .connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).build();
    }

    /**
     * @return true for loopback, link-local, site-local/private, unique-local,
     *         wildcard, multicast and carrier-grade NAT addresses
     */
    static boolean isDisallowedAddress(InetAddress address) {
        if (address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isAnyLocalAddress() || address.isMulticastAddress())
            return true;

        byte[] b = address.getAddress();
        if (address instanceof Inet6Address) {
            // fc00::/7 unique local
            if ((b[0] & 0xFE) == 0xFC) return true;
            // IPv4-compatible / IPv4-mapped forms that Java did not already unwrap
            boolean leadingZeros = true;
            for (int i = 0; i < 10; i++) leadingZeros &= b[i] == 0;
            if (leadingZeros && ((b[10] == 0 && b[11] == 0) || (b[10] == (byte) 0xFF && b[11] == (byte) 0xFF))) {
                try {
                    return isDisallowedAddress(InetAddress.getByAddress(Arrays.copyOfRange(b, 12, 16)));
                } catch (UnknownHostException e) {
                    return true;
                }
            }
            return false;
        }

        int first = b[0] & 0xFF;
        int second = b[1] & 0xFF;
        // 0.0.0.0/8 "this network" and 100.64.0.0/10 carrier-grade NAT
        return first == 0 || (first == 100 && second >= 64 && second <= 127);
    }

    private static String stripBrackets(String host) {
        return host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
    }
}
