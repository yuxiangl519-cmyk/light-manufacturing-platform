package io.github.lightmanufacturing;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.cfg.*;
import com.fasterxml.jackson.databind.type.LogicalType;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.*;

@Configuration
public class JsonConfiguration {
  static ObjectMapper strict(ObjectMapper mapper) {
    mapper.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    mapper
        .coercionConfigFor(LogicalType.Textual)
        .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
        .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
    mapper
        .coercionConfigFor(LogicalType.Integer)
        .setCoercion(CoercionInputShape.String, CoercionAction.Fail)
        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail);
    mapper
        .coercionConfigFor(LogicalType.Boolean)
        .setCoercion(CoercionInputShape.String, CoercionAction.Fail)
        .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail);
    return mapper;
  }

  @Bean
  Jackson2ObjectMapperBuilderCustomizer strictJson() {
    return builder -> builder.postConfigurer(JsonConfiguration::strict);
  }
}
