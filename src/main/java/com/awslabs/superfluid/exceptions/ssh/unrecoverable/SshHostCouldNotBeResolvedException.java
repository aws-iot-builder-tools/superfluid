package com.awslabs.superfluid.exceptions.ssh.unrecoverable;

import com.awslabs.superfluid.exceptions.ssh.SshUnrecoverableException;

public class SshHostCouldNotBeResolvedException extends SshUnrecoverableException {
    public SshHostCouldNotBeResolvedException(String hostname) {
        super(String.format("Host [%s] could not be resolved, is the hostname correct?", hostname));
    }
}
