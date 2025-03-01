/*******************************************************************************
 *     ___                  _   ____  ____
 *    / _ \ _   _  ___  ___| |_|  _ \| __ )
 *   | | | | | | |/ _ \/ __| __| | | |  _ \
 *   | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *    \__\_\\__,_|\___||___/\__|____/|____/
 *
 *  Copyright (c) 2014-2019 Appsicle
 *  Copyright (c) 2019-2022 QuestDB
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

package io.questdb.griffin.engine.functions.conditional;

import io.questdb.cairo.sql.Function;
import io.questdb.cairo.sql.Record;
import io.questdb.griffin.engine.functions.BinFunction;
import io.questdb.std.BinarySequence;
import io.questdb.std.ObjList;

class BinCaseFunction extends BinFunction implements CaseFunction {
    private final ObjList<Function> args;
    private final CaseFunctionPicker picker;

    public BinCaseFunction(CaseFunctionPicker picker, ObjList<Function> args) {
        this.picker = picker;
        this.args = args;
    }

    @Override
    public ObjList<Function> getArgs() {
        return args;
    }

    @Override
    public BinarySequence getBin(Record rec) {
        return picker.pick(rec).getBin(rec);
    }

    @Override
    public long getBinLen(Record rec) {
        return picker.pick(rec).getBinLen(rec);
    }
}
