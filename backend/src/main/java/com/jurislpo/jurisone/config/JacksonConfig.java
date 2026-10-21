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
        public void serialize(Double value, JsonGenerator generator, SerializerProvider provider) throws IOException {
            if (value == null) {
                generator.writeNull();
            } else if (!value.isNaN() && !value.isInfinite() && value == Math.rint(value) && Math.abs(value) < 1e15) {
                generator.writeNumber(value.longValue());
            } else {
                generator.writeNumber(value);
            }
        }
    }

    public static SimpleModule jsNumbers() {
        SimpleModule module = new SimpleModule("js-numbers");
        module.addSerializer(Double.class, new JsDouble());
        module.addSerializer(double.class, new JsDouble());
        return module;
    }

    @Bean
    Jackson2ObjectMapperBuilderCustomizer jsNumberCustomizer() {
        return b -> b.modulesToInstall(jsNumbers());
    }
}
