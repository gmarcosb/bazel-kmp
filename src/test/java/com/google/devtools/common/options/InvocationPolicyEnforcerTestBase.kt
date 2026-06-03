// Copyright 2015 The Bazel Authors. All rights reserved.
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
package com.google.devtools.common.options

import com.google.devtools.build.lib.runtime.BlazeServerStartupOptions

/** Useful setup for testing InvocationPolicy.  */
open class InvocationPolicyEnforcerTestBase {
    /** Test converter that splits a string by commas to produce a list.  */
    class ToListConverter : Contextless<MutableList<String?>?>() {
        @Throws(OptionsParsingException::class)
        public override fun convert(input: String): MutableList<String?> {
            return java.util.Arrays.asList<String?>(*input.split(",".toRegex()).dropLastWhile { it.isEmpty() }
                .toTypedArray())
        }

        val typeDescription: String
            get() = "a list of strings"
    }

    var parser: OptionsParser? = null

    @Before
    @Throws(java.lang.Exception::class)
    fun setParser() {
        parser =
            OptionsParser.builder().optionsClasses(com.google.devtools.common.options.TestOptions::class.java).build()
    }

    val testOptions: com.google.devtools.common.options.TestOptions
        get() = parser.getOptions(com.google.devtools.common.options.TestOptions::class.java)

    companion object {
        @Throws(java.lang.Exception::class)
        fun createOptionsPolicyEnforcer(
            invocationPolicyBuilder: InvocationPolicy.Builder
        ): InvocationPolicyEnforcer {
            val policyProto: InvocationPolicy = invocationPolicyBuilder.build()

            // An OptionsPolicyEnforcer could be constructed in the test directly from the InvocationPolicy
            // proto, however Blaze will actually take the policy as another flag with a Base64 encoded
            // binary proto and parse that, so exercise that code path in the test.
            val out: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
            policyProto.writeTo(out)
            val policyBase64: String = com.google.common.io.BaseEncoding.base64().encode(out.toByteArray())

            val startupOptionsParser: OptionsParser =
                OptionsParser.builder().optionsClasses(BlazeServerStartupOptions::class.java).build()
            val policyOption = "--invocation_policy=" + policyBase64
            startupOptionsParser.parse(policyOption)

            return InvocationPolicyEnforcer(
                InvocationPolicyParser.parsePolicy(
                    startupOptionsParser.getOptions(BlazeServerStartupOptions::class.java).invocationPolicy
                ),
                java.util.logging.Level.INFO,  /* conversionContext= */
                null
            )
        }

        @BeforeClass
        @Throws(java.lang.Exception::class)
        fun setCommandNameCache() {
            CommandNameCache.CommandNameCacheInstance.INSTANCE.setCommandNameCache(
                object : CommandNameCache() {
                    public override fun get(commandName: String): com.google.common.collect.ImmutableSet<String?> {
                        return com.google.common.collect.ImmutableSet.of<String?>(commandName)
                    }
                })
        }
    }
}
