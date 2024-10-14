/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.seatunnel.connectors.seatunnel.milvus.utils;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import com.google.protobuf.ProtocolStringList;
import io.milvus.client.MilvusServiceClient;
import io.milvus.common.utils.JacksonUtils;
import io.milvus.grpc.*;
import io.milvus.param.ConnectParam;
import io.milvus.param.R;
import io.milvus.param.collection.DescribeCollectionParam;
import io.milvus.param.collection.ShowCollectionsParam;
import io.milvus.param.index.DescribeIndexParam;
import io.milvus.param.partition.ShowPartitionsParam;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.hadoop.util.Lists;
import org.apache.seatunnel.api.configuration.ReadonlyConfig;
import org.apache.seatunnel.api.table.catalog.*;
import org.apache.seatunnel.api.table.catalog.exception.CatalogException;
import org.apache.seatunnel.api.table.type.*;
import org.apache.seatunnel.common.utils.BufferUtils;
import org.apache.seatunnel.common.utils.JsonUtils;
import org.apache.seatunnel.connectors.seatunnel.milvus.catalog.MilvusOptions;
import org.apache.seatunnel.connectors.seatunnel.milvus.config.MilvusSourceConfig;
import org.apache.seatunnel.connectors.seatunnel.milvus.exception.MilvusConnectionErrorCode;
import org.apache.seatunnel.connectors.seatunnel.milvus.exception.MilvusConnectorException;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
public class MilvusConvertUtils {

    private static final Gson gson = new Gson();
    private final ReadonlyConfig config;

    public MilvusConvertUtils(ReadonlyConfig config) {
        this.config = config;
    }

    public Map<TablePath, CatalogTable> getSourceTables() {
        MilvusServiceClient client =
                new MilvusServiceClient(
                        ConnectParam.newBuilder()
                                .withUri(config.get(MilvusSourceConfig.URL))
                                .withToken(config.get(MilvusSourceConfig.TOKEN))
                                .build());

        String database = config.get(MilvusSourceConfig.DATABASE);
        List<String> collectionList = new ArrayList<>();
        if (StringUtils.isNotEmpty(config.get(MilvusSourceConfig.COLLECTION))) {
            collectionList.add(config.get(MilvusSourceConfig.COLLECTION));
        } else {
            R<ShowCollectionsResponse> response =
                    client.showCollections(
                            ShowCollectionsParam.newBuilder()
                                    .withDatabaseName(database)
                                    .withShowType(ShowType.All)
                                    .build());
            if (response.getStatus() != R.Status.Success.getCode()) {
                throw new MilvusConnectorException(
                        MilvusConnectionErrorCode.SHOW_COLLECTIONS_ERROR);
            }

            ProtocolStringList collections = response.getData().getCollectionNamesList();
            if (CollectionUtils.isEmpty(collections)) {
                throw new MilvusConnectorException(
                        MilvusConnectionErrorCode.DATABASE_NO_COLLECTIONS, database);
            }
            collectionList.addAll(collections);
        }

        Map<TablePath, CatalogTable> map = new HashMap<>();
        for (String collection : collectionList) {
            CatalogTable catalogTable = getCatalogTable(client, database, collection);
            TablePath tablePath = TablePath.of(database, null, collection);
            map.put(tablePath, catalogTable);

        }
        return map;
    }

