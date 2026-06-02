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
package com.google.devtools.build.lib.query2.query.output

import com.google.devtools.build.lib.cmdline.Label

internal class RankAndLabel(val rank: Int, label: Label) : Comparable<RankAndLabel?> {
    private val label: Label

    init {
        this.label = label
    }

    override fun compareTo(o: RankAndLabel): Int {
        if (this.rank != o.rank) {
            return this.rank - o.rank
        }
        return this.label.compareTo(o.label)
    }

    override fun toString(): String {
        throw UnsupportedOperationException("Use toString(LabelPrinter) instead")
    }

    fun toString(labelPrinter: LabelPrinter): String {
        return rank.toString() + " " + labelPrinter.toString(label)
    }
}
