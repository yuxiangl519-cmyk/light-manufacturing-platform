package io.github.lightmanufacturing.ai.application;
import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.Map;

class AiContextTest {
  final ObjectMapper json=new ObjectMapper();
  @Test void persistenceAcceptsOnlyBoundedConditionsAndNeverRawHistoryOrResults()throws Exception{
    var input=json.readTree("""
      {"version":1,"domain":"sales","startDate":"2026-10-01","endDate":"2026-10-03","metric":"sales","dimension":"customer","entityId":"11",
       "originalMessage":"销售 13800138000 demo@example.org","gatewayToken":"secret","results":{"amount":"999"},"candidates":[{"phone":"13800138000"}],"confirmedOptions":{"entityId":"11"}}
      """);
    var result=AiContext.clean(input);String text=result.toString();
    assertEquals("11",result.path("entityId").asText());assertEquals("11",result.path("confirmedOptions").path("entityId").asText());
    for(String word:new String[]{"13800138000","demo@example","gatewayToken","results","candidates","originalMessage"})assertFalse(text.contains(word));
    assertTrue(text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=4096);
  }
  @Test void lookupPersistsOnlyPurposeAndLiteralQueryNotCandidateObjects()throws Exception{
    var result=AiContext.clean(json.readTree("""
      {"question":"operation","operation":{"name":"get_current_stock","request":"full private conversation"},"operationsLookup":{"dimension":"warehouse","field":"warehouseId","query":"原料仓","candidate":{"id":"999"}},"operationsArgs":{"materialId":"21"}}
      """));
    assertEquals("原料仓",result.path("operationsLookup").path("query").asText());
    assertFalse(result.toString().contains("full private"));assertFalse(result.toString().contains("candidate"));
  }
  @Test void invalidFieldsAndSensitiveNameQueriesAreDiscarded()throws Exception{
    var result=AiContext.clean(json.readTree("""
      {"entityId":"9223372036854775808","warehouseId":"0","dimension":"supplier","metric":"profit","entityQuery":"demo@example.org","operation":{"name":"delete_stock"}}
      """));
    assertEquals(Map.of("version",1),json.convertValue(result,Map.class));
  }
  @Test void retryKeepsOnlyBoundedNonSensitiveLastFailedQuestion()throws Exception{
    assertEquals("本月销售",AiContext.clean(json.readTree("{\"version\":1,\"retryRequest\":\"本月销售\"}")).path("retryRequest").asText());
    for(String value:new String[]{"x".repeat(401),"demo@example.org","13800138000"}){
      assertFalse(AiContext.clean(json.createObjectNode().put("retryRequest",value)).has("retryRequest"));
    }
  }
}
