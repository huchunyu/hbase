/**
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hbase.regionserver;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.HBaseTestCase;
import org.apache.hadoop.hbase.HBaseTestingUtility;
import org.apache.hadoop.hbase.HRegionInfo;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.SmallTests;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.io.HFileLink;
import org.apache.hadoop.hbase.io.encoding.DataBlockEncoding;
import org.apache.hadoop.hbase.io.hfile.BlockCache;
import org.apache.hadoop.hbase.io.hfile.CacheConfig;
import org.apache.hadoop.hbase.io.hfile.CacheStats;
import org.apache.hadoop.hbase.io.hfile.HFile;
import org.apache.hadoop.hbase.io.hfile.HFileDataBlockEncoder;
import org.apache.hadoop.hbase.io.hfile.HFileDataBlockEncoderImpl;
import org.apache.hadoop.hbase.io.hfile.HFileScanner;
import org.apache.hadoop.hbase.io.hfile.NoOpDataBlockEncoder;
import org.apache.hadoop.hbase.regionserver.BloomType;
import org.apache.hadoop.hbase.util.BloomFilterFactory;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.ChecksumType;
import org.apache.hadoop.hbase.util.FSUtils;
import org.junit.experimental.categories.Category;
import org.mockito.Mockito;

import com.google.common.base.Joiner;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;

/**
 * Test HStoreFile
 */
@Category(SmallTests.class)
public class TestStoreFile extends HBaseTestCase {
  static final Log LOG = LogFactory.getLog(TestStoreFile.class);
  private static final HBaseTestingUtility TEST_UTIL = new HBaseTestingUtility();
  private CacheConfig cacheConf =  new CacheConfig(TEST_UTIL.getConfiguration());
  private static String ROOT_DIR =
    TEST_UTIL.getDataTestDir("TestStoreFile").toString();
  private static final ChecksumType CKTYPE = ChecksumType.CRC32;
  private static final int CKBYTES = 512;

  @Override
  public void setUp() throws Exception {
    super.setUp();
  }

  @Override
  public void tearDown() throws Exception {
    super.tearDown();
  }

  /**
   * Write a file and then assert that we can read from top and bottom halves
   * using two HalfMapFiles.
   * @throws Exception
   */
  public void testBasicHalfMapFile() throws Exception {
    // Make up a directory hierarchy that has a regiondir and familyname.
    Path outputDir = new Path(new Path(this.testDir, "regionname"),
        "familyname");
    StoreFile.Writer writer = new StoreFile.WriterBuilder(conf, cacheConf,
        this.fs, 2 * 1024)
            .withOutputDir(outputDir)
            .build();
    writeStoreFile(writer);
    checkHalfHFile(new StoreFile(this.fs, writer.getPath(), conf, cacheConf,
        BloomType.NONE, NoOpDataBlockEncoder.INSTANCE));
  }

  private void writeStoreFile(final StoreFile.Writer writer) throws IOException {
    writeStoreFile(writer, Bytes.toBytes(getName()), Bytes.toBytes(getName()));
  }
  /*
   * Writes HStoreKey and ImmutableBytes data to passed writer and
   * then closes it.
   * @param writer
   * @throws IOException
   */
  public static void writeStoreFile(final StoreFile.Writer writer, byte[] fam, byte[] qualifier)
  throws IOException {
    long now = System.currentTimeMillis();
    try {
      for (char d = FIRST_CHAR; d <= LAST_CHAR; d++) {
        for (char e = FIRST_CHAR; e <= LAST_CHAR; e++) {
          byte[] b = new byte[] { (byte) d, (byte) e };
          writer.append(new KeyValue(b, fam, qualifier, now, b));
        }
      }
    } finally {
      writer.close();
    }
  }

  /**
   * Test that our mechanism of writing store files in one region to reference
   * store files in other regions works.
   * @throws IOException
   */
  public void testReference()
  throws IOException {
    Path storedir = new Path(new Path(this.testDir, "regionname"), "familyname");
    Path dir = new Path(storedir, "1234567890");
    // Make a store file and write data to it.
    StoreFile.Writer writer = new StoreFile.WriterBuilder(conf, cacheConf,
        this.fs, 8 * 1024)
            .withOutputDir(dir)
            .build();
    writeStoreFile(writer);
    StoreFile hsf = new StoreFile(this.fs, writer.getPath(), conf, cacheConf,
        BloomType.NONE, NoOpDataBlockEncoder.INSTANCE);
    StoreFile.Reader reader = hsf.createReader();
    // Split on a row, not in middle of row.  Midkey returned by reader
    // may be in middle of row.  Create new one with empty column and
    // timestamp.
    KeyValue kv = KeyValue.createKeyValueFromKey(reader.midkey());
    byte [] midRow = kv.getRow();
    kv = KeyValue.createKeyValueFromKey(reader.getLastKey());
    byte [] finalRow = kv.getRow();
    // Make a reference
    Path refPath = StoreFile.split(fs, dir, hsf, midRow, true);
    StoreFile refHsf = new StoreFile(this.fs, refPath, conf, cacheConf,
        BloomType.NONE, NoOpDataBlockEncoder.INSTANCE);
    // Now confirm that I can read from the reference and that it only gets
    // keys from top half of the file.
    HFileScanner s = refHsf.createReader().getScanner(false, false);
    for(boolean first = true; (!s.isSeeked() && s.seekTo()) || s.next();) {
      ByteBuffer bb = s.getKey();
      kv = KeyValue.createKeyValueFromKey(bb);
      if (first) {
        assertTrue(Bytes.equals(kv.getRow(), midRow));
        first = false;
      }
    }
    assertTrue(Bytes.equals(kv.getRow(), finalRow));
  }

