package org.hl7.davinci.priorauth.bfd;

import java.io.FileInputStream;
import java.security.KeyStore;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import org.hl7.davinci.priorauth.PALogger;

/**
 * Configuration for the BFD (Beneficiary FHIR Data) client connection.
 * Supports mTLS authentication required by the BFD API.
 */
public class BfdConfiguration {

    private static final Logger logger = PALogger.getLogger();

    private final String serverUrl;
    private final String clientCertPath;
    private final String clientKeyPath;
    private final String clientKeyPassword;
    private final String trustStorePath;
    private final String trustStorePassword;

    /**
     * Create a BFD configuration from environment variables or defaults.
     */
    public BfdConfiguration() {
        this.serverUrl = getEnvOrDefault("BFD_SERVER_URL", "https://localhost:1234");
        this.clientCertPath = getEnvOrDefault("BFD_CLIENT_CERT_PATH", "");
        this.clientKeyPath = getEnvOrDefault("BFD_CLIENT_KEY_PATH", "");
        this.trustStorePath = getEnvOrDefault("BFD_TRUST_STORE_PATH", "");
        this.trustStorePassword = getEnvOrDefault("BFD_TRUST_STORE_PASSWORD", "changeit");
        // Default client key password to trust store password for backward compatibility
        this.clientKeyPassword = getEnvOrDefault("BFD_CLIENT_KEY_PASSWORD", this.trustStorePassword);
    }

    /**
     * Create a BFD configuration with explicit values.
     */
    public BfdConfiguration(String serverUrl, String clientCertPath, String clientKeyPath,
                            String clientKeyPassword, String trustStorePath, String trustStorePassword) {
        this.serverUrl = serverUrl;
        this.clientCertPath = clientCertPath;
        this.clientKeyPath = clientKeyPath;
        this.clientKeyPassword = clientKeyPassword;
        this.trustStorePath = trustStorePath;
        this.trustStorePassword = trustStorePassword;
    }

    public String getServerUrl() {
        return serverUrl;
    }

    public String getClientCertPath() {
        return clientCertPath;
    }

    public String getClientKeyPath() {
        return clientKeyPath;
    }

    public String getClientKeyPassword() {
        return clientKeyPassword;
    }

    public String getTrustStorePath() {
        return trustStorePath;
    }

    public String getTrustStorePassword() {
        return trustStorePassword;
    }

    /**
     * Check if BFD integration is enabled (server URL is configured and not default).
     */
    public boolean isEnabled() {
        return serverUrl != null && !serverUrl.isEmpty()
                && !serverUrl.equals("https://localhost:1234");
    }

    /**
     * Check if mTLS is configured (client cert and trust store paths are set).
     */
    public boolean isMtlsConfigured() {
        return clientCertPath != null && !clientCertPath.isEmpty()
                && trustStorePath != null && !trustStorePath.isEmpty();
    }

    /**
     * Build an SSLContext for mTLS communication with the BFD server.
     *
     * @return SSLContext configured with client certificate and trust store, or null if not configured
     */
    public SSLContext buildSslContext() {
        if (!isMtlsConfigured()) {
            logger.info("BfdConfiguration::buildSslContext: mTLS not configured, skipping SSL context creation");
            return null;
        }

        try {
            // Load the client keystore (PKCS12 format)
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            try (FileInputStream keyStoreStream = new FileInputStream(clientCertPath)) {
                keyStore.load(keyStoreStream, clientKeyPassword.toCharArray());
            }

            KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(
                    KeyManagerFactory.getDefaultAlgorithm());
            keyManagerFactory.init(keyStore, clientKeyPassword.toCharArray());

            // Load the trust store
            KeyStore trustStore = KeyStore.getInstance("JKS");
            try (FileInputStream trustStoreStream = new FileInputStream(trustStorePath)) {
                trustStore.load(trustStoreStream, trustStorePassword.toCharArray());
            }

            TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            trustManagerFactory.init(trustStore);

            // Build the SSL context
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(keyManagerFactory.getKeyManagers(),
                    trustManagerFactory.getTrustManagers(), null);

            logger.info("BfdConfiguration::buildSslContext: SSL context created successfully");
            return sslContext;
        } catch (Exception e) {
            logger.log(Level.SEVERE, "BfdConfiguration::buildSslContext: Failed to create SSL context", e);
            return null;
        }
    }

    private static String getEnvOrDefault(String key, String defaultValue) {
        String value = System.getenv(key);
        return (value != null && !value.isEmpty()) ? value : defaultValue;
    }
}
