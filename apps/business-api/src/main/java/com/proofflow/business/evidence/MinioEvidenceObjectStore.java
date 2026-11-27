package com.proofflow.business.evidence;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.errors.MinioException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;

final class MinioEvidenceObjectStore implements EvidenceObjectStore {
    private final MinioClient client;
    private final String bucket;

    MinioEvidenceObjectStore(MinioClient client, String bucket) {
        this.client = client;
        this.bucket = bucket;
    }

    void ensureBucket() {
        try {
            if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                createBucket();
            }
        } catch (MinioException | IOException | GeneralSecurityException error) {
            throw new IllegalStateException("Could not open the MinIO evidence bucket", error);
        }
    }

    private void createBucket() throws MinioException, IOException, GeneralSecurityException {
        try {
            client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        } catch (ErrorResponseException error) {
            if (!"BucketAlreadyOwnedByYou".equals(error.errorResponse().code())) {
                throw error;
            }
        }
    }

    @Override
    public void put(String key, byte[] content) {
        try {
            client.putObject(PutObjectArgs.builder().bucket(bucket).object(key)
                .stream(new ByteArrayInputStream(content), content.length, -1)
                .contentType("application/octet-stream").build());
        } catch (MinioException | IOException | GeneralSecurityException error) {
            throw new EvidenceStorageException("Could not write evidence object", error);
        }
    }

    @Override
    public byte[] read(String key, long expectedSize) {
        if (expectedSize < 1 || expectedSize > CaseEvidenceController.MAX_FILE_BYTES) {
            throw new EvidenceStorageException("Invalid evidence object size in metadata", null);
        }
        try (var stream = client.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build())) {
            byte[] content = stream.readNBytes((int) expectedSize + 1);
            if (content.length != expectedSize) {
                throw new EvidenceStorageException("Evidence object size does not match metadata", null);
            }
            return content;
        } catch (MinioException | IOException | GeneralSecurityException error) {
            throw new EvidenceStorageException("Could not read evidence object", error);
        }
    }
}
