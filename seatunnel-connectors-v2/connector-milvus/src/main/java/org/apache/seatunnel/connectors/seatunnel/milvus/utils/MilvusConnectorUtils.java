package org.apache.seatunnel.connectors.seatunnel.milvus.utils;

import io.milvus.client.MilvusClient;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.DescribeCollectionResponse;
import io.milvus.grpc.FieldSchema;
import io.milvus.param.ConnectParam;
import io.milvus.param.R;
import io.milvus.param.collection.DescribeCollectionParam;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.DescribeCollectionReq;
import io.milvus.v2.service.collection.response.DescribeCollectionResp;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class MilvusConnectorUtils {

    public static Boolean hasPartitionKey(MilvusClientV2 milvusClient, String collectionName) {

        DescribeCollectionResp describeCollectionResp = milvusClient.describeCollection(
                DescribeCollectionReq.builder()
                        .collectionName(collectionName)
                        .build());
        return describeCollectionResp.getCollectionSchema().getFieldSchemaList().stream()
                .anyMatch(CreateCollectionReq.FieldSchema::getIsPartitionKey);
    }
}