    public CatalogTable getCatalogTable(
            MilvusServiceClient client, String database, String collection) {
        R<DescribeCollectionResponse> response =
                client.describeCollection(
                        DescribeCollectionParam.newBuilder()
                                .withDatabaseName(database)
                                .withCollectionName(collection)
                                .build());

        if (response.getStatus() != R.Status.Success.getCode()) {
            throw new MilvusConnectorException(MilvusConnectionErrorCode.DESC_COLLECTION_ERROR);
        }
        log.info("describe collection database: {}, collection: {}, response: {}", database, collection, response);
        // collection column
        DescribeCollectionResponse collectionResponse = response.getData();
        CollectionSchema schema = collectionResponse.getSchema();
        List<Column> columns = new ArrayList<>();
        boolean existPartitionKeyField = false;
        String partitionKeyField = null;
        for (FieldSchema fieldSchema : schema.getFieldsList()) {
            columns.add(MilvusConvertUtils.convertColumn(fieldSchema));
            if (fieldSchema.getIsPartitionKey()) {
                existPartitionKeyField = true;
                partitionKeyField = fieldSchema.getName();
            }
        }

        // primary key
        PrimaryKey primaryKey = buildPrimaryKey(schema.getFieldsList());

        // index
        R<DescribeIndexResponse> describeIndexResponseR =
                client.describeIndex(
                        DescribeIndexParam.newBuilder()
                                .withDatabaseName(database)
                                .withCollectionName(collection)
                                .build());
        if (describeIndexResponseR.getStatus() != R.Status.Success.getCode()) {
            throw new MilvusConnectorException(MilvusConnectionErrorCode.DESC_INDEX_ERROR);
        }
        DescribeIndexResponse indexResponse = describeIndexResponseR.getData();
        List<ConstraintKey.ConstraintKeyColumn> vectorIndexes = buildVectorIndexes(indexResponse);

        // build tableSchema
        TableSchema tableSchema =
                TableSchema.builder()
                        .columns(columns)
                        .primaryKey(primaryKey)
                        .constraintKey(
                                ConstraintKey.of(
                                        ConstraintKey.ConstraintType.VECTOR_INDEX_KEY,
                                        "vector_index",
                                        vectorIndexes))
                        .build();

        // build tableId
        String CATALOG_NAME = "Milvus";
        TableIdentifier tableId = TableIdentifier.of(CATALOG_NAME, database, null, collection);
        // build options info
        Map<String, String> options = new HashMap<>();
        options.put(MilvusOptions.ENABLE_DYNAMIC_FIELD, String.valueOf(schema.getEnableDynamicField()));
        options.put(MilvusOptions.SHARDS_NUM, String.valueOf(collectionResponse.getShardsNum()));
        if (existPartitionKeyField) {
            options.put(MilvusOptions.PARTITION_KEY_FIELD, partitionKeyField);
        } else {
            fillPartitionNames(options, client, database, collection);
        }

        return CatalogTable.of(
                tableId, tableSchema, options, new ArrayList<>(), schema.getDescription());
    }

    private static void fillPartitionNames(Map<String, String> options,  MilvusServiceClient client, String database, String collection){
        // not exist partition key, will read partition
        R<ShowPartitionsResponse> partitionsResponseR = client.showPartitions(ShowPartitionsParam.newBuilder()
                .withDatabaseName(database)
                .withCollectionName(collection)
                .build());
        if (partitionsResponseR.getStatus() != R.Status.Success.getCode()) {
            throw new MilvusConnectorException(MilvusConnectionErrorCode.SHOW_PARTITION_ERROR, partitionsResponseR.getMessage());
        }

        ProtocolStringList partitionNamesList = partitionsResponseR.getData().getPartitionNamesList();
        List<String> list = new ArrayList<>();
        for (String partition : partitionNamesList) {
            if (partition.equals("_default")){
                continue;
            }
            list.add(partition);
        }
        if (CollectionUtils.isEmpty(partitionNamesList)) {
            return;
        }

        options.put(MilvusOptions.PARTITION_NAMES, String.join(",", list));
    }

    private static List<ConstraintKey.ConstraintKeyColumn> buildVectorIndexes(
            DescribeIndexResponse indexResponse) {
        if (CollectionUtils.isEmpty(indexResponse.getIndexDescriptionsList())) {
            return null;
        }

        List<ConstraintKey.ConstraintKeyColumn> list = new ArrayList<>();
        for (IndexDescription per : indexResponse.getIndexDescriptionsList()) {
            Map<String, String> paramsMap =
                    per.getParamsList().stream()
                            .collect(
                                    Collectors.toMap(KeyValuePair::getKey, KeyValuePair::getValue));

            VectorIndex index =
                    new VectorIndex(
                            per.getIndexName(),
                            per.getFieldName(),
                            paramsMap.get("index_type"),
                            paramsMap.get("metric_type"));

            list.add(index);
        }

        return list;
    }

