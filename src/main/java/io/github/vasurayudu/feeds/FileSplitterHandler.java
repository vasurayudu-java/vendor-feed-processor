package io.github.vasurayudu.feeds;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.S3Event;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequestEntry;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchResponse;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Lambda #1, triggered when a vendor uploads a file to S3.
 * Streams the file line by line and sends each payment line to SQS in batches of 10 (the SQS maximum).
 * The file is never loaded fully into memory, so large files are fine.
 */
public class FileSplitterHandler implements RequestHandler<S3Event, String> {

    private static final int SQS_BATCH_SIZE = 10;

    // Clients are created once per container and reused across invocations (faster warm starts).
    private final S3Client s3;
    private final SqsClient sqs;
    private final String queueUrl;

    public FileSplitterHandler() {
        this(S3Client.create(), SqsClient.create(), System.getenv("QUEUE_URL"));
    }

    FileSplitterHandler(S3Client s3, SqsClient sqs, String queueUrl) {
        this.s3 = s3;
        this.sqs = sqs;
        this.queueUrl = queueUrl;
    }

    @Override
    public String handleRequest(S3Event event, Context context) {
        int total = 0;
        for (var record : event.getRecords()) {
            String bucket = record.getS3().getBucket().getName();
            String key = URLDecoder.decode(record.getS3().getObject().getKey(), StandardCharsets.UTF_8);
            total += splitFile(bucket, key, context);
        }
        return "Queued " + total + " records";
    }

    private int splitFile(String bucket, String key, Context context) {
        List<SendMessageBatchRequestEntry> batch = new ArrayList<>(SQS_BATCH_SIZE);
        int lineNo = 0, queued = 0;

        try (ResponseInputStream<GetObjectResponse> in = s3.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build());
             var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {

            String line;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                if (lineNo == 1 || line.isBlank()) continue; // header or empty line
                batch.add(SendMessageBatchRequestEntry.builder()
                        .id("line-" + lineNo)
                        .messageBody(line)
                        .messageAttributes(java.util.Map.of(
                                "sourceFile", attr(key),
                                "lineNumber", attr(String.valueOf(lineNo))))
                        .build());
                if (batch.size() == SQS_BATCH_SIZE) {
                    queued += flush(batch);
                }
            }
            queued += flush(batch);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Could not read s3://" + bucket + "/" + key, e);
        }
        context.getLogger().log("Queued %d records from s3://%s/%s".formatted(queued, bucket, key));
        return queued;
    }

    private int flush(List<SendMessageBatchRequestEntry> batch) {
        if (batch.isEmpty()) return 0;
        SendMessageBatchResponse response = sqs.sendMessageBatch(
                SendMessageBatchRequest.builder().queueUrl(queueUrl).entries(batch).build());
        if (!response.failed().isEmpty()) {
            // Failing the invocation makes S3 retry it. The record processor is idempotent, so a re-send is safe.
            throw new IllegalStateException(response.failed().size() + " messages failed to queue");
        }
        int sent = batch.size();
        batch.clear();
        return sent;
    }

    private static software.amazon.awssdk.services.sqs.model.MessageAttributeValue attr(String value) {
        return software.amazon.awssdk.services.sqs.model.MessageAttributeValue.builder()
                .dataType("String").stringValue(value).build();
    }
}
