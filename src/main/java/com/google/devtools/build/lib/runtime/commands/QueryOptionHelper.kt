// Copyright 2022 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.package com.google.devtools.build.lib.runtime.commands;
package com.google.devtools.build.lib.runtime.commands

import com.google.devtools.build.lib.query2.common.CommonQueryOptions

/**
 * Reads the query for query, cquery and aquery using the --query_file option or from the residue of
 * the command line.
 */
object QueryOptionHelper {
    @Throws(com.google.devtools.build.lib.query2.engine.QueryException::class)
    fun readQuery(
        queryOptions: CommonQueryOptions,
        options: com.google.devtools.common.options.OptionsParsingResult,
        env: CommandEnvironment,
        allowEmptyQuery: Boolean
    ): String {
        var query = ""
        if (!options.getResidue().isEmpty()) {
            if (!queryOptions.getQueryFile().isEmpty()) {
                throw com.google.devtools.build.lib.query2.engine.QueryException(
                    "Command-line query and --query_file cannot both be specified",
                    Query.Code.QUERY_FILE_WITH_COMMAND_LINE_EXPRESSION
                )
            }
            query = com.google.common.base.Joiner.on(' ').join(options.getResidue())
        } else if (!queryOptions.getQueryFile().isEmpty()) {
            // Works for absolute or relative query file.
            val residuePath: com.google.devtools.build.lib.vfs.Path =
                env.getWorkingDirectory().getRelative(queryOptions.getQueryFile())
            try {
                env.getEventBus()
                    .post(InputFileEvent.Companion.create( /* type= */"query_file", residuePath.getFileSize()))
                query = String(
                    com.google.devtools.build.lib.vfs.FileSystemUtils.readContent(residuePath),
                    java.nio.charset.StandardCharsets.ISO_8859_1
                )
            } catch (unused: IOException) {
                throw com.google.devtools.build.lib.query2.engine.QueryException(
                    "I/O error reading from " + residuePath.getPathString(),
                    Query.Code.QUERY_FILE_READ_FAILURE
                )
            }
        } else {
            // When querying for the state of Skyframe, it's possible to omit the query expression.
            if (!allowEmptyQuery) {
                throw com.google.devtools.build.lib.query2.engine.QueryException(
                    java.lang.String.format(
                        "missing query expression. Type '%s help query' for syntax and help",
                        env.getRuntime().productName
                    ),
                    Query.Code.COMMAND_LINE_EXPRESSION_MISSING
                )
            }
        }
        return query
    }
}
