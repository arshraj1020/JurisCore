package com.juriscore.app.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.math.BigDecimal;

/**
 * JurisCore's JSON contract for decimals.
 *
 * <p>Every {@link BigDecimal} leaves this API as a JSON <em>string</em>, never a number.
 *
 * <p>The reason is the client, and it is not a matter of taste. JSON numbers are read by
 * JavaScript as IEEE-754 doubles, so a total the server computed exactly as
 * {@code 11800.00} arrives in a browser as a binary approximation, and an eight-line
 * invoice accumulates enough drift to display a figure a paisa away from the one that
 * will be billed. Scale is lost with it: {@code 0.10} and {@code 0.1} are the same double
 * but not the same money, and {@code 2.500} hours is not {@code 2.5} on a printed
 * invoice. A string crosses the wire with the exact digits the server decided on, and the
 * frontend's decimal handling — which is written against strings on purpose — keeps them.
 *
 * <p>{@link BigDecimal#toPlainString()} rather than {@code toString()}: the latter emits
 * scientific notation for some scales ({@code 1E+2}), which is valid JSON and useless to
 * a client formatting currency.
 *
 * <p>Only serialization is affected. Jackson already reads a {@code BigDecimal} from
 * either a JSON number or a JSON string, so every existing request body — and every
 * existing client sending one — continues to work unchanged.
 */
@Configuration
public class JacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer decimalsAsStrings() {
        SimpleModule module = new SimpleModule("juriscore-decimals");
        module.addSerializer(BigDecimal.class, new PlainStringBigDecimalSerializer());
        return builder -> builder.modulesToInstall(module);
    }

    static final class PlainStringBigDecimalSerializer extends JsonSerializer<BigDecimal> {

        @Override
        public void serialize(BigDecimal value, JsonGenerator generator, SerializerProvider provider)
                throws IOException {
            generator.writeString(value.toPlainString());
        }
    }
}
