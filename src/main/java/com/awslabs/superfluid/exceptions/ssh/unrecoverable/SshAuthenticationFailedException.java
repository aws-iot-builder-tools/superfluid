package com.awslabs.superfluid.exceptions.ssh.unrecoverable;

import com.awslabs.superfluid.exceptions.ssh.SshUnrecoverableException;

public class SshAuthenticationFailedException extends SshUnrecoverableException {
    public SshAuthenticationFailedException() {
        super("Authentication error occurred, the SSH key may be missing or incorrect. Giving up.");
    }

    public SshAuthenticationFailedException(String message) {
        super(message);
    }
}
