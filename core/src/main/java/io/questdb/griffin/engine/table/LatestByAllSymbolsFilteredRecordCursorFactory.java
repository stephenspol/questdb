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

package io.questdb.griffin.engine.table;

import io.questdb.cairo.CairoConfiguration;
import io.questdb.cairo.ColumnTypes;
import io.questdb.cairo.RecordSink;
import io.questdb.cairo.map.Map;
import io.questdb.cairo.map.MapFactory;
import io.questdb.cairo.sql.DataFrameCursorFactory;
import io.questdb.cairo.sql.Function;
import io.questdb.cairo.sql.RecordMetadata;
import io.questdb.griffin.PlanSink;
import io.questdb.griffin.Plannable;
import io.questdb.std.IntList;
import io.questdb.std.Transient;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class LatestByAllSymbolsFilteredRecordCursorFactory extends AbstractTreeSetRecordCursorFactory {

    public LatestByAllSymbolsFilteredRecordCursorFactory(
            @NotNull RecordMetadata metadata,
            @NotNull CairoConfiguration configuration,
            @NotNull DataFrameCursorFactory dataFrameCursorFactory,
            @NotNull RecordSink recordSink,
            @Transient @NotNull ColumnTypes partitionByColumnTypes,
            @NotNull IntList partitionByColumnIndexes,
            @Nullable IntList partitionBySymbolCounts,
            @Nullable Function filter,
            @NotNull IntList columnIndexes
    ) {
        super(metadata, dataFrameCursorFactory, configuration);
        Map map = MapFactory.createMap(configuration, partitionByColumnTypes);
        this.cursor = new LatestByAllSymbolsFilteredRecordCursor(
                map,
                rows,
                recordSink,
                filter,
                columnIndexes,
                partitionByColumnIndexes,
                partitionBySymbolCounts
        );
    }

    @Override
    public boolean recordCursorSupportsRandomAccess() {
        return true;
    }

    @Override
    public void toPlan(PlanSink sink) {
        sink.type("LatestByAllSymbolsFiltered");
        sink.optAttr("filter", ((LatestByAllSymbolsFilteredRecordCursor) cursor).getFilter());
        sink.child((Plannable) cursor);
        sink.child(dataFrameCursorFactory);
    }

    @Override
    protected void _close() {
        this.cursor.close();
        super._close();
    }
}
