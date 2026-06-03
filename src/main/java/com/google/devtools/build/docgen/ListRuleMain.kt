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
package com.google.devtools.build.docgen

import com.google.devtools.build.lib.packages.RuleClass

/**
 * Prints out list of known rules.
 */
object ListRuleMain {
    @Throws(
        java.lang.ClassNotFoundException::class,
        java.lang.NoSuchMethodException::class,
        java.lang.reflect.InvocationTargetException::class,
        java.lang.IllegalAccessException::class
    )
    private fun createRuleClassProvider(classProvider: String?): ConfiguredRuleClassProvider {
        val providerClass: java.lang.Class<*> = java.lang.Class.forName(classProvider)
        val createMethod: java.lang.reflect.Method = providerClass.getMethod("create")
        return createMethod.invoke(null) as ConfiguredRuleClassProvider
    }

    @Throws(java.lang.Exception::class)
    @kotlin.jvm.JvmStatic
    fun main(args: Array<String>) {
        if (args.size == 0) {
            java.lang.System.err.println(
                "Expected one input parameter, please provide the name of the rule class provider"
            )
        }

        val provider: RuleClassProvider = createRuleClassProvider(args[0])
        val rcMap: MutableMap<String?, RuleClass?> = provider.getRuleClassMap()
        for (name in rcMap.keys) {
            println(name)
        }
    }
}
