package io.github.lightmanufacturing;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import io.github.lightmanufacturing.system.dto.ApiDtos.UserRoles;
import org.junit.jupiter.api.Test;

class StrictJsonTest {
  @Test
  void numericIdsCannotBeSilentlyCoercedToStrings() {
    ObjectMapper mapper = JsonConfiguration.strict(new ObjectMapper());
    assertThrows(
        Exception.class,
        () -> mapper.readValue("{\"version\":0,\"roleIds\":[12]}", UserRoles.class));
  }

  @Test
  void unknownPropertiesAreRejected() {
    ObjectMapper mapper = JsonConfiguration.strict(new ObjectMapper());
    assertThrows(
        Exception.class,
        () ->
            mapper.readValue(
                "{\"version\":0,\"roleIds\":[],\"permissions\":[\"root\"]}", UserRoles.class));
  }

  @Test
  void stringIdsAndNumericVersionRemainValid() throws Exception {
    var body =
        JsonConfiguration.strict(new ObjectMapper())
            .readValue("{\"version\":0,\"roleIds\":[\"12\"]}", UserRoles.class);
    assertEquals("12", body.roleIds().get(0));
  }
}
