package tv.kenley.cloudprnt.common;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** {@link Contents} in memory, for tests. */
public final class InMemoryContents implements Contents {
  private final Map<String, byte[]> contents = new HashMap<>();
  private final Map<String, String> types = new HashMap<>();

  @Override
  public void put(String key, String mediaType, byte[] content) {
    contents.put(key, content.clone());
    types.put(key, mediaType);
  }

  @Override
  public Optional<byte[]> get(String key) {
    return Optional.ofNullable(contents.get(key)).map(byte[]::clone);
  }

  /** The media type {@code key} was stored with. */
  public String mediaType(String key) {
    return types.get(key);
  }

  /** Removes {@code key}, as S3's lifecycle rule would. */
  public void expire(String key) {
    contents.remove(key);
  }
}
