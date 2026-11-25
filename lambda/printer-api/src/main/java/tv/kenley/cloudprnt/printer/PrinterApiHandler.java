package tv.kenley.cloudprnt.printer;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import java.time.Clock;
import tv.kenley.cloudprnt.common.LambdaEvents;
import tv.kenley.cloudprnt.common.Printers;
import tv.kenley.cloudprnt.common.Settings;

/**
 * The Lambda entry point for the CloudPRNT endpoint, behind an API Gateway HTTP API. Its handler
 * string is {@code tv.kenley.cloudprnt.printer.PrinterApiHandler::handleRequest}.
 */
public final class PrinterApiHandler
    implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {
  private final PrinterApi api;

  /** The handler Lambda creates once per execution environment, configured from the environment. */
  public PrinterApiHandler() {
    this(fromEnvironment());
  }

  PrinterApiHandler(PrinterApi api) {
    this.api = api;
  }

  @Override
  public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
    return LambdaEvents.response(api.handle(LambdaEvents.request(event, context)));
  }

  private static PrinterApi fromEnvironment() {
    Settings settings = Settings.from(System.getenv());
    var db = Settings.dynamoDb();
    Printers printers = settings.printers(db);
    CloudPrntService service =
        new CloudPrntService(
            printers, settings.jobs(db), settings.contents(Settings.s3()), Clock.systemUTC());
    return new PrinterApi(printers, service, System.out::println, System.err::println);
  }
}
