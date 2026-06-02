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
package com.google.devtools.build.lib.analysis

import com.google.devtools.build.lib.packages.AspectClass

/**
 * Test for AspectDescriptor.
 */
@RunWith(JUnit4::class)
class AspectDescriptorTest {
    @org.junit.Test
    fun serializeDescriptorNoArguments() {
        assertDescription("foobar", "foobar")
    }

    @org.junit.Test
    fun serializeDescriptorArgument() {
        assertDescription(
            "foobar[x=\"1\"]",
            "foobar",
            "x", "1"
        )
    }

    @org.junit.Test
    fun serializeDescriptorArgumentEscaped() {
        assertDescription(
            "foobar[x=\"\\\"1\\\"\"]",
            "foobar",
            "x", "\"1\""
        )
    }


    @org.junit.Test
    fun serializeDescriptorTwoArguments() {
        assertDescription(
            "foobar[x=\"1\",y=\"2\"]",
            "foobar",
            "x", "1",
            "y", "2"
        )
    }

    @org.junit.Test
    fun serializeDescriptorTwoArgumentsMulti() {
        assertDescription(
            "foobar[x=\"1\",y=\"2\",y=\"3\"]",
            "foobar",
            "x", "1",
            "y", "2",
            "y", "3"
        )
    }

    companion object {
        private fun assertDescription(
            description: String?,
            aspectClassName: String,
            vararg params: String?
        ) {
            assertThat(aspectDescriptor(aspectClass(aspectClassName), *params).getDescription())
                .isEqualTo(description)
        }

        private fun aspectDescriptor(
            aspectClass: AspectClass?,
            vararg parameters: String?
        ): AspectDescriptor {
            Truth.assertThat(parameters.size % 2).isEqualTo(0)

            val params: AspectParameters.Builder = Builder()
            var i = 0
            while (i < parameters.size) {
                params.addAttribute(parameters[i], parameters[i + 1])
                i += 2
            }
            return AspectDescriptor.of(aspectClass, params.build())
        }

        private fun aspectClass(name: String): AspectClass {
            return object : NativeAspectClass() {
                public override fun getName(): String {
                    return name
                }

                public override fun getDefinition(aspectParameters: AspectParameters?): AspectDefinition? {
                    throw java.lang.UnsupportedOperationException()
                }
            }
        }
    }
}
