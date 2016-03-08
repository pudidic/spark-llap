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

package org.apache.spark.sql.hive.llap

import collection.JavaConversions._

import org.apache.spark.Logging
import org.apache.spark.sql.SQLContext
import org.apache.spark.rdd.HadoopRDD
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.Row
import org.apache.spark.sql.sources.BaseRelation
import org.apache.spark.sql.sources.Filter
import org.apache.spark.sql.sources.TableScan
import org.apache.spark.sql.sources.PrunedFilteredScan
import org.apache.spark.sql.types.StructType

import java.sql.Connection
import java.util.Properties
import java.util.UUID

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.mapred.JobConf
import org.apache.hadoop.mapred.InputFormat
import org.apache.hadoop.mapred.InputSplit
import org.apache.hadoop.io.NullWritable
import org.apache.hadoop.io.Text
import org.apache.hadoop.io.Writable

// TODO: this may need to be replaced
import org.apache.hadoop.hive.metastore.api.FieldSchema


case class LlapRelation(@transient sc: SQLContext, @transient val parameters: Map[String, String])
  extends BaseRelation
  with PrunedFilteredScan
  with Logging {

  override def sqlContext(): SQLContext = {
    sc
  }

  @transient val tableSchema:StructType = {
    val queryKey = getQueryType()
    if (queryKey == "table") {
      DefaultJDBCWrapper.resolveTable(getConnection(), parameters("table"))
    } else {
      DefaultJDBCWrapper.resolveQuery(getConnection(), parameters("query"))
    }
  }

  override def schema(): StructType = {
    logDebug("tableSchema = " + tableSchema)
    tableSchema
  }

  def getQueryType(): String = {
    val hasTable = parameters.isDefinedAt("table")
    val hasQuery = parameters.isDefinedAt("query")
    if (hasTable && hasQuery) {
      throw new Exception("LlapRelation has both table and query parameters, can only have one or the other")
    }
    if (!hasTable && !hasQuery) {
      throw new Exception("LlapRelation requires either a table or query parameter")
    }
    if (hasTable) {
      "table"
    } else {
      "query"
    }
  }

  // PrunedFilteredScan implementation
  override def buildScan(requiredColumns: Array[String], filters: Array[Filter]): RDD[Row] = {
    val queryString = getQueryString(requiredColumns, filters)

    @transient val inputFormatClass = Class.forName("org.apache.hive.jdbc.LlapInputFormat")
      .asInstanceOf[Class[_ <: InputFormat[NullWritable, Text]]]
    @transient val jobConf = new JobConf(sc.sparkContext.hadoopConfiguration)
    // Set JDBC url/etc
    jobConf.set("llap.if.hs2.connection", parameters("url"))
    jobConf.set("llap.if.query", queryString)
    var userName = getUser()
    jobConf.set("llap.if.user", userName)
    jobConf.set("llap.if.pwd", "password")

    // This should be set to the number of executors
    var numPartitions = sc.sparkContext.defaultMinPartitions


    //val rdd = sc.sparkContext.hadoopRDD(jobConf, inputFormatClass,
    //  classOf[NullWritable], classOf[Text], numPartitions).asInstanceOf[HadoopRDD[NullWritable, Text]]

    // For debugging
    val rdd = new HadoopRDD(sc.sparkContext, jobConf, inputFormatClass,
        classOf[NullWritable], classOf[Text], numPartitions) with OverrideRDD[(NullWritable, Text)]

    // Convert from RDD into Spark Rows
    val preservesPartitioning = false; // ???
    rdd.mapPartitionsWithInputSplit(LlapRelation.textRddToRows, preservesPartitioning)
  }

  private def getQueryString(requiredColumns: Array[String], filters: Array[Filter]): String = {
    logDebug("requiredColumns: " + requiredColumns.mkString(","))
    logDebug("filters: " + filters.mkString(","))

    var selectCols = "*"
    if (requiredColumns.length > 0) {
      selectCols = requiredColumns.mkString(",")
    }

    val baseQuery = getQueryType match {
      case "table" => "select * from " + parameters("table")
      case "query" => parameters("query")
    }
    val baseQueryAlias = "q_" + UUID.randomUUID().toString().replaceAll("[^A-Za-z0-9 ]", "")

    val whereClause = FilterPushdown.buildWhereClause(schema, filters)

    var queryString =
      s"""select $selectCols from ($baseQuery) as $baseQueryAlias $whereClause"""
    logDebug("Generated queryString: " + queryString);

    queryString
  }

  def getUser(): String = {
    sc match {
      case hs2Context: LlapContext => hs2Context.userName
      case _ => {
        LlapContext.getUser()
      }
    }
  }

  def getConnection(): Connection = {
    sc match {
      case hs2Context: LlapContext => hs2Context.connection
      case _ => {
        //throw new Exception("TODO not supported")
        DefaultJDBCWrapper.getConnector(None, parameters("url"), getUser())
      }
    }
  }
  
}


import org.apache.spark.Partition
import org.apache.spark.SerializableWritable
import org.apache.spark.rdd.HadoopPartition
import org.apache.spark.serializer.JavaSerializer

// Override getPartitions() for some debugging
trait OverrideRDD[T] extends RDD[T] {
  abstract override def getPartitions: Array[Partition] = {
    val partitions = super.getPartitions
    logDebug("Partitions = " + partitions.map(part => part.toString()).mkString(","))
    partitions
  }
}

object LlapRelation {
  /**
   * Convert the RDD from LLAP (Text data) into Spark Rows
   */
  def textRddToRows(inputSplit:InputSplit, iterator:Iterator[(Writable, Text)]): Iterator[Row] = {
    val jdbcllapInputSplit = inputSplit.asInstanceOf[org.apache.hive.jdbc.LlapInputSplit[Text]]
    val llapInputSplit = jdbcllapInputSplit.getSplit.asInstanceOf[org.apache.hadoop.hive.llap.LlapInputSplit]
    val schema:Seq[FieldSchema] = llapInputSplit.getSchema.getFieldSchemas
    val colNames = schema.map(fieldSchema => { fieldSchema.getName })
    val colTypes = schema.map(fieldSchema => { DataTypeUtils.parse(fieldSchema.getType) })
    val props = new Properties

    // Parse the row as delimited text
    val parser = new TextRowParser(colNames, colTypes, props)
    iterator.map((tuple) => {
      val row = parser.parseRow(tuple._2)
      row
    })
  }
}