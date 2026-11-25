package tv.kenley.cloudprnt.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import java.security.SecureRandom;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import tv.kenley.cloudprnt.common.InMemoryContents;
import tv.kenley.cloudprnt.common.InMemoryJobs;
import tv.kenley.cloudprnt.common.InMemoryPrinters;

final class JobApiHandlerTest {
  @Test
  void convertsTheEventAndResponse() {
    JobApiHandler handler =
        new JobApiHandler(
            new JobApi(
                new JobService(
                    new InMemoryPrinters(),
                    new InMemoryJobs(),
                    new InMemoryContents(),
                    Clock.systemUTC(),
                    new SecureRandom()),
                line -> {},
                line -> {}));
    Context context = mock(Context.class);
    when(context.getAwsRequestId()).thenReturn("req-1");
    APIGatewayV2HTTPEvent event =
        APIGatewayV2HTTPEvent.builder()
            .withRawPath("/v1/printers")
            .withRequestContext(
                APIGatewayV2HTTPEvent.RequestContext.builder()
                    .withHttp(
                        APIGatewayV2HTTPEvent.RequestContext.Http.builder()
                            .withMethod("GET")
                            .withSourceIp("192.0.2.1")
                            .build())
                    .build())
            .build();

    assertEquals(200, handler.handleRequest(event, context).getStatusCode());
  }

  @Test
  void needsItsSettings() {
    assertThrows(IllegalArgumentException.class, JobApiHandler::new);
  }
}
