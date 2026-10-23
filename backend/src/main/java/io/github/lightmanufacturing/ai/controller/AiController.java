package io.github.lightmanufacturing.ai.controller;

import io.github.lightmanufacturing.Policy;
import io.github.lightmanufacturing.ai.application.*;
import io.github.lightmanufacturing.ai.dto.AiDtos.*;
import io.github.lightmanufacturing.system.security.SessionPrincipal;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/ai/conversations")
@PreAuthorize("hasAnyAuthority('erp:sales:read','wms:stock:read','mes:production:read','wms:document:read')")
public class AiController {
  private final AiQuotaService quota;private final AiConversationService service;private final AiStreamService streams;
  public AiController(AiConversationService service,AiStreamService streams,AiQuotaService quota){this.quota=quota;this.service=service;this.streams=streams;}
  @GetMapping("/quota") public AiQuotaService.Quota quota(@AuthenticationPrincipal SessionPrincipal p){return quota.quota(p.id());}
  @GetMapping("/calls") public List<AiQuotaService.Call> calls(@AuthenticationPrincipal SessionPrincipal p){return quota.calls(p.id());}
  @GetMapping public List<Conversation> list(@AuthenticationPrincipal SessionPrincipal p){return service.list(p.id());}
  @PostMapping @ResponseStatus(HttpStatus.CREATED) public Conversation create(@AuthenticationPrincipal SessionPrincipal p,@Valid @RequestBody Create body){return service.create(p.id(),body.title());}
  @GetMapping("/{id}") public Detail detail(@AuthenticationPrincipal SessionPrincipal p,@PathVariable String id){return service.detail(Policy.id(id),p.id());}
  @PostMapping(value="/{id}/messages",produces=MediaType.TEXT_EVENT_STREAM_VALUE)
  public ResponseEntity<SseEmitter> send(@AuthenticationPrincipal SessionPrincipal p,@PathVariable String id,@Valid @RequestBody Send body){return ResponseEntity.ok().header("Cache-Control","no-cache, no-store").header("X-Accel-Buffering","no").body(streams.start(Policy.id(id),p,body.message()));}
  @PostMapping("/{id}/cancel") @ResponseStatus(HttpStatus.NO_CONTENT) public void cancel(@AuthenticationPrincipal SessionPrincipal p,@PathVariable String id){streams.cancel(Policy.id(id),p.id());}
}
