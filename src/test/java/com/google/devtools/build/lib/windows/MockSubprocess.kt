// Copyright 2016 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.windows

import com.google.devtools.build.lib.buildtool.util.BuildIntegrationTestCase.write
import java.io.PrintStream
import java.nio.file.Paths
import java.util.HashMap

/**
 * Mock subprocess to be used for testing Windows process management. Command line usage:
 * 
 * 
 *  * `I<register><count>`: Read count bytes to the specified register
 *  * `O-<string>`: Write a string to stdout
 *  * `E-<string>`: Write a string to stderr
 *  * `O$<variable>`: Write an environment variable to stdout
 *  * `E$<variable>`: Write an environment variable to stderr
 *  * `O.`: Write the cwd stdout
 *  * `E.`: Write the cwd stderr
 *  * `O<register>`: Write the contents of a register to stdout
 *  * `E<register>`: Write the contents of a register to stderr
 *  * `X<exit code%gt;`: Exit with the specified exit code
 *  * `S<seconds>`: Wait the specified number of seconds
 * 
 * 
 * 
 * Registers are single characters. Each command line argument is interpreted as a single
 * operation. Example:
 * 
 * `
 * Ia10 Oa Oa Ea E-OVER X42
` * 
 * 
 * Means: read 10 bytes from stdin, write them back twice to stdout and once to stderr, write
 * the string "OVER" to stderr then exit with exit code 42.
 */
object MockSubprocess {
    private val registers: MutableMap<Char?, ByteArray> = HashMap<Char?, ByteArray>()
    private val UTF8: java.nio.charset.Charset = java.nio.charset.Charset.forName("UTF-8")

    @Throws(java.lang.Exception::class)
    private fun writeBytes(stream: PrintStream, arg: String) {
        val buf: ByteArray
        when (arg.get(1)) {
            '-' ->         // Immediate string
                buf = arg.substring(2).toByteArray(UTF8)

            '$' ->         // Environment variable
                buf = com.google.common.base.Strings.nullToEmpty(java.lang.System.getenv(arg.substring(2)))
                    .toByteArray(UTF8)

            '.' -> buf = Paths.get(".").toAbsolutePath().normalize().toString().toByteArray(UTF8)
            else -> buf = registers.get(arg.get(1))!!
        }

        stream.write(buf, 0, buf.size)
    }

    @Throws(java.lang.Exception::class)
    @kotlin.jvm.JvmStatic
    fun main(args: Array<String>) {
        for (arg in args) {
            when (arg.get(0)) {
                'I' -> {
                    val register = arg.get(1)
                    val length: Int = arg.substring(2).toInt()
                    val buf: ByteArray?
                    if (length > 0) {
                        buf = ByteArray(length)
                        java.lang.System.`in`.read(buf, 0, length)
                    } else {
                        buf = java.lang.System.`in`.readAllBytes()
                    }
                    registers.put(register, buf!!)
                }

                'E' -> writeBytes(java.lang.System.err, arg)
                'O' -> writeBytes(java.lang.System.out, arg)
                'W' -> try {
                    java.lang.Thread.sleep((arg.substring(1).toInt() * 1000).toLong())
                } catch (e: java.lang.InterruptedException) {
                    // This is good enough for a mock process
                    throw java.lang.IllegalStateException(e)
                }

                'X' -> java.lang.System.exit(arg.substring(1).toInt())
                else -> {}
            }
        }
    }
}
