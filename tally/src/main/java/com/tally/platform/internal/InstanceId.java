package com.tally.platform.internal;

import java.util.UUID;

/** Names this instance in {@code claimed_by}, for diagnosis only. Ownership uses claim tokens. */
record InstanceId(String value) {

    static InstanceId fromEnvironment() {
        String host = System.getenv("HOSTNAME");
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return new InstanceId((host == null || host.isBlank() ? "instance" : host) + "-" + suffix);
    }
}