  public void testHFileLink() throws IOException {
    final String columnFamily = "f";
    HRegionInfo hri = new HRegionInfo(Bytes.toBytes("table-link"));
    Path storedir = new Path(new Path(FSUtils.getRootDir(conf),
      new Path(hri.getTableNameAsString(), hri.getEncodedName())), columnFamily);

    // Make a store file and write data to it.
    StoreFile.Writer writer = new StoreFile.WriterBuilder(conf, cacheConf,
         this.fs, 8 * 1024)
            .withOutputDir(storedir)
            .build();
    Path storeFilePath = writer.getPath();
    writeStoreFile(writer);
    writer.close();

    Path dstPath = new Path(FSUtils.getRootDir(conf), new Path("test-region", columnFamily));
    HFileLink.create(conf, this.fs, dstPath, hri, storeFilePath.getName());
    Path linkFilePath = new Path(dstPath,
                  HFileLink.createHFileLinkName(hri, storeFilePath.getName()));

    // Try to open store file from link
    StoreFile hsf = new StoreFile(this.fs, linkFilePath, conf, cacheConf,
        BloomType.NONE, NoOpDataBlockEncoder.INSTANCE);
    assertTrue(hsf.isLink());

    // Now confirm that I can read from the link
    int count = 1;
    HFileScanner s = hsf.createReader().getScanner(false, false);
    s.seekTo();
    while (s.next()) {
      count++;
    }
    assertEquals((LAST_CHAR - FIRST_CHAR + 1) * (LAST_CHAR - FIRST_CHAR + 1), count);
  }

