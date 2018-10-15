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

package com.uber.athenax.vm.compiler.planner;

import com.uber.athenax.vm.compiler.executor.CompilationResult;
import com.uber.athenax.vm.compiler.executor.ContainedExecutor;
import com.uber.athenax.vm.compiler.executor.JobDescriptor;
import com.uber.athenax.vm.compiler.parser.impl.ParseException;
import com.uber.athenax.vm.compiler.parser.impl.SqlParserImpl;
import org.apache.calcite.config.Lex;
import org.apache.calcite.sql.SqlNodeList;
import org.apache.flink.annotation.VisibleForTesting;
import org.apache.hadoop.fs.Path;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;

public class Planner {
  private static final int DEFAULT_IDENTIFIER_MAX_LENGTH = 128;

  private List<String> outputs;

  public Planner(List<String> outputs) {
    this.outputs = outputs;
  }

  public JobCompilationResult sql(String sql, int parallelism) throws Throwable {
    // TODO: udfCreateSQL get from somewhere
//    String udfCreateSQL =
//            "CREATE FUNCTION myUDF AS 'com.oppo.dc.ostream.udf.MyUDF' USING JAR 'hdfs://bj1230:8020/ostream/ostream-table-1.0-SNAPSHOT.jar';" +
//            "CREATE FUNCTION myUDF1 AS 'com.oppo.dc.ostream.udf.MyUDF1' USING JAR 'hdfs://bj1230:8020/ostream/ostream-table-1.0-SNAPSHOT.jar';";
//
//    SqlNodeList stmtsUDF = parse(udfCreateSQL);
//    Validator validatorUDF = new Validator();
//    validatorUDF.validateUDF(stmtsUDF);

    StringBuilder sbSQL = new StringBuilder();
    for(String splitSql: sql.split(";")) {
      SqlNodeList stmts = parse(splitSql);
      Validator validator = new Validator();
      validator.validateInsert(stmts);
      sbSQL.append(validator.statement().toString() + ";");
    }

    JobDescriptor job = new JobDescriptor(
        new HashMap<>(),
        outputs,
        parallelism,
        sbSQL.toString());

    // uses contained executor instead of direct compile for: JobCompiler.compileJob(job);
    CompilationResult res = new ContainedExecutor().run(job);

    if (res.remoteThrowable() != null) {
      throw res.remoteThrowable();
    }
    return new JobCompilationResult(res.jobGraph(),
        new ArrayList<>());
  }

  @VisibleForTesting
  static SqlNodeList parse(String sql) throws ParseException {
    // Keep the SQL syntax consistent with Flink
    try (StringReader in = new StringReader(sql)) {
      SqlParserImpl impl = new SqlParserImpl(in);

      // back tick as the quote
      impl.switchTo("BTID");
      impl.setTabSize(1);
      impl.setQuotedCasing(Lex.JAVA.quotedCasing);
      impl.setUnquotedCasing(Lex.JAVA.unquotedCasing);
      impl.setIdentifierMaxLength(DEFAULT_IDENTIFIER_MAX_LENGTH);
      return impl.SqlStmtsEof();
    }
  }
}
