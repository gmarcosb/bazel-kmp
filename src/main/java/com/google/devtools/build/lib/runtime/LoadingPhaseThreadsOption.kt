// Copyright 2017 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.runtime

import com.google.common.flogger.GoogleLogger
import com.google.devtools.build.lib.query2.engine.QueryEnvironment.QueryFunction.name
import com.google.devtools.build.lib.util.ResourceConverter

/** Defines the --loading_phase_threads option which is used by multiple commands.  */
@com.google.devtools.common.options.OptionsClass
abstract class LoadingPhaseThreadsOption : com.google.devtools.common.options.OptionsBase() {
    @get:com.google.devtools.common.options.Option(
        name = "loading_phase_threads",
        defaultValue = "auto",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.EXECUTION_STRATEGY,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.BAZEL_INTERNAL_CONFIGURATION],
        converter = LoadingPhaseThreadCountConverter::class,
        help = ("Number of parallel threads to use for the loading/analysis phase."
                + "Takes "
                + ResourceConverter.FLAG_SYNTAX
                + ". \"auto\" sets a reasonable default based on "
                + "host resources. Must be at least 1.")
    )
    abstract val threads: Int

    /**
     * A converter for loading phase thread count. Takes {@value FLAG_SYNTAX}. Caps at 20 for tests.
     */
    class LoadingPhaseThreadCountConverter

        :
        com.google.devtools.build.lib.util.ResourceConverter.IntegerConverter( /* auto= */ResourceConverter.HOST_CPUS_SUPPLIER,  /* minValue= */
            1,  /* maxValue= */
            java.lang.Integer.MAX_VALUE
        ) {
        @Throws(com.google.devtools.common.options.OptionsParsingException::class)
        override fun checkAndLimit(value: Int): Int? {
            // Cap thread count while running tests. Test cases are typically small and large thread
            // pools vying for a relatively small number of CPU cores may induce non-optimal
            // performance.
            //
            // TODO(jmmv): If tests care about this, it's them who should be setting a cap.
            var value = value
            if (com.google.devtools.build.lib.util.TestType.isInTest()) {
                value = java.lang.Math.min(20, value)
                logger.atInfo().log("Running under a test; loading_phase_threads capped at %d", value)
            }
            return super.checkAndLimit(value)
        }
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()
    }
}
