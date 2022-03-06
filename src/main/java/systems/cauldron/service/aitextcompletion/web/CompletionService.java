package systems.cauldron.service.aitextcompletion.web;

import io.helidon.webserver.Routing;
import io.helidon.webserver.ServerRequest;
import io.helidon.webserver.ServerResponse;
import io.helidon.webserver.Service;
import io.opentracing.Span;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import systems.cauldron.completion.CompletionProvider;
import systems.cauldron.completion.config.CompletionRequest;
import systems.cauldron.completion.config.SamplingConfig;
import systems.cauldron.completion.config.TerminationConfig;

import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonReader;
import java.io.ByteArrayInputStream;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.SubmissionPublisher;

public class CompletionService implements Service {

    private final static Logger LOG = LogManager.getLogger(CompletionService.class);

    private final Map<CompletionProvider.Type, CompletionProvider> providers;
    private final String apiSecret;

    public CompletionService(Map<CompletionProvider.Type, CompletionProvider> providers, String apiSecret) {
        this.providers = providers;
        this.apiSecret = apiSecret;
    }

    @Override
    public void update(Routing.Rules rules) {
        rules.post("/complete", this::complete);
    }

    private void complete(ServerRequest request, ServerResponse response) {
        var spanBuilder = request.tracer().buildSpan("complete");
        request.spanContext().ifPresent(spanBuilder::asChildOf);
        Span span = spanBuilder.start();
        request.headers()
                .first("Authorization")
                .ifPresentOrElse(
                        authorizationValue -> {
                            if (!apiSecret.equals(authorizationValue)) {
                                response.status(401).send().thenRun(span::finish);
                            } else {
                                request.content()
                                        .as(byte[].class)
                                        .map(body -> {
                                            try (JsonReader reader = Json.createReader(new ByteArrayInputStream(body))) {
                                                return reader.readObject();
                                            }
                                        })
                                        .thenAccept(jsonRequest -> handleRequest(jsonRequest).ifPresentOrElse(
                                                publisher -> response.send(publisher, String.class),
                                                () -> response.status(400).send()
                                        ))
                                        .exceptionallyAccept(throwable -> {
                                            LOG.error("exception while handling completion request", throwable);
                                            response.status(500);
                                        })
                                        .thenRun(span::finish);
                            }
                        },
                        () -> response.status(401).send().thenRun(span::finish)
                );
    }

    private Optional<SubmissionPublisher<String>> handleRequest(JsonObject jsonRequest) {
        CompletionProvider.Type providerType;
        String prompt;
        int maxTokens;
        double temperature;
        try {
            String providerTypeValue = jsonRequest.getString("provider");
            providerType = CompletionProvider.Type.valueOf(providerTypeValue);
            prompt = jsonRequest.getString("prompt");
            maxTokens = jsonRequest.getInt("maxTokens");
            temperature = jsonRequest.getJsonNumber("temperature").doubleValue();
        } catch (Exception ex) {
            LOG.warn("received invalid request: {}", ex.getMessage());
            return Optional.empty();
        }
        TerminationConfig terminationConfig = new TerminationConfig(maxTokens, new String[]{"\n"});
        SamplingConfig samplingConfig = new SamplingConfig(temperature, 1.0);
        CompletionRequest completionRequest = new CompletionRequest(prompt, terminationConfig, samplingConfig);
        CompletionProvider provider = providers.get(providerType);
        SubmissionPublisher<String> publisher = new SubmissionPublisher<>();
        provider.complete(completionRequest, publisher);
        return Optional.of(publisher);
    }
}
