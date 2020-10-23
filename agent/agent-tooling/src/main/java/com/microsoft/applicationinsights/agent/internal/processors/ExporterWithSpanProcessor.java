package com.microsoft.applicationinsights.agent.internal.processors;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import com.microsoft.applicationinsights.agent.bootstrap.configuration.InstrumentationSettings.ProcessorConfig;
import com.microsoft.applicationinsights.agent.internal.processors.AgentProcessor.IncludeExclude;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;

public class ExporterWithSpanProcessor implements SpanExporter {

    private final SpanExporter delegate;
    private final SpanProcessor spanProcessor;

    // caller should check config.isValid before creating
    public ExporterWithSpanProcessor(ProcessorConfig config, SpanExporter delegate) {
        if (!config.isValid()) {
            throw new IllegalArgumentException("User provided span processor config is not valid!!!");
        }
        spanProcessor = SpanProcessor.create(config);
        this.delegate = delegate;
    }

    @Override
    public CompletableResultCode export(Collection<SpanData> spans) {
        // we need to filter attributes before passing on to delegate
        if (!spanProcessor.hasValidConfig()) {
            return delegate.export(spans);
        } else {
            List<SpanData> copy = new ArrayList<>();
            for (SpanData span : spans) {
                copy.add(process(span));
            }
            return delegate.export(copy);
        }
    }

    private SpanData process(SpanData span) {
        IncludeExclude include = spanProcessor.getInclude();
        if (include != null && !include.isMatch(span)) {
            //If Not included we can skip further processing
            return span;
        }
        IncludeExclude exclude = spanProcessor.getExclude();
        if (exclude != null && exclude.isMatch(span)) {
            return span;
        }
        // performing insert last, since no need to apply other actions to those inserted attributes
        SpanData updatedSpan = spanProcessor.processFromAttributes(span);
        return spanProcessor.processToAttributes(updatedSpan);
    }

    @Override
    public CompletableResultCode flush() {
        return delegate.flush();
    }

    @Override
    public CompletableResultCode shutdown() {
        return delegate.shutdown();
    }
}
