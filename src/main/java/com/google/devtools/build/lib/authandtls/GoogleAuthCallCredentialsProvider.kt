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

import com.google.devtools.build.lib.authandtls.CallCredentialsProvider
import io.grpc.CallCredentials
import io.grpc.auth.MoreCallCredentials
import java.io.IOException

/** A [CallCredentialsProvider] implementation which uses [Credentials]  */
@com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe
class GoogleAuthCallCredentialsProvider(credentials: com.google.auth.Credentials) : CallCredentialsProvider {
    private val credentials: com.google.auth.Credentials
    private val callCredentials: CallCredentials
    private val refreshStopwatch: com.google.common.base.Stopwatch

    init {
        com.google.common.base.Preconditions.checkNotNull<com.google.auth.Credentials?>(credentials, "credentials")
        this.credentials = credentials

        callCredentials = MoreCallCredentials.from(credentials)
        refreshStopwatch = com.google.common.base.Stopwatch.createStarted()
    }

    override fun getCallCredentials(): CallCredentials {
        return callCredentials
    }

    @Throws(IOException::class)
    override fun refresh() {
        synchronized(this) {
            // Call credentials.refresh() at most once per second. The one second was arbitrarily chosen,
            // as a small enough value that we don't expect to interfere with actual token lifetimes, but
            // it should just make sure that potentially hundreds of threads don't call this method
            // at the same time.
            if (refreshStopwatch.elapsed().compareTo(java.time.Duration.ofSeconds(1)) > 0) {
                credentials.refresh()
                refreshStopwatch.reset().start()
            }
        }
    }
}