    public static PrimaryKey buildPrimaryKey(List<FieldSchema> fields) {
        for (FieldSchema field : fields) {
            if (field.getIsPrimaryKey()) {
                return PrimaryKey.of(
                        field.getName(), Lists.newArrayList(field.getName()), field.getAutoID());
            }
        }

        return null;
    }

    public static PhysicalColumn convertColumn(FieldSchema fieldSchema) {
        DataType dataType = fieldSchema.getDataType();
        PhysicalColumn.PhysicalColumnBuilder builder = PhysicalColumn.builder();
        builder.name(fieldSchema.getName());
        builder.sourceType(dataType.name());
        builder.comment(fieldSchema.getDescription());

        switch (dataType) {
            case Bool:
                builder.dataType(BasicType.BOOLEAN_TYPE);
                break;
            case Int8:
                builder.dataType(BasicType.BYTE_TYPE);
                break;
            case Int16:
                builder.dataType(BasicType.SHORT_TYPE);
                break;
            case Int32:
                builder.dataType(BasicType.INT_TYPE);
                break;
            case Int64:
                builder.dataType(BasicType.LONG_TYPE);
                break;
            case Float:
                builder.dataType(BasicType.FLOAT_TYPE);
                break;
            case Double:
                builder.dataType(BasicType.DOUBLE_TYPE);
                break;
            case VarChar:
                builder.dataType(BasicType.STRING_TYPE);
                for (KeyValuePair keyValuePair : fieldSchema.getTypeParamsList()) {
                    if (keyValuePair.getKey().equals("max_length")) {
                        builder.columnLength(Long.parseLong(keyValuePair.getValue()) * 4);
                        break;
                    }
                }
                break;
            case String:
                builder.dataType(BasicType.STRING_TYPE);
                break;
            case JSON:
                builder.dataType(BasicType.JSON_TYPE);
                break;
            case Array:
                builder.dataType(ArrayType.STRING_ARRAY_TYPE);
                break;
            case FloatVector:
                builder.dataType(VectorType.VECTOR_FLOAT_TYPE);
                for (KeyValuePair keyValuePair : fieldSchema.getTypeParamsList()) {
                    if (keyValuePair.getKey().equals("dim")) {
                        builder.scale(Integer.valueOf(keyValuePair.getValue()));
                        break;
                    }
                }
                break;
            case BinaryVector:
                builder.dataType(VectorType.VECTOR_BINARY_TYPE);
                for (KeyValuePair keyValuePair : fieldSchema.getTypeParamsList()) {
                    if (keyValuePair.getKey().equals("dim")) {
                        builder.scale(Integer.valueOf(keyValuePair.getValue()));
                        break;
                    }
                }
                break;
            case SparseFloatVector:
                builder.dataType(VectorType.VECTOR_SPARSE_FLOAT_TYPE);
                break;
            case Float16Vector:
                builder.dataType(VectorType.VECTOR_FLOAT16_TYPE);
                for (KeyValuePair keyValuePair : fieldSchema.getTypeParamsList()) {
                    if (keyValuePair.getKey().equals("dim")) {
                        builder.scale(Integer.valueOf(keyValuePair.getValue()));
                        break;
                    }
                }
                break;
            case BFloat16Vector:
                builder.dataType(VectorType.VECTOR_BFLOAT16_TYPE);
                for (KeyValuePair keyValuePair : fieldSchema.getTypeParamsList()) {
                    if (keyValuePair.getKey().equals("dim")) {
                        builder.scale(Integer.valueOf(keyValuePair.getValue()));
                        break;
                    }
                }
                break;
            default:
                throw new UnsupportedOperationException("Unsupported data type: " + dataType);
        }

        return builder.build();
    }