  private void checkHalfHFile(final StoreFile f)
  throws IOException {
    byte [] midkey = f.createReader().midkey();
    KeyValue midKV = KeyValue.createKeyValueFromKey(midkey);
    byte [] midRow = midKV.getRow();
    // Create top split.
    Path topDir = HStore.getStoreHomedir(this.testDir, "1",
      Bytes.toBytes(f.getPath().getParent().getName()));
    if (this.fs.exists(topDir)) {
      this.fs.delete(topDir, true);
    }
    Path topPath = StoreFile.split(this.fs, topDir, f, midRow, true);
    // Create bottom split.
    Path bottomDir = HStore.getStoreHomedir(this.testDir, "2",
      Bytes.toBytes(f.getPath().getParent().getName()));
    if (this.fs.exists(bottomDir)) {
      this.fs.delete(bottomDir, true);
    }
    Path bottomPath = StoreFile.split(this.fs, bottomDir, f, midRow, false);
    // Make readers on top and bottom.
    StoreFile.Reader top =
        new StoreFile(this.fs, topPath, conf, cacheConf, BloomType.NONE,
            NoOpDataBlockEncoder.INSTANCE).createReader();
    StoreFile.Reader bottom = new StoreFile(this.fs, bottomPath,
        conf, cacheConf, BloomType.NONE,
        NoOpDataBlockEncoder.INSTANCE).createReader();
    ByteBuffer previous = null;
    LOG.info("Midkey: " + midKV.toString());
    ByteBuffer bbMidkeyBytes = ByteBuffer.wrap(midkey);
    try {
      // Now make two HalfMapFiles and assert they can read the full backing
      // file, one from the top and the other from the bottom.
      // Test bottom half first.
      // Now test reading from the top.
      boolean first = true;
      ByteBuffer key = null;
      HFileScanner topScanner = top.getScanner(false, false);
      while ((!topScanner.isSeeked() && topScanner.seekTo()) ||
          (topScanner.isSeeked() && topScanner.next())) {
        key = topScanner.getKey();

        if (topScanner.getReader().getComparator().compare(key.array(),
          key.arrayOffset(), key.limit(), midkey, 0, midkey.length) < 0) {
          fail("key=" + Bytes.toStringBinary(key) + " < midkey=" +
              Bytes.toStringBinary(midkey));
        }
        if (first) {
          first = false;
          LOG.info("First in top: " + Bytes.toString(Bytes.toBytes(key)));
        }
      }
      LOG.info("Last in top: " + Bytes.toString(Bytes.toBytes(key)));

      first = true;
      HFileScanner bottomScanner = bottom.getScanner(false, false);
      while ((!bottomScanner.isSeeked() && bottomScanner.seekTo()) ||
          bottomScanner.next()) {
        previous = bottomScanner.getKey();
        key = bottomScanner.getKey();
        if (first) {
          first = false;
          LOG.info("First in bottom: " +
            Bytes.toString(Bytes.toBytes(previous)));
        }
        assertTrue(key.compareTo(bbMidkeyBytes) < 0);
      }
      if (previous != null) {
        LOG.info("Last in bottom: " + Bytes.toString(Bytes.toBytes(previous)));
      }
      // Remove references.
      this.fs.delete(topPath, false);
      this.fs.delete(bottomPath, false);

      // Next test using a midkey that does not exist in the file.
      // First, do a key that is < than first key. Ensure splits behave
      // properly.
      byte [] badmidkey = Bytes.toBytes("  .");
      topPath = StoreFile.split(this.fs, topDir, f, badmidkey, true);
      bottomPath = StoreFile.split(this.fs, bottomDir, f, badmidkey, false);
      top = new StoreFile(this.fs, topPath, conf, cacheConf,
          BloomType.NONE,
          NoOpDataBlockEncoder.INSTANCE).createReader();
      bottom = new StoreFile(this.fs, bottomPath, conf, cacheConf,
          BloomType.NONE,
          NoOpDataBlockEncoder.INSTANCE).createReader();
      bottomScanner = bottom.getScanner(false, false);
      int count = 0;
      while ((!bottomScanner.isSeeked() && bottomScanner.seekTo()) ||
          bottomScanner.next()) {
        count++;
      }
      // When badkey is < than the bottom, should return no values.
      assertTrue(count == 0);
      // Now read from the top.
      first = true;
      topScanner = top.getScanner(false, false);
      while ((!topScanner.isSeeked() && topScanner.seekTo()) ||
          topScanner.next()) {
        key = topScanner.getKey();
        assertTrue(topScanner.getReader().getComparator().compare(key.array(),
          key.arrayOffset(), key.limit(), badmidkey, 0, badmidkey.length) >= 0);
        if (first) {
          first = false;
          KeyValue keyKV = KeyValue.createKeyValueFromKey(key);
          LOG.info("First top when key < bottom: " + keyKV);
          String tmp = Bytes.toString(keyKV.getRow());
          for (int i = 0; i < tmp.length(); i++) {
            assertTrue(tmp.charAt(i) == 'a');
          }
        }
      }
      KeyValue keyKV = KeyValue.createKeyValueFromKey(key);
      LOG.info("Last top when key < bottom: " + keyKV);
      String tmp = Bytes.toString(keyKV.getRow());
      for (int i = 0; i < tmp.length(); i++) {
        assertTrue(tmp.charAt(i) == 'z');
      }
      // Remove references.
      this.fs.delete(topPath, false);
      this.fs.delete(bottomPath, false);

      // Test when badkey is > than last key in file ('||' > 'zz').
      badmidkey = Bytes.toBytes("|||");
      topPath = StoreFile.split(this.fs, topDir, f, badmidkey, true);
      bottomPath = StoreFile.split(this.fs, bottomDir, f, badmidkey, false);
      top = new StoreFile(this.fs, topPath, conf, cacheConf,
          BloomType.NONE,
          NoOpDataBlockEncoder.INSTANCE).createReader();
      bottom = new StoreFile(this.fs, bottomPath, conf, cacheConf,
          BloomType.NONE,
          NoOpDataBlockEncoder.INSTANCE).createReader();
      first = true;
      bottomScanner = bottom.getScanner(false, false);
      while ((!bottomScanner.isSeeked() && bottomScanner.seekTo()) ||
          bottomScanner.next()) {
        key = bottomScanner.getKey();
        if (first) {
          first = false;
          keyKV = KeyValue.createKeyValueFromKey(key);
          LOG.info("First bottom when key > top: " + keyKV);
          tmp = Bytes.toString(keyKV.getRow());
          for (int i = 0; i < tmp.length(); i++) {
            assertTrue(tmp.charAt(i) == 'a');
          }
        }
      }
      keyKV = KeyValue.createKeyValueFromKey(key);
      LOG.info("Last bottom when key > top: " + keyKV);
      for (int i = 0; i < tmp.length(); i++) {
        assertTrue(Bytes.toString(keyKV.getRow()).charAt(i) == 'z');
      }
      count = 0;
      topScanner = top.getScanner(false, false);
      while ((!topScanner.isSeeked() && topScanner.seekTo()) ||
          (topScanner.isSeeked() && topScanner.next())) {
        count++;
      }
      // When badkey is < than the bottom, should return no values.
      assertTrue(count == 0);
    } finally {
      if (top != null) {
        top.close(true); // evict since we are about to delete the file
      }
      if (bottom != null) {
        bottom.close(true); // evict since we are about to delete the file
      }
      fs.delete(f.getPath(), true);
    }
  }

