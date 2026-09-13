package com.zhihu.hackathon;

import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class FallbackErrorControllerTest {
  private final FallbackErrorController controller = new FallbackErrorController();

  @Test void mapsKnownStatusesToEnvelopeWithoutDebugFields() {
    for (int status : new int[]{400, 401, 403, 404, 405, 429, 500, 502}) {
      var request = new MockHttpServletRequest();
      request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, status);
      ResponseEntity<Map<String, Map<String, String>>> response = controller.error(request);
      assertThat(response.getStatusCode().value()).isEqualTo(status);
      assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
      assertThat(response.getBody()).isNotNull().containsKey("error");
      assertThat(response.getBody().get("error")).containsKeys("code", "message");
      assertThat(response.getBody().toString()).doesNotContain("timestamp").doesNotContain("path");
    }
  }

  @Test void missingStatusAttributeDefaultsToInternalError() {
    ResponseEntity<Map<String, Map<String, String>>> response = controller.error(new MockHttpServletRequest());
    assertThat(response.getStatusCode().value()).isEqualTo(500);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().get("error").get("code")).isEqualTo("INTERNAL_ERROR");
  }

  @Test void fourHundredRangeWithoutDedicatedMessageUsesGenericRejection() {
    var request = new MockHttpServletRequest();
    request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 418);
    ResponseEntity<Map<String, Map<String, String>>> response = controller.error(request);
    assertThat(response.getStatusCode().value()).isEqualTo(418);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().get("error").get("code")).isEqualTo("REQUEST_REJECTED");
  }
}
