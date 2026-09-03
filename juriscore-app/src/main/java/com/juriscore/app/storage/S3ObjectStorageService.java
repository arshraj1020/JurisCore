package com.juriscore.app.storage;

import com.juriscore.app.config.AwsProperties;
import com.juriscore.common.storage.ObjectStorageException;
import com.juriscore.common.storage.ObjectStorageService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * The real thing: S3 through the SDK v2 clients {@code AwsConfig} already builds.
 *
 * <h2>What a presigned PUT actually enforces</h2>
 *
 * <p>Worth stating precisely, because it is the one place where the guarantee is narrower
 * than it looks. Presigning signs the request the SDK was given, so anything expressed as
 * a signed header is enforced by S3 and anything else is not:
 *
 * <ul>
 *   <li><strong>The object key is enforced.</strong> The signature covers the path, so a
 *       client cannot redirect an upload to another key — which is what keeps one firm's
 *       upload URL from ever writing into another firm's prefix.</li>
 *   <li><strong>The method is enforced.</strong> A PUT link cannot GET or DELETE.</li>
 *   <li><strong>Content-Type is enforced</strong>, because it is set on the request and
 *       therefore signed. An upload declaring a different type is rejected by S3.</li>
 *   <li><strong>Size is NOT enforced by the signature.</strong> {@code contentLength} on a
 *       presigned PUT is not part of what S3 checks; a client that ignores it can push a
 *       larger object. This is a real limitation of presigned PUT, not an oversight here.
 *       It is covered in two other places instead: the completion step reads the object's
 *       actual size back from S3 and refuses anything over the configured maximum, and in
 *       a deployed environment the bucket policy is the belt to this braces. The window in
 *       between is bounded storage cost on an object that can never become AVAILABLE.</li>
 * </ul>
 *
 * <p>Nothing in this class logs a URL. A presigned URL is a bearer credential; putting one
 * in a log line hands anyone with log access the object for as long as it lives.
 */
@Component
@ConditionalOnProperty(prefix = "juriscore.aws", name = "enabled", havingValue = "true",
        matchIfMissing = true)
@RequiredArgsConstructor
public class S3ObjectStorageService implements ObjectStorageService {

    private static final Logger log = LoggerFactory.getLogger(S3ObjectStorageService.class);

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    private final AwsProperties properties;

    @Override
    public PresignedUrl presignUpload(String key, String contentType, long sizeBytes) {
        Duration expiry = properties.getUploadUrlExpiry();
        try {
            PutObjectRequest put = PutObjectRequest.builder()
                    .bucket(properties.getDocumentBucket())
                    .key(key)
                    // Signed, so the upload must declare the type it was authorised for.
                    .contentType(contentType)
                    // Sent for correctness and for S3's own accounting. Not a security
                    // control — see the class javadoc.
                    .contentLength(sizeBytes)
                    .build();

            var presigned = s3Presigner.presignPutObject(PutObjectPresignRequest.builder()
                    .signatureDuration(expiry)
                    .putObjectRequest(put)
                    .build());

            log.debug("Issued an upload link for key {} valid for {}", key, expiry);
            return new PresignedUrl(presigned.url().toString(), expiry, Instant.now().plus(expiry));
        } catch (SdkException e) {
            throw new ObjectStorageException("Could not create an upload link for " + key, e);
        }
    }

    @Override
    public PresignedUrl presignDownload(String key, String downloadFilename) {
        Duration expiry = properties.getDownloadUrlExpiry();
        try {
            GetObjectRequest get = GetObjectRequest.builder()
                    .bucket(properties.getDocumentBucket())
                    .key(key)
                    // So the browser saves the file under the name a person recognises
                    // rather than the opaque key. The key stays internal.
                    .responseContentDisposition(contentDisposition(downloadFilename))
                    .build();

            var presigned = s3Presigner.presignGetObject(GetObjectPresignRequest.builder()
                    .signatureDuration(expiry)
                    .getObjectRequest(get)
                    .build());

            log.debug("Issued a download link for key {} valid for {}", key, expiry);
            return new PresignedUrl(presigned.url().toString(), expiry, Instant.now().plus(expiry));
        } catch (SdkException e) {
            throw new ObjectStorageException("Could not create a download link for " + key, e);
        }
    }

    @Override
    public Optional<StoredObject> head(String key) {
        try {
            HeadObjectResponse response = s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(properties.getDocumentBucket())
                    .key(key)
                    .build());
            return Optional.of(new StoredObject(key,
                    response.contentLength() == null ? 0L : response.contentLength(),
                    response.contentType()));
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        } catch (AwsServiceException e) {
            // A 404 arrives as NoSuchKeyException above; a 403 usually means the object is
            // absent and the caller lacks s3:ListBucket. Treating it as "not there" is the
            // safe reading, because the only thing it gates is whether an upload counts as
            // finished.
            if (e.statusCode() == 404 || e.statusCode() == 403) {
                return Optional.empty();
            }
            throw new ObjectStorageException("Could not read object metadata for " + key, e);
        } catch (SdkException e) {
            throw new ObjectStorageException("Could not read object metadata for " + key, e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(properties.getDocumentBucket())
                    .key(key)
                    .build());
        } catch (SdkException e) {
            throw new ObjectStorageException("Could not delete object " + key, e);
        }
    }

    @Override
    public boolean isDurable() {
        return true;
    }

    /**
     * Quotes the filename and strips the two characters that could break out of the
     * header. The name has already been validated on the way in; this is the second layer,
     * because a header injection here would be reflected straight back to a browser.
     */
    private static String contentDisposition(String filename) {
        String safe = filename == null ? "document" : filename.replace("\"", "").replace("\\", "");
        return "attachment; filename=\"" + safe + "\"";
    }
}
