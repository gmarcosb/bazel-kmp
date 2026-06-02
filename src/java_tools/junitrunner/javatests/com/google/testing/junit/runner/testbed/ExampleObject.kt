// Copyright 2015 The Bazel Authors. All Rights Reserved.
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
package com.google.testing.junit.runner.testbed

import com.google.common.base.Preconditions

/**
 * A sample class that is under test by XmlOutputExercises.
 */
class ExampleObject(data: String?) : Comparable<ExampleObject?> {
    private var data: String

    init {
        this.data = Preconditions.checkNotNull<String>(data)
    }

    fun getData(): String {
        return data
    }

    fun setData(data: String?) {
        this.data = Preconditions.checkNotNull<String>(data)
    }

    override fun compareTo(that: ExampleObject): Int {
        return this.data.compareTo(that.data)
    }

    override fun equals(o: Any?): Boolean {
        if (this === o) {
            return true
        }
        if (o == null || javaClass != o.javaClass) {
            return false
        }

        val that = o as ExampleObject
        return data == that.data
    }

    override fun hashCode(): Int {
        return data.hashCode()
    }

    override fun toString(): String {
        return this.data
    }
}
