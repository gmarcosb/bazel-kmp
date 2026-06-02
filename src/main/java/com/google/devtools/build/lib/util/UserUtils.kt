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
package com.google.devtools.build.lib.util

import com.google.devtools.build.lib.util.UserUtils

/**
 * User information utility methods.
 */
object UserUtils {
    val userName: String?
        /**
         * Returns the user name as provided by system property 'user.name'.
         */
        get() = com.google.devtools.build.lib.util.UserUtils.Holder.userName

    /**
     * Returns the originating user for this build from the command-line or the environment.
     */
    fun getOriginatingUser(originatingUser: String?): String? {
        if (!com.google.common.base.Strings.isNullOrEmpty(originatingUser)) {
            return originatingUser
        }

        return userName
    }

    private object Holder {
        val userName: String? = com.google.common.base.StandardSystemProperty.USER_NAME.value()
    }
}
