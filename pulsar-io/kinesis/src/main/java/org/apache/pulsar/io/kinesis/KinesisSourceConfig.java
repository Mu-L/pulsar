/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pulsar.io.kinesis;

import static com.google.common.base.Preconditions.checkArgument;
import static org.apache.commons.lang3.StringUtils.isNotBlank;
import java.io.Serializable;
import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.apache.pulsar.io.aws.AwsCredentialProviderPlugin;
import org.apache.pulsar.io.common.IOConfigUtils;
import org.apache.pulsar.io.core.SourceContext;
import org.apache.pulsar.io.core.annotations.FieldDoc;
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient;
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClientBuilder;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClientBuilder;
import software.amazon.awssdk.services.kinesis.KinesisAsyncClient;
import software.amazon.awssdk.services.kinesis.KinesisAsyncClientBuilder;
import software.amazon.kinesis.common.InitialPositionInStream;
import software.amazon.kinesis.common.InitialPositionInStreamExtended;
import software.amazon.kinesis.common.KinesisClientUtil;

@Data
@EqualsAndHashCode(callSuper = true)
public class KinesisSourceConfig extends BaseKinesisConfig implements Serializable {

    private static final long serialVersionUID = 1L;

    @FieldDoc(
        required = false,
        defaultValue = "LATEST",
        help = "Used to specify the position in the stream where the connector should start from.\n"
                + "  #\n"
                + "  # The available options are: \n"
                + "  #\n"
                + "  # - AT_TIMESTAMP \n"
                + "  #\n"
                + "  #   Start from the record at or after the specified timestamp. \n"
                + "  #\n"
                + "  # - LATEST \n"
                + "  #\n"
                + "  #   Start after the most recent data record (fetch new data). \n"
                + "  #\n"
                + "  # - TRIM_HORIZON \n"
                + "  #\n"
                + "  #   Start from the oldest available data record. \n"
    )
    private InitialPositionInStream initialPositionInStream = InitialPositionInStream.LATEST;

    @FieldDoc(
        required = false,
        defaultValue = "",
        help = "If the initalPositionInStream is set to 'AT_TIMESTAMP', then this "
                + " property specifies the point in time to start consumption."
    )
    private Date startAtTime;

    @FieldDoc(
        required = false,
        defaultValue = "pulsar-kinesis",
        help = "Name of the Amazon Kinesis application. By default the application name is included "
                + "in the user agent string used to make AWS requests. This can assist with troubleshooting "
                + "(e.g. distinguish requests made by separate connectors instances)."
    )
    private String applicationName = "pulsar-kinesis";

    @FieldDoc(
        required = false,
        defaultValue = "60000",
        help = "The frequency of the Kinesis stream checkpointing (in milliseconds)"
    )
    private long checkpointInterval = 60000L;

    @FieldDoc(
        required = false,
        defaultValue = "3000",
        help = "The amount of time to delay between requests when the connector encounters a Throttling"
                + "exception from AWS Kinesis (in milliseconds)"
    )
    private long backoffTime = 3000L;

    @FieldDoc(
        required = false,
        defaultValue = "3",
        help = "The number of re-attempts to make when the connector encounters an "
                + "exception while trying to set a checkpoint"
    )
    private int numRetries = 3;

    @FieldDoc(
        required = false,
        defaultValue = "1000",
        help = "The maximum number of AWS Records that can be buffered inside the connector. "
                + "Once this is reached, the connector will not consume any more messages from "
                + "Kinesis until some of the messages in the queue have been successfully consumed."
    )
    private int receiveQueueSize = 1000;

    @FieldDoc(
        required = false,
        defaultValue = "",
        help = "Dynamo end-point url. It can be found at https://docs.aws.amazon.com/general/latest/gr/rande.html"
    )
    private String dynamoEndpoint = "";

    @FieldDoc(
        required = false,
        defaultValue = "true",
        help = "When true, uses Kinesis enhanced fan-out, when false, uses polling"
    )
    private boolean useEnhancedFanOut = true;

