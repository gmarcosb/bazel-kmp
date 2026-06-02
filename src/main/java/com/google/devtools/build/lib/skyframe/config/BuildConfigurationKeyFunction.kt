// Copyright 2024 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe.config

import com.google.devtools.build.lib.analysis.config.BuildOptions

/** Function that returns a fully updated [BuildConfigurationKey].  */
class BuildConfigurationKeyFunction : SkyFunction {
    @Throws(BuildConfigurationKeyFunctionException::class, java.lang.InterruptedException::class)
    override fun compute(skyKey: SkyKey, env: SkyFunction.Environment?): SkyValue? {
        // Delegate all work to BuildConfigurationKeyProducer.
        val key: com.google.devtools.build.lib.skyframe.config.BuildConfigurationKeyValue.Key =
            skyKey.argument() as com.google.devtools.build.lib.skyframe.config.BuildConfigurationKeyValue.Key
        val buildOptions: BuildOptions = key.buildOptions()
        val sink: Sink = com.google.devtools.build.lib.skyframe.config.BuildConfigurationKeyFunction.Sink()
        val driver: com.google.devtools.build.skyframe.state.Driver =
            com.google.devtools.build.skyframe.state.Driver(
                BuildConfigurationKeyMapProducer(
                    sink,  /* runAfter= */
                    StateMachine.DONE,
                    com.google.common.collect.ImmutableMap.of<K?, V?>(BUILD_OPTIONS_MAP_SINGLETON_KEY, buildOptions),
                    null
                )
            )

        val complete: Boolean = driver.drive(env)

        try {
            // Check for exceptions before returning whether to restart.
            sink.checkErrors()
            if (!complete) {
                return null
            }

            val buildConfigurationKey: BuildConfigurationKey? = sink.key
            return BuildConfigurationKeyValue.Companion.create(buildConfigurationKey)
        } catch (e: com.google.devtools.common.options.OptionsParsingException) {
            throw BuildConfigurationKeyFunctionException(e)
        } catch (e: PlatformMappingException) {
            throw BuildConfigurationKeyFunctionException(e)
        } catch (e: InvalidPlatformException) {
            throw BuildConfigurationKeyFunctionException(e)
        } catch (e: BuildOptionsScopeFunctionException) {
            throw BuildConfigurationKeyFunctionException(e)
        }
    }

    /** Sink implementation to handle results from [BuildConfigurationKeyMapProducer].  */
    private class Sink : ResultSink {
        private var transitionedOptions: com.google.common.collect.ImmutableMap<String?, BuildConfigurationKey?>? = null
        private var optionsParsingException: com.google.devtools.common.options.OptionsParsingException? = null
        private var platformMappingException: PlatformMappingException? = null
        private var invalidPlatformException: InvalidPlatformException? = null
        private var buildOptionsScopeFunctionException: BuildOptionsScopeFunctionException? = null

        public override fun acceptBuildOptionsScopeFunctionError(e: BuildOptionsScopeFunctionException?) {
            this.buildOptionsScopeFunctionException = e
        }

        public override fun acceptOptionsParsingError(e: com.google.devtools.common.options.OptionsParsingException?) {
            this.optionsParsingException = e
        }

        public override fun acceptPlatformMappingError(e: PlatformMappingException?) {
            this.platformMappingException = e
        }

        public override fun acceptPlatformFlagsError(e: InvalidPlatformException?) {
            this.invalidPlatformException = e
        }

        public override fun acceptTransitionedConfigurations(
            transitionedOptions: com.google.common.collect.ImmutableMap<String?, BuildConfigurationKey?>?
        ) {
            this.transitionedOptions = transitionedOptions
        }

        @Throws(
            com.google.devtools.common.options.OptionsParsingException::class,
            PlatformMappingException::class,
            InvalidPlatformException::class,
            BuildOptionsScopeFunctionException::class
        )
        fun checkErrors() {
            if (this.optionsParsingException != null) {
                throw this.optionsParsingException
            }
            if (this.platformMappingException != null) {
                throw this.platformMappingException
            }
            if (this.invalidPlatformException != null) {
                throw this.invalidPlatformException
            }

            if (this.buildOptionsScopeFunctionException != null) {
                throw this.buildOptionsScopeFunctionException
            }
        }

        val key: BuildConfigurationKey?
            get() {
                if (this.transitionedOptions != null) {
                    return this.transitionedOptions.get(BUILD_OPTIONS_MAP_SINGLETON_KEY)
                }
                throw java.lang.IllegalStateException("No exceptions or result value found")
            }
    }

    /** Exception type for errors while creating the [BuildConfigurationKeyValue].  */
    class BuildConfigurationKeyFunctionException : SkyFunctionException {
        constructor(optionsParsingException: com.google.devtools.common.options.OptionsParsingException?) : super(
            optionsParsingException,
            Transience.PERSISTENT
        )

        constructor(platformMappingException: PlatformMappingException?) : super(
            platformMappingException,
            Transience.PERSISTENT
        )

        constructor(invalidPlatformException: InvalidPlatformException?) : super(
            invalidPlatformException,
            Transience.PERSISTENT
        )

        constructor(buildOptionsScopeFunctionException: BuildOptionsScopeFunctionException?) : super(
            buildOptionsScopeFunctionException,
            Transience.PERSISTENT
        )
    }

    companion object {
        /**
         * [BuildConfigurationKeyMapProducer] works on a `Map<String, BuildOptions>`, but this
         * skyfunction only operates on a single [BuildOptions], so this static key is used to
         * create that map and read the resulting [BuildConfigurationKey].
         */
        private const val BUILD_OPTIONS_MAP_SINGLETON_KEY = "key"
    }
}
