package systems.cauldron.service.aitextcompletion;

import io.helidon.common.LogConfig;
import io.helidon.config.Config;
import io.helidon.health.HealthSupport;
import io.helidon.health.checks.HealthChecks;
import io.helidon.logging.common.HelidonMdc;
import io.helidon.media.jsonp.JsonpSupport;
import io.helidon.metrics.prometheus.PrometheusSupport;
import io.helidon.tracing.TracerBuilder;
import io.helidon.tracing.config.ComponentTracingConfig;
import io.helidon.tracing.config.SpanTracingConfig;
import io.helidon.tracing.config.TracingConfig;
import io.helidon.webserver.Routing;
import io.helidon.webserver.Service;
import io.helidon.webserver.WebServer;
import io.helidon.webserver.WebTracingConfig;
import io.helidon.webserver.cors.CorsSupport;
import io.helidon.webserver.cors.CrossOriginConfig;
import io.opentracing.Tracer;
import systems.cauldron.completion.CompletionProvider;
import systems.cauldron.service.aitextcompletion.web.CompletionService;

import java.net.URI;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class Server {

    private final static Logger LOG = Logger.getLogger(Server.class.getName());

    private Server() {
    }

    public static void main(String[] args) {
        start();
    }

    static WebServer start() {
        HelidonMdc.clear();
        LogConfig.configureRuntime();
        Config config = Config.create();

        CompletionService completionService = getCompletionService();

        Map<String, Service> serviceMap = Map.of("/api/v1", completionService);

        WebServer.Builder serverBuilder = WebServer.builder(getRouting(serviceMap))
                .config(config.get("server"))
                .addMediaSupport(JsonpSupport.create());
        loadOptionalEnvironmentVariable("TRACES_RECEIVER_URL").ifPresent(url -> {
            Tracer tracer = TracerBuilder.create("ai-text-completion-service")
                    .collectorUri(URI.create(url))
                    .build();
            serverBuilder.tracer(tracer);
        });
        WebServer server = serverBuilder.build();
        server.start().thenAccept(s -> {
            LOG.info("server started @ http://localhost:" + s.port());
            s.whenShutdown().thenRun(() -> {
                LOG.info("server stopped");
            });
        }).exceptionally(ex -> {
            LOG.log(Level.SEVERE, "startup failed", ex);
            return null;
        });
        return server;
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

    private static Routing getRouting(Map<String, Service> serviceMap) {
        PrometheusSupport metrics = PrometheusSupport.create();
        HealthSupport health = HealthSupport.builder()
                .addLiveness(HealthChecks.healthChecks())
                .build();
        WebTracingConfig tracing = WebTracingConfig.create(TracingConfig.builder()
                .addComponent(ComponentTracingConfig.builder("web-server")
                        .addSpan(SpanTracingConfig.builder("HTTP Request").build())
                        .addSpan(SpanTracingConfig.builder("content-read").build())
                        .addSpan(SpanTracingConfig.builder("content-write").build())
                        .build())
                .build());
        Routing.Builder routing = Routing.builder()
                .register(health)
                .register(metrics)
                .register(tracing);
        CorsSupport corsSupport = CorsSupport.builder()
                .addCrossOrigin("/complete", CrossOriginConfig.builder()
                        .allowOrigins("*")
                        .allowMethods("*")
                        .allowHeaders("*")
                        .allowCredentials(true)
                        .enabled(true)
                        .build())
                .build();
        serviceMap.forEach((path, service) -> routing.register(path, corsSupport, service));
        return routing.build();
    }
}