    public static Object convertBySeaTunnelType(SeaTunnelDataType<?> fieldType, Object value) {
        SqlType sqlType = fieldType.getSqlType();
        switch (sqlType) {
            case INT:
                return Integer.parseInt(value.toString());
            case TINYINT:
                return Byte.parseByte(value.toString());
            case BIGINT:
                return Long.parseLong(value.toString());
            case SMALLINT:
                return Short.parseShort(value.toString());
            case STRING:
            case DATE:
                return value.toString();
            case JSON:
                return value;
            case FLOAT_VECTOR:
                ByteBuffer floatVectorBuffer = (ByteBuffer) value;
                Float[] floats = BufferUtils.toFloatArray(floatVectorBuffer);
                return Arrays.stream(floats).collect(Collectors.toList());
            case BINARY_VECTOR:
            case BFLOAT16_VECTOR:
            case FLOAT16_VECTOR:
                ByteBuffer binaryVector = (ByteBuffer) value;
                return gson.toJsonTree(binaryVector.array());
            case SPARSE_FLOAT_VECTOR:
                return JsonParser.parseString(JacksonUtils.toJsonString(value)).getAsJsonObject();
            case FLOAT:
                return Float.parseFloat(value.toString());
            case BOOLEAN:
                return Boolean.parseBoolean(value.toString());
            case DOUBLE:
                return Double.parseDouble(value.toString());
            case ARRAY:
                ArrayType<?, ?> arrayType = (ArrayType<?, ?>) fieldType;
                switch (arrayType.getElementType().getSqlType()) {
                    case STRING:
                        String[] stringArray = (String[]) value;
                        return Arrays.asList(stringArray);
                    case SMALLINT:
                        Short[] shortArray = (Short[]) value;
                        return Arrays.asList(shortArray);
                    case TINYINT:
                        Byte[] byteArray = (Byte[]) value;
                        return Arrays.asList(byteArray);
                    case INT:
                        Integer[] intArray = (Integer[]) value;
                        return Arrays.asList(intArray);
                    case BIGINT:
                        Long[] longArray = (Long[]) value;
                        return Arrays.asList(longArray);
                    case FLOAT:
                        Float[] floatArray = (Float[]) value;
                        return Arrays.asList(floatArray);
                    case DOUBLE:
                        Double[] doubleArray = (Double[]) value;
                        return Arrays.asList(doubleArray);
                }
            case ROW:
                SeaTunnelRow row = (SeaTunnelRow) value;
                return JsonUtils.toJsonString(row.getFields());
            case MAP:
                return JacksonUtils.toJsonString(value);
            default:
                throw new MilvusConnectorException(
                        MilvusConnectionErrorCode.NOT_SUPPORT_TYPE, sqlType.name());
        }
    }

    public static DataType convertSqlTypeToDataType(SqlType sqlType) {
        switch (sqlType) {
            case BOOLEAN:
                return DataType.Bool;
            case TINYINT:
                return DataType.Int8;
            case SMALLINT:
                return DataType.Int16;
            case INT:
                return DataType.Int32;
            case BIGINT:
                return DataType.Int64;
            case FLOAT:
                return DataType.Float;
            case DOUBLE:
                return DataType.Double;
            case STRING:
                return DataType.VarChar;
            case JSON:
                return DataType.JSON;
            case ARRAY:
                return DataType.Array;
            case FLOAT_VECTOR:
                return DataType.FloatVector;
            case BINARY_VECTOR:
                return DataType.BinaryVector;
            case FLOAT16_VECTOR:
                return DataType.Float16Vector;
            case BFLOAT16_VECTOR:
                return DataType.BFloat16Vector;
            case SPARSE_FLOAT_VECTOR:
                return DataType.SparseFloatVector;
            case DATE:
                return DataType.VarChar;
            case ROW:
                return DataType.VarChar;
        }
        throw new CatalogException(
                String.format("Not support convert to milvus type, sqlType is %s", sqlType));
    }
}