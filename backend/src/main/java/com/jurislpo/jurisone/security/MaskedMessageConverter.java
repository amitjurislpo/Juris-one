package com.jurislpo.jurisone.security;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/** The log line's message (logback word {@code maskedMessage}, see logback-spring.xml) with personal data and credentials hidden. */
public class MaskedMessageConverter extends ClassicConverter {

    @Override
    public String convert(ILoggingEvent event) {
        return LogMasking.mask(event.getFormattedMessage());
    }
}
