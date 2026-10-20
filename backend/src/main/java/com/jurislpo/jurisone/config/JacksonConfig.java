package com.jurislpo.jurisone.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import java.io.IOException;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * JSON numbers the way JavaScript writes them: a whole-number double is written as an integer ({@code 1}, not
 * {@code 1.0}). Payslip snapshots, audit rows and API responses then look exactly like the Node app's.
 */
@Configuration
public class JacksonConfig {

    static final class JsDouble extends JsonSerializer<Double> {
        @Override
        public void serialize(Double v, JsonGenerator g, SerializerProvider p) throws IOException {
            if (v == null) {
                g.writeNull();
            } else if (!v.isNaN() && !v.isInfinite() && v == Math.rint(v) && Math.abs(v) < 1e15) {
                g.writeNumber(v.longValue());
            } else {
                g.writeNumber(v);
            }
        }
    }

    public static SimpleModule jsNumbers() {
        SimpleModule m = new SimpleModule("js-numbers");
        m.addSerializer(Double.class, new JsDouble());
        m.addSerializer(double.class, new JsDouble());
        return m;
    }

    @Bean
    Jackson2ObjectMapperBuilderCustomizer jsNumberCustomizer() {
        return b -> b.modulesToInstall(jsNumbers());
    }
}
