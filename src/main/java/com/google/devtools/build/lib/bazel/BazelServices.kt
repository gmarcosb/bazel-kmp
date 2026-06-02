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
package com.google.devtools.build.lib.bazel

import com.google.devtools.build.lib.platform.PlatformNativeDepsServiceImpl
import com.google.devtools.build.lib.profiler.SystemNetworkStatsServiceImpl
import com.google.devtools.build.lib.profiler.TraceProfilerServiceImpl
import com.google.devtools.build.lib.server.GrpcCommandServerServiceImpl
import com.google.devtools.build.lib.skyframe.FsEventsNativeDepsServiceImpl
import com.google.devtools.build.lib.starlarkprofiler.CpuProfilerServiceImpl
import com.google.devtools.build.lib.unix.NativePosixFilesServiceImpl
import com.google.devtools.build.lib.unix.ProcessUtilsServiceImpl
import com.google.devtools.build.lib.util.ServerLogPathServiceImpl

/** Services that are used in Bazel  */
// Class names fully qualified for clarity.
object BazelServices {
    @kotlin.jvm.JvmField
    val BAZEL_SERVICES: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.runtime.BlazeService?> =
        com.google.common.collect.ImmutableList.of<com.google.devtools.build.lib.runtime.BlazeService?>(
            FsEventsNativeDepsServiceImpl(),
            PlatformNativeDepsServiceImpl(),
            SystemNetworkStatsServiceImpl(),
            TraceProfilerServiceImpl(),
            NativePosixFilesServiceImpl(),
            ProcessUtilsServiceImpl(),
            GrpcCommandServerServiceImpl(),
            CpuProfilerServiceImpl(),
            ServerLogPathServiceImpl()
        )
}
