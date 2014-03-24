/**
 * Copyright 2012 Twitter, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package parquet.hadoop;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.apache.commons.codec.binary.Base64;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.mapreduce.InputSplit;

import parquet.Log;
import parquet.column.Encoding;
import parquet.hadoop.metadata.BlockMetaData;
import parquet.hadoop.metadata.ColumnChunkMetaData;
import parquet.hadoop.metadata.ColumnPath;
import parquet.hadoop.metadata.CompressionCodecName;
import parquet.schema.PrimitiveType.PrimitiveTypeName;

/**
 * An input split for the Parquet format
 * It contains the information to read one block of the file.
 *
 * @author Julien Le Dem
 */
public class ParquetInputSplit extends InputSplit implements Writable {

  private static final Log LOG = Log.getLog(ParquetInputSplit.class);

  private String path;
  private long start;
  private long length;
  private String[] hosts;
  private List<BlockMetaData> blocks;
  private String requestedSchema;
  private String fileSchema;
  private Map<String, String> extraMetadata;
  private Map<String, String> readSupportMetadata;


  /**
   * Writables must have a parameterless constructor
   */
  public ParquetInputSplit() {
  }

  /**
   * Used by {@link ParquetInputFormat#getSplits(org.apache.hadoop.mapreduce.JobContext)}
   * @param path the path to the file
   * @param start the offset of the block in the file
   * @param length the size of the block in the file
   * @param hosts the hosts where this block can be found
   * @param blocks the block meta data (Columns locations)
   * @param schema the file schema
   * @param readSupportClass the class used to materialize records
   * @param requestedSchema the requested schema for materialization
   * @param fileSchema the schema of the file
   * @param extraMetadata the app specific meta data in the file
   * @param readSupportMetadata the read support specific metadata
   */
  public ParquetInputSplit(
      Path path,
      long start,
      long length,
      String[] hosts,
      List<BlockMetaData> blocks,
      String requestedSchema,
      String fileSchema,
      Map<String, String> extraMetadata,
      Map<String, String> readSupportMetadata) {
    this.path = path.toUri().toString().intern();
    this.start = start;
    this.length = length;
    this.hosts = hosts;
    this.blocks = blocks;
    this.requestedSchema = requestedSchema;
    this.fileSchema = fileSchema;
    this.extraMetadata = extraMetadata;
    this.readSupportMetadata = readSupportMetadata;
  }

