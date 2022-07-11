package com.awslabs.superfluid.exceptions.ssh.recoverable;

import com.awslabs.superfluid.exceptions.ssh.SshRecoverableException;

public class SshRefusedException extends SshRecoverableException {
    public SshRefusedException() {
        super(null);
    }
}
