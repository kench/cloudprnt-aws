package tv.kenley.cloudprnt.common;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

final class S3ContentsTest {
  private final S3Client s3 = mock(S3Client.class, CALLS_REAL_METHODS);
  private final S3Contents contents = new S3Contents(s3, "bucket");

  @Test
  void putStoresTheContentWithItsType() {
    doReturn(PutObjectResponse.builder().build())
        .when(s3)
        .putObject(any(PutObjectRequest.class), any(RequestBody.class));

    contents.put("jobs/a/b", "text/plain", new byte[] {1, 2});

    ArgumentCaptor<PutObjectRequest> request = ArgumentCaptor.forClass(PutObjectRequest.class);
    verify(s3).putObject(request.capture(), any(RequestBody.class));
    assertEquals("bucket", request.getValue().bucket());
    assertEquals("jobs/a/b", request.getValue().key());
    assertEquals("text/plain", request.getValue().contentType());
  }

  @Test
  void getReturnsTheBytes() {
    doReturn(ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), new byte[] {1, 2}))
        .when(s3)
        .getObjectAsBytes(any(GetObjectRequest.class));

    assertArrayEquals(new byte[] {1, 2}, contents.get("jobs/a/b").orElseThrow());
  }

  @Test
  void missingObjectIsEmpty() {
    doThrow(NoSuchKeyException.builder().build())
        .when(s3)
        .getObjectAsBytes(any(GetObjectRequest.class));

    assertEquals(Optional.empty(), contents.get("jobs/a/b"));
  }
}
