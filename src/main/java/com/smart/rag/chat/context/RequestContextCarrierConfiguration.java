package com.smart.rag.chat.context;
import com.smart.rag.mode.RequestContext;

import com.smart.rag.infrastructure.concurrent.context.ContextCarrier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RequestContextCarrierConfiguration {

    @Bean
    public ContextCarrier<RequestContext> requestContextCarrier() {
        return new ContextCarrier<>() {
            @Override
            public RequestContext capture() {
                return RequestContextHolder.get();
            }

            @Override
            public RequestContext restore(RequestContext snapshot) {
                RequestContext previous = RequestContextHolder.get();
                RequestContextHolder.set(snapshot);
                return previous;
            }

            @Override
            public void clear(RequestContext previous) {
                RequestContextHolder.set(previous);
            }
        };
    }
}