  private static final String localFormatter = "%010d";

  private void bloomWriteRead(StoreFile.Writer writer, FileSystem fs)
  throws Exception {
    float err = conf.getFloat(
        BloomFilterFactory.IO_STOREFILE_BLOOM_ERROR_RATE, 0);
    Path f = writer.getPath();
    long now = System.currentTimeMillis();
    for (int i = 0; i < 2000; i += 2) {
      String row = String.format(localFormatter, i);
      KeyValue kv = new KeyValue(row.getBytes(), "family".getBytes(),
        "col".getBytes(), now, "value".getBytes());
      writer.append(kv);
    }
    writer.close();

    StoreFile.Reader reader = new StoreFile.Reader(fs, f, cacheConf,
        DataBlockEncoding.NONE);
    reader.loadFileInfo();
    reader.loadBloomfilter();
    StoreFileScanner scanner = reader.getStoreFileScanner(false, false);

    // check false positives rate
    int falsePos = 0;
    int falseNeg = 0;
    for (int i = 0; i < 2000; i++) {
      String row = String.format(localFormatter, i);
      TreeSet<byte[]> columns = new TreeSet<byte[]>(Bytes.BYTES_COMPARATOR);
      columns.add("family:col".getBytes());

      Scan scan = new Scan(row.getBytes(),row.getBytes());
      scan.addColumn("family".getBytes(), "family:col".getBytes());
      boolean exists = scanner.shouldUseScanner(scan, columns, Long.MIN_VALUE);
      if (i % 2 == 0) {
        if (!exists) falseNeg++;
      } else {
        if (exists) falsePos++;
      }
    }
    reader.close(true); // evict because we are about to delete the file
    fs.delete(f, true);
    assertEquals("False negatives: " + falseNeg, 0, falseNeg);
    int maxFalsePos = (int) (2 * 2000 * err);
    assertTrue("Too many false positives: " + falsePos + " (err=" + err
        + ", expected no more than " + maxFalsePos + ")",
        falsePos <= maxFalsePos);
  }

  public void testBloomFilter() throws Exception {
    FileSystem fs = FileSystem.getLocal(conf);
    conf.setFloat(BloomFilterFactory.IO_STOREFILE_BLOOM_ERROR_RATE,
        (float) 0.01);
    conf.setBoolean(BloomFilterFactory.IO_STOREFILE_BLOOM_ENABLED, true);

    // write the file
    Path f = new Path(ROOT_DIR, getName());
    StoreFile.Writer writer = new StoreFile.WriterBuilder(conf, cacheConf, fs,
        StoreFile.DEFAULT_BLOCKSIZE_SMALL)
            .withFilePath(f)
            .withBloomType(BloomType.ROW)
            .withMaxKeyCount(2000)
            .withChecksumType(CKTYPE)
            .withBytesPerChecksum(CKBYTES)
            .build();
    bloomWriteRead(writer, fs);
  }

  public void testDeleteFamilyBloomFilter() throws Exception {
    FileSystem fs = FileSystem.getLocal(conf);
    conf.setFloat(BloomFilterFactory.IO_STOREFILE_BLOOM_ERROR_RATE,
        (float) 0.01);
    conf.setBoolean(BloomFilterFactory.IO_STOREFILE_BLOOM_ENABLED, true);
    float err = conf.getFloat(BloomFilterFactory.IO_STOREFILE_BLOOM_ERROR_RATE,
        0);

    // write the file
    Path f = new Path(ROOT_DIR, getName());

    StoreFile.Writer writer = new StoreFile.WriterBuilder(conf, cacheConf,
        fs, StoreFile.DEFAULT_BLOCKSIZE_SMALL)
            .withFilePath(f)
            .withMaxKeyCount(2000)
            .withChecksumType(CKTYPE)
            .withBytesPerChecksum(CKBYTES)
            .build();

    // add delete family
    long now = System.currentTimeMillis();
    for (int i = 0; i < 2000; i += 2) {
      String row = String.format(localFormatter, i);
      KeyValue kv = new KeyValue(row.getBytes(), "family".getBytes(),
          "col".getBytes(), now, KeyValue.Type.DeleteFamily, "value".getBytes());
      writer.append(kv);
    }
    writer.close();

    StoreFile.Reader reader = new StoreFile.Reader(fs, f, cacheConf, DataBlockEncoding.NONE);
    reader.loadFileInfo();
    reader.loadBloomfilter();

    // check false positives rate
    int falsePos = 0;
    int falseNeg = 0;
    for (int i = 0; i < 2000; i++) {
      String row = String.format(localFormatter, i);
      byte[] rowKey = Bytes.toBytes(row);
      boolean exists = reader.passesDeleteFamilyBloomFilter(rowKey, 0,
          rowKey.length);
      if (i % 2 == 0) {
        if (!exists)
          falseNeg++;
      } else {
        if (exists)
          falsePos++;
      }
    }
    assertEquals(1000, reader.getDeleteFamilyCnt());
    reader.close(true); // evict because we are about to delete the file
    fs.delete(f, true);
    assertEquals("False negatives: " + falseNeg, 0, falseNeg);
    int maxFalsePos = (int) (2 * 2000 * err);
    assertTrue("Too many false positives: " + falsePos + " (err=" + err
        + ", expected no more than " + maxFalsePos, falsePos <= maxFalsePos);
  }