  /**
   * @return the block meta data
   */
  public List<BlockMetaData> getBlocks() {
    return blocks;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public long getLength() throws IOException, InterruptedException {
    return length;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String[] getLocations() throws IOException, InterruptedException {
    return hosts;
  }

  /**
   * @return the offset of the block in the file
   */
  public long getStart() {
    return start;
  }

  /**
   * @return the path of the file containing the block
   */
  public Path getPath() {
    try {
      return new Path(new URI(path));
    } catch (URISyntaxException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * @return the requested schema
   */
  public String getRequestedSchema() {
    return requestedSchema;
  }

  /**
   * @return the file schema
   */
  public String getFileSchema() {
    return fileSchema;
  }

  /**
   * @return app specific metadata from the file
   */
  public Map<String, String> getExtraMetadata() {
    return extraMetadata;
  }

  /**
   * @return app specific metadata provided by the read support in the init phase
   */
  public Map<String, String> getReadSupportMetadata() {
    return readSupportMetadata;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void readFields(DataInput in) throws IOException {
    this.path = in.readUTF().intern();
    this.start = in.readLong();
    this.length = in.readLong();
    this.hosts = new String[in.readInt()];
    for (int i = 0; i < hosts.length; i++) {
      hosts[i] = in.readUTF().intern();
    }
    int blocksSize = in.readInt();
    this.blocks = new ArrayList<BlockMetaData>(blocksSize);
    for (int i = 0; i < blocksSize; i++) {
      blocks.add(readBlock(in));
    }
    this.requestedSchema = decompressString(Text.readString(in));
    this.fileSchema = decompressString(Text.readString(in));
    this.extraMetadata = readKeyValues(in);
    this.readSupportMetadata = readKeyValues(in);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void write(DataOutput out) throws IOException {
    out.writeUTF(path);
    out.writeLong(start);
    out.writeLong(length);
    out.writeInt(hosts.length);
    for (String host : hosts) {
      out.writeUTF(host);
    }
    out.writeInt(blocks.size());
    for (BlockMetaData block : blocks) {
      writeBlock(out, block);
    }
    Text.writeString(out, compressString(requestedSchema));
    Text.writeString(out, compressString(fileSchema));
    writeKeyValues(out, extraMetadata);
    writeKeyValues(out, readSupportMetadata);
  }

  String compressString(String str) {
    ByteArrayOutputStream obj = new ByteArrayOutputStream();
    GZIPOutputStream gzip;
    try {
      gzip = new GZIPOutputStream(obj);
      gzip.write(str.getBytes("UTF-8"));
      gzip.close();
    } catch (IOException e) {
      // Not really sure how we can get here. I guess the best thing to do is to croak.
      LOG.error("Unable to gzip InputSplit string " + str, e);
      throw new RuntimeException("Unable to gzip InputSplit string", e);
    }
    String compressedStr = Base64.encodeBase64String(obj.toByteArray());
    return compressedStr;
  }

  String decompressString(String str) {
    byte[] decoded  = Base64.decodeBase64(str);
    ByteArrayInputStream obj = new ByteArrayInputStream(decoded);
    GZIPInputStream gzip = null;
    String outStr = "";
    try {
      gzip = new GZIPInputStream(obj);
      BufferedReader reader = new BufferedReader(new InputStreamReader(gzip, "UTF-8"));
      char[] buffer = new char[1024];
      int n = 0;
      StringBuilder sb = new StringBuilder();
      while (-1 != (n = reader.read(buffer))) {
        sb.append(buffer, 0, n);
      }
      outStr = sb.toString();
    } catch (IOException e) {
      // Not really sure how we can get here. I guess the best thing to do is to croak.
      LOG.error("Unable to uncompress InputSplit string " + str, e);
      throw new RuntimeException("Unable to uncompress InputSplit String", e);
    } finally {
      if (null != gzip) {
        try {
          gzip.close();
        } catch (IOException e) {
          LOG.error("Unable to uncompress InputSplit string " + str, e);
          throw new RuntimeException("Unable to uncompress InputSplit String", e);
        }
      }
    }
    return outStr;
  }

  private BlockMetaData readBlock(DataInput in) throws IOException {
    final BlockMetaData block = new BlockMetaData();
    int size = in.readInt();
    for (int i = 0; i < size; i++) {
      block.addColumn(readColumn(in));
    }
    block.setRowCount(in.readLong());
    block.setTotalByteSize(in.readLong());
    if (!in.readBoolean()) {
      block.setPath(in.readUTF().intern());
    }
    return block;
  }

  private void writeBlock(DataOutput out, BlockMetaData block)
      throws IOException {
    out.writeInt(block.getColumns().size());
    for (ColumnChunkMetaData column : block.getColumns()) {
      writeColumn(out, column);
    }
    out.writeLong(block.getRowCount());
    out.writeLong(block.getTotalByteSize());
    out.writeBoolean(block.getPath() == null);
    if (block.getPath() != null) {
      out.writeUTF(block.getPath());
    }
  }

  private ColumnChunkMetaData readColumn(DataInput in)
      throws IOException {
    CompressionCodecName codec = CompressionCodecName.values()[in.readInt()];
    String[] columnPath = new String[in.readInt()];
    for (int i = 0; i < columnPath.length; i++) {
      columnPath[i] = in.readUTF().intern();
    }
    PrimitiveTypeName type = PrimitiveTypeName.values()[in.readInt()];
    int encodingsSize = in.readInt();
    Set<Encoding> encodings = new HashSet<Encoding>(encodingsSize);
    for (int i = 0; i < encodingsSize; i++) {
      encodings.add(Encoding.values()[in.readInt()]);
    }
    ColumnChunkMetaData column = ColumnChunkMetaData.get(
        ColumnPath.get(columnPath), type, codec, encodings,
        in.readLong(), in.readLong(), in.readLong(), in.readLong(), in.readLong());
    return column;
  }

  private void writeColumn(DataOutput out, ColumnChunkMetaData column)
      throws IOException {
    out.writeInt(column.getCodec().ordinal());
    out.writeInt(column.getPath().size());
    for (String s : column.getPath()) {
      out.writeUTF(s);
    }
    out.writeInt(column.getType().ordinal());
    out.writeInt(column.getEncodings().size());
    for (Encoding encoding : column.getEncodings()) {
      out.writeInt(encoding.ordinal());
    }
    out.writeLong(column.getFirstDataPageOffset());
    out.writeLong(column.getDictionaryPageOffset());
    out.writeLong(column.getValueCount());
    out.writeLong(column.getTotalSize());
    out.writeLong(column.getTotalUncompressedSize());
  }

  private Map<String, String> readKeyValues(DataInput in) throws IOException {
    int size = in.readInt();
    Map<String, String> map = new HashMap<String, String>(size);
    for (int i = 0; i < size; i++) {
      String key = in.readUTF().intern();
      String value = in.readUTF().intern();
      map.put(key, value);
    }
    return map;
  }

  private void writeKeyValues(DataOutput out, Map<String, String> map) throws IOException {
    if (map == null) {
      out.writeInt(0);
    } else {
      out.writeInt(map.size());
      for (Entry<String, String> entry : map.entrySet()) {
        out.writeUTF(entry.getKey());
        out.writeUTF(entry.getValue());
      }
    }
  }


  @Override
  public String toString() {
    String hosts[] = {};
    long length = -1;
    try{
       hosts = getLocations();
      length = getLength();
    }catch(Exception ignore){} // IOException/InterruptedException could be thrown

    return this.getClass().getSimpleName() + "{" +
           "part: " + getPath()
        + " start: " + getStart()
        + " length: " + length
        + " hosts: " + Arrays.toString(hosts)
        + " blocks: " + blocks.size()
        + " requestedSchema: " + (fileSchema.equals(requestedSchema) ? "same as file" : requestedSchema)
        + " fileSchema: " + fileSchema
        + " extraMetadata: " + extraMetadata
        + " readSupportMetadata: " + readSupportMetadata
        + "}";
  }

}
