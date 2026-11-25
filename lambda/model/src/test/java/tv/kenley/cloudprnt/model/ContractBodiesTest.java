package tv.kenley.cloudprnt.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import software.amazon.smithy.java.core.error.ModeledException;
import software.amazon.smithy.java.core.schema.SerializableShape;
import software.amazon.smithy.java.core.schema.ShapeBuilder;
import software.amazon.smithy.java.json.JsonCodec;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.knowledge.TopDownIndex;
import software.amazon.smithy.model.node.Node;
import software.amazon.smithy.model.node.ObjectNode;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.ServiceShape;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.traits.HttpHeaderTrait;
import software.amazon.smithy.model.traits.HttpLabelTrait;
import software.amazon.smithy.model.traits.HttpPayloadTrait;
import software.amazon.smithy.model.traits.HttpQueryTrait;
import software.amazon.smithy.protocoltests.traits.HttpMessageTestCase;
import software.amazon.smithy.protocoltests.traits.HttpRequestTestsTrait;
import software.amazon.smithy.protocoltests.traits.HttpResponseTestsTrait;
import tv.kenley.cloudprnt.api.model.JobNotCancellable;
import tv.kenley.cloudprnt.api.model.PrinterExists;
import tv.kenley.cloudprnt.api.model.RegisterPrinterOutput;
import tv.kenley.cloudprnt.protocol.model.NoJob;
import tv.kenley.cloudprnt.protocol.model.Unauthorized;
import tv.kenley.cloudprnt.protocol.model.UnsupportedMediaType;

/**
 * Checks the generated types against the contract: every protocol-test body in the model
 * deserializes into its generated type and serializes back to the same JSON.
 */
final class ContractBodiesTest {
  /** Each service and the Java package its types are generated in. */
  private static final Map<ShapeId, String> SERVICES =
      Map.of(
          ShapeId.from("tv.kenley.cloudprnt.protocol#CloudPrnt"),
          Unauthorized.class.getPackageName(),
          ShapeId.from("tv.kenley.cloudprnt.api#PrintJobs"),
          PrinterExists.class.getPackageName());

  private static final JsonCodec CODEC =
      JsonCodec.builder().useJsonName(true).useTimestampFormat(true).build();

  private static final Model MODEL =
      Model.assembler()
          .discoverModels(ContractBodiesTest.class.getClassLoader())
          .addImport(Path.of(System.getProperty("cloudprnt.contract.model")))
          .assemble()
          .unwrap();

  /** A protocol-test body, the shape it belongs to and the package of its type. */
  private record Case(String id, ShapeId shape, String javaPackage, String body) {}

  @TestFactory
  Stream<DynamicTest> everyProtocolTestBodyRoundTrips() {
    return cases().stream().map(c -> DynamicTest.dynamicTest(c.id(), () -> roundTrip(c)));
  }

  @Test
  void theModelHasProtocolTests() {
    assertFalse(cases().isEmpty());
  }

  @Test
  void errorsKeepTheirHttpStatus() {
    assertEquals(401, ModeledException.getHttpStatusCode(Unauthorized.$SCHEMA));
    assertEquals(404, ModeledException.getHttpStatusCode(NoJob.$SCHEMA));
    assertEquals(415, ModeledException.getHttpStatusCode(UnsupportedMediaType.$SCHEMA));
    assertEquals(409, ModeledException.getHttpStatusCode(PrinterExists.$SCHEMA));
    assertEquals(409, ModeledException.getHttpStatusCode(JobNotCancellable.$SCHEMA));
  }

  @Test
  void passwordsAreRedactedInToString() {
    RegisterPrinterOutput output =
        RegisterPrinterOutput.builder()
            .printer(
                tv.kenley.cloudprnt.api.model.PrinterSummary.builder()
                    .mac("00:11:62:12:34:56")
                    .createdAt(java.time.Instant.EPOCH)
                    .build())
            .password("hunter2-hunter2")
            .build();

    assertFalse(output.toString().contains("hunter2"), output.toString());
  }

  private static List<Case> cases() {
    List<Case> cases = new ArrayList<>();
    TopDownIndex index = TopDownIndex.of(MODEL);
    SERVICES.forEach((id, javaPackage) -> {
      ServiceShape service = MODEL.expectShape(id, ServiceShape.class);
      for (OperationShape operation : index.getContainedOperations(service)) {
        operation
            .getTrait(HttpRequestTestsTrait.class)
            .ifPresent(t -> add(cases, t.getTestCases(), operation.getInputShape(), javaPackage));
        operation
            .getTrait(HttpResponseTestsTrait.class)
            .ifPresent(t ->
                add(
                    cases,
                    t.getTestCases(),
                    payloadOrSelf(operation.getOutputShape()),
                    javaPackage));
      }
    });
    return cases;
  }

  private static void add(
      List<Case> cases, List<? extends HttpMessageTestCase> tests, ShapeId shape, String pkg) {
    for (HttpMessageTestCase test : tests) {
      test.getBody()
          .filter(body -> !body.isBlank())
          .ifPresent(body -> cases.add(new Case(test.getId(), shape, pkg, body)));
    }
  }

  /** The {@code @httpPayload} member's target, which is the whole body, or the structure. */
  private static ShapeId payloadOrSelf(ShapeId structure) {
    Optional<MemberShape> payload =
        MODEL.expectShape(structure).members().stream()
            .filter(member -> member.hasTrait(HttpPayloadTrait.class))
            .findFirst();
    return payload.map(MemberShape::getTarget).orElse(structure);
  }

  private static void roundTrip(Case c) throws ReflectiveOperationException {
    // Generated types are named after their shapes, with a static builder().
    Class<?> type = Class.forName(c.javaPackage() + "." + c.shape().getName());
    ShapeBuilder<?> builder = (ShapeBuilder<?>) type.getMethod("builder").invoke(null);

    SerializableShape shape =
        (SerializableShape)
            CODEC.deserializeShape(c.body().getBytes(StandardCharsets.UTF_8), builder);

    // The codec writes every member; members bound to the path, query or headers aren't body.
    ObjectNode written = Node.parse(CODEC.serializeToString(shape)).expectObjectNode();
    for (MemberShape member : MODEL.expectShape(c.shape()).members()) {
      if (member.hasTrait(HttpLabelTrait.class)
          || member.hasTrait(HttpQueryTrait.class)
          || member.hasTrait(HttpHeaderTrait.class)) {
        written = written.withoutMember(member.getMemberName());
      }
    }
    assertEquals(Node.parse(c.body()), written, c.id());
  }
}
