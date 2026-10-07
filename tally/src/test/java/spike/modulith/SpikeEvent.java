package spike.modulith;

import java.util.UUID;

/** A test-only event for Spike A. Lives outside com.tally, so it is no Tally module's event. */
public record SpikeEvent(UUID id) {
}
