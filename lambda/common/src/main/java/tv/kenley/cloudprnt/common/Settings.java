package tv.kenley.cloudprnt.common;

import java.util.Map;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * The Lambdas' settings, from environment variables that the stack sets: {@code PRINTERS_TABLE},
 * {@code JOBS_TABLE} and {@code CONTENT_BUCKET}.
 *
 * @param printersTable the printers table
 * @param jobsTable the jobs table
 * @param contentBucket the job content bucket
 */
public record Settings(String printersTable, String jobsTable, String contentBucket) {

  /**
   * The settings in {@code env}.
   *
   * @throws IllegalArgumentException if one is missing
   */
  public static Settings from(Map<String, String> env) {
    return new Settings(
        required(env, "PRINTERS_TABLE"),
        required(env, "JOBS_TABLE"),
        required(env, "CONTENT_BUCKET"));
  }

  /** The printers, from DynamoDB. */
  public Printers printers(DynamoDbClient db) {
    return new DynamoPrinters(db, printersTable);
  }

  /** The jobs, from DynamoDB. */
  public Jobs jobs(DynamoDbClient db) {
    return new DynamoJobs(db, jobsTable);
  }

  /** The job content, from S3. */
  public Contents contents(S3Client s3) {
    return new S3Contents(s3, contentBucket);
  }

  /** A DynamoDB client on the JDK's HTTP client. */
  public static DynamoDbClient dynamoDb() {
    return DynamoDbClient.builder().httpClient(UrlConnectionHttpClient.create()).build();
  }

  /** An S3 client on the JDK's HTTP client. */
  public static S3Client s3() {
    return S3Client.builder().httpClient(UrlConnectionHttpClient.create()).build();
  }

  private static String required(Map<String, String> env, String name) {
    String value = env.get(name);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must be set");
    }
    return value;
  }
}
