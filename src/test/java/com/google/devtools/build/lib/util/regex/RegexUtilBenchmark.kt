// Copyright 2025 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.util.regex

import com.google.devtools.build.lib.exec.util.SpawnBuilder.build
import com.google.devtools.build.lib.util.regex.RegexUtil.asOptimizedMatchingPredicate
import com.google.devtools.common.options.testing.ConverterTesterMap.Builder.build
import net.starlark.java.syntax.FileOptions.Builder.build
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.Warmup
import java.util.concurrent.TimeUnit

/** A benchmark for [RegexUtil.asOptimizedMatchingPredicate].  */
@BenchmarkMode(org.openjdk.jmh.annotations.Mode.Throughput)
@org.openjdk.jmh.annotations.State(org.openjdk.jmh.annotations.Scope.Benchmark)
@Warmup(iterations = 4, time = 2, timeUnit = TimeUnit.SECONDS)
@org.openjdk.jmh.annotations.Measurement(iterations = 4, time = 2, timeUnit = TimeUnit.SECONDS)
@Fork(value = 3)
class RegexUtilBenchmark {
    @org.openjdk.jmh.annotations.Param(
        "bazel-out/darwin_arm64-opt-exec-ST-fad1763555eb/bin/src/main/java/com/google/devtools/build/lib/bazel/BazelServer",
        "bazel-out/darwin_arm64-fastbuild/testlogs/src/test/java/com/google/devtools/common/options/AllTests/test.log"
    )
    var haystack: String? = null

    @org.openjdk.jmh.annotations.Param(
        ".*/coverage\\.dat",
        ".*/my_action.outputs/.*",
        ".*/testlogs/.*/test\\.xml",
        ".*/testlogs/.*/attempt_[0-9]+\\.xml"
    )
    var needle: String? = null

    private var originalPattern: java.util.regex.Pattern? = null
    private var optimizedMatcher: java.util.function.Predicate<String?>? = null

    @Setup
    fun compile() {
        originalPattern = java.util.regex.Pattern.compile(needle)
        optimizedMatcher =
            com.google.devtools.build.lib.util.regex.RegexUtil.asOptimizedMatchingPredicate(originalPattern)
    }

    @org.openjdk.jmh.annotations.Benchmark
    fun baseline(): Boolean {
        return originalPattern.matcher(haystack).matches()
    }

    @org.openjdk.jmh.annotations.Benchmark
    fun optimized(): Boolean {
        return optimizedMatcher.test(haystack)
    }
}
