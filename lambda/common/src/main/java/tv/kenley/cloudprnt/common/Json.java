package tv.kenley.cloudprnt.common;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import software.amazon.smithy.java.core.error.ModeledException;
import software.amazon.smithy.java.core.schema.SerializableShape;
import software.amazon.smithy.java.core.schema.SerializableStruct;
import software.amazon.smithy.java.core.schema.ShapeBuilder;
import software.amazon.smithy.java.core.schema.ValidationError;
import software.amazon.smithy.java.core.schema.Validator;
import software.amazon.smithy.java.framework.model.ValidationException;
import software.amazon.smithy.java.framework.model.ValidationExceptionField;
import software.amazon.smithy.java.json.JsonCodec;

/**
 * JSON bodies in the generated types, checked against the model's constraints, and modelled errors
 * as their HTTP status, an {@code X-Amzn-Errortype} header and a {@code message}. Every response
 * has {@code Cache-Control: no-store}.
 */
public final class Json {
  private static final JsonCodec CODEC =
      JsonCodec.builder().useJsonName(true).useTimestampFormat(true).build();

  private static final Validator VALIDATOR = Validator.builder().build();

  private Json() {}

  /**
   * Decodes {@code body} into {@code builder}, lets {@code bind} set members bound to the path,
   * query or headers, and validates the result.
   *
   * @throws ValidationException if the body isn't valid JSON for the shape, or a constraint fails
   */
  public static <T extends SerializableShape, B extends ShapeBuilder<T>> T decode(
      byte[] body, B builder, java.util.function.Consumer<? super B> bind) {
    byte[] json = body.length == 0 ? "{}".getBytes(StandardCharsets.UTF_8) : body;
    T shape;
    try {
      builder.deserialize(CODEC.createDeserializer(json));
      bind.accept(builder);
      shape = builder.build();
    } catch (RuntimeException e) {
      throw ValidationException.builder()
          .message("The request isn't valid for this operation.")
          .build();
    }
    return validate(shape);
  }

  /** Builds and validates a shape with no body. */
  public static <T extends SerializableShape, B extends ShapeBuilder<T>> T bind(
      B builder, java.util.function.Consumer<? super B> bind) {
    return decode(new byte[0], builder, bind);
  }

  /**
   * {@code shape}, if it meets the model's constraints.
   *
   * @throws ValidationException listing the failures
   */
  public static <T extends SerializableShape> T validate(T shape) {
    List<ValidationError> errors = VALIDATOR.validate(shape);
    if (errors.isEmpty()) {
      return shape;
    }
    throw ValidationException.builder()
        .message(errors.size() + " validation error(s): " + errors.getFirst().message())
        .fieldList(
            errors.stream()
                .map(error ->
                    ValidationExceptionField.builder()
                        .path(error.path())
                        .message(error.message())
                        .build())
                .toList())
        .build();
  }

  /** {@code status} with {@code body} as JSON. */
  public static HttpResponse ok(int status, SerializableStruct body) {
    return new HttpResponse(status, headers(Map.of()), bytes(CODEC.serializeToString(body)));
  }

  /** A response with no body. */
  public static HttpResponse empty(int status) {
    return new HttpResponse(status, headers(Map.of()), new byte[0]);
  }

  /** A modelled error: its {@code @httpError} status, error type header and members. */
  public static HttpResponse error(ModeledException e) {
    return error(e, Map.of());
  }

  /** A modelled error with extra headers. */
  public static HttpResponse error(ModeledException e, Map<String, String> extraHeaders) {
    Map<String, String> extra = new TreeMap<>(extraHeaders);
    extra.put("X-Amzn-Errortype", errorType(e));
    return new HttpResponse(
        ModeledException.getHttpStatusCode(e.schema()),
        headers(extra),
        bytes(CODEC.serializeToString(e)));
  }

  /** 404 for a method and path that aren't an operation. */
  public static HttpResponse unknownOperation() {
    return new HttpResponse(
        404,
        headers(Map.of("X-Amzn-Errortype", "UnknownOperationException")),
        bytes("{\"message\":\"Not found.\"}"));
  }

  /** The error's shape name, as in {@code X-Amzn-Errortype}. */
  public static String errorType(ModeledException e) {
    return e.schema().id().getName();
  }

  private static byte[] bytes(String s) {
    return s.getBytes(StandardCharsets.UTF_8);
  }

  private static Map<String, String> headers(Map<String, String> extra) {
    Map<String, String> headers = new TreeMap<>(extra);
    headers.put("Content-Type", "application/json");
    headers.put("Cache-Control", "no-store");
    return Map.copyOf(headers);
  }
}
