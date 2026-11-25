package tv.kenley.cloudprnt.common;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class LambdaEventsTest {
  private static Context context() {
    Context context = mock(Context.class);
    when(context.getAwsRequestId()).thenReturn("req-1");
    return context;
  }

  private static APIGatewayV2HTTPEvent.RequestContext requestContext(String method) {
    return APIGatewayV2HTTPEvent.RequestContext.builder()
        .withHttp(
            APIGatewayV2HTTPEvent.RequestContext.Http.builder()
                .withMethod(method)
                .withSourceIp("192.0.2.1")
                .build())
        .build();
  }

  @Test
  void readsPlainRequest() {
    APIGatewayV2HTTPEvent event =
        APIGatewayV2HTTPEvent.builder()
            .withRawPath("/cloudprnt")
            .withHeaders(Map.of("Authorization", "Basic x"))
            .withQueryStringParameters(Map.of("mac", "00:11:62:12:34:56"))
            .withBody("{}")
            .withIsBase64Encoded(false)
            .withRequestContext(requestContext("POST"))
            .build();

    HttpRequest request = LambdaEvents.request(event, context());

    assertEquals("POST", request.method());
    assertEquals("/cloudprnt", request.path());
    assertEquals("Basic x", request.header("authorization").orElseThrow());
    assertEquals("00:11:62:12:34:56", request.query().get("mac"));
    assertArrayEquals("{}".getBytes(StandardCharsets.UTF_8), request.body());
    assertEquals("192.0.2.1", request.sourceIp());
    assertEquals("req-1", request.requestId());
    assertNull(request.caller());
  }

  @Test
  void readsBase64BodyAndIamCaller() {
    APIGatewayV2HTTPEvent.RequestContext rc = requestContext("GET");
    rc.setAuthorizer(
        APIGatewayV2HTTPEvent.RequestContext.Authorizer.builder()
            .withIam(
                APIGatewayV2HTTPEvent.RequestContext.IAM
                    .builder()
                    .withUserArn("arn:aws:iam::123456789012:user/pos")
                    .build())
            .build());
    APIGatewayV2HTTPEvent event =
        APIGatewayV2HTTPEvent.builder()
            .withRawPath("/v1/printers")
            .withBody("AQI=")
            .withIsBase64Encoded(true)
            .withRequestContext(rc)
            .build();

    HttpRequest request = LambdaEvents.request(event, context());

    assertArrayEquals(new byte[] {1, 2}, request.body());
    assertEquals("arn:aws:iam::123456789012:user/pos", request.caller());
    assertTrue(request.query().isEmpty());
    assertTrue(request.headers().isEmpty());
  }

  @Test
  void anEmptyBodyIsEmpty() {
    APIGatewayV2HTTPEvent event =
        APIGatewayV2HTTPEvent.builder()
            .withRawPath("/v1/printers")
            .withRequestContext(requestContext("GET"))
            .build();

    assertEquals(0, LambdaEvents.request(event, context()).body().length);
  }

  @Test
  void jsonResponsesAreText() {
    APIGatewayV2HTTPResponse response =
        LambdaEvents.response(
            new HttpResponse(
                200,
                Map.of("Content-Type", "application/json"),
                "{}".getBytes(StandardCharsets.UTF_8)));

    assertEquals(200, response.getStatusCode());
    assertEquals("{}", response.getBody());
    assertFalse(response.getIsBase64Encoded());
  }

  @Test
  void otherResponsesAreBase64() {
    APIGatewayV2HTTPResponse response =
        LambdaEvents.response(
            new HttpResponse(200, Map.of("Content-Type", "image/png"), new byte[] {1, 2}));

    assertEquals("AQI=", response.getBody());
    assertTrue(response.getIsBase64Encoded());
  }
}
