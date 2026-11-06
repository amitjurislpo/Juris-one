package com.jurislpo.jurisone.security;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/** The log line's message (logback word {@code maskedMessage}, see logback-spring.xml) with personal data and credentials hidden. */
public class MaskedMessageConverter extends ClassicConverter {
    private static final String MAIL_LOGGER_SUFFIX = ".MailService";
    private static final String DEV_MAILBOX_MARK = "[email:dev]";

    @Override
    public String convert(ILoggingEvent event) {
        String message = event.getFormattedMessage();
        // Local development has no mail server: the mail service prints each email (with its sign-in link) so a developer can read it. Never in sqe, uat or prd.
        boolean isDevMailbox = event.getLoggerName().endsWith(MAIL_LOGGER_SUFFIX) && message != null && message.contains(DEV_MAILBOX_MARK);
        return isDevMailbox ? message : LogMasking.mask(message);
    }
}
