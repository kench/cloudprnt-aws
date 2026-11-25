package tv.kenley.cloudprnt.common;

import java.util.Optional;

/** Job content, by key. */
public interface Contents {
  /** Stores {@code content} under {@code key}. */
  void put(String key, String mediaType, byte[] content);

  /** The content under {@code key}, if it is still there. */
  Optional<byte[]> get(String key);
}
