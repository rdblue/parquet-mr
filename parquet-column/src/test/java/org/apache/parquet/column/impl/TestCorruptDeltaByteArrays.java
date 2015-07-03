/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.parquet.column.impl;

import org.apache.parquet.CorruptDeltaByteArrays;
import org.apache.parquet.SemanticVersion;
import org.apache.parquet.VersionParser.ParsedVersion;
import org.apache.parquet.bytes.BytesInput;
import org.apache.parquet.column.ColumnDescriptor;
import org.apache.parquet.column.Encoding;
import org.apache.parquet.column.ParquetProperties;
import org.apache.parquet.column.page.PageWriter;
import org.apache.parquet.column.page.mem.MemPageStore;
import org.apache.parquet.column.statistics.BinaryStatistics;
import org.apache.parquet.column.values.ValuesWriter;
import org.apache.parquet.column.values.deltastrings.DeltaByteArrayReader;
import org.apache.parquet.column.values.deltastrings.DeltaByteArrayWriter;
import org.apache.parquet.io.api.Binary;
import org.apache.parquet.io.api.PrimitiveConverter;
import org.apache.parquet.schema.PrimitiveType;
import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class TestCorruptDeltaByteArrays {
  @Test
  public void testCorruptDeltaByteArrayVerisons() {
    assertTrue(CorruptDeltaByteArrays.requireSequentialReads("parquet-mr version 1.6.0 (build abcd)"));
    assertTrue(CorruptDeltaByteArrays.requireSequentialReads((String) null));
    assertTrue(CorruptDeltaByteArrays.requireSequentialReads((ParsedVersion) null));
    assertTrue(CorruptDeltaByteArrays.requireSequentialReads((SemanticVersion) null));
    assertTrue(CorruptDeltaByteArrays.requireSequentialReads("parquet-mr version 1.8.0-SNAPSHOT (build abcd)"));
    assertFalse(CorruptDeltaByteArrays.requireSequentialReads("parquet-mr version 1.8.0 (build abcd)"));
  }

  @Test
  public void testEncodingRequiresSequentailRead() {
    ParsedVersion impala = new ParsedVersion("impala", "1.2.0", "abcd");
    assertFalse(Encoding.DELTA_BYTE_ARRAY.requiresSequentialReads(impala));
    ParsedVersion broken = new ParsedVersion("parquet-mr", "1.8.0-SNAPSHOT", "abcd");
    assertTrue(Encoding.DELTA_BYTE_ARRAY.requiresSequentialReads(broken));
    ParsedVersion fixed = new ParsedVersion("parquet-mr", "1.8.0", "abcd");
    assertFalse(Encoding.DELTA_BYTE_ARRAY.requiresSequentialReads(fixed));
  }

  @Test
  public void testReassemblyWithCorruptPage() throws Exception {
    DeltaByteArrayWriter writer = new DeltaByteArrayWriter(10, 100);

    String lastValue = null;
    for (int i = 0; i < 10; i += 1) {
      lastValue = str(i);
      writer.writeBytes(Binary.fromString(lastValue));
    }
    byte[] firstPageBytes = writer.getBytes().toByteArray();

    writer.reset(); // sets previous to new byte[0]
    corruptWriter(writer, lastValue);

    for (int i = 10; i < 20; i += 1) {
      writer.writeBytes(Binary.fromString(str(i)));
    }
    byte[] corruptPageBytes = writer.getBytes().toByteArray();

    DeltaByteArrayReader firstPageReader = new DeltaByteArrayReader();
    firstPageReader.initFromPage(10, firstPageBytes, 0);
    for (int i = 0; i < 10; i += 1) {
      assertEquals(firstPageReader.readBytes().toStringUsingUTF8(), str(i));
    }

    DeltaByteArrayReader corruptPageReader = new DeltaByteArrayReader();
    corruptPageReader.initFromPage(10, corruptPageBytes, 0);
    try {
      corruptPageReader.readBytes();
      fail("Corrupt page did not throw an exception when read");
    } catch (ArrayIndexOutOfBoundsException e) {
      // expected, this is a corrupt page
    }

    DeltaByteArrayReader secondPageReader = new DeltaByteArrayReader();
    secondPageReader.initFromPage(10, corruptPageBytes, 0);
    secondPageReader.setPreviousReader(firstPageReader);

    for (int i = 10; i < 20; i += 1) {
      assertEquals(secondPageReader.readBytes().toStringUsingUTF8(), str(i));
    }
  }

  @Test
  public void testReassemblyWithoutCorruption() throws Exception {
    DeltaByteArrayWriter writer = new DeltaByteArrayWriter(10, 100);

    for (int i = 0; i < 10; i += 1) {
      writer.writeBytes(Binary.fromString(str(i)));
    }
    byte[] firstPageBytes = writer.getBytes().toByteArray();

    writer.reset(); // sets previous to new byte[0]

    for (int i = 10; i < 20; i += 1) {
      writer.writeBytes(Binary.fromString(str(i)));
    }
    byte[] secondPageBytes = writer.getBytes().toByteArray();

    DeltaByteArrayReader firstPageReader = new DeltaByteArrayReader();
    firstPageReader.initFromPage(10, firstPageBytes, 0);
    for (int i = 0; i < 10; i += 1) {
      assertEquals(firstPageReader.readBytes().toStringUsingUTF8(), str(i));
    }

    DeltaByteArrayReader secondPageReader = new DeltaByteArrayReader();
    secondPageReader.initFromPage(10, secondPageBytes, 0);
    secondPageReader.setPreviousReader(firstPageReader);

    for (int i = 10; i < 20; i += 1) {
      assertEquals(secondPageReader.readBytes().toStringUsingUTF8(), str(i));
    }
  }

  @Test
  public void testOldReassemblyWithoutCorruption() throws Exception {
    DeltaByteArrayWriter writer = new DeltaByteArrayWriter(10, 100);

    for (int i = 0; i < 10; i += 1) {
      writer.writeBytes(Binary.fromString(str(i)));
    }
    byte[] firstPageBytes = writer.getBytes().toByteArray();

    writer.reset(); // sets previous to new byte[0]

    for (int i = 10; i < 20; i += 1) {
      writer.writeBytes(Binary.fromString(str(i)));
    }
    byte[] secondPageBytes = writer.getBytes().toByteArray();

    DeltaByteArrayReader firstPageReader = new DeltaByteArrayReader();
    firstPageReader.initFromPage(10, firstPageBytes, 0);
    for (int i = 0; i < 10; i += 1) {
      assertEquals(firstPageReader.readBytes().toStringUsingUTF8(), str(i));
    }

    DeltaByteArrayReader secondPageReader = new DeltaByteArrayReader();
    secondPageReader.initFromPage(10, secondPageBytes, 0);

    for (int i = 10; i < 20; i += 1) {
      assertEquals(secondPageReader.readBytes().toStringUsingUTF8(), str(i));
    }
  }

  @Test
  public void testColumnReaderImplWithCorruptPage() throws Exception {
    ColumnDescriptor column = new ColumnDescriptor(
        new String[] {"s"}, PrimitiveType.PrimitiveTypeName.BINARY, 0, 0);
    MemPageStore pages = new MemPageStore(0);
    PageWriter memWriter = pages.getPageWriter(column);

    // get generic repetition and definition level bytes to use for pages
    ValuesWriter rdValues = ParquetProperties
        .getColumnDescriptorValuesWriter(0, 10, 100);
    for (int i = 0; i < 10; i += 1) {
      rdValues.writeInteger(0);
    }
    // use a byte array backed BytesInput because it is reused
    BytesInput rd = BytesInput.from(rdValues.getBytes().toByteArray());
    DeltaByteArrayWriter writer = new DeltaByteArrayWriter(10, 100);
    String lastValue = null;
    List<String> values = new ArrayList<String>();
    for (int i = 0; i < 10; i += 1) {
      lastValue = str(i);
      writer.writeBytes(Binary.fromString(lastValue));
      values.add(lastValue);
    }

    memWriter.writePage(BytesInput.concat(rd, rd, writer.getBytes()),
        10, /* number of values in the page */
        new BinaryStatistics(),
        rdValues.getEncoding(),
        rdValues.getEncoding(),
        writer.getEncoding());
    pages.addRowCount(10);

    writer.reset(); // sets previous to new byte[0]
    corruptWriter(writer, lastValue);
    for (int i = 10; i < 20; i += 1) {
      String value = str(i);
      writer.writeBytes(Binary.fromString(value));
      values.add(value);
    }

    memWriter.writePage(BytesInput.concat(rd, rd, writer.getBytes()),
        10, /* number of values in the page */
        new BinaryStatistics(),
        rdValues.getEncoding(),
        rdValues.getEncoding(),
        writer.getEncoding());
    pages.addRowCount(10);

    final List<String> actualValues = new ArrayList<String>();
    PrimitiveConverter converter = new PrimitiveConverter() {
      @Override
      public void addBinary(Binary value) {
        actualValues.add(value.toStringUsingUTF8());
      }
    };

    ColumnReaderImpl columnReader = new ColumnReaderImpl(
        column, pages.getPageReader(column), converter,
        new ParsedVersion("parquet-mr", "1.6.0", "abcd"));

    while (actualValues.size() < columnReader.getTotalValueCount()) {
      columnReader.writeCurrentValueToConverter();
      columnReader.consume();
    }

    Assert.assertEquals(values, actualValues);
  }

  public void corruptWriter(DeltaByteArrayWriter writer, String data) throws Exception {
    Field previous = writer.getClass().getDeclaredField("previous");
    previous.setAccessible(true);
    previous.set(writer, Binary.fromString(data).getBytesUnsafe());
  }

  public String str(int i) {
    char c = 'a';
    return "aaaaaaaaaaa" + (char) (c + i);
  }
}
