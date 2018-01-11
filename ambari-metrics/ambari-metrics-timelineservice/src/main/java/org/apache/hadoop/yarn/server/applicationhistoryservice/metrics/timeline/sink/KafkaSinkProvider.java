/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.yarn.server.applicationhistoryservice.metrics.timeline.sink;

import static org.apache.hadoop.yarn.server.applicationhistoryservice.metrics.timeline.TimelineMetricConfiguration.KAFKA_ACKS;
import static org.apache.hadoop.yarn.server.applicationhistoryservice.metrics.timeline.TimelineMetricConfiguration.KAFKA_BATCH_SIZE;
import static org.apache.hadoop.yarn.server.applicationhistoryservice.metrics.timeline.TimelineMetricConfiguration.KAFKA_BUFFER_MEM;
import static org.apache.hadoop.yarn.server.applicationhistoryservice.metrics.timeline.TimelineMetricConfiguration.KAFKA_LINGER_MS;
import static org.apache.hadoop.yarn.server.applicationhistoryservice.metrics.timeline.TimelineMetricConfiguration.KAFKA_RETRIES;
import static org.apache.hadoop.yarn.server.applicationhistoryservice.metrics.timeline.TimelineMetricConfiguration.KAFKA_SERVERS;
import static org.apache.hadoop.yarn.server.applicationhistoryservice.metrics.timeline.TimelineMetricConfiguration.KAFKA_SINK_TIMEOUT_SECONDS;
import static org.apache.hadoop.yarn.server.applicationhistoryservice.metrics.timeline.TimelineMetricConfiguration.TIMELINE_METRICS_CACHE_COMMIT_INTERVAL;

import java.util.Collection;
import java.util.Properties;
import java.util.concurrent.Future;

import kafka.admin.AdminUtils;
import kafka.admin.RackAwareMode;
import kafka.admin.RackAwareMode$;
import kafka.utils.ZKStringSerializer$;
import kafka.utils.ZkUtils;
import org.I0Itec.zkclient.ZkClient;
import org.I0Itec.zkclient.ZkConnection;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.metrics2.sink.timeline.TimelineMetrics;
import org.apache.hadoop.yarn.server.applicationhistoryservice.metrics.timeline.TimelineMetricConfiguration;
import org.apache.hadoop.yarn.server.applicationhistoryservice.metrics.timeline.source.InternalSourceProvider.SOURCE_NAME;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/*
  This will be used by the single Metrics committer thread. Hence it is
  important to make this non-blocking export.
 */
public class KafkaSinkProvider implements ExternalSinkProvider {
  private static String TOPIC_NAME = "ambari-metrics-topic";
  private static final Log LOG = LogFactory.getLog(KafkaSinkProvider.class);

  private Producer producer;
  private int TIMEOUT_SECONDS = 10;
  private int FLUSH_SECONDS = 3;

  ObjectMapper objectMapper = new ObjectMapper();

  public KafkaSinkProvider() {
    TimelineMetricConfiguration configuration = TimelineMetricConfiguration.getInstance();

    Properties configProperties = new Properties();
    try {
      configProperties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, configuration.getMetricsConf().getTrimmed(KAFKA_SERVERS));
      configProperties.put(ProducerConfig.ACKS_CONFIG, configuration.getMetricsConf().getTrimmed(KAFKA_ACKS, "all"));
      // Avoid duplicates - No transactional semantics
      configProperties.put(ProducerConfig.RETRIES_CONFIG, configuration.getMetricsConf().getInt(KAFKA_RETRIES, 0));
      configProperties.put(ProducerConfig.BATCH_SIZE_CONFIG, configuration.getMetricsConf().getInt(KAFKA_BATCH_SIZE, 128));
      configProperties.put(ProducerConfig.LINGER_MS_CONFIG, configuration.getMetricsConf().getInt(KAFKA_LINGER_MS, 1));
      configProperties.put(ProducerConfig.BUFFER_MEMORY_CONFIG, configuration.getMetricsConf().getLong(KAFKA_BUFFER_MEM, 33554432)); // 32 MB
      configProperties.put("partitioner.class", "org.apache.hadoop.yarn.server.applicationhistoryservice.metrics.timeline.sink.MetricAppIdKafkaPartitioner");
      FLUSH_SECONDS = configuration.getMetricsConf().getInt(TIMELINE_METRICS_CACHE_COMMIT_INTERVAL, 3);
      TIMEOUT_SECONDS = configuration.getMetricsConf().getInt(KAFKA_SINK_TIMEOUT_SECONDS, 10);
    } catch (Exception e) {
      LOG.error("Configuration error!", e);
      throw new ExceptionInInitializerError(e);
    }
    configProperties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,"org.apache.kafka.common.serialization.ByteArraySerializer");
    configProperties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,"org.apache.kafka.connect.json.JsonSerializer");

    createKafkaTopic();
    producer = new KafkaProducer(configProperties);
  }

  private void createKafkaTopic() {
    ZkClient zkClient = null;
    ZkUtils zkUtils = null;
    try {
      String zookeeperHosts = TimelineMetricConfiguration.getInstance().getClusterZKQuorum();
      int sessionTimeOutInMs = 15 * 1000;
      int connectionTimeOutInMs = 10 * 1000;

      zkClient = new ZkClient(zookeeperHosts, sessionTimeOutInMs, connectionTimeOutInMs, ZKStringSerializer$.MODULE$);
      zkUtils = new ZkUtils(zkClient, new ZkConnection(zookeeperHosts), false);

      String topicName = TOPIC_NAME;
      int noOfPartitions = 4;
      int noOfReplication = 1;
      Properties topicConfiguration = new Properties();

      AdminUtils.createTopic(zkUtils, topicName, noOfPartitions, noOfReplication, topicConfiguration, RackAwareMode.Disabled$.MODULE$);
    } catch (Exception ex) {
      LOG.error(ex);
    } finally {
      if (zkClient != null) {
        zkClient.close();
      }
    }
  }

  @Override
  public ExternalMetricsSink getExternalMetricsSink(SOURCE_NAME sourceName) {
    switch (sourceName) {
      case RAW_METRICS:
        return new KafkaRawMetricsSink();
      default:
        throw new UnsupportedOperationException("Provider does not support " +
          "the expected source " + sourceName);
    }
  }

  class KafkaRawMetricsSink implements ExternalMetricsSink {

    @Override
    public int getSinkTimeOutSeconds() {
      return TIMEOUT_SECONDS;
    }

    @Override
    public int getFlushSeconds() {
      return FLUSH_SECONDS;
    }

    @Override
    public void sinkMetricData(Collection<TimelineMetrics> metrics) {
      for (TimelineMetrics timelineMetrics : metrics) {
        if (CollectionUtils.isNotEmpty(timelineMetrics.getMetrics())) {
          if (timelineMetrics.getMetrics().get(0).getAppId().equalsIgnoreCase("HOST")) {
            JsonNode jsonNode = objectMapper.valueToTree(timelineMetrics);
            ProducerRecord<String, JsonNode> rec = new ProducerRecord<String, JsonNode>(TOPIC_NAME, jsonNode);
            Future<RecordMetadata> f = producer.send(rec);
          }
        }
      }
    }
  }
}
