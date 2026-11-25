package tv.kenley.cloudprnt.printer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import tv.kenley.cloudprnt.common.InMemoryContents;
import tv.kenley.cloudprnt.common.InMemoryJobs;
import tv.kenley.cloudprnt.common.InMemoryPrinters;

final class PrinterApiHandlerTest {
  @Test
  void convertsTheEventAndResponse() {
    InMemoryPrinters printers = new InMemoryPrinters();
    PrinterApiHandler handler =
        new PrinterApiHandler(
            new PrinterApi(
                printers,
                new CloudPrntService(
                    printers, new InMemoryJobs(), new InMemoryContents(), Clock.systemUTC()),
                line -> {},
                line -> {}));
    Context context = mock(Context.class);
    when(context.getAwsRequestId()).thenReturn("req-1");
    APIGatewayV2HTTPEvent event =
        APIGatewayV2HTTPEvent.builder()
            .withRawPath("/cloudprnt")
            .withBody("{}")
            .withRequestContext(
                APIGatewayV2HTTPEvent.RequestContext.builder()
                    .withHttp(
                        APIGatewayV2HTTPEvent.RequestContext.Http.builder()
                            .withMethod("POST")
                            .withSourceIp("192.0.2.1")
                            .build())
                    .build())
            .build();

    assertEquals(401, handler.handleRequest(event, context).getStatusCode());
  }

  @Test
  void needsItsSettings() {
    // The Lambda sets these; without them the handler can't start.
    assertThrows(IllegalArgumentException.class, PrinterApiHandler::new);
  }
}
