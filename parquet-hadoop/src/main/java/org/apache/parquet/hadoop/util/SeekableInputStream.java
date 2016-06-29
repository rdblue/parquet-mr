/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */

package org.apache.parquet.hadoop.util;

import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.parquet.Log;
import org.apache.parquet.io.ParquetDecodingException;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;

public abstract class SeekableInputStream extends InputStream {

  private static final Log LOG = Log.getLog(SeekableInputStream.class);

  private static final Class<?> byteBufferReadableClass;

  private static final Constructor<SeekableInputStream> h2SeekableConstructor;

  static {
    byteBufferReadableClass = getReadableClass();
    h2SeekableConstructor = getH2SeekableConstructor();
  }

  public static SeekableInputStream wrap(FSDataInputStream stream) {
    if (byteBufferReadableClass != null && h2SeekableConstructor != null &&
        byteBufferReadableClass.isInstance(stream)) {
      try {
        return h2SeekableConstructor.newInstance(stream);
      } catch (InstantiationException e) {
        LOG.warn("Could not instantiate H2SeekableInputStream, falling back to byte array reads", e);
        return new H1SeekableInputStream(stream);
      } catch (IllegalAccessException e) {
        LOG.warn("Could not instantiate H2SeekableInputStream, falling back to byte array reads", e);
        return new H1SeekableInputStream(stream);
      } catch (InvocationTargetException e) {
        throw new ParquetDecodingException(
            "Could not instantiate H2SeekableInputStream", e.getTargetException());
      }
    } else {
      return new H1SeekableInputStream(stream);
    }
  }

  public abstract long getPos() throws IOException;

  public abstract void seek(long newPos) throws IOException;

  public abstract void readFully(byte[] bytes) throws IOException;

  public abstract void readFully(byte[] bytes, int start, int len) throws IOException;

  public abstract int read(ByteBuffer buf) throws IOException;

  public abstract void readFully(ByteBuffer buf) throws IOException;

  private static Class<?> getReadableClass() {
    try {
      return Class.forName("org.apache.hadoop.fs.ByteBufferReadable");
    } catch (ClassNotFoundException e) {
      return null;
    } catch (NoClassDefFoundError e) {
      return null;
    }
  }

  @SuppressWarnings("unchecked")
  private static Class<SeekableInputStream> getH2SeekableClass() {
    try {
      return (Class<SeekableInputStream>) Class.forName(
          "org.apache.parquet.hadoop.util.H2SeekableInputStream");
    } catch (ClassNotFoundException e) {
      return null;
    } catch (NoClassDefFoundError e) {
      return null;
    }
  }

  private static Constructor<SeekableInputStream> getH2SeekableConstructor() {
    Class<SeekableInputStream> h2SeekableClass = getH2SeekableClass();
    if (h2SeekableClass != null) {
      try {
        return h2SeekableClass.getConstructor(FSDataInputStream.class);
      } catch (NoSuchMethodException e) {
        return null;
      }
    }
    return null;
  }
}
