package systems.cauldron.service.aitextcompletion.web;

import io.helidon.webserver.Routing;
import io.helidon.webserver.ServerRequest;
import io.helidon.webserver.ServerResponse;
import io.helidon.webserver.Service;
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
import java.util.Optional;
import java.util.concurrent.SubmissionPublisher;

public class CompletionService implements Service {

    private final static Logger LOG = LogManager.getLogger(CompletionService.class);

    private final CompletionProvider provider;
    private final String apiSecret;

    public CompletionService(CompletionProvider provider, String apiSecret) {
        this.provider = provider;
        this.apiSecret = apiSecret;
    }

    @Override
    public void update(Routing.Rules rules) {
        rules.post("/complete", this::complete);
    }

    private void complete(ServerRequest request, ServerResponse response) {
        request.headers()
                .first("Authorization")
                .ifPresentOrElse(
                        authorizationValue -> {
                            if (!apiSecret.equals(authorizationValue)) {
                                response.status(401).send();
                            } else {
                                request.content()
                                        .as(byte[].class)
                                        .map(body -> {
                                            try (JsonReader reader = Json.createReader(new ByteArrayInputStream(body))) {
                                                return reader.readObject();
                                            }
                                        })
                                        .thenAccept(jsonRequest -> buildCompletionRequest(jsonRequest).ifPresentOrElse(
                                                completionRequest -> {
                                                    SubmissionPublisher<String> publisher = new SubmissionPublisher<>();
                                                    provider.complete(completionRequest, publisher);
                                                    response.send(publisher, String.class);
                                                },
                                                () -> response.status(400).send()
                                        ))
                                        .exceptionallyAccept(throwable -> {
                                            LOG.error("exception while handling completion request", throwable);
                                            response.status(500);
                                        });
                            }
                        },
                        () -> response.status(401).send()
                );
    }

    private Optional<CompletionRequest> buildCompletionRequest(JsonObject jsonRequest) {
        String prompt;
        int maxTokens;
        double temperature;
        try {
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
        return Optional.of(completionRequest);
    }
}