package io.github.lightmanufacturing.ai.application;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class AiPresentationTest {
 @Test void hidesSecretsSqlAndThoughtsWithoutChangingBusinessAmounts(){
  String text=AiPresentation.text("销售额120.01\n来源sales_order.status=SHIPPED;created_at;deleted_at IS NULL\n<think>private reasoning</think>\nSELECT * FROM sales_order;\nBearer fake-ticket-value\nsk-THIS_IS_A_FAKE_SECRET_KEY\n电话13800138000 demo@example.org");
  assertTrue(text.contains("120.01"));assertTrue(text.contains("销售订单"));
  for(String hidden:new String[]{"sales_order","SELECT","private reasoning","fake-ticket-value","FAKE_SECRET","13800138000","demo@example"})assertFalse(text.contains(hidden),hidden);
 }
 @Test void stagesAreFixedLabelsRatherThanProviderText(){assertEquals("核对证据完整性",AiPresentation.stage("Reflection"));assertEquals("处理查询",AiPresentation.stage("privateReasoning"));}
 @Test void amountsAndIdsAreNotMistakenForPhoneNumbers(){assertEquals("金额13800138000.00；客户ID 13800138000",AiPresentation.text("金额13800138000.00；客户ID 13800138000"));}
}