  public void testBloomTypes() throws Exception {
    float err = (float) 0.01;
    FileSystem fs = FileSystem.getLocal(conf);
    conf.setFloat(BloomFilterFactory.IO_STOREFILE_BLOOM_ERROR_RATE, err);
    conf.setBoolean(BloomFilterFactory.IO_STOREFILE_BLOOM_ENABLED, true);

    int rowCount = 50;
    int colCount = 10;
    int versions = 2;

    // run once using columns and once using rows
    BloomType[] bt =
      {BloomType.ROWCOL, BloomType.ROW};
    int[] expKeys    = {rowCount*colCount, rowCount};
    // below line deserves commentary.  it is expected bloom false positives
    //  column = rowCount*2*colCount inserts
    //  row-level = only rowCount*2 inserts, but failures will be magnified by
    //              2nd for loop for every column (2*colCount)
    float[] expErr   = {2*rowCount*colCount*err, 2*rowCount*2*colCount*err};

    for (int x : new int[]{0,1}) {
      // write the file
      Path f = new Path(ROOT_DIR, getName() + x);
      StoreFile.Writer writer = new StoreFile.WriterBuilder(conf, cacheConf,
          fs, StoreFile.DEFAULT_BLOCKSIZE_SMALL)
              .withFilePath(f)
              .withBloomType(bt[x])
              .withMaxKeyCount(expKeys[x])
              .withChecksumType(CKTYPE)
              .withBytesPerChecksum(CKBYTES)
              .build();

      long now = System.currentTimeMillis();
      for (int i = 0; i < rowCount*2; i += 2) { // rows
        for (int j = 0; j < colCount*2; j += 2) {   // column qualifiers
          String row = String.format(localFormatter, i);
          String col = String.format(localFormatter, j);
          for (int k= 0; k < versions; ++k) { // versions
            KeyValue kv = new KeyValue(row.getBytes(),
              "family".getBytes(), ("col" + col).getBytes(),
                now-k, Bytes.toBytes((long)-1));
            writer.append(kv);
          }
        }
      }
      writer.close();

      StoreFile.Reader reader = new StoreFile.Reader(fs, f, cacheConf,
          DataBlockEncoding.NONE);
      reader.loadFileInfo();
      reader.loadBloomfilter();
      StoreFileScanner scanner = reader.getStoreFileScanner(false, false);
      assertEquals(expKeys[x], reader.generalBloomFilter.getKeyCount());

      // check false positives rate
      int falsePos = 0;
      int falseNeg = 0;
      for (int i = 0; i < rowCount*2; ++i) { // rows
        for (int j = 0; j < colCount*2; ++j) {   // column qualifiers
          String row = String.format(localFormatter, i);
          String col = String.format(localFormatter, j);
          TreeSet<byte[]> columns = new TreeSet<byte[]>(Bytes.BYTES_COMPARATOR);
          columns.add(("col" + col).getBytes());

          Scan scan = new Scan(row.getBytes(),row.getBytes());
          scan.addColumn("family".getBytes(), ("col"+col).getBytes());
          boolean exists =
              scanner.shouldUseScanner(scan, columns, Long.MIN_VALUE);
          boolean shouldRowExist = i % 2 == 0;
          boolean shouldColExist = j % 2 == 0;
          shouldColExist = shouldColExist || bt[x] == BloomType.ROW;
          if (shouldRowExist && shouldColExist) {
            if (!exists) falseNeg++;
          } else {
            if (exists) falsePos++;
          }
        }
      }
      reader.close(true); // evict because we are about to delete the file
      fs.delete(f, true);
      System.out.println(bt[x].toString());
      System.out.println("  False negatives: " + falseNeg);
      System.out.println("  False positives: " + falsePos);
      assertEquals(0, falseNeg);
      assertTrue(falsePos < 2*expErr[x]);
    }
  }

