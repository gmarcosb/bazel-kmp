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
package com.google.devtools.build.lib.actions

/** Command line OS limitations, such as the max length.  */
class CommandLineLimits(val maxLength: Int) {
    companion object {
        /**
         * "Unlimited" command line limits.
         * 
         * 
         * Use these limits when you want to prohibit param files, or you don't use param files so you
         * don't care what the limit is.
         */
        @kotlin.jvm.JvmField
        val UNLIMITED: CommandLineLimits = CommandLineLimits(java.lang.Integer.MAX_VALUE)
    }
}
