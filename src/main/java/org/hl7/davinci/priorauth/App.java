package org.hl7.davinci.priorauth;

import org.hl7.davinci.ruleutils.ModelResolver;
import org.hl7.davinci.priorauth.authorization.AuthUtils;
import org.hl7.davinci.priorauth.bfd.BfdClientService;
import org.hl7.davinci.priorauth.bfd.BfdConfiguration;
import org.hl7.davinci.rules.PriorAuthRule;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import ca.uhn.fhir.context.FhirContext;

/**
 * The Da Vinci Prior Authorization Reference Implementation microservice is
 * launched with this App.
 */
@SpringBootApplication
public class App {

  /**
   * HAPI FHIR Context. HAPI FHIR warns that the context creation is expensive,
   * and should be performed per-application, not per-record.
   */
  private static final FhirContext FHIR_CTX = FhirContext.forR4();

  /**
   * Create a singular model resolver to be used throughout the application
   */
  private static final ModelResolver MODEL_RESOLVER = new ModelResolver(FHIR_CTX);

  /**
   * Local database for FHIR resources.
   */
  private static Database DB;

  /**
   * Shared BFD client service instance. Created once at startup to avoid
   * expensive FhirContext creation per request.
   */
  private static BfdClientService bfdClientService;

  private static boolean debugMode = false;

  private static String baseUrl;

  /**
   * Launch the Prior Authorization microservice.
   * 
   * @param args - ignored.
   */
  public static void main(String[] args) {
    if ((args.length > 0 && args[0].equalsIgnoreCase("debug")) || 
      (System.getenv("debug") != null && System.getenv("debug").equalsIgnoreCase("true"))) {
        debugMode = true;
    }
    // Set the DB
    initializeAppDB();
    // Assemble the microservice
    SpringApplication server = new SpringApplication(App.class);
    server.run(args);

  }

  public static void initializeAppDB() {
    if (DB == null) {
      DB = new Database();
      PriorAuthRule.populateRulesTable();
      AuthUtils.populateClientTable();
      ClaimResponseFactory.initializeRequestMapping();
      initializeBfdClient();
    }
  }

  /**
   * Initialize the shared BFD client service if BFD integration is enabled.
   */
  private static void initializeBfdClient() {
    try {
      BfdConfiguration bfdConfig = new BfdConfiguration();
      if (bfdConfig.isEnabled()) {
        bfdClientService = new BfdClientService(bfdConfig);
        bfdClientService.initialize();
      }
    } catch (Exception e) {
      // BFD integration is optional; log and continue
      PALogger.getLogger().warning("App::initializeBfdClient: Failed to initialize BFD client: " + e.getMessage());
    }
  }

  /**
   * Get the shared BFD client service, or null if BFD is not enabled.
   */
  public static BfdClientService getBfdClientService() {
    return bfdClientService;
  }

  public static Database getDB() {
    return DB;
  }

  /**
   * Set the base URI for the microservice. This is necessary so
   * Bundle.entry.fullUrl data is accurately populated.
   * 
   * @param base - from FhirUtils.setServiceBaseUrl(HttpServletRequest)
   */
  public static void setBaseUrl(String base) {
    baseUrl = base;
  }

  /**
   * Get the base URL for the microservice
   * 
   * @return the base url
   */
  public static String getBaseUrl() {
    return baseUrl;
  }

  /**
   * Get the FHIR Context for R4
   * 
   * @return the R4 fhir context
   */
  public static FhirContext getFhirContext() {
    return FHIR_CTX;
  }

  /**
   * Get the Model Resolver (for R4)
   * 
   * @return the FhirModelResolver
   */
  public static ModelResolver getModelResolver() {
    return MODEL_RESOLVER;
  }

  /**
   * Check if debugMode is enabled
   * 
   * @return true if debugMode is true, false otherwise
   */
  public static boolean isDebugModeEnabled() {
    return debugMode;
  }
}
