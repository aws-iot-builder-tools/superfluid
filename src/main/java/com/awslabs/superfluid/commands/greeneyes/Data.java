package com.awslabs.superfluid.commands.greeneyes;

import static com.awslabs.superfluid.helpers.Shared.*;
import static java.lang.System.out;
import static java.text.MessageFormat.format;

public class Data {

    public static String tesRoleName(String thingName) {
        return format("{0}TESRole", thingName);
    }

    public static String thingGroupName(String thingName) {
        return format("{0}Group", thingName);
    }

    private static String s3BucketPrefix(String thingName) {
        return thingName.toLowerCase();
    }

    public static String s3BucketName(String thingName) {
        return String.join("-", s3BucketPrefix(thingName), regionString(), accountId());
    }

    private static String s3Link(String thingName) {
        return format("https://s3.console.aws.amazon.com/s3/buckets/{0}?region={1}", s3BucketName(thingName), regionString());
    }

    private static String consolePrefix() {
        return format("https://{0}.console.aws.amazon.com", regionString());
    }

    public static String thingLink(String thingName) {
        return format("{0}/iot/home?region={1}#/thing/{2}", consolePrefix(), regionString(), thingName);
    }

    public static String thingGroupLink(String thingName) {
        return format("{0}/iot/home?region={1}#/thinggroup/{2}", consolePrefix(), regionString(), thingGroupName(thingName));
    }

    private static String iamV2Prefix() {
        return format("{0}/iamv2/home#", consolePrefix());
    }

    private static String roleDetailsPrefix() {
        return format("{0}/roles/details", iamV2Prefix());
    }

    private static String roleDetailsSuffix() {
        return "?section=permissions";
    }

    private static String iamPrefix() {
        return format("{0}/iam/home#", consolePrefix());
    }

    private static String policyDetailsPrefix() {
        return format("{0}/policies", iamPrefix());
    }

    public static String tesRoleLink(String thingName) {
        return format("{0}/{1}{2}", roleDetailsPrefix(), tesRoleName(thingName), roleDetailsSuffix());
    }

    private static String s3PolicyName(String thingName) {
        return format("S3-access-{0}", s3BucketName(thingName));
    }

    private static String s3PolicyArn(String thingName) {
        return format("arn:aws:iam::{0}:policy/{1}", accountId(), s3BucketName(thingName));
    }

    private static String s3PolicyLink(String thingName) {
        return format("{0}/arn:aws:iam::{1}:policy/{2}", policyDetailsPrefix(), accountId(), s3PolicyName(thingName));
    }

    public static String tesRoleAliasName(String thingName) {
        return format("{0}TESAlias", thingName);
    }

    private static String iotTesRolePolicyName(String thingName) {
        return format("GreengrassTESCertificate{0}", tesRoleAliasName(thingName));
    }

    public static void printWarning(String thingName) {
        println("WARNING! WARNING! This program is going to remove all of the AWS resources related to instance of Greengrass on this machine.");
        println("");
        println("The following resources will be permanently removed:");
        println("  - S3 bucket [{}] and all of its contents", s3BucketName(thingName));
        println("    - {}", s3Link(thingName));
        println("  - AWS IoT Thing [{}], all attached certificates, and all policies attached to those certificates", thingName);
        println("    - {}", thingLink(thingName));
        println("  - AWS IoT Thing Group [{}]", thingGroupName(thingName));
        println("    - {}", thingGroupLink(thingName));
        println("  - IAM role for Greengrass Token Exchange Service [{}]", tesRoleName(thingName));
        println("    - {}", tesRoleLink(thingName));
        println("  - IAM policy for S3 bucket access [{}]", s3PolicyName(thingName));
        println("    - {}", s3PolicyLink(thingName));
    }
}
