package io.github.lightmanufacturing.ai.dto;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;

public final class AiDtos {
  private AiDtos() {}
  public record Create(@Size(max=160) String title) {}
  public record Send(@NotBlank @Size(max=4000) String message) {}
  public record Conversation(String id,String title,String status,Instant updatedAt) {}
  public record Message(String id,String role,String content,Instant createdAt) {}
  public record Detail(String id,String title,String status,List<Message> messages,String pendingQuestion,com.fasterxml.jackson.databind.JsonNode context) {}
  public record Query(java.time.LocalDate startDate,java.time.LocalDate endDate,String templateId, String dimension, String entityId, @Size(max=120) String query, Integer limit, String warehouseId, String materialId, String productionOrderId) {
    public Query(java.time.LocalDate startDate,java.time.LocalDate endDate,String templateId,String dimension,String entityId,String query,Integer limit){this(startDate,endDate,templateId,dimension,entityId,query,limit,null,null,null);}
  }
}
