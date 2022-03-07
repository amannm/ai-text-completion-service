module systems.cauldron.service.aitextcompletion {
    requires systems.cauldron.completion;
    requires io.helidon.webserver.cors;
    requires io.helidon.metrics;
    requires io.helidon.tracing;
    requires io.helidon.tracing.zipkin;
    requires io.helidon.health.checks;
    requires io.helidon.health;
    requires io.helidon.media.jsonp;
    requires io.helidon.logging.common;
    requires org.apache.logging.log4j;
    requires org.apache.logging.log4j.core;
    exports systems.cauldron.service.aitextcompletion;
}