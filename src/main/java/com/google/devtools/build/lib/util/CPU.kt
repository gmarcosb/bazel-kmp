// Copyright 2015 The Bazel Authors. All rights reserved.
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

/**
 * Detects the CPU of the running JVM and returns a describing enum value.
 */
enum class CPU(canonicalName: String, archs: com.google.common.collect.ImmutableSet<String?>) {
    X86_32("x86_32", com.google.common.collect.ImmutableSet.of<String?>("i386", "i486", "i586", "i686", "i786", "x86")),
    X86_64("x86_64", com.google.common.collect.ImmutableSet.of<String?>("amd64", "x86_64", "x64")),
    PPC("ppc", com.google.common.collect.ImmutableSet.of<String?>("ppc", "ppc64", "ppc64le")),
    ARM("arm", com.google.common.collect.ImmutableSet.of<String?>("arm", "armv7l")),
    AARCH64("aarch64", com.google.common.collect.ImmutableSet.of<String?>("aarch64")),
    S390X("s390x", com.google.common.collect.ImmutableSet.of<String?>("s390x", "s390")),
    MIPS64("mips64", com.google.common.collect.ImmutableSet.of<String?>("mips64el", "mips64")),
    RISCV64("riscv64", com.google.common.collect.ImmutableSet.of<String?>("riscv64")),
    UNKNOWN("unknown", com.google.common.collect.ImmutableSet.of<String?>());

    val canonicalName: String?
    private val archs: com.google.common.collect.ImmutableSet<String?>

    init {
        this.canonicalName = canonicalName
        this.archs = archs
    }

    companion object {
        private val HOST_CPU: CPU = com.google.devtools.build.lib.util.CPU.Companion.determineCurrentCpu()

        val current: CPU
            /**
             * The current CPU.
             */
            get() = com.google.devtools.build.lib.util.CPU.Companion.HOST_CPU

        private fun determineCurrentCpu(): CPU {
            val currentArch: String? = com.google.common.base.StandardSystemProperty.OS_ARCH.value()

            for (cpu in com.google.devtools.build.lib.util.CPU.entries) {
                if (cpu.archs.contains(currentArch)) {
                    return cpu
                }
            }

            return com.google.devtools.build.lib.util.CPU.UNKNOWN
        }
    }
}
