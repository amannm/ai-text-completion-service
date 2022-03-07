package systems.cauldron.service.aitextcompletion.web;

import io.helidon.logging.common.HelidonMdc;
import io.helidon.webserver.Routing;
import io.helidon.webserver.ServerRequest;
import io.helidon.webserver.ServerResponse;
import io.helidon.webserver.Service;
import io.opentracing.Span;
import io.opentracing.Tracer;
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
        Span span = startSpan(request, "complete");
        request.headers()
                .first("Authorization")
                .ifPresentOrElse(
                        authorizationValue -> {
                            if (!apiSecret.equals(authorizationValue)) {
                                response.status(401).send()
                                        .thenRun(() -> endSpan(span));
                            } else {
                                request.content()
                                        .as(byte[].class)
                                        .map(body -> {
                                            try (JsonReader reader = Json.createReader(new ByteArrayInputStream(body))) {
                                                return reader.readObject();
                                            }
                                        })
                                        .thenAccept(jsonRequest -> {
                                            var result = handleRequest(jsonRequest);
                                            result.ifPresentOrElse(
                                                    publisher -> {
                                                        response.send(publisher, String.class);
                                                    },
                                                    () -> {
                                                        response.status(400).send();
                                                    }
                                            );
                                        })
                                        .exceptionallyAccept(throwable -> {
                                            LOG.error("exception while handling completion request", throwable);
                                            response.status(500);
                                        })
                                        .thenRun(() -> endSpan(span));
                            }
                        },
                        () -> response.status(401).send()
                                .thenRun(() -> endSpan(span))
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
        LOG.info("starting completion request");
        provider.complete(completionRequest, publisher);
        LOG.info("completion request complete");
        return Optional.of(publisher);
    }

    private static Span startSpan(ServerRequest request, String name) {
        Tracer.SpanBuilder spanBuilder = request.tracer().buildSpan(name);
        request.spanContext().ifPresent(spanBuilder::asChildOf);
        Span span = spanBuilder.start();
        HelidonMdc.set("traceId", span.context().toTraceId());
        HelidonMdc.set("spanId", span.context().toSpanId());
        return span;
    }

    private static void endSpan(Span span) {
        span.finish();
        HelidonMdc.remove("traceId");
        HelidonMdc.remove("spanId");
    }
}
