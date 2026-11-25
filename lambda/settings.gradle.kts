rootProject.name = "cloudprnt-aws-lambda"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

// Types generated from the Smithy model in ../contract.
include("model")

// Shared by the Lambdas: HTTP glue, JSON, storage.
include("common")

// The CloudPRNT endpoint that printers poll.
include("printer-api")

// The print-job API for applications.
include("job-api")
