package systems.cauldron.service.aitextcompletion;

import io.helidon.common.LogConfig;
import io.helidon.config.Config;
import io.helidon.health.HealthSupport;
import io.helidon.health.checks.HealthChecks;
import io.helidon.media.jsonp.JsonpSupport;
import io.helidon.metrics.MetricsSupport;
import io.helidon.webserver.Routing;
import io.helidon.webserver.Service;
import io.helidon.webserver.WebServer;
import io.helidon.webserver.cors.CorsSupport;
import io.helidon.webserver.cors.CrossOriginConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import systems.cauldron.completion.CompletionProvider;
import systems.cauldron.service.aitextcompletion.web.CompletionService;

import java.util.Map;

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

        String providerApiToken = loadRequiredEnvironmentVariable("OPENAI_API_TOKEN");
        String apiSecret = loadRequiredEnvironmentVariable("API_SECRET");

        CompletionProvider provider = CompletionProvider.create(providerApiToken, CompletionProvider.Type.OPENAI_DAVINCI);
        CompletionService completionService = new CompletionService(provider, apiSecret);

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
        MetricsSupport metrics = MetricsSupport.create();
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