package com.awslabs.superfluid.helpers;

import io.vavr.Lazy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.helpers.MessageFormatter;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.regions.providers.DefaultAwsRegionProviderChain;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.greengrassv2.GreengrassV2Client;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iot.IotClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.GetCallerIdentityRequest;

public class Shared {
    private static final Lazy<Logger> lazyLog = Lazy.of(() -> LoggerFactory.getLogger(Shared.class));
    private static final Lazy<Region> lazyRegion = Lazy.of(() -> DefaultAwsRegionProviderChain.builder().build().getRegion());
    private static final Lazy<Ec2Client> lazyEc2Client = Lazy.of(Ec2Client::create);
    private static final Lazy<IamClient> lazyIamClient = Lazy.of(() -> IamClient.builder().region(Region.AWS_GLOBAL).build());
    private static final Lazy<IotClient> lazyIotClient = Lazy.of(IotClient::create);
    private static final Lazy<GreengrassV2Client> lazyGreengrassV2Client = Lazy.of(GreengrassV2Client::create);
    private static final Lazy<StsClient> lazyStsClient = Lazy.of(StsClient::create);
    private static final Lazy<S3Client> lazyS3Client = Lazy.of(() -> S3Client.builder().build());
    public static final software.amazon.awssdk.services.s3.S3Client s3Client = software.amazon.awssdk.services.s3.S3Client.builder().build();

    private static final Lazy<String> lazyAccountId = Lazy.of(() -> stsClient().getCallerIdentity(GetCallerIdentityRequest.builder().build()).account());

    public static void setVerbose(boolean[] verbose) {
        throw new RuntimeException("Not implemented");
    }

    public static Logger log() {
        return lazyLog.get();
    }

    public static Region region() {
        return lazyRegion.get();
    }

    public static String regionString() {
        return lazyRegion.get().id();
    }

    public static String accountId() {
        return lazyAccountId.get();
    }

    public static Ec2Client ec2Client() {
        return lazyEc2Client.get();
    }

    public static StsClient stsClient() {
        return lazyStsClient.get();
    }

    public static S3Client s3Client() {
        return lazyS3Client.get();
    }

    public static IamClient iamClient() {
        return lazyIamClient.get();
    }

    public static IotClient iotClient() {
        return lazyIotClient.get();
    }

    public static GreengrassV2Client greengrassV2Client() {
        return lazyGreengrassV2Client.get();
    }

    public static void print(String format, Object... args) {
        System.out.print(MessageFormatter.arrayFormat(format, args).getMessage());
    }

    public static void println() {
        System.out.println();
    }

    public static void println(String format, Object... args) {
        System.out.println(MessageFormatter.arrayFormat(format, args).getMessage());
    }
}
