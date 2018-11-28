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
//    String udfJarPath = "hdfs://bj1181:8020/ostream/udf/ostream-table-1.0-SNAPSHOT.jar";
    String udfJarPath = "hdfs://bj1230:8020/ostream/udf/ostream-table-1.0-SNAPSHOT.jar";
    String udfCreateSQL =
            // common
            "CREATE FUNCTION tsToFormatDate AS 'com.oppo.dc.ostream.udf.common.TimeStampToFormatDate' USING JAR '" + udfJarPath + "';" +
            "CREATE FUNCTION mapToJSONString AS 'com.oppo.dc.ostream.udf.common.MapToJSONString' USING JAR '" + udfJarPath + "';" +
            // app_install
            "CREATE FUNCTION toId AS 'com.oppo.dc.ostream.udf.AppPacakgeToId' USING JAR '" + udfJarPath + "';" +
            // browse_feeds
            "CREATE FUNCTION mySplit AS 'com.oppo.dc.ostream.udf.feeds.MySplit' USING JAR '" + udfJarPath + "';" +
            "CREATE FUNCTION toTimeStamp AS 'com.oppo.dc.ostream.udf.feeds.DateToTimeStamp' USING JAR '" + udfJarPath + "';" +
            // push_log_count
            "CREATE FUNCTION bodySplit AS 'com.oppo.dc.ostream.udf.pushlog.BodySplitFun' USING JAR '" + udfJarPath + "';" +
            "CREATE FUNCTION arriveCount AS 'com.oppo.dc.ostream.udf.pushlog.GetArriveCountFun' USING JAR '" + udfJarPath + "';" +
            "CREATE FUNCTION showCount AS 'com.oppo.dc.ostream.udf.pushlog.GetShowCountFun' USING JAR '" + udfJarPath + "';" +
            "CREATE FUNCTION clickCount AS 'com.oppo.dc.ostream.udf.pushlog.GetClickCountFun' USING JAR '" + udfJarPath + "';" +
            "CREATE FUNCTION ts AS 'com.oppo.dc.ostream.udf.pushlog.TimeStampFun' USING JAR '" + udfJarPath + "';" +
            // position_tag
            "CREATE FUNCTION toMapHome AS 'com.oppo.dc.ostream.udf.positiontag.HomeMap' USING JAR '" + udfJarPath + "';" +
            "CREATE FUNCTION toMapCompany AS 'com.oppo.dc.ostream.udf.positiontag.CompanyMap' USING JAR '" + udfJarPath + "';" +
            // browser_app_excaption
            "CREATE FUNCTION appExceptionBodySplit AS 'com.oppo.dc.ostream.udf.appexception.BodySplitFun' USING JAR '" + udfJarPath + "';";

    SqlNodeList stmtsUDF = parse(udfCreateSQL);
    Validator validatorUDF = new Validator();
    validatorUDF.validateUDF(stmtsUDF);

    StringBuilder sbSQL = new StringBuilder();
    for(String splitSql: sql.split(";")) {
      SqlNodeList stmts = parse(splitSql);
      Validator validator = new Validator();
      validator.validateInsert(stmts);
      sbSQL.append(validator.statement().toString() + ";");
    }

    JobDescriptor job = new JobDescriptor(
        validatorUDF.userDefinedFunctions(),
        outputs,
        parallelism,
        sbSQL.toString());

    // uses contained executor instead of direct compile for: JobCompiler.compileJob(job);
    CompilationResult res = new ContainedExecutor().run(job);

    if (res.remoteThrowable() != null) {
      throw res.remoteThrowable();
    }
    return new JobCompilationResult(res.jobGraph(),
            validatorUDF.additionalResourcesUnique().stream().map(Path::new).collect(Collectors.toList()));
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
