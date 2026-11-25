$version: "2"

namespace tv.kenley.cloudprnt.api

use aws.auth#sigv4
use aws.protocols#restJson1
use smithy.framework#ValidationException
use smithy.test#httpRequestTests
use smithy.test#httpResponseTests

/// Registers printers and queues print jobs for them. Callers sign requests with SigV4.
@restJson1
@sigv4(name: "execute-api")
@title("CloudPRNT print jobs")
service PrintJobs {
    version: "2026-11-25"
    operations: [
        RegisterPrinter
        GetPrinter
        ListPrinters
        DeletePrinter
        ResetPrinterPassword
        SubmitPrintJob
        GetPrintJob
        ListPrintJobs
        CancelPrintJob
    ]
    errors: [
        ValidationException
        InternalError
    ]
}

/// Registers a printer and issues the password it polls with. The password is returned only
/// here and by `ResetPrinterPassword`.
@http(method: "POST", uri: "/v1/printers", code: 201)
@httpRequestTests([
    {
        id: "RegisterPrinter"
        protocol: restJson1
        method: "POST"
        uri: "/v1/printers"
        headers: { "Content-Type": "application/json" }
        body: """
            {"mac": "00:11:62:12:34:56", "name": "Front counter"}"""
        bodyMediaType: "application/json"
        params: { mac: "00:11:62:12:34:56", name: "Front counter" }
    }
])
operation RegisterPrinter {
    input := {
        @required
        mac: MacAddress

        name: PrinterName
    }

    output := {
        @required
        printer: PrinterSummary

        @required
        password: Password
    }

    errors: [
        PrinterExists
    ]
}

/// A printer and the status from its last poll.
@readonly
@http(method: "GET", uri: "/v1/printers/{mac}", code: 200)
@httpResponseTests([
    {
        id: "GetPrinter"
        protocol: restJson1
        code: 200
        headers: { "Content-Type": "application/json" }
        body: """
            {"mac": "00:11:62:12:34:56", "name": "Front counter", "createdAt": "2026-11-25T09:00:00Z", "lastPollAt": "2026-11-25T09:05:00Z", "statusCode": "200 OK", "status": "23 86 00 00 00 00 00 00 00", "printingInProgress": false, "clientType": "Star mC-Print3", "clientVersion": "5.1", "printWidthDots": 576}"""
        bodyMediaType: "application/json"
        params: {
            printer: {
                mac: "00:11:62:12:34:56"
                name: "Front counter"
                createdAt: 1795597200
                lastPollAt: 1795597500
                statusCode: "200 OK"
                status: "23 86 00 00 00 00 00 00 00"
                printingInProgress: false
                clientType: "Star mC-Print3"
                clientVersion: "5.1"
                printWidthDots: 576
            }
        }
    }
])
operation GetPrinter {
    input := {
        @required
        @httpLabel
        mac: MacAddress
    }

    output := {
        @required
        @httpPayload
        printer: PrinterSummary
    }

    errors: [
        PrinterNotFound
    ]
}

/// Lists printers, in no particular order.
@readonly
@paginated(inputToken: "nextToken", outputToken: "nextToken", pageSize: "maxResults", items: "printers")
@http(method: "GET", uri: "/v1/printers", code: 200)
operation ListPrinters {
    input := {
        @httpQuery("nextToken")
        nextToken: NextToken

        @httpQuery("maxResults")
        maxResults: MaxResults
    }

    output := {
        @required
        printers: PrinterSummaries

        nextToken: NextToken
    }
}

/// Removes a printer. Its jobs expire with the usual retention.
@idempotent
@http(method: "DELETE", uri: "/v1/printers/{mac}", code: 204)
operation DeletePrinter {
    input := {
        @required
        @httpLabel
        mac: MacAddress
    }

    output := {}

    errors: [
        PrinterNotFound
    ]
}

/// Issues a new password for a printer. The old one stops working.
@http(method: "POST", uri: "/v1/printers/{mac}/password", code: 200)
operation ResetPrinterPassword {
    input := {
        @required
        @httpLabel
        mac: MacAddress
    }

    output := {
        @required
        password: Password
    }

    errors: [
        PrinterNotFound
    ]
}

/// Queues a job. The printer fetches it in the given media type on its next poll.
@http(method: "POST", uri: "/v1/printers/{mac}/jobs", code: 201)
@httpRequestTests([
    {
        id: "SubmitPrintJob"
        protocol: restJson1
        method: "POST"
        uri: "/v1/printers/00%3A11%3A62%3A12%3A34%3A56/jobs"
        headers: { "Content-Type": "application/json" }
        body: """
            {"mediaType": "text/plain", "content": "SGVsbG8sIHdvcmxkIQo="}"""
        bodyMediaType: "application/json"
        params: { mac: "00:11:62:12:34:56", mediaType: "text/plain", content: "Hello, world!\n" }
    }
])
operation SubmitPrintJob {
    input := {
        @required
        @httpLabel
        mac: MacAddress

        /// The job's format, such as `application/vnd.star.starprnt`, `image/png` or `text/plain`.
        @required
        mediaType: MediaType

        /// The job, base64-encoded in JSON.
        @required
        content: JobContent
    }

    output := {
        @required
        @httpPayload
        job: PrintJobSummary
    }

    errors: [
        PrinterNotFound
    ]
}

