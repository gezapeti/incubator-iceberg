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

package org.apache.iceberg.spark.source;

import com.google.common.collect.ImmutableSet;
import java.util.Map;
import java.util.Set;
import org.apache.iceberg.Table;
import org.apache.iceberg.spark.SparkSchemaUtil;
import org.apache.iceberg.spark.SparkUtil;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.connector.catalog.SupportsRead;
import org.apache.spark.sql.connector.catalog.SupportsWrite;
import org.apache.spark.sql.connector.catalog.TableCapability;
import org.apache.spark.sql.connector.expressions.Transform;
import org.apache.spark.sql.connector.read.ScanBuilder;
import org.apache.spark.sql.connector.write.WriteBuilder;
import org.apache.spark.sql.types.StructType;
import org.apache.spark.sql.util.CaseInsensitiveStringMap;

class SparkTable implements org.apache.spark.sql.connector.catalog.Table, SupportsRead, SupportsWrite {

  private static final Set<TableCapability> CAPABILITIES = ImmutableSet.of(
      TableCapability.BATCH_READ,
      TableCapability.BATCH_WRITE,
      TableCapability.STREAMING_WRITE,
      TableCapability.OVERWRITE_BY_FILTER,
      TableCapability.OVERWRITE_DYNAMIC);

  private final Table icebergTable;
  private final StructType requestedSchema;
  private StructType lazyTableSchema = null;
  private SparkSession lazySpark = null;

  SparkTable(Table icebergTable, StructType requestedSchema) {
    this.icebergTable = icebergTable;
    this.requestedSchema = requestedSchema;

    if (requestedSchema != null) {
      // convert the requested schema to throw an exception if any requested fields are unknown
      SparkSchemaUtil.convert(icebergTable.schema(), requestedSchema);
    }
  }

  private SparkSession sparkSession() {
    if (lazySpark == null) {
      this.lazySpark = SparkSession.active();
    }

    return lazySpark;
  }

  @Override
  public String name() {
    return icebergTable.toString();
  }

  @Override
  public StructType schema() {
    if (lazyTableSchema == null) {
      if (requestedSchema != null) {
        this.lazyTableSchema = SparkSchemaUtil.convert(SparkSchemaUtil.prune(icebergTable.schema(), requestedSchema));
      } else {
        this.lazyTableSchema = SparkSchemaUtil.convert(icebergTable.schema());
      }
    }

    return lazyTableSchema;
  }

  @Override
  public Transform[] partitioning() {
    return SparkUtil.toTransforms(icebergTable.spec());
  }

  @Override
  public Map<String, String> properties() {
    return icebergTable.properties();
  }

  @Override
  public Set<TableCapability> capabilities() {
    return CAPABILITIES;
  }

  @Override
  public ScanBuilder newScanBuilder(CaseInsensitiveStringMap options) {
    SparkScanBuilder scanBuilder = new SparkScanBuilder(sparkSession(), icebergTable, options);
    if (requestedSchema != null) {
      scanBuilder.pruneColumns(requestedSchema);
    }

    return scanBuilder;
  }

  @Override
  public WriteBuilder newWriteBuilder(CaseInsensitiveStringMap options) {
    return new SparkWriteBuilder(sparkSession(), icebergTable, options);
  }

}