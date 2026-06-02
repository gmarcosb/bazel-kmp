// Copyright 2014 The Bazel Authors. All rights reserved.
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
// limitations under the License.
package com.google.devtools.build.lib.query2.query.output

import com.google.common.hash.HashFunction
import com.google.devtools.build.lib.events.EventHandler
import com.google.devtools.build.lib.query2.engine.QueryException
import java.io.OutputStream

/** Interface for classes which order, format and print the result of a Blaze graph query.  */
abstract class OutputFormatter {
    /** Returns the user-visible name of the output formatter.  */
    abstract val name: String?

    /**
     * Workaround for a bug in [java.nio.channels.Channels.newChannel], which
     * attempts to close the output stream on interrupt, which can cause a deadlock if there is an
     * ongoing write. If this formatter uses Channels.newChannel, then it must return false here, and
     * perform its own buffering.
     */
    fun canBeBuffered(): Boolean {
        return true
    }

    /**
     * Verifies that the environment and expression are compatible with this formatter, throws a
     * [QueryException] if not.
     */
    @Throws(QueryException::class)
    open fun verifyCompatible(env: QueryEnvironment<*>?, expr: QueryExpression?) {
    }

    /**
     * Format the result (a set of target nodes implicitly ordered according to the graph maintained
     * by the QueryEnvironment), and print it to "out".
     */
    @Throws(IOException::class, InterruptedException::class)
    abstract fun output(
        options: QueryOptions?,
        result: Digraph<Target?>?,
        out: OutputStream?,
        aspectProvider: AspectResolver?,
        eventHandler: EventHandler?,
        hashFunction: HashFunction?,
        labelPrinter: LabelPrinter?
    )
}
