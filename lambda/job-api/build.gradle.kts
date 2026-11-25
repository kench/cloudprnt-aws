// The print-job API for applications.

plugins {
    alias(libs.plugins.spdx.sbom)
}

dependencies {
    implementation(project(":common"))

    testImplementation(testFixtures(project(":common")))
    testImplementation(libs.mockito.core)
}

val mockitoAgent = configurations.create("mockitoAgent")
dependencies {
    mockitoAgent(libs.mockito.core) { isTransitive = false }
}
tasks.test {
    jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("-javaagent:${mockitoAgent.asPath}") })
}

// The deployment package: the code and its dependencies in lib/.
val lambdaZip = tasks.register<Zip>("lambdaZip") {
    archiveFileName = "job-api.zip"
    destinationDirectory = layout.buildDirectory.dir("lambda")
    into("lib") {
        from(tasks.jar)
        from(configurations.runtimeClasspath)
    }
}
tasks.assemble { dependsOn(lambdaZip) }

// The SPDX SBOM of the deployment package: build/spdx/job-api.spdx.json.
spdxSbom {
    onlyUseLocalLicenses = true
    targets {
        create("job-api") {
            configurations = listOf("runtimeClasspath")
            System.getenv("GITHUB_SHA")?.let { sha ->
                scm {
                    uri = "${System.getenv("GITHUB_SERVER_URL")}/${System.getenv("GITHUB_REPOSITORY")}"
                    revision = sha
                }
            }
        }
    }
}
