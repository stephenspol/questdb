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

package io.questdb.griffin;

import io.questdb.cairo.sql.RecordCursorFactory;
import io.questdb.cairo.sql.RecordMetadata;
import org.junit.Assert;
import org.junit.Test;

public class JsonPlanSinkTest {

    @Test
    public void testSink() {
        String expected = "[\n" +
                "  {\n" +
                "    \"Plan\": {\n" +
                "        \"Node Type\": \"test\",\n" +
                "        \"geohash\": \"\"00000000000000011000101010010010\"\",\n" +
                "        \"long256\": \"0x04000000000000000300000000000000020000000000000001\",\n" +
                "        \"plan\": \"null\",\n" +
                "        \"double\": \"0.0\",\n" +
                "        \"float\": \"1.0\",\n" +
                "    }\n" +
                "  }\n" +
                "]";

        JsonPlanSink sink = new JsonPlanSink();
        sink.of(new TestFactory(), null);
        Assert.assertEquals(expected, sink.getSink().toString());
    }

    class TestFactory implements RecordCursorFactory {

        @Override
        public RecordMetadata getMetadata() {
            return null;
        }

        @Override
        public boolean recordCursorSupportsRandomAccess() {
            return false;
        }

        @Override
        public void toPlan(PlanSink sink) {
            sink.type("test");
            sink.attr("geohash");
            sink.val(101010L, 32);
            sink.attr("long256");
            sink.val(1L, 2L, 3L, 4L);
            sink.attr("plan");
            sink.val((Plannable) null);
            sink.attr("double");
            sink.val(0.0f);
            sink.attr("float");
            sink.val(1.0d);
        }
    }
}