  public void testSeqIdComparator() {
    assertOrdering(StoreFile.Comparators.SEQ_ID,
        mockStoreFile(true,  100,   1000, -1, "/foo/123"),
        mockStoreFile(true,  100,   1000, -1, "/foo/124"),
        mockStoreFile(true,  99,    1000, -1, "/foo/126"),
        mockStoreFile(true,  98,    2000, -1, "/foo/126"),
        mockStoreFile(false, 3453, -1,     1, "/foo/1"),
        mockStoreFile(false, 2,    -1,     3, "/foo/2"),
        mockStoreFile(false, 1000, -1,     5, "/foo/2"),
        mockStoreFile(false, 76,   -1,     5, "/foo/3"));
  }

  /**
   * Assert that the given comparator orders the given storefiles in the
   * same way that they're passed.
   */
  private void assertOrdering(Comparator<StoreFile> comparator, StoreFile ... sfs) {
    ArrayList<StoreFile> sorted = Lists.newArrayList(sfs);
    Collections.shuffle(sorted);
    Collections.sort(sorted, comparator);
    LOG.debug("sfs: " + Joiner.on(",").join(sfs));
    LOG.debug("sorted: " + Joiner.on(",").join(sorted));
    assertTrue(Iterables.elementsEqual(Arrays.asList(sfs), sorted));
  }

  /**
   * Create a mock StoreFile with the given attributes.
   */
  private StoreFile mockStoreFile(boolean bulkLoad,
                                  long size,
                                  long bulkTimestamp,
                                  long seqId,
                                  String path) {
    StoreFile mock = Mockito.mock(StoreFile.class);
    StoreFile.Reader reader = Mockito.mock(StoreFile.Reader.class);

    Mockito.doReturn(size).when(reader).length();

    Mockito.doReturn(reader).when(mock).getReader();
    Mockito.doReturn(bulkLoad).when(mock).isBulkLoadResult();
    Mockito.doReturn(bulkTimestamp).when(mock).getBulkLoadTimestamp();
    Mockito.doReturn(seqId).when(mock).getMaxSequenceId();
    Mockito.doReturn(new Path(path)).when(mock).getPath();
    String name = "mock storefile, bulkLoad=" + bulkLoad +
      " bulkTimestamp=" + bulkTimestamp +
      " seqId=" + seqId +
      " path=" + path;
    Mockito.doReturn(name).when(mock).toString();
    return mock;
  }

  /**
   * Generate a list of KeyValues for testing based on given parameters
   * @param timestamps
   * @param numRows
   * @param qualifier
   * @param family
   * @return
   */
  List<KeyValue> getKeyValueSet(long[] timestamps, int numRows,
      byte[] qualifier, byte[] family) {
    List<KeyValue> kvList = new ArrayList<KeyValue>();
    for (int i=1;i<=numRows;i++) {
      byte[] b = Bytes.toBytes(i) ;
      LOG.info(Bytes.toString(b));
      LOG.info(Bytes.toString(b));
      for (long timestamp: timestamps)
      {
        kvList.add(new KeyValue(b, family, qualifier, timestamp, b));
      }
    }
    return kvList;
  }

  /**
   * Test to ensure correctness when using StoreFile with multiple timestamps
   * @throws IOException
   */
  public void testMultipleTimestamps() throws IOException {
    byte[] family = Bytes.toBytes("familyname");
    byte[] qualifier = Bytes.toBytes("qualifier");
    int numRows = 10;
    long[] timestamps = new long[] {20,10,5,1};
    Scan scan = new Scan();

    Path storedir = new Path(new Path(this.testDir, "regionname"),
    "familyname");
    Path dir = new Path(storedir, "1234567890");
    StoreFile.Writer writer = new StoreFile.WriterBuilder(conf, cacheConf,
        this.fs, 8 * 1024)
            .withOutputDir(dir)
            .build();

    List<KeyValue> kvList = getKeyValueSet(timestamps,numRows,
        family, qualifier);

    for (KeyValue kv : kvList) {
      writer.append(kv);
    }
    writer.appendMetadata(0, false);
    writer.close();

    StoreFile hsf = new StoreFile(this.fs, writer.getPath(), conf, cacheConf,
        BloomType.NONE, NoOpDataBlockEncoder.INSTANCE);
    StoreFile.Reader reader = hsf.createReader();
    StoreFileScanner scanner = reader.getStoreFileScanner(false, false);
    TreeSet<byte[]> columns = new TreeSet<byte[]>(Bytes.BYTES_COMPARATOR);
    columns.add(qualifier);

    scan.setTimeRange(20, 100);
    assertTrue(scanner.shouldUseScanner(scan, columns, Long.MIN_VALUE));

    scan.setTimeRange(1, 2);
    assertTrue(scanner.shouldUseScanner(scan, columns, Long.MIN_VALUE));

    scan.setTimeRange(8, 10);
    assertTrue(scanner.shouldUseScanner(scan, columns, Long.MIN_VALUE));

    scan.setTimeRange(7, 50);
    assertTrue(scanner.shouldUseScanner(scan, columns, Long.MIN_VALUE));

    // This test relies on the timestamp range optimization
    scan.setTimeRange(27, 50);
    assertTrue(!scanner.shouldUseScanner(scan, columns, Long.MIN_VALUE));
  }

