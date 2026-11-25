$version: "2"

namespace tv.kenley.cloudprnt.protocol

use smithy.framework#ValidationException
use smithy.test#httpRequestTests
use smithy.test#httpResponseTests

/// Star CloudPRNT over HTTP. The printer polls with a JSON status, fetches a job when one is ready,
/// then confirms it. JSON fields keep Star's names.
@protocolDefinition
@trait(selector: "service")
structure cloudPrntHttp {}

/// The requests a CloudPRNT printer makes. Every request carries HTTP Basic credentials: the
/// printer's MAC address and the password issued when it was registered.
@cloudPrntHttp
service CloudPrnt {
    version: "1.0"
    operations: [
        Poll
        GetJob
        ConfirmJob
    ]
    errors: [
        Unauthorized
        ValidationException
        InternalError
    ]
}

/// Reports the printer's status and asks whether a job is ready.
@http(method: "POST", uri: "/cloudprnt", code: 200)
@httpRequestTests([
    {
        id: "PollWithStatus"
        protocol: cloudPrntHttp
        method: "POST"
        uri: "/cloudprnt"
        headers: { "Content-Type": "application/json" }
        body: """
            {"status": "23 86 00 00 00 00 00 00 00", "printerMAC": "00:11:62:12:34:56", "statusCode": "200%20OK", "printingInProgress": false}"""
        bodyMediaType: "application/json"
        params: {
            status: "23 86 00 00 00 00 00 00 00"
            printerMAC: "00:11:62:12:34:56"
            statusCode: "200%20OK"
            printingInProgress: false
        }
    }
    {
        id: "PollWithClientActionResults"
        protocol: cloudPrntHttp
        method: "POST"
        uri: "/cloudprnt"
        headers: { "Content-Type": "application/json" }
        body: """
            {"printerMAC": "00:11:62:12:34:56", "statusCode": "200%20OK", "clientAction": [{"request": "ClientType", "result": "Star mC-Print3"}, {"request": "PageInfo", "result": {"paperWidth": "80", "printWidth": "72", "horizontalResolution": "8", "verticalResolution": "8"}}]}"""
        bodyMediaType: "application/json"
        params: {
            printerMAC: "00:11:62:12:34:56"
            statusCode: "200%20OK"
            clientAction: [
                { request: "ClientType", result: "Star mC-Print3" }
                {
                    request: "PageInfo"
                    result: { paperWidth: "80", printWidth: "72", horizontalResolution: "8", verticalResolution: "8" }
                }
            ]
        }
    }
])
@httpResponseTests([
    {
        id: "PollJobReady"
        protocol: cloudPrntHttp
        code: 200
        headers: { "Content-Type": "application/json" }
        body: """
            {"jobReady": true, "mediaTypes": ["application/vnd.star.starprnt"], "jobToken": "j-01JD5VZ6R1H8P3S0W2Y4A6C8E0"}"""
        bodyMediaType: "application/json"
        params: {
            jobReady: true
            mediaTypes: ["application/vnd.star.starprnt"]
            jobToken: "j-01JD5VZ6R1H8P3S0W2Y4A6C8E0"
        }
    }
    {
        id: "PollRequestsClientInfo"
        protocol: cloudPrntHttp
        code: 200
        headers: { "Content-Type": "application/json" }
        body: """
            {"jobReady": false, "clientAction": [{"request": "ClientType", "options": ""}, {"request": "PageInfo", "options": ""}]}"""
        bodyMediaType: "application/json"
        params: {
            jobReady: false
            clientAction: [
                { request: "ClientType", options: "" }
                { request: "PageInfo", options: "" }
            ]
        }
    }
])
operation Poll {
    input := {
        /// The printer's MAC address.
        @required
        printerMAC: MacAddress

        /// The raw ASB status, as hex bytes.
        status: String

        /// A short status, URL-encoded, such as `200%20OK`.
        statusCode: String

        /// Whether the printer is printing a job (CloudPRNT 2 and later).
        printingInProgress: Boolean

        /// The ID the server set with the `SetID` client action, if any.
        uniqueID: String

        /// Results of the client actions requested by the previous poll response.
        clientAction: ClientActionResults
    }

    output := {
        /// Whether a job is waiting.
        @required
        jobReady: Boolean

        /// The media types the job can be fetched in, when one is ready.
        mediaTypes: MediaTypes

        /// Passed back as `token` when the printer fetches and confirms the job.
        jobToken: String

        /// How the printer confirms a job: `DELETE` (the default) or `GET`.
        deleteMethod: String

        /// Requests for information or settings from the printer.
        clientAction: ClientActionRequests
    }
}

/// Fetches the ready job in one of the media types offered by the poll.
@readonly
@http(method: "GET", uri: "/cloudprnt", code: 200)
operation GetJob {
    input := {
        @required
        @httpQuery("mac")
        mac: MacAddress

        /// The media type the printer chose.
        @required
        @httpQuery("type")
        type: MediaType

        /// The poll's `jobToken`.
        @httpQuery("token")
        token: String
    }

    output := {
        @required
        @httpHeader("Content-Type")
        contentType: MediaType

        @required
        @httpPayload
        content: Blob
    }

    errors: [
        NoJob
        UnsupportedMediaType
    ]
}

/// Reports how a job went. A `code` starting with 2 means it printed.
@idempotent
@http(method: "DELETE", uri: "/cloudprnt", code: 200)
operation ConfirmJob {
    input := {
        @required
        @httpQuery("mac")
        mac: MacAddress

        /// The printer's result, such as `200 OK` or `520 Download timeout`.
        @required
        @httpQuery("code")
        @length(max: 200)
        code: String

        /// The poll's `jobToken`.
        @httpQuery("token")
        token: String
    }

    output := {}
}

/// A MAC address, such as `00:11:62:12:34:56`.
@pattern("^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}$")
string MacAddress

/// A media type, such as `application/vnd.star.starprnt`.
@length(min: 3, max: 100)
@pattern("^[a-z]+/[a-z0-9.+-]+$")
string MediaType

list MediaTypes {
    member: MediaType
}

/// A request for information or a setting, such as `ClientType`, `ClientVersion` or `PageInfo`.
structure ClientActionRequest {
    @required
    request: String

    @required
    options: String
}

list ClientActionRequests {
    member: ClientActionRequest
}

/// The printer's answer to a client action. Its `result` is usually a string; `PageInfo`'s is an
/// object of `paperWidth`, `printWidth` (mm), `horizontalResolution` and `verticalResolution`
/// (dots per mm).
structure ClientActionResult {
    @required
    request: String

    result: Document
}

list ClientActionResults {
    member: ClientActionResult
}

/// The request's credentials are missing or wrong.
@error("client")
@httpError(401)
structure Unauthorized {
    @required
    message: String
}

/// No job is waiting for this printer.
@error("client")
@httpError(404)
structure NoJob {
    @required
    message: String
}

/// The job isn't available in the requested media type.
@error("client")
@httpError(415)
structure UnsupportedMediaType {
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
