/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.uber.athenax.vm.compiler.executor;

import com.oppo.dc.ostream.catalog.OStreamTableCatalogProvider;
import com.uber.athenax.vm.api.functions.AthenaXAggregateFunction;
import com.uber.athenax.vm.api.functions.AthenaXScalarFunction;
import com.uber.athenax.vm.api.functions.AthenaXTableFunction;
import com.uber.athenax.vm.api.tables.AthenaXTableCatalog;
import com.uber.athenax.vm.api.tables.AthenaXTableSinkProvider;
import org.apache.flink.api.common.restartstrategy.RestartStrategies;
import org.apache.flink.runtime.jobgraph.JobGraph;
import org.apache.flink.streaming.api.CheckpointingMode;
import org.apache.flink.streaming.api.TimeCharacteristic;
import org.apache.flink.streaming.api.environment.CheckpointConfig;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.graph.StreamGraph;
import org.apache.flink.table.api.Table;
import org.apache.flink.table.api.java.StreamTableEnvironment;
import org.apache.flink.table.catalog.ExternalCatalogTable;
import org.apache.flink.table.factories.TableFactoryUtil;
import org.apache.flink.table.functions.AggregateFunction;
import org.apache.flink.table.functions.ScalarFunction;
import org.apache.flink.table.functions.TableFunction;
import org.apache.flink.table.sinks.AppendStreamTableSink;
import org.apache.flink.types.Row;
import org.apache.flink.util.Preconditions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class JobCompiler {
  private static final Logger LOG = LoggerFactory.getLogger(JobCompiler.class);
  private final StreamTableEnvironment env;
  private final JobDescriptor job;

  private final OStreamTableCatalogProvider catalogProvider = new OStreamTableCatalogProvider();

  JobCompiler(StreamTableEnvironment env, JobDescriptor job) {
    this.job = job;
    this.env = env;
  }

  public static void main(String[] args) throws IOException {
    CompilationResult res = null;
    try {
      JobDescriptor job = getJobConf(System.in);
      res = compileJob(job);
    } catch (Throwable e) {
      res = new CompilationResult();
      res.remoteThrowable(e);
    }

    try (OutputStream out = chooseOutputStream(args)) {
      out.write(res.serialize());
    }
  }

  public static CompilationResult compileJob(JobDescriptor job) {
    StreamExecutionEnvironment execEnv = StreamExecutionEnvironment.createLocalEnvironment();
    StreamTableEnvironment env = StreamTableEnvironment.getTableEnvironment(execEnv);

//    // start a checkpoint every 1000 ms
//    execEnv.enableCheckpointing(10 * 60 * 1000);
//    // advanced options:
//    // set mode to exactly-once (this is the default)
//    execEnv.getCheckpointConfig().setCheckpointingMode(CheckpointingMode.EXACTLY_ONCE);
//    // make sure 500 ms of progress happen between checkpoints
//    execEnv.getCheckpointConfig().setMinPauseBetweenCheckpoints(60 * 1000);
//    // checkpoints have to complete within one minute, or are discarded
//    execEnv.getCheckpointConfig().setCheckpointTimeout(5 * 60 * 1000);
//    // allow only one checkpoint to be in progress at the same time
//    execEnv.getCheckpointConfig().setMaxConcurrentCheckpoints(1);
//    // enable externalized checkpoints which are retained after job cancellation
//    execEnv.getCheckpointConfig().enableExternalizedCheckpoints(CheckpointConfig.ExternalizedCheckpointCleanup.RETAIN_ON_CANCELLATION);

    // enable restarts
    execEnv.setRestartStrategy(RestartStrategies.fixedDelayRestart(
            Integer.MAX_VALUE, // number of restart attempts
            org.apache.flink.api.common.time.Time.of(10, TimeUnit.SECONDS) // delay
    ));

    execEnv.setStreamTimeCharacteristic(TimeCharacteristic.ProcessingTime);
    CompilationResult res = new CompilationResult();

    try {
      res.jobGraph(new JobCompiler(env, job).getJobGraph());
    } catch (IOException e) {
      res.remoteThrowable(e);
    }

    return res;
  }

  private static OutputStream chooseOutputStream(String[] args) throws IOException {
    if (args.length > 0) {
      int port = Integer.parseInt(args[0]);
      Socket sock = new Socket();
      sock.connect(new InetSocketAddress(InetAddress.getLocalHost(), port));
      return sock.getOutputStream();
    } else {
      return System.out;
    }
  }

  JobGraph getJobGraph() throws IOException {
    StreamExecutionEnvironment exeEnv = env.execEnv();
    exeEnv.setParallelism(job.parallelism());
    this
        .registerUdfs()
        .registerInputCatalogs();
    for(String sql: job.sql().split(";")) {
      env.sqlUpdate(sql);
    }

//    //AthenaXTableCatalog outputCatalog = catalogProvider.getOutputCatalog("", job.outputs());
//    for (String t : job.outputs()) {
//      //ExternalCatalogTable tb = outputCatalog.getTable(t);
//      //env.registerTableSink(t,TableFactoryUtil.findAndCreateTableSink(env, tb));
//      table.insertInto(t);
//    }
    StreamGraph streamGraph = exeEnv.getStreamGraph();
    streamGraph.setJobName(job.id().toString());
    return streamGraph.getJobGraph();
  }

  static JobDescriptor getJobConf(InputStream is) throws IOException, ClassNotFoundException {
    try (ObjectInputStream ois = new ObjectInputStream(is)) {
      return (JobDescriptor) ois.readObject();
    }
  }

  private JobCompiler registerUdfs() {
    for (Map.Entry<String, String> e : job.udf().entrySet()) {
      final String name = e.getKey();
      String clazzName = e.getValue();
      final Object udf;

      try {
        Class<?> clazz = Class.forName(clazzName);
        udf = clazz.newInstance();
      } catch (ClassNotFoundException | IllegalAccessException | InstantiationException ex) {
        throw new IllegalArgumentException("Invalid UDF " + name, ex);
      }

      if (udf instanceof ScalarFunction) {
        env.registerFunction(name, (ScalarFunction) udf);
      } else if (udf instanceof TableFunction) {
        env.registerFunction(name, (TableFunction<?>) udf);
      } else if (udf instanceof AthenaXAggregateFunction) {
        env.registerFunction(name, (AggregateFunction<?, ?>) udf);
      } else {
        LOG.warn("Unknown UDF {} was found.", clazzName);
      }
    }
    return this;
  }

  private JobCompiler registerInputCatalogs() {
    for (Map.Entry<String, AthenaXTableCatalog> e : catalogProvider.getInputCatalog("").entrySet()) {
      LOG.debug("Registering input catalog {}", e.getKey());
      env.registerExternalCatalog(e.getKey(), e.getValue());
    }
    return this;
  }

  private AppendStreamTableSink<Row> getOutputTable(
      ExternalCatalogTable output) throws IOException {
    AthenaXTableSinkProvider c = TableSinkProviderRegistry.getProvider(output);
    Preconditions.checkNotNull(c, "Cannot find output connectors for " + output);
    return c.getAppendStreamTableSink(output);
  }
}
