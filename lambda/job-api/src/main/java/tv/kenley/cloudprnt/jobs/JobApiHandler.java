package tv.kenley.cloudprnt.jobs;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import java.security.SecureRandom;
import java.time.Clock;
import tv.kenley.cloudprnt.common.LambdaEvents;
import tv.kenley.cloudprnt.common.Settings;

/**
 * The Lambda entry point for the print-job API, behind an API Gateway HTTP API. Its handler string
 * is {@code tv.kenley.cloudprnt.jobs.JobApiHandler::handleRequest}.
 */
public final class JobApiHandler
    implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {
  private final JobApi api;

  /** The handler Lambda creates once per execution environment, configured from the environment. */
  public JobApiHandler() {
    this(fromEnvironment());
  }

  JobApiHandler(JobApi api) {
    this.api = api;
  }

  @Override
  public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
    return LambdaEvents.response(api.handle(LambdaEvents.request(event, context)));
  }

  private static JobApi fromEnvironment() {
    Settings settings = Settings.from(System.getenv());
    var db = Settings.dynamoDb();
    JobService service =
        new JobService(
            settings.printers(db),
            settings.jobs(db),
            settings.contents(Settings.s3()),
            Clock.systemUTC(),
            new SecureRandom());
    return new JobApi(service, System.out::println, System.err::println);
  }
}
