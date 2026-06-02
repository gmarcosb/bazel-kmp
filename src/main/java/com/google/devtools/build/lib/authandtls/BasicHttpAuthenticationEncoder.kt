// Copyright 2020 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.authandtls

import com.google.devtools.build.lib.unsafe.StringUnsafe

/**
 * Encoder for Basic Http Authentication.
 * 
 * @see [The 'Basic' HTTP Authentication Scheme](https://tools.ietf.org/html/rfc7617)
 */
object BasicHttpAuthenticationEncoder {
    /**
     * Encode username and password into a token.
     * 
     * 
     * username and password are expected to use Bazel's internal string encoding. The returned
     * string is a regular Unicode string.
     */
    @kotlin.jvm.JvmStatic
    fun encode(username: String?, password: String?): String {
        // The raw bytes in the internal string are assumed to be UTF-8, which is the encoding used for
        // basic authentication.
        return ("Basic "
                + java.util.Base64.getEncoder()
            .encodeToString(StringUnsafe.getInternalStringBytes(username + ":" + password)))
    }
}
