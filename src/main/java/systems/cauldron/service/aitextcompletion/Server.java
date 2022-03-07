package systems.cauldron.service.aitextcompletion;

import io.helidon.common.LogConfig;
import io.helidon.config.Config;
import io.helidon.health.HealthSupport;
import io.helidon.health.checks.HealthChecks;
import io.helidon.logging.common.HelidonMdc;
import io.helidon.media.jsonp.JsonpSupport;
import io.helidon.metrics.MetricsSupport;
import io.helidon.tracing.TracerBuilder;
import io.helidon.webserver.Routing;
import io.helidon.webserver.WebServer;
import io.helidon.webserver.WebTracingConfig;
import io.helidon.webserver.cors.CorsSupport;
import io.helidon.webserver.cors.CrossOriginConfig;
import io.opentracing.Tracer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.microprofile.health.HealthCheck;
import systems.cauldron.completion.CompletionProvider;
import systems.cauldron.service.aitextcompletion.web.CompletionService;

import java.net.URI;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Optional;
import java.util.stream.Collectors;

public class Server {

    static {
        System.setProperty("java.util.logging.manager", "org.apache.logging.log4j.jul.LogManager");
    }

    private final static Logger LOG = LogManager.getLogger(Server.class);

    private Server() {
    }

    public static void main(String[] args) {
        start();
    }

    static WebServer start() {
        HelidonMdc.clear();
        LogConfig.configureRuntime();
        Config config = Config.create();
        WebServer.Builder serverBuilder = WebServer.builder(getRouting(config))
                .config(config.get("server"))
                .addMediaSupport(JsonpSupport.create());
        loadOptionalEnvironmentVariable("ZIPKIN_ENDPOINT").ifPresent(endpoint -> {
            Tracer tracer = TracerBuilder.create("ai-text-completion-service")
                    .config(config.get("tracing"))
                    .collectorUri(URI.create(endpoint))
                    .registerGlobal(true)
                    .build();
            serverBuilder.tracer(tracer);
        });
        WebServer server = serverBuilder.build();
        server.start().thenAccept(s -> {
            LOG.info("server started on port {}", s.port());
            s.whenShutdown().thenRun(() -> {
                LOG.info("server stopped");
            });
        }).exceptionally(ex -> {
            LOG.error("server startup failed", ex);
            return null;
        });
        return server;
    }

    private static String loadRequiredEnvironmentVariable(String environmentVariable) {
        String apiToken = System.getenv(environmentVariable);
        if (apiToken == null) {
            throw new IllegalArgumentException("missing required environment variable: " + environmentVariable);
        }
        return apiToken;
    }

    private static Optional<String> loadOptionalEnvironmentVariable(String environmentVariable) {
        return Optional.ofNullable(System.getenv(environmentVariable));
    }

    private static Routing getRouting(Config config) {
        HealthCheck[] healthChecks = HealthChecks.healthChecks(config.get("health.checks"));
        HealthSupport health = HealthSupport.builder()
                .config(config.get("health"))
                .addReadiness(healthChecks)
                .addLiveness(healthChecks)
                .build();
        MetricsSupport metrics = MetricsSupport.create(config.get("metrics"));
        WebTracingConfig tracing = WebTracingConfig.create(config.get("tracing"));
        CorsSupport corsSupport = CorsSupport.builder()
                .addCrossOrigin("/complete", CrossOriginConfig.builder()
                        .allowOrigins("*")
                        .allowMethods("*")
                        .allowHeaders("*")
                        .allowCredentials(true)
                        .enabled(true)
                        .build())
                .build();
        Routing.Builder routing = Routing.builder()
                .register(health)
                .register(metrics)
                .register(tracing)
                .register("/api/v1", corsSupport, getCompletionService());
        return routing.build();
    }

    private static CompletionService getCompletionService() {
        String openAiApiToken = loadRequiredEnvironmentVariable("OPENAI_API_TOKEN");
        String ai21ApiToken = loadRequiredEnvironmentVariable("AI21_API_TOKEN");
        String gooseAiApiToken = loadRequiredEnvironmentVariable("GOOSEAI_API_TOKEN");
        String apiSecret = loadRequiredEnvironmentVariable("API_SECRET");
        EnumMap<CompletionProvider.Type, CompletionProvider> providers = Arrays.stream(CompletionProvider.Type.values())
                .collect(Collectors.toMap(
                        providerType -> providerType,
                        providerType -> {
                            String apiToken = switch (providerType) {
                                case OPENAI_DAVINCI, OPENAI_CURIE, OPENAI_BABBAGE, OPENAI_ADA -> openAiApiToken;
                                case AI21_J1_LARGE, AI21_J1_JUMBO -> ai21ApiToken;
                                case GOOSEAI_GPT_J_6B, GOOSEAI_GPT_NEO_20B, GOOSEAI_GPT_NEO_2_7B, GOOSEAI_GPT_NEO_1_3B, GOOSEAI_GPT_NEO_125M, GOOSEAI_FAIRSEQ_13B, GOOSEAI_FAIRSEQ_6_7B, GOOSEAI_FAIRSEQ_2_7B, GOOSEAI_FAIRSEQ_1_3B, GOOSEAI_FAIRSEQ_125M -> gooseAiApiToken;
                            };
                            return CompletionProvider.create(apiToken, providerType);
                        },
                        (a, b) -> {
                            throw new AssertionError();
                        },
                        () -> new EnumMap<>(CompletionProvider.Type.class)
                ));
        return new CompletionService(providers, apiSecret);
    }
}