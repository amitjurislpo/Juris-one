package com.jurislpo.jurisone.security;

import ch.qos.logback.classic.pattern.ThrowableProxyConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/** The stack trace of a log line (logback word {@code maskedException}, see logback-spring.xml) with personal data in exception messages hidden. */
public class MaskedThrowableConverter extends ThrowableProxyConverter {
    @Override
    public String convert(ILoggingEvent event) {
        return LogMasking.mask(super.convert(event));
    }
}
