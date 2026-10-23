package org.hl7.davinci.priorauth.authorization;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.servlet.http.HttpServletRequest;

import okhttp3.Dns;
import okhttp3.HttpUrl;
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

        HttpUrl url = HttpUrl.parse(jwksUrl.trim());
        if (url == null) throw new IllegalArgumentException("jwks_url is not a valid http(s) URL");
        if (!url.isHttps()) throw new IllegalArgumentException("jwks_url must use https");
        String raw = jwksUrl.trim();
        int authorityStart = raw.indexOf("://") + 3;
        int authorityEnd = raw.indexOf('/', authorityStart);
        String authority = raw.substring(authorityStart, authorityEnd < 0 ? raw.length() : authorityEnd);
        if (!url.username().isEmpty() || !url.password().isEmpty() || authority.contains("@"))
            throw new IllegalArgumentException("jwks_url must not contain user info");

        try {
            PUBLIC_ONLY_DNS.lookup(url.host());
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
            // IPv4-compatible / IPv4-mapped forms that Java did not already unwrap, and NAT64 64:ff9b::/96
            boolean leadingZeros = true;
            for (int i = 0; i < 10; i++) leadingZeros &= b[i] == 0;
            boolean nat64 = b[0] == 0 && b[1] == 0x64 && b[2] == (byte) 0xFF && b[3] == (byte) 0x9B;
            for (int i = 4; i < 12 && nat64; i++) nat64 = b[i] == 0;
            if (nat64 || (leadingZeros && ((b[10] == 0 && b[11] == 0) || (b[10] == (byte) 0xFF && b[11] == (byte) 0xFF)))) {
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
        int third = b[2] & 0xFF;
        int fourth = b[3] & 0xFF;
        return first == 0 // 0.0.0.0/8 "this network"
                || (first == 100 && second >= 64 && second <= 127) // 100.64.0.0/10 carrier-grade NAT
                || (first == 192 && second == 0 && third == 0 && fourth != 9 && fourth != 10) // 192.0.0.0/24 except anycast .9/.10
                || (first == 192 && second == 0 && third == 2) // 192.0.2.0/24
                || (first == 198 && (second == 18 || second == 19)) // 198.18.0.0/15 benchmarking
                || (first == 198 && second == 51 && third == 100) // 198.51.100.0/24 documentation
                || (first == 203 && second == 0 && third == 113) // 203.0.113.0/24 documentation
                || first >= 240; // 240.0.0.0/4 reserved, broadcast
    }
}
