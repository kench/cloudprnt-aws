package tv.kenley.cloudprnt.common;

import java.util.Map;

/**
 * An HTTP response.
 *
 * @param status the HTTP status
 * @param headers the response headers
 * @param body the body bytes
 */
public record HttpResponse(int status, Map<String, String> headers, byte[] body) {}
