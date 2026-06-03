// Copyright 2017 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis.config

import com.google.devtools.build.lib.util.CPU

/**
 * Converter to auto-detect the cpu of the machine on which Bazel runs.
 * 
 * 
 * If the compilation happens remotely then the cpu of the remote machine might be different from
 * the auto-detected one and the --cpu and --host_cpu options must be set explicitly.
 */
class AutoCpuConverter : Contextless<String?>() {
    @Throws(OptionsParsingException::class)
    public override fun convert(input: String): String {
        if (input.isEmpty()) {
            // TODO(philwo) - replace these deprecated names with more logical ones (e.g. k8 becomes
            // linux-x86_64, darwin includes the CPU architecture, ...).
            return when (OS.getCurrent()) {
                DARWIN -> when (CPU.getCurrent()) {
                    X86_64 -> "darwin_x86_64"
                    AARCH64 -> "darwin_arm64"
                    else -> "unknown"
                }

                FREEBSD -> "freebsd"
                OPENBSD -> "openbsd"
                WINDOWS -> when (CPU.getCurrent()) {
                    X86_64 -> "x64_windows"
                    AARCH64 -> "arm64_windows"
                    else -> "unknown"
                }

                LINUX -> when (CPU.getCurrent()) {
                    X86_32 -> "piii"
                    X86_64 -> "k8"
                    PPC -> "ppc"
                    ARM -> "arm"
                    AARCH64 -> "aarch64"
                    S390X -> "s390x"
                    MIPS64 -> "mips64"
                    RISCV64 -> "riscv64"
                    else -> "unknown"
                }

                else -> "unknown"
            }
        }
        return input
    }

    public override fun getTypeDescription(): String {
        return "a string"
    }
}
