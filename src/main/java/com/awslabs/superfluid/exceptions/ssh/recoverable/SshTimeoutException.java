package com.awslabs.superfluid.exceptions.ssh.recoverable;

import com.awslabs.superfluid.exceptions.ssh.SshRecoverableException;

public class SshTimeoutException extends SshRecoverableException {
    public SshTimeoutException() {
        super(null);
    }
}
