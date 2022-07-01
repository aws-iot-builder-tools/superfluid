package com.awslabs.superfluid.data;

import org.immutables.value.Value;

import java.util.List;

@Value.Immutable
public abstract class ProcessOutput extends NoToString {
    public static Builder builder() {
        return new Builder();
    }

    public abstract int exitCode();

    public abstract List<String> standardOutStrings();

    public abstract List<String> standardErrorStrings();

    public static class Builder extends ImmutableProcessOutput.Builder {
    }
}
