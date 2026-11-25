package tv.kenley.cloudprnt.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import software.amazon.smithy.java.framework.model.ValidationException;
import tv.kenley.cloudprnt.api.model.PrinterExists;
import tv.kenley.cloudprnt.api.model.RegisterPrinterInput;
import tv.kenley.cloudprnt.api.model.SubmitPrintJobInput;

final class JsonTest {
  private static byte[] bytes(String s) {
    return s.getBytes(StandardCharsets.UTF_8);
  }

  private static String text(HttpResponse response) {
    return new String(response.body(), StandardCharsets.UTF_8);
  }

  @Test
  void decodesAndBinds() {
    SubmitPrintJobInput input =
        Json.decode(
            bytes("{\"mediaType\": \"text/plain\", \"content\": \"aGk=\"}"),
            SubmitPrintJobInput.builder(),
            b -> b.mac("00:11:62:12:34:56"));

    assertEquals("00:11:62:12:34:56", input.getMac());
    assertEquals("hi", StandardCharsets.UTF_8.decode(input.getContent()).toString());
  }

  @Test
  void ignoresUnknownMembers() {
    RegisterPrinterInput input =
        Json.decode(
            bytes("{\"mac\": \"00:11:62:12:34:56\", \"extra\": 1}"),
            RegisterPrinterInput.builder(),
            b -> {});

    assertEquals("00:11:62:12:34:56", input.getMac());
  }

  @Test
  void checksConstraints() {
    ValidationException e =
        assertThrows(
            ValidationException.class,
            () -> Json.bind(RegisterPrinterInput.builder(), b -> b.mac("not a mac")));

    assertEquals("/mac", e.getFieldList().getFirst().getPath());
  }

  @Test
  void malformedOrIncompleteBodiesAreValidationErrors() {
    assertThrows(
        ValidationException.class,
        () -> Json.decode(bytes("{nope"), RegisterPrinterInput.builder(), b -> {}));
    assertThrows(
        ValidationException.class,
        () -> Json.decode(new byte[0], RegisterPrinterInput.builder(), b -> {}));
  }

  @Test
  void errorsCarryTheirStatusTypeAndExtraHeaders() {
    HttpResponse response =
        Json.error(
            PrinterExists.builder().message("Exists.").build(), java.util.Map.of("X-A", "b"));

    assertEquals(409, response.status());
    assertEquals("PrinterExists", response.headers().get("X-Amzn-Errortype"));
    assertEquals("b", response.headers().get("X-A"));
    assertEquals("no-store", response.headers().get("Cache-Control"));
    assertEquals("{\"message\":\"Exists.\"}", text(response));
  }

  @Test
  void emptyAndUnknownResponses() {
    assertEquals(0, Json.empty(204).body().length);
    HttpResponse unknown = Json.unknownOperation();
    assertEquals(404, unknown.status());
    assertTrue(text(unknown).contains("Not found"));
  }
}