    @FieldDoc(required = false,
            defaultValue = "kinesis.arrival.timestamp,kinesis.encryption.type,kinesis.partition.key,"
                    + "kinesis.sequence.number",
            help = "A comma-separated list of Kinesis metadata properties to include in the Pulsar message properties."
                    + " The supported properties are: kinesis.arrival.timestamp, kinesis.encryption.type, "
                    + "kinesis.partition.key, kinesis.sequence.number, kinesis.shard.id, kinesis.millis.behind.latest")
    private String kinesisRecordProperties = "kinesis.arrival.timestamp,kinesis.encryption.type,"
            + "kinesis.partition.key,kinesis.sequence.number";
    private transient Set<String> propertiesToInclude;

    public static KinesisSourceConfig load(Map<String, Object> config, SourceContext sourceContext) {
        KinesisSourceConfig kinesisSourceConfig = IOConfigUtils.loadWithSecrets(config,
                KinesisSourceConfig.class, sourceContext);
        boolean isNotBlankEndpoint = isNotBlank(kinesisSourceConfig.getAwsEndpoint())
                && isNotBlank(kinesisSourceConfig.getCloudwatchEndpoint())
                && isNotBlank(kinesisSourceConfig.getDynamoEndpoint());
        checkArgument(isNotBlank(kinesisSourceConfig.getAwsRegion()) || isNotBlankEndpoint,
                "Either \"awsRegion\" must be set OR all of "
                        + "[ \"awsEndpoint\", \"cloudwatchEndpoint\", and \"dynamoEndpoint\" ] must be set.");
        if (kinesisSourceConfig.getInitialPositionInStream() == InitialPositionInStream.AT_TIMESTAMP) {
            checkArgument((kinesisSourceConfig.getStartAtTime() != null),
                    "When initialPositionInStream is AT_TIMESTAMP, startAtTime must be specified");
        }
        if (isNotBlank(kinesisSourceConfig.getKinesisRecordProperties())) {
            Set<String> properties = Arrays.stream(kinesisSourceConfig.getKinesisRecordProperties().split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toSet());
            kinesisSourceConfig.setPropertiesToInclude(properties);
        } else {
            kinesisSourceConfig.setPropertiesToInclude(Collections.emptySet());
        }
        return kinesisSourceConfig;
    }

    public KinesisAsyncClient buildKinesisAsyncClient(AwsCredentialProviderPlugin credPlugin) {
        KinesisAsyncClientBuilder builder = KinesisAsyncClient.builder();

        if (!this.getAwsEndpoint().isEmpty()) {
            builder.endpointOverride(URI.create(this.getAwsEndpoint()));
        }
        if (!this.getAwsRegion().isEmpty()) {
            builder.region(this.regionAsV2Region());
        }
        builder.credentialsProvider(credPlugin.getV2CredentialsProvider());
        return KinesisClientUtil.createKinesisAsyncClient(builder);
    }

    public DynamoDbAsyncClient buildDynamoAsyncClient(AwsCredentialProviderPlugin credPlugin) {
        DynamoDbAsyncClientBuilder builder = DynamoDbAsyncClient.builder();

        if (!this.getDynamoEndpoint().isEmpty()) {
            builder.endpointOverride(URI.create(this.getDynamoEndpoint()));
        }
        if (!this.getAwsRegion().isEmpty()) {
            builder.region(this.regionAsV2Region());
        }
        builder.credentialsProvider(credPlugin.getV2CredentialsProvider());
        return builder.build();
    }

    public CloudWatchAsyncClient buildCloudwatchAsyncClient(AwsCredentialProviderPlugin credPlugin) {
        CloudWatchAsyncClientBuilder builder = CloudWatchAsyncClient.builder();

        if (!this.getCloudwatchEndpoint().isEmpty()) {
            builder.endpointOverride(URI.create(this.getCloudwatchEndpoint()));
        }
        if (!this.getAwsRegion().isEmpty()) {
            builder.region(this.regionAsV2Region());
        }
        builder.credentialsProvider(credPlugin.getV2CredentialsProvider());
        return builder.build();
    }

    public InitialPositionInStreamExtended getStreamStartPosition() {
        if (initialPositionInStream == InitialPositionInStream.AT_TIMESTAMP) {
            return InitialPositionInStreamExtended.newInitialPositionAtTimestamp(getStartAtTime());
        } else {
            return InitialPositionInStreamExtended.newInitialPosition(this.getInitialPositionInStream());
        }
    }
}
