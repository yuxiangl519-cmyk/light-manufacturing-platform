package io.github.lightmanufacturing.ai.controller;

import io.github.lightmanufacturing.ai.application.AiToolGateway;
import io.github.lightmanufacturing.ai.dto.AiDtos.Query;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/ai/tools")
public class AiInternalController {
  private final AiToolGateway gateway;
  public AiInternalController(AiToolGateway gateway){this.gateway=gateway;}
  @PostMapping("/{name}") public Object execute(@RequestHeader("X-AI-Context") String token,@PathVariable String name,@Valid @RequestBody Query query){return gateway.execute(token,name,query);}
}