  public void testCacheOnWriteEvictOnClose() throws Exception {
    Configuration conf = this.conf;

    // Find a home for our files
    Path baseDir = new Path(new Path(this.testDir, "regionname"),"twoCOWEOC");

    // Grab the block cache and get the initial hit/miss counts
    BlockCache bc = new CacheConfig(conf).getBlockCache();
    assertNotNull(bc);
    CacheStats cs = bc.getStats();
    long startHit = cs.getHitCount();
    long startMiss = cs.getMissCount();
    long startEvicted = cs.getEvictedCount();

    // Let's write a StoreFile with three blocks, with cache on write off
    conf.setBoolean(CacheConfig.CACHE_BLOCKS_ON_WRITE_KEY, false);
    CacheConfig cacheConf = new CacheConfig(conf);
    Path pathCowOff = new Path(baseDir, "123456789");
    StoreFile.Writer writer = writeStoreFile(conf, cacheConf, pathCowOff, 3);
    StoreFile hsf = new StoreFile(this.fs, writer.getPath(), conf, cacheConf,
        BloomType.NONE, NoOpDataBlockEncoder.INSTANCE);
    LOG.debug(hsf.getPath().toString());

    // Read this file, we should see 3 misses
    StoreFile.Reader reader = hsf.createReader();
    reader.loadFileInfo();
    StoreFileScanner scanner = reader.getStoreFileScanner(true, true);
    scanner.seek(KeyValue.LOWESTKEY);
    while (scanner.next() != null);
    assertEquals(startHit, cs.getHitCount());
    assertEquals(startMiss + 3, cs.getMissCount());
    assertEquals(startEvicted, cs.getEvictedCount());
    startMiss += 3;
    scanner.close();
    reader.close(cacheConf.shouldEvictOnClose());

    // Now write a StoreFile with three blocks, with cache on write on
    conf.setBoolean(CacheConfig.CACHE_BLOCKS_ON_WRITE_KEY, true);
    cacheConf = new CacheConfig(conf);
    Path pathCowOn = new Path(baseDir, "123456788");
    writer = writeStoreFile(conf, cacheConf, pathCowOn, 3);
    hsf = new StoreFile(this.fs, writer.getPath(), conf, cacheConf,
        BloomType.NONE, NoOpDataBlockEncoder.INSTANCE);

    // Read this file, we should see 3 hits
    reader = hsf.createReader();
    scanner = reader.getStoreFileScanner(true, true);
    scanner.seek(KeyValue.LOWESTKEY);
    while (scanner.next() != null);
    assertEquals(startHit + 3, cs.getHitCount());
    assertEquals(startMiss, cs.getMissCount());
    assertEquals(startEvicted, cs.getEvictedCount());
    startHit += 3;
    scanner.close();
    reader.close(cacheConf.shouldEvictOnClose());

    // Let's read back the two files to ensure the blocks exactly match
    hsf = new StoreFile(this.fs, pathCowOff, conf, cacheConf,
        BloomType.NONE, NoOpDataBlockEncoder.INSTANCE);
    StoreFile.Reader readerOne = hsf.createReader();
    readerOne.loadFileInfo();
    StoreFileScanner scannerOne = readerOne.getStoreFileScanner(true, true);
    scannerOne.seek(KeyValue.LOWESTKEY);
    hsf = new StoreFile(this.fs, pathCowOn, conf, cacheConf,
        BloomType.NONE, NoOpDataBlockEncoder.INSTANCE);
    StoreFile.Reader readerTwo = hsf.createReader();
    readerTwo.loadFileInfo();
    StoreFileScanner scannerTwo = readerTwo.getStoreFileScanner(true, true);
    scannerTwo.seek(KeyValue.LOWESTKEY);
    KeyValue kv1 = null;
    KeyValue kv2 = null;
    while ((kv1 = scannerOne.next()) != null) {
      kv2 = scannerTwo.next();
      assertTrue(kv1.equals(kv2));
      assertTrue(Bytes.compareTo(
          kv1.getBuffer(), kv1.getKeyOffset(), kv1.getKeyLength(), 
          kv2.getBuffer(), kv2.getKeyOffset(), kv2.getKeyLength()) == 0);
      assertTrue(Bytes.compareTo(
          kv1.getBuffer(), kv1.getValueOffset(), kv1.getValueLength(),
          kv2.getBuffer(), kv2.getValueOffset(), kv2.getValueLength()) == 0);
    }
    assertNull(scannerTwo.next());
    assertEquals(startHit + 6, cs.getHitCount());
    assertEquals(startMiss, cs.getMissCount());
    assertEquals(startEvicted, cs.getEvictedCount());
    startHit += 6;
    scannerOne.close();
    readerOne.close(cacheConf.shouldEvictOnClose());
    scannerTwo.close();
    readerTwo.close(cacheConf.shouldEvictOnClose());

    // Let's close the first file with evict on close turned on
    conf.setBoolean("hbase.rs.evictblocksonclose", true);
    cacheConf = new CacheConfig(conf);
    hsf = new StoreFile(this.fs, pathCowOff, conf, cacheConf,
        BloomType.NONE, NoOpDataBlockEncoder.INSTANCE);
    reader = hsf.createReader();
    reader.close(cacheConf.shouldEvictOnClose());

    // We should have 3 new evictions
    assertEquals(startHit, cs.getHitCount());
    assertEquals(startMiss, cs.getMissCount());
    assertEquals(startEvicted + 3, cs.getEvictedCount());
    startEvicted += 3;

    // Let's close the second file with evict on close turned off
    conf.setBoolean("hbase.rs.evictblocksonclose", false);
    cacheConf = new CacheConfig(conf);
    hsf = new StoreFile(this.fs, pathCowOn, conf, cacheConf,
        BloomType.NONE, NoOpDataBlockEncoder.INSTANCE);
    reader = hsf.createReader();
    reader.close(cacheConf.shouldEvictOnClose());

    // We expect no changes
    assertEquals(startHit, cs.getHitCount());
    assertEquals(startMiss, cs.getMissCount());
    assertEquals(startEvicted, cs.getEvictedCount());
  }

