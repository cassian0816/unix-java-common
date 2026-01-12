package exchange.antx.common.logger;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;

public class MetricsLogbackAppender extends AppenderBase<ILoggingEvent> {

    private static final LongCounter LOG_COUNTER = GlobalOpenTelemetry.get()
            .getMeter("antx.common.logger.count")
            .counterBuilder("antx_log_count")
            .setDescription("antx log event count by level and log name")
            .build();

    private static final AttributeKey<String> LOG_LEVEL_KEY = AttributeKey.stringKey("log_level");
    private static final AttributeKey<String> LOG_NAME_KEY = AttributeKey.stringKey("log_name");

    @Override
    protected void append(ILoggingEvent eventObject) {
        LOG_COUNTER.add(
                1L,
                Attributes.of(
                        LOG_LEVEL_KEY, eventObject.getLevel().toString(),
                        LOG_NAME_KEY, eventObject.getLoggerName()));
    }
}