/// A job and its state.
@readonly
@http(method: "GET", uri: "/v1/printers/{mac}/jobs/{jobId}", code: 200)
@httpResponseTests([
    {
        id: "GetPrintedJob"
        protocol: restJson1
        code: 200
        headers: { "Content-Type": "application/json" }
        body: """
            {"jobId": "j-01JD5VZ6R1H8P3S0W2Y4A6C8E0", "mac": "00:11:62:12:34:56", "mediaType": "text/plain", "size": 14, "state": "PRINTED", "createdAt": "2026-11-25T09:00:00Z", "updatedAt": "2026-11-25T09:00:07Z", "attempts": 1, "result": "200 OK"}"""
        bodyMediaType: "application/json"
        params: {
            job: {
                jobId: "j-01JD5VZ6R1H8P3S0W2Y4A6C8E0"
                mac: "00:11:62:12:34:56"
                mediaType: "text/plain"
                size: 14
                state: "PRINTED"
                createdAt: 1795597200
                updatedAt: 1795597207
                attempts: 1
                result: "200 OK"
            }
        }
    }
])
operation GetPrintJob {
    input := {
        @required
        @httpLabel
        mac: MacAddress

        @required
        @httpLabel
        jobId: JobId
    }

    output := {
        @required
        @httpPayload
        job: PrintJobSummary
    }

    errors: [
        JobNotFound
    ]
}

/// Lists a printer's jobs, newest first.
@readonly
@paginated(inputToken: "nextToken", outputToken: "nextToken", pageSize: "maxResults", items: "jobs")
@http(method: "GET", uri: "/v1/printers/{mac}/jobs", code: 200)
operation ListPrintJobs {
    input := {
        @required
        @httpLabel
        mac: MacAddress

        @httpQuery("nextToken")
        nextToken: NextToken

        @httpQuery("maxResults")
        maxResults: MaxResults
    }

    output := {
        @required
        jobs: PrintJobSummaries

        nextToken: NextToken
    }
}

/// Cancels a job the printer hasn't fetched yet.
@http(method: "POST", uri: "/v1/printers/{mac}/jobs/{jobId}/cancel", code: 200)
operation CancelPrintJob {
    input := {
        @required
        @httpLabel
        mac: MacAddress

        @required
        @httpLabel
        jobId: JobId
    }

    output := {
        @required
        @httpPayload
        job: PrintJobSummary
    }

    errors: [
        JobNotFound
        JobNotCancellable
    ]
}

/// A printer and the status from its last poll.
structure PrinterSummary {
    @required
    mac: MacAddress

    name: PrinterName

    @required
    @timestampFormat("date-time")
    createdAt: Timestamp

    @timestampFormat("date-time")
    lastPollAt: Timestamp

    /// The short status from the last poll, such as `200 OK`.
    statusCode: String

    /// The raw ASB status from the last poll.
    status: String

    printingInProgress: Boolean

    /// From the `ClientType` client action.
    clientType: String

    /// From the `ClientVersion` client action.
    clientVersion: String

    /// The print width in dots, from the `PageInfo` client action.
    printWidthDots: Integer
}

list PrinterSummaries {
    member: PrinterSummary
}

/// A job and its state.
structure PrintJobSummary {
    @required
    jobId: JobId

    @required
    mac: MacAddress

    @required
    mediaType: MediaType

    /// The content's size in bytes.
    @required
    size: Long

    @required
    state: JobState

    @required
    @timestampFormat("date-time")
    createdAt: Timestamp

    @required
    @timestampFormat("date-time")
    updatedAt: Timestamp

    /// How many times the printer has fetched the job.
    @required
    attempts: Integer

    /// The printer's last result code, such as `200 OK`.
    result: String
}

list PrintJobSummaries {
    member: PrintJobSummary
}

enum JobState {
    /// Waiting for the printer.
    QUEUED

    /// Fetched by the printer, not yet confirmed.
    SENT

    PRINTED

    FAILED

    CANCELLED
}

/// A MAC address, such as `00:11:62:12:34:56`.
@pattern("^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}$")
string MacAddress

/// A job ID: `j-` and a ULID.
@pattern("^j-[0-9A-HJKMNP-TV-Z]{26}$")
string JobId

@length(min: 1, max: 100)
string PrinterName

/// A media type, such as `application/vnd.star.starprnt`.
@length(min: 3, max: 100)
@pattern("^[a-z]+/[a-z0-9.+-]+$")
string MediaType

/// Up to 4 MiB, so the base64 JSON fits a Lambda payload.
@length(min: 1, max: 4194304)
blob JobContent

/// The printer's HTTP Basic password.
@sensitive
string Password

@length(min: 1, max: 1024)
string NextToken

@range(min: 1, max: 100)
integer MaxResults

/// No printer is registered with this MAC address.
@error("client")
@httpError(404)
structure PrinterNotFound {
    @required
    message: String
}

/// A printer with this MAC address is already registered.
@error("client")
@httpError(409)
structure PrinterExists {
    @required
    message: String
}

/// The printer has no job with this ID.
@error("client")
@httpError(404)
structure JobNotFound {
    @required
    message: String
}

/// The job has already been fetched or finished.
@error("client")
@httpError(409)
structure JobNotCancellable {
    @required
    message: String
}

/// Something went wrong on the server.
@error("server")
@httpError(500)
structure InternalError {
    @required
    message: String
}
