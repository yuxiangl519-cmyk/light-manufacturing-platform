package io.github.lightmanufacturing.system.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.github.lightmanufacturing.system.repository.*;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

class AuditServiceTest {
  @Test
  void failureAuditSurvivesRequestContextAlreadyBeingCleared() {
    var logs = mock(LogRepository.class);
    var request = mock(HttpServletRequest.class);
    when(request.getRemoteAddr()).thenThrow(new IllegalStateException("No thread-bound request"));
    var audit = new AuditService(logs, mock(UserRepository.class), request);
    assertDoesNotThrow(() -> audit.failure("WRITE", "HTTP_403"));
    verify(logs).save(argThat(log -> !log.getSuccess() && log.getErrorCode().equals("HTTP_403")));
  }
}
