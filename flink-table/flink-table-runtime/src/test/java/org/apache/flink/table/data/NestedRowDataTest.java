/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.	See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.	You may obtain a copy of the License at
 *
 *		http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.table.data;

import org.apache.flink.api.common.serialization.SerializerConfigImpl;
import org.apache.flink.api.common.typeutils.TypeSerializer;
import org.apache.flink.api.common.typeutils.base.IntSerializer;
import org.apache.flink.api.common.typeutils.base.LongSerializer;
import org.apache.flink.api.common.typeutils.base.StringSerializer;
import org.apache.flink.api.java.typeutils.GenericTypeInfo;
import org.apache.flink.core.memory.MemorySegment;
import org.apache.flink.table.api.DataTypes;
import org.apache.flink.table.data.binary.BinaryRowData;
import org.apache.flink.table.data.binary.NestedRowData;
import org.apache.flink.table.data.writer.BinaryRowWriter;
import org.apache.flink.table.runtime.typeutils.RawValueDataSerializer;
import org.apache.flink.table.runtime.typeutils.RowDataSerializer;
import org.apache.flink.table.runtime.typeutils.StringDataSerializer;
import org.apache.flink.table.types.logical.LogicalType;

import org.junit.jupiter.api.Test;

import static org.apache.flink.table.data.util.DataFormatTestUtil.MyObj;
import static org.apache.flink.table.data.util.DataFormatTestUtil.splitBytes;
import static org.assertj.core.api.Assertions.assertThat;

/** Test for {@link NestedRowData}s. */
class NestedRowDataTest {

    @Test
    void testNestedRowDataWithOneSegment() {
        BinaryRowData row = getBinaryRowData();
        GenericTypeInfo<MyObj> info = new GenericTypeInfo<>(MyObj.class);
        TypeSerializer<MyObj> genericSerializer = info.createSerializer(new SerializerConfigImpl());

        RowData nestedRow = row.getRow(0, 5);
        assertThat(1).isEqualTo(nestedRow.getInt(0));
        assertThat(5L).isEqualTo(nestedRow.getLong(1));
        assertThat(StringData.fromString("12345678")).isEqualTo(nestedRow.getString(2));
        assertThat(nestedRow.isNullAt(3)).isTrue();
        assertThat(nestedRow.<MyObj>getRawValue(4).toObject(genericSerializer))
                .isEqualTo(new MyObj(15, 5));
    }

    @Test
    void testNestedRowDataWithMultipleSegments() {
        BinaryRowData row = getBinaryRowData();
        GenericTypeInfo<MyObj> info = new GenericTypeInfo<>(MyObj.class);
        TypeSerializer<MyObj> genericSerializer = info.createSerializer(new SerializerConfigImpl());

        MemorySegment[] segments = splitBytes(row.getSegments()[0].getHeapMemory(), 3);
        row.pointTo(segments, 3, row.getSizeInBytes());
        {
            RowData nestedRow = row.getRow(0, 5);
            assertThat(1).isEqualTo(nestedRow.getInt(0));
            assertThat(5L).isEqualTo(nestedRow.getLong(1));
            assertThat(StringData.fromString("12345678")).isEqualTo(nestedRow.getString(2));
            assertThat(nestedRow.isNullAt(3)).isTrue();
            assertThat(nestedRow.<MyObj>getRawValue(4).toObject(genericSerializer))
                    .isEqualTo(new MyObj(15, 5));
        }
    }

    @Test
    void testNestInNestedRowData() {
        // layer1
        GenericRowData gRow = new GenericRowData(4);
        gRow.setField(0, 1);
        gRow.setField(1, 5L);
        gRow.setField(2, StringData.fromString("12345678"));
        gRow.setField(3, null);

        // layer2
        RowDataSerializer serializer =
                new RowDataSerializer(
                        new LogicalType[] {
                            DataTypes.INT().getLogicalType(),
                            DataTypes.BIGINT().getLogicalType(),
                            DataTypes.STRING().getLogicalType(),
                            DataTypes.STRING().getLogicalType()
                        },
                        new TypeSerializer[] {
                            IntSerializer.INSTANCE,
                            LongSerializer.INSTANCE,
                            StringSerializer.INSTANCE,
                            StringSerializer.INSTANCE
                        });
        BinaryRowData row = new BinaryRowData(2);
        BinaryRowWriter writer = new BinaryRowWriter(row);
        writer.writeString(0, StringData.fromString("hahahahafff"));
        writer.writeRow(1, gRow, serializer);
        writer.complete();

        // layer3
        BinaryRowData row2 = new BinaryRowData(1);
        BinaryRowWriter writer2 = new BinaryRowWriter(row2);
        writer2.writeRow(0, row, null);
        writer2.complete();

        // verify
        {
            NestedRowData nestedRow = (NestedRowData) row2.getRow(0, 2);
            BinaryRowData binaryRow = new BinaryRowData(2);
            binaryRow.pointTo(
                    nestedRow.getSegments(), nestedRow.getOffset(), nestedRow.getSizeInBytes());
            assertThat(row).isEqualTo(binaryRow);
        }

        assertThat(StringData.fromString("hahahahafff")).isEqualTo(row2.getRow(0, 2).getString(0));
        RowData nestedRow = row2.getRow(0, 2).getRow(1, 4);
        assertThat(1).isEqualTo(nestedRow.getInt(0));
        assertThat(5L).isEqualTo(nestedRow.getLong(1));
        assertThat(StringData.fromString("12345678")).isEqualTo(nestedRow.getString(2));
        assertThat(nestedRow.isNullAt(3)).isTrue();
    }

    private BinaryRowData getBinaryRowData() {
        BinaryRowData row = new BinaryRowData(1);
        BinaryRowWriter writer = new BinaryRowWriter(row);

        GenericTypeInfo<MyObj> info = new GenericTypeInfo<>(MyObj.class);
        TypeSerializer<MyObj> genericSerializer = info.createSerializer(new SerializerConfigImpl());
        GenericRowData gRow = new GenericRowData(5);
        gRow.setField(0, 1);
        gRow.setField(1, 5L);
        gRow.setField(2, StringData.fromString("12345678"));
        gRow.setField(3, null);
        gRow.setField(4, RawValueData.fromObject(new MyObj(15, 5)));

        RowDataSerializer serializer =
                new RowDataSerializer(
                        new LogicalType[] {
                            DataTypes.INT().getLogicalType(),
                            DataTypes.BIGINT().getLogicalType(),
                            DataTypes.STRING().getLogicalType(),
                            DataTypes.STRING().getLogicalType(),
                            DataTypes.RAW(
                                            info.getTypeClass(),
                                            info.createSerializer(new SerializerConfigImpl()))
                                    .getLogicalType()
                        },
                        new TypeSerializer[] {
                            IntSerializer.INSTANCE,
                            LongSerializer.INSTANCE,
                            StringDataSerializer.INSTANCE,
                            StringDataSerializer.INSTANCE,
                            new RawValueDataSerializer<>(genericSerializer)
                        });
        writer.writeRow(0, gRow, serializer);
        writer.complete();

        return row;
    }
}
