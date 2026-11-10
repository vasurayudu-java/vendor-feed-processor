package io.github.vasurayudu.feeds;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Lambda #2, triggered by SQS. Validates each payment and stores it in DynamoDB.
 *
 *  - Invalid records are stored with status REJECTED and a reason. They are not retried,
 *    because retrying bad data never fixes it.
 *  - Duplicates (the same recordId delivered twice) are ignored through a conditional write, so
 *    processing is idempotent and the result is effectively exactly-once.
 *  - Unexpected errors (for example DynamoDB throttling) are reported as partial batch failures.
 *    Only those messages return to the queue, and after 3 attempts SQS moves them to the DLQ.
 */
public class RecordProcessorHandler implements RequestHandler<SQSEvent, SQSBatchResponse> {

    private final DynamoDbClient dynamo;
    private final String tableName;

    public RecordProcessorHandler() {
        this(DynamoDbClient.create(), System.getenv("TABLE_NAME"));
    }

    RecordProcessorHandler(DynamoDbClient dynamo, String tableName) {
        this.dynamo = dynamo;
        this.tableName = tableName;
    }

    @Override
    public SQSBatchResponse handleRequest(SQSEvent event, Context context) {
        List<SQSBatchResponse.BatchItemFailure> failures = new ArrayList<>();

        for (SQSEvent.SQSMessage message : event.getRecords()) {
            try {
                process(message, context);
            } catch (Exception e) {
                context.getLogger().log("Will retry message " + message.getMessageId() + ": " + e.getMessage());
                failures.add(SQSBatchResponse.BatchItemFailure.builder()
                        .withItemIdentifier(message.getMessageId()).build());
            }
        }
        return SQSBatchResponse.builder().withBatchItemFailures(failures).build();
    }

    void process(SQSEvent.SQSMessage message, Context context) {
        Map<String, AttributeValue> item;
        try {
            PaymentRecord p = PaymentRecord.parse(message.getBody());
            item = Map.of(
                    "recordId", s(p.recordId()),
                    "vendorId", s(p.vendorId()),
                    "accountRef", s(p.accountRef()),
                    "amount", AttributeValue.fromN(p.amount().toPlainString()),
                    "currency", s(p.currency()),
                    "valueDate", s(p.valueDate().toString()),
                    "status", s("ACCEPTED"),
                    "processedAt", s(Instant.now().toString()));
            context.getLogger().log("Accepted %s %s %s for account %s"
                    .formatted(p.recordId(), p.amount(), p.currency(), p.maskedAccountRef()));
        } catch (InvalidRecordException e) {
            String fallbackId = "REJECTED#" + message.getMessageId();
            item = Map.of(
                    "recordId", s(fallbackId),
                    "status", s("REJECTED"),
                    "reason", s(e.getMessage()),
                    "processedAt", s(Instant.now().toString()));
            context.getLogger().log("Rejected message %s: %s".formatted(message.getMessageId(), e.getMessage()));
        }

        try {
            dynamo.putItem(PutItemRequest.builder()
                    .tableName(tableName)
                    .item(item)
                    .conditionExpression("attribute_not_exists(recordId)")
                    .build());
        } catch (ConditionalCheckFailedException duplicate) {
            context.getLogger().log("Duplicate ignored: " + item.get("recordId").s());
        }
    }

    private static AttributeValue s(String value) {
        return AttributeValue.fromS(value);
    }
}
