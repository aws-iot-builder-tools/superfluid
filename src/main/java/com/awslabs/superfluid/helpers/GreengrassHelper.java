package com.awslabs.superfluid.helpers;


import io.vavr.control.Try;
import software.amazon.awssdk.services.greengrassv2.model.DeleteCoreDeviceRequest;
import software.amazon.awssdk.services.greengrassv2.model.DeleteCoreDeviceResponse;

import static com.awslabs.superfluid.helpers.AwsSdkHelper.DELETE;
import static com.awslabs.superfluid.helpers.AwsSdkHelper.resultWithSpinner;
import static com.awslabs.superfluid.helpers.Shared.greengrassV2Client;

public class GreengrassHelper {
    public static Try<DeleteCoreDeviceResponse> deleteCoreDevice(String thingName) {
        DeleteCoreDeviceRequest deleteCoreDeviceRequest = DeleteCoreDeviceRequest.builder()
                .coreDeviceThingName(thingName)
                .build();

        return resultWithSpinner(() -> greengrassV2Client().deleteCoreDevice(deleteCoreDeviceRequest),
                "Greengrass core device " + thingName, DELETE);
    }
}
