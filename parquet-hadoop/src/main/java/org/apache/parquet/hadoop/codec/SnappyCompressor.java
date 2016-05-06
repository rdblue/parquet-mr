/* 
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
package org.apache.parquet.hadoop.codec;

import org.xerial.snappy.Snappy;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * This class is a wrapper around the snappy compressor. It always consumes the
 * entire input in setInput and compresses it as one compressed block.
 */
public class SnappyCompressor extends NonBlockingCompressor {
  @Override
  protected int getMaxCompressedLength(int numInputBytes) {
    return Snappy.maxCompressedLength(numInputBytes);
  }

  @Override
  protected int compress(ByteBuffer inputBuffer, ByteBuffer outputBuffer) throws IOException {
    return Snappy.compress(inputBuffer, outputBuffer);
  }
}
