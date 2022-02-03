package systems.cauldron.service.aitextcompletion;

import io.helidon.common.LogConfig;
import io.helidon.config.Config;
import io.helidon.health.HealthSupport;
import io.helidon.health.checks.HealthChecks;
import io.helidon.media.jsonp.JsonpSupport;
import io.helidon.metrics.prometheus.PrometheusSupport;
import io.helidon.webserver.Routing;
import io.helidon.webserver.Service;
import io.helidon.webserver.WebServer;
import io.helidon.webserver.cors.CorsSupport;
import io.helidon.webserver.cors.CrossOriginConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import systems.cauldron.completion.CompletionProvider;
import systems.cauldron.service.aitextcompletion.web.CompletionService;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import java.util.stream.Collectors;

public class Server {

    private final static Logger LOG = LogManager.getLogger(Server.class);

    private Server() {
    }

    public static void main(String[] args) {
        start();
    }

    static WebServer start() {
        LogConfig.configureRuntime();

        Config config = Config.create();

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
        CompletionService completionService = new CompletionService(providers, apiSecret);

        Map<String, Service> serviceMap = Map.of("/api/v1", completionService);

        WebServer server = WebServer.builder(getRouting(serviceMap))
                .config(config.get("server"))
                .addMediaSupport(JsonpSupport.create())
                .build();
        server.start().thenAccept(s -> {
            LOG.info("server started @ http://localhost:" + s.port());
            s.whenShutdown().thenRun(() -> {
                LOG.info("server stopped");
            });
        }).exceptionally(ex -> {
            LOG.error("startup failed", ex);
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

    private static Routing getRouting(Map<String, Service> serviceMap) {
        PrometheusSupport metrics = PrometheusSupport.create();
        HealthSupport health = HealthSupport.builder()
                .addLiveness(HealthChecks.healthChecks())
                .build();
        Routing.Builder routing = Routing.builder()
                .register(health)
                .register(metrics);
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