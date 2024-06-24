package com.awslabs.superfluid.helpers;

import io.vavr.control.Try;
import org.junit.Test;

import java.nio.file.Path;

import static com.awslabs.superfluid.helpers.SshHelper.getFingerprintFromPublicKey;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;

public class SshFingerprintTest {
    public static final String EXPECTED_FINGERPRINT = "f7:f4:a0:42:11:6b:c7:f0:3a:cb:8b:62:ba:12:78:98";
    public static final String MODIFIED_FINGERPRINT = "f7:f4:a0:42:11:6b:c7:f0:3a:cb:8b:62:ba:12:78:99";
    public static final String THROWAWAY_PUBLIC_KEY = "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABgQClrLnD+0P/uXKevWp/+NEsIc8anyrULuYfAvFafolfoFshzaPJqNfoFLPl8syB6nrqgvplQoIB5F6cxbHfsuaJbdHwYXmh+9K0ziNEZy5jAna1D/J+yPZukg8K03Mcqn9K4QjBt92pTp5GIJ/8fgIEySlBvLDwGHvBRkppFJccWpba4Yeog6zWtUnEi3cXdHl03eQcGLyUz8yhDQk6gtJKqUOzW0afPl4YfuJeinjejI6TN7h4TtcnlAcBOK2oJxy7XpaU0qFwtsSFXTk9Ub0fJvcPjt37kC0y6RGjyAFaRIqj4FeRGWms4wTRPd4Ut+a3pcqNHfIoHlNx2e2Yd2UHyhk/gYlkNdgXoLo9OzclsdHa3smkxWTA/yph9mh9QyfMAyZGNqb0RQ/tiTZRrbDo9hpMObsqTC/4YXyW6s9BPId751z/bavOFk57+RUPAAa0TMaxMTq6glv4YKk25HOlR5WGSG4CP5IbKCpZS/1MI3Dn+PJ1dO/3vFs7l4eCaZU= throwaway";

    @Test
    public void sshFingerprintTest() {
        Try<String> fingerprintTry = getFingerprintFromPublicKey(THROWAWAY_PUBLIC_KEY);

        assertThat("fingerprint should not be a failure", fingerprintTry.isSuccess());
        assertThat("fingerprint should match expected value", fingerprintTry.get(), equalTo(EXPECTED_FINGERPRINT));
        assertThat("fingerprint should not match modified value", fingerprintTry.get(), not(equalTo(MODIFIED_FINGERPRINT)));
    }
}
