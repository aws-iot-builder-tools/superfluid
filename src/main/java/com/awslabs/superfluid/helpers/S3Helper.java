package com.awslabs.superfluid.helpers;


import com.awslabs.superfluid.visual.Spinner;
import io.vavr.collection.List;
import io.vavr.control.Try;
import software.amazon.awssdk.services.s3.model.*;

import java.util.stream.Stream;

import static com.awslabs.superfluid.helpers.AwsSdkHelper.*;
import static com.awslabs.superfluid.helpers.Shared.s3Client;
import static java.text.MessageFormat.format;

public class S3Helper {
    private static final Spinner SPINNER = Spinner.Standard();

    private static final int MAX_OBJECTS_TO_DELETE_AT_ONCE = 1000;

    public static Try<Stream<S3Object>> streamObjectsInBucket(String bucketName) {
        ListObjectsV2Request listObjectsV2Request = ListObjectsV2Request.builder()
                .bucket(bucketName)
                .build();

        return resultStream(() -> s3Client().listObjectsV2Paginator(listObjectsV2Request), ListObjectsV2Response::contents);
    }

    public static Try<List<S3Object>> listObjectsInBucket(String bucketName) {
        return resultListWithSpinner(() -> streamObjectsInBucket(bucketName), "objects in the S3 bucket " + bucketName, LIST);
    }

    public static List<Try<DeleteObjectsResponse>> deleteObjectsFromBucket(String bucketName, List<S3Object> s3Objects) {
        SPINNER.start(format("Deleting {0} object(s) from the S3 bucket {1}", s3Objects.size(), bucketName));

        List<Try<DeleteObjectsResponse>> results = List.empty();

        // Don't touch the original list so we can keep track of what we've deleted
        List<S3Object> tempS3Objects = s3Objects;

        while (tempS3Objects.nonEmpty()) {
            // Take as many as we can
            List<ObjectIdentifier> objectIdentifiers = tempS3Objects.take(MAX_OBJECTS_TO_DELETE_AT_ONCE)
                    .map(s3Object -> ObjectIdentifier.builder().key(s3Object.key()).build());

            // Remove them from the existing list
            tempS3Objects = tempS3Objects.drop(MAX_OBJECTS_TO_DELETE_AT_ONCE);

            // Package them in the delete structure for the request
            Delete objectsDelete = Delete.builder()
                    .objects(objectIdentifiers.asJava())
                    .build();

            // Create the delete request
            DeleteObjectsRequest deleteObjectsRequest = DeleteObjectsRequest.builder()
                    .bucket(bucketName)
                    .delete(objectsDelete)
                    .build();

            results = results.append(Try.of(() -> s3Client().deleteObjects(deleteObjectsRequest)));
        }

        if (results.filter(Try::isFailure).isEmpty()) {
            SPINNER.success(format("Deleted {0} object(s) from the S3 bucket {1}", s3Objects.size(), bucketName));
        } else {
            SPINNER.fail(format("Failed to delete some of the {0} object(s) from the S3 bucket {1}", s3Objects.size(), bucketName));
        }

        return results;
    }

    public static Try<DeleteBucketResponse> deleteBucket(String bucketName) {
        SPINNER.start(format("Deleting bucket {0}", bucketName));

        return Try.of(() -> s3Client().deleteBucket(DeleteBucketRequest.builder().bucket(bucketName).build()))
                .onFailure(e -> SPINNER.fail(format("Failed to delete the S3 bucket {0} {1}", bucketName, e.getMessage())))
                .onSuccess(list -> SPINNER.success(format("Deleted the S3 bucket {0}", bucketName)));

    }
}
