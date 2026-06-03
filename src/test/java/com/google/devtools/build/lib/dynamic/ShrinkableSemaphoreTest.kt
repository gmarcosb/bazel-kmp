// Copyright 2021 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.dynamic

import com.google.common.truth.Truth
import com.google.devtools.build.lib.dynamic.ShrinkableSemaphore
import com.google.devtools.build.lib.dynamic.ShrinkableSemaphore.updateLoad
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** Tests for [ShrinkableSemaphore].  */
@RunWith(JUnit4::class)
class ShrinkableSemaphoreTest {
    @org.junit.Test
    fun testUpdateLoad_shrinksUnderLoad() {
        val sem: ShrinkableSemaphore = ShrinkableSemaphore(12, 30, 0.75)
        Truth.assertThat(sem.availablePermits()).isEqualTo(12)
        sem.updateLoad(0)
        Truth.assertThat(sem.availablePermits()).isEqualTo(12)
        sem.updateLoad(10) // 1/3 load * 0.75 factor = 1/12 reduction
        Truth.assertThat(sem.availablePermits()).isEqualTo(11)
        sem.updateLoad(20)
        Truth.assertThat(sem.availablePermits()).isEqualTo(10)
        sem.updateLoad(30)
        Truth.assertThat(sem.availablePermits()).isEqualTo(9)
        sem.updateLoad(4)
        Truth.assertThat(sem.availablePermits()).isEqualTo(12)
    }

    @org.junit.Test
    @Throws(java.lang.InterruptedException::class)
    fun testUpdateLoad_shrinksProperlyWhenPermitsTaken() {
        val sem: ShrinkableSemaphore = ShrinkableSemaphore(12, 30, 0.5)
        Truth.assertThat(sem.availablePermits()).isEqualTo(12)
        sem.acquire(5)
        sem.updateLoad(0)
        Truth.assertThat(sem.availablePermits()).isEqualTo(7)
        sem.updateLoad(10) // 1/3 load * 0.5 factor = 1/6 reduction, and 5 acquired
        Truth.assertThat(sem.availablePermits()).isEqualTo(5)
        sem.acquire(5)
        Truth.assertThat(sem.availablePermits()).isEqualTo(0)
        sem.updateLoad(20) // More permits temporarily taken than available
        Truth.assertThat(sem.availablePermits()).isEqualTo(-2)
        sem.release()
        Truth.assertThat(sem.availablePermits()).isEqualTo(-1)
        sem.updateLoad(30) // Only 6 permits allowed under load, 9 still acquired
        Truth.assertThat(sem.availablePermits()).isEqualTo(-3)
        sem.updateLoad(10) // Now 10 permits allowed under load, 9 still acquired
        Truth.assertThat(sem.availablePermits()).isEqualTo(1)
    }

    @org.junit.Test
    @Throws(java.lang.InterruptedException::class)
    fun testUpdateLoad_noShrinkWithZeroFactor() {
        val sem: ShrinkableSemaphore = ShrinkableSemaphore(12, 30, 0.0)
        Truth.assertThat(sem.availablePermits()).isEqualTo(12)
        sem.acquire(5)
        sem.updateLoad(0)
        Truth.assertThat(sem.availablePermits()).isEqualTo(7)
        sem.updateLoad(30)
        Truth.assertThat(sem.availablePermits()).isEqualTo(7)
        sem.release(2)
        Truth.assertThat(sem.availablePermits()).isEqualTo(9)
    }

    @org.junit.Test
    fun testUpdateLoad_noShrinkBelowZero() {
        val sem: ShrinkableSemaphore = ShrinkableSemaphore(12, 30, 0.5)
        Truth.assertThat(sem.availablePermits()).isEqualTo(12)
        sem.updateLoad(60)
        Truth.assertThat(sem.availablePermits()).isEqualTo(1)
        sem.updateLoad(80)
        Truth.assertThat(sem.availablePermits()).isEqualTo(1)
        sem.updateLoad(40)
        Truth.assertThat(sem.availablePermits()).isEqualTo(4)
    }
}
