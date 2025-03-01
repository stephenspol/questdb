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

package io.questdb.griffin.engine.union;

import io.questdb.cairo.CairoConfiguration;
import io.questdb.cairo.ColumnTypes;
import io.questdb.cairo.RecordSink;
import io.questdb.cairo.map.Map;
import io.questdb.cairo.map.MapFactory;
import io.questdb.cairo.sql.Function;
import io.questdb.cairo.sql.RecordCursorFactory;
import io.questdb.cairo.sql.RecordMetadata;
import io.questdb.std.Misc;
import io.questdb.std.ObjList;

public class IntersectRecordCursorFactory extends AbstractSetRecordCursorFactory {

    public IntersectRecordCursorFactory(
            CairoConfiguration configuration,
            RecordMetadata metadata,
            RecordCursorFactory factoryA,
            RecordCursorFactory factoryB,
            ObjList<Function> castFunctionsA,
            ObjList<Function> castFunctionsB,
            RecordSink recordSink,
            ColumnTypes valueTypes
    ) {
        super(metadata, factoryA, factoryB, castFunctionsA, castFunctionsB);
        Map map = MapFactory.createMap(configuration, metadata, valueTypes);
        if (castFunctionsA == null && castFunctionsB == null) {
            this.cursor = new IntersectRecordCursor(map, recordSink);
        } else {
            assert castFunctionsA != null && castFunctionsB != null;
            this.cursor = new IntersectCastRecordCursor(map, recordSink, castFunctionsA, castFunctionsB);
        }
    }

    @Override
    protected void _close() {
        Misc.free(this.cursor);
        super._close();
    }

    @Override
    protected CharSequence getOperation() {
        return "Intersect";
    }

    @Override
    protected boolean isSecondFactoryHashed() {
        return true;
    }
}
