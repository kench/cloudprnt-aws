package tv.kenley.cloudprnt.common;

import java.util.Optional;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

/** Job content in an S3 bucket. */
public final class S3Contents implements Contents {
  private final S3Client s3;
  private final String bucket;

  /** Content in {@code bucket}. */
  public S3Contents(S3Client s3, String bucket) {
    this.s3 = s3;
    this.bucket = bucket;
  }

  @Override
  public void put(String key, String mediaType, byte[] content) {
    s3.putObject(
        r -> r.bucket(bucket).key(key).contentType(mediaType), RequestBody.fromBytes(content));
  }

  @Override
  public Optional<byte[]> get(String key) {
    try {
      return Optional.of(s3.getObjectAsBytes(r -> r.bucket(bucket).key(key)).asByteArray());
    } catch (NoSuchKeyException e) {
      return Optional.empty();
    }
  }
}
