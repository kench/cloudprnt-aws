// Shared by the Lambdas: API Gateway glue, JSON, IDs, passwords, and the
// DynamoDB and S3 stores.

plugins {
    `java-library`
    `java-test-fixtures`
}

dependencies {
    api(project(":model"))
    api(libs.aws.lambda.core)
    api(libs.aws.lambda.events)
    api(platform(libs.aws.sdk.bom))
    api(libs.aws.sdk.dynamodb) {
        // The JDK's HTTP client (url-connection-client) keeps the package and
        // cold start small.
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
        exclude(group = "software.amazon.awssdk", module = "apache-client")
        exclude(group = "software.amazon.awssdk", module = "apache5-client")
    }
    api(libs.aws.sdk.s3) {
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
        exclude(group = "software.amazon.awssdk", module = "apache-client")
        exclude(group = "software.amazon.awssdk", module = "apache5-client")
    }
    api(libs.aws.sdk.url.connection.client)

    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.junit.jupiter)
}

// Mockito's inline mock maker is a Java agent; load it at startup.
val mockitoAgent = configurations.create("mockitoAgent")
dependencies {
    mockitoAgent(libs.mockito.core) { isTransitive = false }
}
tasks.test {
    jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("-javaagent:${mockitoAgent.asPath}") })
}
