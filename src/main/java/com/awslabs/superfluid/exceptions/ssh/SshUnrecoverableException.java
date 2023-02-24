package com.awslabs.superfluid.exceptions.ssh;

public class SshUnrecoverableException extends RuntimeException {
    public SshUnrecoverableException(String message) {
        super(message);
    }
}
