module systems.cauldron.service.aitextcompletion {
    requires systems.cauldron.completion;
    requires io.helidon.webserver.cors;
    requires io.helidon.metrics;
    requires io.helidon.metrics.prometheus;
    requires io.helidon.tracing;
    requires io.helidon.tracing.jaeger;
    requires io.helidon.health.checks;
    requires io.helidon.health;
    requires io.helidon.media.jsonp;
    requires org.apache.logging.log4j;
    exports systems.cauldron.service.aitextcompletion;
}