package tv.kenley.cloudprnt.common;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** Converts API Gateway HTTP API (payload 2.0) events to {@link HttpRequest}s and back. */
public final class LambdaEvents {
  private LambdaEvents() {}

  /** The request in {@code event}. */
  public static HttpRequest request(APIGatewayV2HTTPEvent event, Context context) {
    String body = event.getBody() == null ? "" : event.getBody();
    byte[] bytes =
        event.getIsBase64Encoded()
            ? Base64.getDecoder().decode(body)
            : body.getBytes(StandardCharsets.UTF_8);
    Map<String, String> headers = new TreeMap<>();
    if (event.getHeaders() != null) {
      event
          .getHeaders()
          .forEach((name, value) -> headers.put(name.toLowerCase(Locale.ROOT), value));
    }
    APIGatewayV2HTTPEvent.RequestContext requestContext = event.getRequestContext();
    String caller =
        Optional.ofNullable(requestContext.getAuthorizer())
            .map(APIGatewayV2HTTPEvent.RequestContext.Authorizer::getIam)
            .map(APIGatewayV2HTTPEvent.RequestContext.IAM::getUserArn)
            .orElse(null);
    return new HttpRequest(
        requestContext.getHttp().getMethod(),
        event.getRawPath(),
        event.getQueryStringParameters() == null
            ? Map.of()
            : Map.copyOf(event.getQueryStringParameters()),
        Map.copyOf(headers),
        bytes,
        requestContext.getHttp().getSourceIp(),
        context.getAwsRequestId(),
        caller);
  }

  /** {@code response} as an HTTP API response event; bodies other than JSON go as base64. */
  public static APIGatewayV2HTTPResponse response(HttpResponse response) {
    boolean json = "application/json".equals(response.headers().get("Content-Type"));
    return APIGatewayV2HTTPResponse.builder()
        .withStatusCode(response.status())
        .withHeaders(response.headers())
        .withBody(
            json
                ? new String(response.body(), StandardCharsets.UTF_8)
                : Base64.getEncoder().encodeToString(response.body()))
        .withIsBase64Encoded(!json)
        .build();
  }
}
