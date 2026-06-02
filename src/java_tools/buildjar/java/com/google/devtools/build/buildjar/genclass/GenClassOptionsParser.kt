// Copyright 2015 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.buildjar.genclass

import java.nio.file.Path
import java.nio.file.Paths

/** A command line parser for [GenClassOptions].  */
object GenClassOptionsParser {
    fun parse(args: Iterable<String?>): GenClassOptions {
        val it: MutableIterator<String> = args.iterator()
        val builder: GenClassOptions.Builder = GenClassOptions.Companion.builder()

        while (it.hasNext()) {
            val arg = it.next()
            when (arg) {
                "--manifest_proto" -> builder.setManifest(readPath(it))
                "--class_jar" -> builder.setClassJar(readPath(it))
                "--output_jar" -> builder.setOutputJar(readPath(it))
                else -> throw IllegalArgumentException(
                    String.format("Unexpected argument: '%s' in %s", arg, args)
                )
            }
        }
        return builder.build()
    }

    private fun readPath(it: MutableIterator<String>): Path? {
        require(it.hasNext()) { String.format("Expected more arguments") }
        return Paths.get(it.next())
    }
}
