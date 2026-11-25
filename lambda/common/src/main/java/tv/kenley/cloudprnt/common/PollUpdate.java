package tv.kenley.cloudprnt.common;

import java.time.Instant;

/**
 * What a poll changes on a printer's record. Null members are left as they are.
 *
 * @param at when the printer polled
 * @param status the raw ASB status
 * @param statusCode the short status, decoded
 * @param printingInProgress whether it is printing
 * @param clientType from the {@code ClientType} client action
 * @param clientVersion from the {@code ClientVersion} client action
 * @param printWidthDots from the {@code PageInfo} client action
 * @param infoRequestedAt set when this poll's response requests client information
 */
public record PollUpdate(
    Instant at,
    String status,
    String statusCode,
    Boolean printingInProgress,
    String clientType,
    String clientVersion,
    Integer printWidthDots,
    Instant infoRequestedAt) {}
