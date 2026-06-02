// Copyright 2025 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.unix

import com.google.devtools.build.lib.jni.JniLoader.loadJni
import com.google.devtools.build.lib.runtime.BlazeService
import com.google.devtools.build.lib.unix.ProcessUtilsService

/** Various utilities related to UNIX processes.  */
class ProcessUtilsServiceImpl : ProcessUtilsService {
    override fun globalInit(
        startupOptions: com.google.devtools.common.options.OptionsProvider?,
        blazeServices: Iterable<BlazeService?>?
    ) {
        ProcessUtilsService.Companion.registerJniService(this)
    }

    override fun getgid(): Int {
        if (com.google.devtools.build.lib.util.OS.getCurrent() == com.google.devtools.build.lib.util.OS.WINDOWS) {
            throw java.lang.UnsupportedOperationException()
        }
        return getgidNative()
    }

    override fun getuid(): Int {
        if (com.google.devtools.build.lib.util.OS.getCurrent() == com.google.devtools.build.lib.util.OS.WINDOWS) {
            throw java.lang.UnsupportedOperationException()
        }
        return getuidNative()
    }

    /**
     * Native wrapper around POSIX getgid(2).
     * 
     * @return the real group ID of the current process.
     */
    private external fun getgidNative(): Int

    /**
     * Native wrapper around POSIX getuid(2).
     * 
     * @return the real user ID of the current process.
     */
    private external fun getuidNative(): Int

    companion object {
        init {
            com.google.devtools.build.lib.jni.JniLoader.loadJni()
        }
    }
}
