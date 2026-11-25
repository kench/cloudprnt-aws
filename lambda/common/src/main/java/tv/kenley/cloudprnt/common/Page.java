package tv.kenley.cloudprnt.common;

import java.util.List;
import java.util.Optional;

/**
 * One page of a listing.
 *
 * @param items the items
 * @param last the key of the last item, when there may be more
 */
public record Page<T>(List<T> items, Optional<String> last) {}
