package com.awslabs.superfluid.data;

import org.immutables.value.Value;

@Value.Immutable
public abstract class AMI extends NoToString {
    public static Builder builder() {
        return new Builder();
    }

    public abstract String id();

    public abstract String initialUser();

    public static class Builder extends ImmutableAMI.Builder {
    }
}
