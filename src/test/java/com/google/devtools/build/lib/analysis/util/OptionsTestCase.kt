// Copyright 2023 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis.util

import com.google.common.collect.ImmutableList
import com.google.devtools.build.lib.analysis.config.BuildOptions
import java.util.*

/** A base class for testing cacheKey related functionality of Option classes.  */
abstract class OptionsTestCase<T : FragmentOptions?> {
    protected abstract val optionsClass: Class<T?>

    /** Construct options parsing the given arguments.  */
    @Throws(Exception::class)
    protected fun create(args: MutableList<String?>?): T? {
        val cls = this.optionsClass
        val parser: OptionsParser = OptionsParser.builder().optionsClasses(ImmutableList.of<E?>(cls)).build()
        parser.parse(args)
        return parser.getOptions<O?>(cls)
    }

    /**
     * Useful for options which are specified multiple times on the command line. `createWithPrefix("--abc=", "x", "y", "z")` is equivalent to `create("--abc=x", "--abc=y", "--abc=z")`
     */
    @Throws(Exception::class)
    protected fun createWithPrefix(prefix: String?, vararg args: String?): T? {
        return createWithPrefix(ImmutableList.of<String?>(), prefix, *args)
    }

    /**
     * Variant of [.createWithPrefix] with additional fixed set of options.
     */
    @Throws(Exception::class)
    protected fun createWithPrefix(fixed: ImmutableList<String?>, prefix: String?, vararg args: String?): T? {
        val builder = ImmutableList.builder<String?>()
        builder.addAll(fixed)
        Arrays.stream<String?>(args).map<String?> { x: String? -> prefix + x }
            .forEach { element: String? -> builder.add(element) }
        return create(builder.build())
    }

    protected fun assertSame(one: T?, two: T?) {
        // We normalize first, since that is what BuildOptions.checkSum() does.
        // We do not use BuildOptions.checkSum() because in case of test failure,
        // the diff on cacheKey is humanreadable.
        val oneNormalized: FragmentOptions = one.getNormalized()
        val twoNormalized: FragmentOptions = two.getNormalized()
        assertThat(BuildOptions.optionsToCacheKey(oneNormalized))
            .isEqualTo(BuildOptions.optionsToCacheKey(twoNormalized))
        // Also check equality of toString() as that influences the ST-hash computation.
        assertThat(oneNormalized.toString()).isEqualTo(twoNormalized.toString())
    }

    protected fun assertDifferent(one: T?, two: T?) {
        // We normalize first, since that is what BuildOptions.checkSum() does.
        // We do not use BuildOptions.checkSum() because in case of test failure,
        // the diff on cacheKey is humanreadable.
        val oneNormalized: FragmentOptions = one.getNormalized()
        val twoNormalized: FragmentOptions = two.getNormalized()
        assertThat(BuildOptions.optionsToCacheKey(oneNormalized))
            .isNotEqualTo(BuildOptions.optionsToCacheKey(twoNormalized))
        // Also check equality of toString() as that influences the ST-hash computation.
        assertThat(oneNormalized.toString()).isNotEqualTo(twoNormalized.toString())
    }
}