  private StoreFile.Writer writeStoreFile(Configuration conf,
      CacheConfig cacheConf, Path path, int numBlocks)
  throws IOException {
    // Let's put ~5 small KVs in each block, so let's make 5*numBlocks KVs
    int numKVs = 5 * numBlocks;
    List<KeyValue> kvs = new ArrayList<KeyValue>(numKVs);
    byte [] b = Bytes.toBytes("x");
    int totalSize = 0;
    for (int i=numKVs;i>0;i--) {
      KeyValue kv = new KeyValue(b, b, b, i, b);
      kvs.add(kv);
      // kv has memstoreTS 0, which takes 1 byte to store.
      totalSize += kv.getLength() + 1;
    }
    int blockSize = totalSize / numBlocks;
    StoreFile.Writer writer = new StoreFile.WriterBuilder(conf, cacheConf, fs,
        blockSize)
            .withFilePath(path)
            .withMaxKeyCount(2000)
            .withChecksumType(CKTYPE)
            .withBytesPerChecksum(CKBYTES)
            .build();
    // We'll write N-1 KVs to ensure we don't write an extra block
    kvs.remove(kvs.size()-1);
    for (KeyValue kv : kvs) {
      writer.append(kv);
    }
    writer.appendMetadata(0, false);
    writer.close();
    return writer;
  }

  /**
   * Check if data block encoding information is saved correctly in HFile's
   * file info.
   */
  public void testDataBlockEncodingMetaData() throws IOException {
    Path dir = new Path(new Path(this.testDir, "regionname"), "familyname");
    Path path = new Path(dir, "1234567890");

    DataBlockEncoding dataBlockEncoderAlgo =
        DataBlockEncoding.FAST_DIFF;
    HFileDataBlockEncoder dataBlockEncoder =
        new HFileDataBlockEncoderImpl(
            dataBlockEncoderAlgo,
            dataBlockEncoderAlgo);
    cacheConf = new CacheConfig(conf);
    StoreFile.Writer writer = new StoreFile.WriterBuilder(conf, cacheConf, fs,
        HFile.DEFAULT_BLOCKSIZE)
            .withFilePath(path)
            .withDataBlockEncoder(dataBlockEncoder)
            .withMaxKeyCount(2000)
            .withChecksumType(CKTYPE)
            .withBytesPerChecksum(CKBYTES)
            .build();
    writer.close();
    
    StoreFile storeFile = new StoreFile(fs, writer.getPath(), conf,
        cacheConf, BloomType.NONE, dataBlockEncoder);
    StoreFile.Reader reader = storeFile.createReader();
    
    Map<byte[], byte[]> fileInfo = reader.loadFileInfo();
    byte[] value = fileInfo.get(HFileDataBlockEncoder.DATA_BLOCK_ENCODING);

    assertEquals(dataBlockEncoderAlgo.getNameInBytes(), value);
  }

}

