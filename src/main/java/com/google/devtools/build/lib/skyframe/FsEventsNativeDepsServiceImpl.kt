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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.skyframe.FsEventsNativeDepsService
import java.util.concurrent.CountDownLatch

/** Implementation of [FsEventsNativeDepsService].  */
class FsEventsNativeDepsServiceImpl : FsEventsNativeDepsService {
    // Keep a pointer to a native structure in the JNI code (the FsEvents callback needs that
    // structure).
    private val nativePointer: Long = 0

    override fun createFsEvents(paths: Array<ByteArray?>?, excludedPaths: Array<ByteArray?>?, latency: Double) {
        create(paths, excludedPaths, latency)
    }

    override fun runFsEvents(listening: CountDownLatch?) {
        run(listening)
    }

    override fun doCloseFsEvents() {
        doClose()
    }

    override fun pollFsEvents(): Array<ByteArray?>? {
        return poll()
    }

    private external fun create(paths: Array<ByteArray?>?, excludedPaths: Array<ByteArray?>?, latency: Double)

    private external fun run(listening: CountDownLatch?)

    private external fun doClose()

    private external fun poll(): Array<ByteArray?>?

    companion object {
        init {
            com.google.devtools.build.lib.jni.JniLoader.loadJni()
        }
    }
}
